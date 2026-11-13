import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityValue

class StoreRuleTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String ruleSetId = "RULES_TEST_SWITCH_ON"

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.user.internalLoginUser("john.doe")
        ec.artifactExecution.disableAuthz()
        removeTestDecision()
        ec.entity.makeValue("moqui.ai.RuleSet").setAll([ruleSetId: ruleSetId, firstMatchOnly: "Y"]).create()
        ec.entity.makeValue("moqui.ai.RuleSetFactType").setAll([ruleSetId: ruleSetId, factTypeId: "checkResult"]).create()
        for (String outcomeId in ["SWITCH_ON", "CHECK_AGAIN", "PUT_BACK"])
            ec.entity.makeValue("moqui.ai.RuleSetOutcome").setAll([ruleSetId: ruleSetId, outcomeId: outcomeId]).create()
    }

    def cleanupSpec() {
        removeTestDecision()
        ec.artifactExecution.enableAuthz()
        ec.user.logoutUser()
        ec.destroy()
    }

    void removeTestDecision() {
        List<String> ruleIds = ec.entity.find("moqui.ai.RuleSetRule").condition("ruleSetId", ruleSetId).list()*.ruleId
        if (ruleIds) {
            ec.entity.find("moqui.ai.RuleSetCondition").condition("ruleId", "in", ruleIds)
                    .condition("parentConditionSeqId", EntityCondition.ComparisonOperator.IS_NOT_NULL, null).deleteAll()
            for (String entityName in ["RuleSetActionField", "RuleSetAction", "RuleSetCondition", "RuleSetRule"])
                ec.entity.find("moqui.ai." + entityName).condition("ruleId", "in", ruleIds).deleteAll()
        }
        for (String entityName in ["RuleSetOutcome", "RuleSetFactType", "RuleSet"])
            ec.entity.find("moqui.ai." + entityName).condition("ruleSetId", ruleSetId).deleteAll()
    }

    Map storeSwitchOn(List<Map> conditions, Integer priority = 30, String outcomeId = "SWITCH_ON") {
        ec.message.clearAll()
        Map result = ec.service.sync().name("ai.rules.RuleServices.store#Rule").parameters([
                ruleSetId: ruleSetId, ruleName: "switch on", priority: priority, conditions: conditions,
                actions: [[actionTypeId: "RAT_OUTCOME", outcomeId: outcomeId]]]).call()
        Map stored = [ruleId: result?.ruleId, errors: ec.message.errors.collect()]
        ec.message.clearAll()
        return stored
    }

    static List<Map> refusedLine(String operatorEnumId, String compareValue, String fieldName = "refusedCount") {
        [[conditionSeqId: "01", conditionTypeId: "RCT_COMPARE", factTypeId: "checkResult", fieldName: fieldName,
          operatorEnumId: operatorEnumId, compareValue: compareValue]]
    }

    List<EntityValue> switchOnRules() {
        ec.entity.find("moqui.ai.RuleSetRule").condition("ruleSetId", ruleSetId).condition("ruleName", "switch on")
                .useCache(false).list()
    }

    List<EntityValue> switchOnLines() {
        ec.entity.find("moqui.ai.RuleSetCondition").condition("ruleId", switchOnRules()[0].ruleId).useCache(false).list()
    }

    List<EntityValue> auditOf(String fieldName) {
        ec.entity.find("moqui.entity.EntityAuditLog").condition("changedEntityName", "moqui.ai.RuleSetCondition")
                .condition("changedFieldName", fieldName).condition("pkPrimaryValue", switchOnRules()[0].ruleId)
                .orderBy("changedDate").useCache(false).list()
    }

    def "step 3: a change edits the rule in place"() {
        when:
        Map first = storeSwitchOn(refusedLine("ENTCO_EQUALS", "0"))
        Map second = storeSwitchOn(refusedLine("ENTCO_LESS_EQ", "1"))

        then:
        first.errors.isEmpty()
        second.errors.isEmpty()
        second.ruleId == first.ruleId
        switchOnRules().size() == 1
        switchOnLines().size() == 1
        switchOnLines()[0].operatorEnumId == "ENTCO_LESS_EQ"
        switchOnLines()[0].compareValue == "1"
    }

    @Unroll
    def "step 4: #change is refused and nothing is saved"() {
        when:
        Map stored = storeSwitchOn(refusedLine(operatorEnumId, compareValue, fieldName), 30, outcomeId)

        then:
        stored.ruleId == null
        stored.errors.any { it.contains(expectedText) }
        switchOnRules().size() == 1
        switchOnLines()[0].compareValue == "1"

        where:
        change                         | fieldName      | operatorEnumId | compareValue | outcomeId    | expectedText
        "a field the decision lacks"   | "createdCount" | "ENTCO_EQUALS" | "0"          | "SWITCH_ON"  | "does not read field createdCount"
        "an outcome not in the list"   | "refusedCount" | "ENTCO_EQUALS" | "0"          | "SWITCH_OFF" | "outcome SWITCH_OFF is not in the list"
        "a value that is not a number" | "refusedCount" | "ENTCO_EQUALS" | "abc"        | "SWITCH_ON"  | "value abc does not fit field refusedCount"
        "an operator not allowed"      | "refusedCount" | "ENTCO_IN"     | "0"          | "SWITCH_ON"  | "operator ENTCO_IN is not allowed"
    }

    def "step 4: #change is refused, and the rule keeps its lines"() {
        when:
        Map stored = storeSwitchOn(conditions)

        then:
        stored.ruleId == null
        stored.errors.any { it.contains(expectedText) }
        switchOnLines()*.conditionSeqId == ["01"]

        where:
        change                         | conditions                                                                                    | expectedText
        "a line under a compare line"  | [compareLine("01", "refusedCount", "0"), compareLine("02", "tryCount", "1", "01")]          | "Condition 01 of rule switch on: a compare line takes no lines under it"
        "a parent not in the rule"     | [compareLine("02", "tryCount", "1", "09")]                                                    | "Condition 02 of rule switch on: its parent 09 is not a line of this rule"
        "an unknown line type"         | [[conditionSeqId: "01", conditionTypeId: "RCT_SOMETIMES"]]                                | "condition type RCT_SOMETIMES is not known"
        "an exists line on a number"   | [[conditionSeqId: "01", conditionTypeId: "RCT_EXISTS", factTypeId: "checkResult", fieldName: "tryCount"],
                                          compareLine("02", "tryCount", "1", "01")]                                                    | "tryCount of checkResult is not a list field the decision reads"
        "a line with no seq id"        | [compareLine(null, "tryCount", "1")]                                                          | "Each condition needs a conditionSeqId"
    }

    def "step 4: a priority another rule has is refused"() {
        given:
        ec.message.clearAll()
        ec.service.sync().name("ai.rules.RuleServices.store#Rule").parameters([ruleSetId: ruleSetId,
                ruleName: "put back", priority: 10, actions: [[actionTypeId: "RAT_OUTCOME", outcomeId: "PUT_BACK"]]]).call()

        when:
        Map stored = storeSwitchOn(refusedLine("ENTCO_EQUALS", "0"), 10)

        then:
        stored.ruleId == null
        stored.errors.any { it.contains("Priority 10 is already used by rule put back") }
        switchOnRules()[0].priority == 30
    }

    def "step 6: the change log keeps the old value, the new value, who and when"() {
        when:
        List<EntityValue> valueLog = auditOf("compareValue")
        List<EntityValue> operatorLog = auditOf("operatorEnumId")

        then:
        valueLog*.newValueText == ["0", "1"]
        valueLog*.oldValueText == [null, "0"]
        operatorLog*.newValueText == ["ENTCO_EQUALS", "ENTCO_LESS_EQ"]
        (valueLog + operatorLog).every { it.changedByUserId == "EX_JOHN_DOE" && it.changedDate != null }
    }

    def "a removed line is deleted and the rule keeps working"() {
        when:
        Map stored = storeSwitchOn([])

        then:
        stored.errors.isEmpty()
        switchOnLines().isEmpty()
        switchOnRules().size() == 1
    }

    def "withdraw: recording the old rule again restores it"() {
        when:
        Map withdrawn = storeSwitchOn(refusedLine("ENTCO_EQUALS", "0"))

        then:
        withdrawn.errors.isEmpty()
        switchOnRules().size() == 1
        switchOnLines()[0].operatorEnumId == "ENTCO_EQUALS"
        switchOnLines()[0].compareValue == "0"
    }

    List<String> storeGrouped(List<Map> conditions, List<Map> actions) {
        ec.message.clearAll()
        ec.service.sync().name("ai.rules.RuleServices.store#Rule").parameters([ruleSetId: ruleSetId, ruleName: "grouped",
                priority: 50, conditions: conditions, actions: actions]).call()
        List<String> errors = ec.message.errors.collect()
        ec.message.clearAll()
        return errors
    }

    static Map compareLine(String seqId, String fieldName, String compareValue, String parentSeqId = null) {
        [conditionSeqId: seqId, parentConditionSeqId: parentSeqId, conditionTypeId: "RCT_COMPARE", factTypeId: "checkResult",
         fieldName: fieldName, operatorEnumId: "ENTCO_EQUALS", compareValue: compareValue]
    }

    static Map addFactAction(Map<String, String> fieldValues) {
        [actionTypeId: "RAT_ADD_FACT", factTypeId: "checkResult",
         fields: fieldValues.collect { String fieldName, String fixedValue -> [fieldName: fieldName, fixedValue: fixedValue] }]
    }

    def "every bad action is reported at once, and nothing is saved"() {
        when:
        List<String> errors = storeGrouped([compareLine("01", "refusedCount", "0")], [
                [actionTypeId: "RAT_OUTCOME", outcomeId: "SWITCH_ONN"],
                addFactAction([tryCount: "abc"]),
                [actionTypeId: "RAT_SOMETIMES"]])

        then:
        errors.containsAll(["Action 1: outcome SWITCH_ONN is not in the list of decision ${ruleSetId}".toString(),
                "Action 2: value abc does not fit field tryCount", "Action 3: action type RAT_SOMETIMES is not known"])
        ec.entity.find("moqui.ai.RuleSetRule").condition([ruleSetId: ruleSetId, ruleName: "grouped"]).count() == 0
    }

    def "an edit can turn a line into a group, add lines under it, and change, add and remove actions"() {
        given:
        storeGrouped([compareLine("01", "refusedCount", "0")],
                [[actionTypeId: "RAT_OUTCOME", outcomeId: "SWITCH_ON"], addFactAction([tryCount: "2", logStatusId: "DmlsFinished"])])
        String ruleId = ec.entity.find("moqui.ai.RuleSetRule").condition([ruleSetId: ruleSetId, ruleName: "grouped"]).one().ruleId
        Closure<List<EntityValue>> rowsOf = { String entityName ->
            ec.entity.find("moqui.ai." + entityName).condition("ruleId", ruleId).useCache(false).list()
        }
        List<String> firstActionSeqIds = rowsOf("RuleSetAction")*.actionSeqId.sort()

        when:
        List<String> groupErrors = storeGrouped([[conditionSeqId: "01", conditionTypeId: "RCT_ANY"],
                compareLine("02", "refusedCount", "0", "01"), compareLine("03", "logStatusId", "DmlsFinished", "01")],
                [[actionTypeId: "RAT_OUTCOME", outcomeId: "CHECK_AGAIN"], addFactAction([tryCount: "3"])])
        Map<String, EntityValue> linesBySeqId = rowsOf("RuleSetCondition").collectEntries { [(it.conditionSeqId): it] }
        List<EntityValue> groupActions = rowsOf("RuleSetAction").sort { it.actionSeqId }
        List<EntityValue> groupActionFields = rowsOf("RuleSetActionField")
        List<String> shortErrors = storeGrouped([], [[actionTypeId: "RAT_OUTCOME", outcomeId: "PUT_BACK"]])

        then:
        groupErrors == []
        linesBySeqId.keySet() == ["01", "02", "03"] as Set
        linesBySeqId["01"].conditionTypeId == "RCT_ANY"
        linesBySeqId["01"].fieldName == null
        linesBySeqId["02"].parentConditionSeqId == "01"
        linesBySeqId["03"].parentConditionSeqId == "01"
        groupActions*.actionSeqId == firstActionSeqIds
        groupActions*.outcomeId == ["CHECK_AGAIN", null]
        groupActionFields.collect { [it.fieldName, it.fixedValue] } == [["tryCount", "3"]]

        shortErrors == []
        rowsOf("RuleSetCondition").isEmpty()
        rowsOf("RuleSetAction")*.outcomeId == ["PUT_BACK"]
        rowsOf("RuleSetActionField").isEmpty()
    }
}
