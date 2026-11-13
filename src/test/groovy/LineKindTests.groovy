import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.entity.EntityCondition

class LineKindTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String ruleSetId = "RULES_TEST_LINE_KINDS"

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.user.internalLoginUser("john.doe")
        ec.artifactExecution.disableAuthz()
        removeDecision()
        ec.entity.makeValue("moqui.ai.RuleSet").setAll([ruleSetId: ruleSetId, firstMatchOnly: "N"]).create()
        for (String factTypeId in ["checkResult", "salesOrder"])
            ec.entity.makeValue("moqui.ai.RuleSetFactType").setAll([ruleSetId: ruleSetId, factTypeId: factTypeId]).create()
        for (String outcomeId in ["NEITHER", "NO_DIGITAL"])
            ec.entity.makeValue("moqui.ai.RuleSetOutcome").setAll([ruleSetId: ruleSetId, outcomeId: outcomeId]).create()
        assert storeRule("neither", 20, "NEITHER", [[conditionSeqId: "01", conditionTypeId: "RCT_NOT"],
                compare("02", "checkResult", "refusedCount", "0", "01"),
                compare("03", "checkResult", "logStatusId", "DmlsFinished", "01")]) == []
        assert storeRule("no digital item", 10, "NO_DIGITAL", [[conditionSeqId: "01", conditionTypeId: "RCT_NOT"],
                [conditionSeqId: "02", parentConditionSeqId: "01", conditionTypeId: "RCT_EXISTS", factTypeId: "salesOrder", fieldName: "items"],
                compare("03", "orderItem", "productTypeId", "DIGITAL_GOOD", "02")]) == []
    }

    def cleanupSpec() {
        removeDecision()
        ec.artifactExecution.enableAuthz()
        ec.user.logoutUser()
        ec.destroy()
    }

    void removeDecision() {
        List<String> ruleIds = ec.entity.find("moqui.ai.RuleSetRule").condition("ruleSetId", ruleSetId).list()*.ruleId
        if (ruleIds) {
            for (int depth = 0; depth < 3; depth++)
                ec.entity.find("moqui.ai.RuleSetCondition").condition("ruleId", "in", ruleIds)
                        .condition("parentConditionSeqId", EntityCondition.ComparisonOperator.IS_NOT_NULL, null).list()
                        .findAll { line -> !ec.entity.find("moqui.ai.RuleSetCondition").condition([ruleId: line.ruleId,
                                parentConditionSeqId: line.conditionSeqId]).count() }
                        .each { it.delete() }
            for (String entityName in ["RuleSetActionField", "RuleSetAction", "RuleSetCondition", "RuleSetRule"])
                ec.entity.find("moqui.ai." + entityName).condition("ruleId", "in", ruleIds).deleteAll()
        }
        for (String entityName in ["RuleSetOutcome", "RuleSetFactType", "RuleSet"])
            ec.entity.find("moqui.ai." + entityName).condition("ruleSetId", ruleSetId).deleteAll()
    }

    static Map compare(String seqId, String factTypeId, String fieldName, String compareValue, String parentSeqId) {
        [conditionSeqId: seqId, parentConditionSeqId: parentSeqId, conditionTypeId: "RCT_COMPARE", factTypeId: factTypeId,
         fieldName: fieldName, operatorEnumId: "ENTCO_EQUALS", compareValue: compareValue]
    }

    List<String> storeRule(String ruleName, int priority, String outcomeId, List<Map> conditions) {
        ec.message.clearAll()
        ec.service.sync().name("ai.rules.RuleServices.store#Rule").parameters([ruleSetId: ruleSetId, ruleName: ruleName,
                priority: priority, conditions: conditions, actions: [[actionTypeId: "RAT_OUTCOME", outcomeId: outcomeId]]]).call()
        List<String> errors = ec.message.errors.collect()
        ec.message.clearAll()
        return errors
    }

    List<String> outcomesFor(String factTypeId, Map fact) {
        Map result = ec.service.sync().name("ai.rules.RuleServices.evaluate#RuleSet")
                .parameters([ruleSetId: ruleSetId, facts: [[factTypeId: factTypeId, fact: fact]]]).call()
        assert !ec.message.hasError(), ec.message.errorsString
        return result.outcomes
    }

    def "a not line is true only when none of its lines match: #story"() {
        expect:
        outcomesFor("checkResult", [refusedCount: refused, logStatusId: status, tryCount: 1]) == expected

        where:
        story                              | refused | status         || expected
        "refused, finished"                | 2       | "DmlsFinished" || []
        "refused, still running"           | 2       | "DmlsRunning"  || ["NEITHER"]
        "none refused, still running"      | 0       | "DmlsRunning"  || []
        "no refused count and no status"   | null    | null           || ["NEITHER"]
    }

    def "a not line over an exists line is true when no item matches"() {
        expect:
        outcomesFor("salesOrder", [orderId: "T1", salesChannelEnumId: "WEB",
                items: [[orderItemSeqId: "01", productId: "P1", productTypeId: "FINISHED_GOOD"]]]) == ["NO_DIGITAL"]
        outcomesFor("salesOrder", [orderId: "T2", salesChannelEnumId: "WEB",
                items: [[orderItemSeqId: "01", productId: "P1", productTypeId: "FINISHED_GOOD"],
                        [orderItemSeqId: "02", productId: "P2", productTypeId: "DIGITAL_GOOD"]]]) == []
        outcomesFor("salesOrder", [orderId: "T3", salesChannelEnumId: "WEB", items: []]) == ["NO_DIGITAL"]
    }

    def "an exists line inside an any line is refused"() {
        when:
        List<String> errors = storeRule("web or digital", 30, "NO_DIGITAL", [[conditionSeqId: "01", conditionTypeId: "RCT_ANY"],
                compare("02", "salesOrder", "salesChannelEnumId", "WEB", "01"),
                [conditionSeqId: "03", parentConditionSeqId: "01", conditionTypeId: "RCT_EXISTS", factTypeId: "salesOrder", fieldName: "items"],
                compare("04", "orderItem", "productTypeId", "DIGITAL_GOOD", "03")])

        then:
        errors == ["Condition 01 of rule web or digital: an exists line cannot be one choice of an any line, write a second rule instead"]
    }

    def "an empty #kind line is refused"() {
        when:
        List<String> errors = storeRule("empty group", 40, "NEITHER", [[conditionSeqId: "01", conditionTypeId: conditionTypeId]])

        then:
        errors == ["Condition 01 of rule empty group: ${expectedText}".toString()]

        where:
        kind  | conditionTypeId || expectedText
        "all" | "RCT_ALL"       || "an all line needs at least one line under it"
        "any" | "RCT_ANY"       || "an any line needs at least one line under it"
        "not" | "RCT_NOT"       || "a not line needs at least one line under it"
    }
}
