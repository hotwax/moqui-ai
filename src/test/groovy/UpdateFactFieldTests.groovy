import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.entity.EntityCondition

class UpdateFactFieldTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String factTypeId = "RULES_TEST_KIND_FIX"
    @Shared String ruleSetId = "RULES_TEST_KIND_FIX_SET"

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.user.internalLoginUser("john.doe")
        ec.artifactExecution.disableAuthz()
        removeTestData()
        ec.entity.makeValue("moqui.ai.FactType").setAll([factTypeId: factTypeId]).create()
        [code: "FVT_INTEGER", amount: "FVT_DECIMAL", reason: "FVT_TEXT"].each { String fieldName, String valueTypeEnumId ->
            ec.entity.makeValue("moqui.ai.FactField").setAll([factTypeId: factTypeId, fieldName: fieldName,
                    valueTypeEnumId: valueTypeEnumId]).create()
        }
        ec.entity.makeValue("moqui.ai.RuleSet").setAll([ruleSetId: ruleSetId, firstMatchOnly: "N"]).create()
        ec.entity.makeValue("moqui.ai.RuleSetFactType").setAll([ruleSetId: ruleSetId, factTypeId: factTypeId]).create()
        ec.entity.makeValue("moqui.ai.RuleSetOutcome").setAll([ruleSetId: ruleSetId, outcomeId: "REVIEW"]).create()
        storeRule("code seven", 30, "code", "ENTCO_EQUALS", "7")
        storeRule("big refund", 20, "amount", "ENTCO_GREATER", "30")
        storeRule("damaged", 10, "reason", "ENTCO_LIKE", "%damage%")
    }

    def cleanupSpec() {
        removeTestData()
        ec.artifactExecution.enableAuthz()
        ec.user.logoutUser()
        ec.destroy()
    }

    void removeTestData() {
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

    void storeRule(String ruleName, int priority, String fieldName, String operatorEnumId, String compareValue) {
        ec.service.sync().name("ai.rules.RuleServices.store#Rule").parameters([ruleSetId: ruleSetId, ruleName: ruleName,
                priority: priority, conditions: [[conditionSeqId: "01", conditionTypeId: "RCT_COMPARE", factTypeId: factTypeId,
                fieldName: fieldName, operatorEnumId: operatorEnumId, compareValue: compareValue]],
                actions: [[actionTypeId: "RAT_OUTCOME", outcomeId: "REVIEW"]]]).call()
        assert !ec.message.hasError(), ec.message.errorsString
    }

    List<String> matchedRuleNames(Map fact) {
        Map result = ec.service.sync().name("ai.rules.RuleServices.evaluate#RuleSet")
                .parameters([ruleSetId: ruleSetId, facts: [[factTypeId: factTypeId, fact: fact]]]).call()
        assert !ec.message.hasError(), ec.message.errorsString
        return result.matchedRules*.ruleName
    }

    List<String> updateKind(String fieldName, String valueTypeEnumId) {
        ec.service.sync().name("ai.rules.RuleServices.update#FactField")
                .parameters([factTypeId: factTypeId, fieldName: fieldName, valueTypeEnumId: valueTypeEnumId]).call()
        List<String> errors = ec.message.errors.collect()
        ec.message.clearAll()
        return errors
    }

    String kindOf(String fieldName) {
        return ec.entity.find("moqui.ai.FactField").condition([factTypeId: factTypeId, fieldName: fieldName])
                .useCache(false).one()?.valueTypeEnumId
    }

    def "a corrected kind is used from the next check on"() {
        expect:
        matchedRuleNames([code: "007"]) == ["code seven"]

        when:
        List<String> errors = updateKind("code", "FVT_TEXT")

        then:
        errors == []
        kindOf("code") == "FVT_TEXT"
        matchedRuleNames([code: "007"]) == []
        matchedRuleNames([code: "7"]) == ["code seven"]
    }

    def "a kind that a rule line does not fit is refused, and the field keeps its kind"() {
        when:
        List<String> errors = updateKind(fieldName, valueTypeEnumId)

        then:
        errors.any { it.contains(problem) }
        kindOf(fieldName) == keptKind

        where:
        fieldName     | valueTypeEnumId | problem                                                | keptKind
        "amount"      | "FVT_BOOLEAN"   | "Rule big refund: value 30 does not fit field amount"              | "FVT_DECIMAL"
        "reason"      | "FVT_INTEGER"   | "Rule damaged: operator ENTCO_LIKE works on text fields only"               | "FVT_TEXT"
        "amount"      | "FVT_LIST"      | "can only change between kinds that hold one value"    | "FVT_DECIMAL"
        "noSuchField" | "FVT_TEXT"      | "has no field noSuchField"                             | null
    }
}
