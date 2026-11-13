import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityValue

class EvaluateRuleSetTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String ruleSetId = "RULES_TEST_EVALUATE"
    @Shared String groupRuleSetId = "RULES_TEST_GROUPS"

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.user.internalLoginUser("john.doe")
        ec.artifactExecution.disableAuthz()
        removeDecision(ruleSetId)
        removeDecision(groupRuleSetId)
        createDecision(ruleSetId)
        storeRule(ruleSetId, "switch on", 30, "SWITCH_ON", [
                compare("01", "refusedCount", "ENTCO_EQUALS", "0"), compare("02", "logStatusId", "ENTCO_EQUALS", "DmlsFinished")])
        storeRule(ruleSetId, "check again", 20, "CHECK_AGAIN", [
                compare("01", "logStatusId", "ENTCO_NOT_EQUALS", "DmlsFinished"), compare("02", "tryCount", "ENTCO_LESS", "3")])
        storeRule(ruleSetId, "put back", 10, "PUT_BACK", [])
        createDecision(groupRuleSetId)
        storeRule(groupRuleSetId, "stop now", 40, "PUT_BACK", [
                [conditionSeqId: "01", conditionTypeId: "RCT_ANY"],
                compare("02", "refusedCount", "ENTCO_GREATER", "100") + [parentConditionSeqId: "01"],
                compare("03", "logStatusId", "ENTCO_LIKE", "%fail%") + [parentConditionSeqId: "01"]])
        storeRule(groupRuleSetId, "not stopped", 30, "SWITCH_ON", [
                [conditionSeqId: "01", conditionTypeId: "RCT_NOT"],
                compare("02", "logStatusId", "ENTCO_IS_NULL", null) + [parentConditionSeqId: "01"]])
    }

    def cleanupSpec() {
        removeDecision(ruleSetId)
        removeDecision(groupRuleSetId)
        ec.artifactExecution.enableAuthz()
        ec.user.logoutUser()
        ec.destroy()
    }

    void createDecision(String id) {
        ec.entity.makeValue("moqui.ai.RuleSet").setAll([ruleSetId: id, firstMatchOnly: "Y"]).create()
        ec.entity.makeValue("moqui.ai.RuleSetFactType").setAll([ruleSetId: id, factTypeId: "checkResult"]).create()
        for (String outcomeId in ["SWITCH_ON", "CHECK_AGAIN", "PUT_BACK"])
            ec.entity.makeValue("moqui.ai.RuleSetOutcome").setAll([ruleSetId: id, outcomeId: outcomeId]).create()
    }

    void removeDecision(String id) {
        List<String> ruleIds = ec.entity.find("moqui.ai.RuleSetRule").condition("ruleSetId", id).list()*.ruleId
        if (ruleIds) {
            ec.entity.find("moqui.ai.RuleSetCondition").condition("ruleId", "in", ruleIds)
                    .condition("parentConditionSeqId", EntityCondition.ComparisonOperator.IS_NOT_NULL, null).deleteAll()
            for (String entityName in ["RuleSetActionField", "RuleSetAction", "RuleSetCondition", "RuleSetRule"])
                ec.entity.find("moqui.ai." + entityName).condition("ruleId", "in", ruleIds).deleteAll()
        }
        for (String entityName in ["RuleSetOutcome", "RuleSetFactType", "RuleSet"])
            ec.entity.find("moqui.ai." + entityName).condition("ruleSetId", id).deleteAll()
    }

    static Map compare(String seqId, String fieldName, String operatorEnumId, String compareValue) {
        [conditionSeqId: seqId, conditionTypeId: "RCT_COMPARE", factTypeId: "checkResult", fieldName: fieldName,
         operatorEnumId: operatorEnumId, compareValue: compareValue]
    }

    void storeRule(String id, String ruleName, int priority, String outcomeId, List<Map> conditions) {
        ec.message.clearAll()
        ec.service.sync().name("ai.rules.RuleServices.store#Rule").parameters([ruleSetId: id, ruleName: ruleName,
                priority: priority, conditions: conditions, actions: [[actionTypeId: "RAT_OUTCOME", outcomeId: outcomeId]]]).call()
        assert !ec.message.hasError(), ec.message.errorsString
    }

    Map evaluate(String id, Map fact, String factTypeId = "checkResult") {
        ec.message.clearAll()
        Map result = ec.service.sync().name("ai.rules.RuleServices.evaluate#RuleSet")
                .parameters([ruleSetId: id, facts: [[factTypeId: factTypeId, fact: fact]]]).call()
        Map evaluated = [outcomes: result?.outcomes, matchedRules: result?.matchedRules, errors: ec.message.errors.collect()]
        ec.message.clearAll()
        return evaluated
    }

    @Unroll
    def "#story returns #expected"() {
        when:
        Map evaluated = evaluate(ruleSetId, [refusedCount: refused, logStatusId: status, tryCount: tries])

        then:
        evaluated.errors.isEmpty()
        evaluated.outcomes == [expected]

        where:
        story                                 | refused | status         | tries || expected
        "0 refused and finished"              | 0       | "DmlsFinished" | 1     || "SWITCH_ON"
        "not finished, first try"             | 0       | "DmlsRunning"  | 1     || "CHECK_AGAIN"
        "2 refused and finished"              | 2       | "DmlsFinished" | 1     || "PUT_BACK"
        "not finished after 3 tries"          | 0       | "DmlsRunning"  | 3     || "PUT_BACK"
        "no log found yet, first try"         | null    | null           | 1     || "CHECK_AGAIN"
        "not finished, try count unknown"     | 0       | "DmlsRunning"  | null  || "CHECK_AGAIN"
    }

    def "every matching rule is listed, highest priority first"() {
        when:
        Map evaluated = evaluate(ruleSetId, [refusedCount: 0, logStatusId: "DmlsFinished", tryCount: 1])

        then:
        evaluated.matchedRules*.ruleName == ["switch on", "put back"]
        evaluated.matchedRules*.outcomeId == ["SWITCH_ON", "PUT_BACK"]
    }

    def "step 5: after the rule changes to accept one refused record, the next call switches on"() {
        given:
        Map fact = [refusedCount: 1, logStatusId: "DmlsFinished", tryCount: 1]
        Map before = evaluate(ruleSetId, fact)

        when:
        storeRule(ruleSetId, "switch on", 30, "SWITCH_ON", [
                compare("01", "refusedCount", "ENTCO_LESS_EQ", "1"), compare("02", "logStatusId", "ENTCO_EQUALS", "DmlsFinished")])
        Map after = evaluate(ruleSetId, fact)

        then:
        before.outcomes == ["PUT_BACK"]
        after.errors.isEmpty()
        after.outcomes == ["SWITCH_ON"]
    }

    def "a rule line changed or removed outside store#Rule is used on the next call"() {
        given:
        String switchOnRuleId = ec.entity.find("moqui.ai.RuleSetRule").condition([ruleSetId: ruleSetId, ruleName: "switch on"])
                .one().ruleId
        Map oneRefused = [refusedCount: 1, logStatusId: "DmlsFinished", tryCount: 1]
        Map twoRefused = [refusedCount: 2, logStatusId: "DmlsFinished", tryCount: 1]

        when:
        ec.entity.find("moqui.ai.RuleSetCondition").condition([ruleId: switchOnRuleId, conditionSeqId: "01"]).one()
                .setAll([operatorEnumId: "ENTCO_EQUALS", compareValue: "0"]).update()
        Map afterChange = evaluate(ruleSetId, oneRefused)
        ec.entity.find("moqui.ai.RuleSetCondition").condition([ruleId: switchOnRuleId, conditionSeqId: "01"]).one().delete()
        Map afterRemove = evaluate(ruleSetId, twoRefused)

        then:
        afterChange.outcomes == ["PUT_BACK"]
        afterRemove.outcomes == ["SWITCH_ON"]
    }

    @Unroll
    def "group lines: #story returns #expected"() {
        when:
        Map evaluated = evaluate(groupRuleSetId, [refusedCount: refused, logStatusId: status, tryCount: 1])

        then:
        evaluated.errors.isEmpty()
        evaluated.outcomes == expected

        where:
        story                                   | refused | status         || expected
        "any: more than 100 refused"            | 101     | "DmlsFinished" || ["PUT_BACK"]
        "any: a status like fail, any case"     | 0       | "DmlsFailed"   || ["PUT_BACK"]
        "not: a status that is present"         | 0       | "DmlsFinished" || ["SWITCH_ON"]
        "neither: no status at all"             | 0       | null           || []
    }

    def "the shipped switch-on decision runs on a fact built from a real log"() {
        given:
        EntityValue log = ec.entity.find("co.hotwax.datamanager.DataManagerLog")
                .condition("failedRecordCount", EntityCondition.GREATER_THAN, 0).condition("statusId", "DmlsFinished")
                .orderBy("-createdDate").limit(1).list().getFirst()
        Map fact = ec.service.sync().name("ai.rules.RuleServices.build#Fact")
                .parameters([factTypeId: "checkResult", parameters: [logId: log.logId, tryCount: 1]]).call().fact

        when:
        Map evaluated = evaluate("SWITCH_ON_A_JOB", fact)

        then:
        log != null
        fact.refusedCount > 0
        evaluated.errors.isEmpty()
        evaluated.outcomes == ["PUT_BACK"]
    }

    def "a field the fact type does not have is refused"() {
        when:
        Map evaluated = evaluate(ruleSetId, [refusedCount: 0, createdCount: 5])

        then:
        evaluated.outcomes == null
        evaluated.errors.any { it.contains("Fact type checkResult has no field createdCount") }
    }

    def "a fact type the decision does not read is refused"() {
        when:
        Map evaluated = evaluate(ruleSetId, [anything: 1], "salesOrder")

        then:
        evaluated.outcomes == null
        evaluated.errors.any { it.contains("does not read fact type salesOrder") }
    }
}
