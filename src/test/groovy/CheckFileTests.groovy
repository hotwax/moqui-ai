import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui

class CheckFileTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String factTypeId = "RULES_TEST_CHECK_REFUNDS"
    @Shared String ruleSetId = "RULES_TEST_CHECK_REFUNDS_SET"
    @Shared String refundsCsv = "orderId,amount,reason\n100234,25.00,damaged\n100235,40.50,late\n"

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.user.internalLoginUser("john.doe")
        ec.artifactExecution.disableAuthz()
        removeTestData()
        callService("learn#FileFields", [factTypeId: factTypeId, fileName: "refunds.csv", fileText: refundsCsv])
        callService("update#FactField", [factTypeId: factTypeId, fieldName: "orderId", valueTypeEnumId: "FVT_TEXT"])
        ec.entity.makeValue("moqui.ai.RuleSet").setAll([ruleSetId: ruleSetId, firstMatchOnly: "Y"]).create()
        ec.entity.makeValue("moqui.ai.RuleSetFactType").setAll([ruleSetId: ruleSetId, factTypeId: factTypeId]).create()
        for (String outcomeId in ["REVIEW", "PAY"])
            ec.entity.makeValue("moqui.ai.RuleSetOutcome").setAll([ruleSetId: ruleSetId, outcomeId: outcomeId]).create()
        callService("store#Rule", [ruleSetId: ruleSetId, ruleName: "big refund", priority: 20,
                conditions: [[conditionSeqId: "01", conditionTypeId: "RCT_COMPARE", factTypeId: factTypeId,
                        fieldName: "amount", operatorEnumId: "ENTCO_GREATER", compareValue: "30"]],
                actions: [[actionTypeId: "RAT_OUTCOME", outcomeId: "REVIEW"]]])
        callService("store#Rule", [ruleSetId: ruleSetId, ruleName: "pay", priority: 10, conditions: [],
                actions: [[actionTypeId: "RAT_OUTCOME", outcomeId: "PAY"]]])
    }

    def cleanupSpec() {
        removeTestData()
        ec.artifactExecution.enableAuthz()
        ec.user.logoutUser()
        ec.destroy()
    }

    void removeTestData() {
        List<String> fileCheckIds = ec.entity.find("moqui.ai.FileCheck").condition("ruleSetId", ruleSetId).list()*.fileCheckId
        if (fileCheckIds) {
            ec.entity.find("moqui.ai.FileCheckRecord").condition("fileCheckId", "in", fileCheckIds).deleteAll()
            ec.entity.find("moqui.ai.FileCheck").condition("fileCheckId", "in", fileCheckIds).deleteAll()
        }
        List<String> ruleIds = ec.entity.find("moqui.ai.RuleSetRule").condition("ruleSetId", ruleSetId).list()*.ruleId
        if (ruleIds) {
            for (String entityName in ["RuleSetAction", "RuleSetCondition", "RuleSetRule"])
                ec.entity.find("moqui.ai." + entityName).condition("ruleId", "in", ruleIds).deleteAll()
        }
        for (String entityName in ["RuleSetOutcome", "RuleSetFactType", "RuleSet"])
            ec.entity.find("moqui.ai." + entityName).condition("ruleSetId", ruleSetId).deleteAll()
        ec.entity.find("moqui.ai.FactField").condition("factTypeId", factTypeId).deleteAll()
        ec.entity.find("moqui.ai.FactType").condition("factTypeId", factTypeId).deleteAll()
    }

    Map callService(String serviceName, Map parameters) {
        Map result = ec.service.sync().name("ai.rules.RuleServices." + serviceName).parameters(parameters).call()
        assert !ec.message.hasError(), ec.message.errorsString
        return result
    }

    Map check(String checkRuleSetId, String fileName, String fileText) {
        Map result = ec.service.sync().name("ai.rules.RuleServices.check#File")
                .parameters([ruleSetId: checkRuleSetId, factTypeId: factTypeId, fileName: fileName, fileText: fileText]).call()
        Map checked = [fileCheckId: result?.fileCheckId, records: result?.checkedRecords, errors: ec.message.errors.collect()]
        ec.message.clearAll()
        return checked
    }

    def "each record of the file gets its own outcome, and a bad record is refused alone"() {
        when:
        Map checked = check(ruleSetId, "refunds-week-1.csv", refundsCsv +
                "100236,abc,lost\n100237,,missing\n100238,5,wrong size,extra\n")

        then:
        checked.errors == []
        checked.fileCheckId != null
        checked.records*.recordNumber == [1, 2, 3, 4, 5]
        checked.records[0].outcomes == ["PAY"]
        checked.records[1].outcomes == ["REVIEW"]
        checked.records[1].matchedRules*.ruleName == ["big refund", "pay"]
        checked.records[2].outcomes == null
        checked.records[2].refusedReasons == ["Value abc does not fit field amount of ${factTypeId}".toString()]
        checked.records[3].outcomes == ["PAY"]
        checked.records[4].refusedReasons == ["It has 4 values, but the file has 3 columns"]
    }

    def "a JSON file of the same kind is checked the same way"() {
        when:
        Map checked = check(ruleSetId, "refunds.json", '[{"orderId": "100239", "amount": 31, "reason": "late"}]')

        then:
        checked.errors == []
        checked.records == [[recordNumber: 1, outcomes: ["REVIEW"], matchedRules: checked.records[0].matchedRules]]
    }

    def "a check is kept, so the Rule Author can review it later"() {
        when:
        String fileCheckId = check(ruleSetId, "refunds-week-2.csv", refundsCsv + "100236,abc,lost\n").fileCheckId
        Map fileCheck = callService("get#FileCheck", [fileCheckId: fileCheckId]).fileCheck

        then:
        fileCheck.ruleSetId == ruleSetId
        fileCheck.factTypeId == factTypeId
        fileCheck.fileName == "refunds-week-2.csv"
        fileCheck.checkedByUserId == "EX_JOHN_DOE"
        fileCheck.checkedDate != null
        fileCheck.recordCount == 3
        fileCheck.refusedCount == 1
        fileCheck.records*.recordNumber == [1, 2, 3]
        fileCheck.records[0].recordValues == [orderId: "100234", amount: "25.00", reason: "damaged"]
        fileCheck.records*.outcomes == [["PAY"], ["REVIEW"], null]
        fileCheck.records[2].refusedReasons == ["Value abc does not fit field amount of ${factTypeId}".toString()]
    }

    def "a kept check downloads as a CSV file with the outcome of each record"() {
        when:
        String fileCheckId = check(ruleSetId, "refunds-week-3.csv", refundsCsv + "100236,abc,lost\n").fileCheckId
        Map download = callService("download#FileCheck", [fileCheckId: fileCheckId])

        then:
        download.fileName == "refunds-week-3-checked.csv"
        ((String) download.fileText).readLines() == ["orderId,amount,reason,outcomes,refusedReasons",
                "100234,25.00,damaged,PAY,", "100235,40.50,late,REVIEW,",
                "100236,abc,lost,,Value abc does not fit field amount of ${factTypeId}".toString()]
    }

    def "a check that does not exist cannot be reviewed or downloaded"() {
        when:
        ec.service.sync().name("ai.rules.RuleServices." + serviceName).parameters([fileCheckId: "NO_SUCH_CHECK"]).call()
        List<String> errors = ec.message.errors.collect()
        ec.message.clearAll()

        then:
        errors == ["File check NO_SUCH_CHECK does not exist"]

        where:
        serviceName << ["get#FileCheck", "download#FileCheck"]
    }

    def "a file that cannot be checked is refused as a whole"() {
        when:
        Map checked = check(checkRuleSetId, fileName, refundsCsv)

        then:
        checked.records == null
        checked.fileCheckId == null
        checked.errors.any { it.contains(problem) }

        where:
        checkRuleSetId    | fileName      | problem
        "SWITCH_ON_A_JOB" | "refunds.csv" | "does not read fact type RULES_TEST_CHECK_REFUNDS"
        "NO_SUCH_SET"     | "refunds.csv" | "Decision NO_SUCH_SET does not exist"
        ruleSetId         | "refunds.txt" | "is not a CSV or JSON file"
    }
}
