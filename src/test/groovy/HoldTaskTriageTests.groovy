import java.sql.Timestamp
import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityValue

class HoldTaskTriageTests extends Specification {
    @Shared ExecutionContext ec
    @Shared Map<String, String> resolverByPurpose = [NEG_RES_REVIEW: "COMPANY_SERVICE", UNMAPPED_SHIP_METHOD: "COMPANY_AFTER_CONFIRM",
            SHPFY_SYNC_ERR: "COMPANY_AFTER_CONFIRM", REVIEW_RISK_ORDER: "PURPOSE_OWNER", INVALID_ADDRESS: "PURPOSE_OWNER",
            ORD_HOLD_CUST_REQ: "PURPOSE_OWNER", ORD_HOLD_MANUAL: "PURPOSE_OWNER", ORD_HOLD_FUTURE_INV: "PURPOSE_OWNER"]

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.user.internalLoginUser("john.doe")
        ec.artifactExecution.disableAuthz()
    }

    def cleanupSpec() {
        ec.artifactExecution.enableAuthz()
        ec.user.logoutUser()
        ec.destroy()
    }

    List<EntityValue> openHoldTasks() {
        ec.entity.find("org.apache.ofbiz.workeffort.workeffort.WorkEffort").condition("workEffortTypeId", "RESOLVE_ONHOLD_ORDER")
                .condition("statusId", EntityCondition.ComparisonOperator.NOT_IN, ["TASK_COMPLETED", "TASK_CANCELLED"])
                .orderBy("workEffortPurposeTypeId").orderBy("-createdStamp").list()
    }

    Map buildHoldTask(String workEffortId) {
        ec.message.clearAll()
        Map fact = ec.service.sync().name("ai.rules.RuleServices.build#Fact")
                .parameters([factTypeId: "holdTask", parameters: [workEffortId: workEffortId]]).call().fact
        assert !ec.message.hasError(), ec.message.errorsString
        return fact
    }

    String decide(String ruleSetId, Map fact) {
        Map result = ec.service.sync().name("ai.rules.RuleServices.evaluate#RuleSet")
                .parameters([ruleSetId: ruleSetId, facts: [[factTypeId: "holdTask", fact: fact]]]).call()
        assert !ec.message.hasError(), ec.message.errorsString
        assert result.outcomes.size() == 1
        return result.outcomes[0]
    }

    long wholeDaysSince(Timestamp createdStamp) {
        (ec.user.nowTimestamp.time - createdStamp.time).intdiv(24L * 60 * 60 * 1000)
    }

    String storyGroup(EntityValue task) {
        EntityValue order = ec.entity.find("org.apache.ofbiz.order.order.OrderHeaderWorkEffort")
                .condition("workEffortId", task.workEffortId).one().findRelatedOne("OrderHeader", false, false)
        if (order.statusId in ["ORDER_COMPLETED", "ORDER_CANCELLED"]) return "STALE"
        boolean netSuiteHasIt = ec.entity.find("co.hotwax.order.OrderIdentification").condition("orderId", order.orderId)
                .condition("orderIdentificationTypeId", "NETSUITE_ORDER_ID").conditionDate("fromDate", "thruDate", ec.user.nowTimestamp).count() > 0
        if (!netSuiteHasIt) return "BLOCKING"
        return wholeDaysSince(task.createdStamp) >= 7 ? "AGEING" : "RECENT"
    }

    def "every open hold task in the sample gets the group and resolver the story gives"() {
        given:
        List<EntityValue> sample = openHoldTasks().groupBy { it.workEffortPurposeTypeId }.values().collectMany { it.take(40) }
        Map<String, Integer> groupsSeen = [:]

        when:
        List<String> mismatches = []
        for (EntityValue task in sample) {
            Map fact = buildHoldTask(task.workEffortId)
            String group = decide("HOLD_TASK_GROUP", fact)
            String resolver = decide("HOLD_TASK_RESOLVER", fact)
            groupsSeen[group] = (groupsSeen[group] ?: 0) + 1
            String expectedGroup = storyGroup(task)
            String expectedResolver = resolverByPurpose[task.workEffortPurposeTypeId] ?: "ASK_SUPPORT_PERSON"
            if (group != expectedGroup || resolver != expectedResolver)
                mismatches.add("${task.workEffortId} ${task.workEffortPurposeTypeId}: got ${group}/${resolver}, story says ${expectedGroup}/${expectedResolver}".toString())
        }

        then:
        sample.size() >= 100
        mismatches == []
        groupsSeen.keySet().containsAll(["BLOCKING", "AGEING"])
    }

    def "a negative reservation task on an approved order NetSuite does not have is blocking, for Company's own service"() {
        given:
        EntityValue task = openHoldTasks().find { it.workEffortPurposeTypeId == "NEG_RES_REVIEW" && storyGroup(it) == "BLOCKING" }

        when:
        Map fact = buildHoldTask(task.workEffortId)

        then:
        fact.purposeId == "NEG_RES_REVIEW"
        fact.orderStatusId == "ORDER_APPROVED"
        fact.netSuiteIdCount == 0L
        decide("HOLD_TASK_GROUP", fact) == "BLOCKING"
        decide("HOLD_TASK_RESOLVER", fact) == "COMPANY_SERVICE"
    }

    def "an ageing task's age is its whole days since it was made"() {
        given:
        EntityValue task = openHoldTasks().find { storyGroup(it) == "AGEING" }

        when:
        Map fact = buildHoldTask(task.workEffortId)

        then:
        fact.taskAgeDays == wholeDaysSince(task.createdStamp)
        fact.taskAgeDays >= 7L
        decide("HOLD_TASK_GROUP", fact) == "AGEING"
    }

    def "a live task NetSuite has is #expectedGroup at #ageDays whole days"() {
        expect:
        decide("HOLD_TASK_GROUP", [workEffortId: "T1", purposeId: "SHPFY_SYNC_ERR", orderStatusId: "ORDER_APPROVED",
                netSuiteIdCount: 1L, taskAgeDays: ageDays]) == expectedGroup

        where:
        ageDays || expectedGroup
        6L      || "RECENT"
        7L      || "AGEING"
    }

    def "a task on a completed order is stale"() {
        given:
        EntityValue link = ec.entity.find("org.apache.ofbiz.order.order.OrderHeaderWorkEffort").condition("workEffortId",
                EntityCondition.ComparisonOperator.IN, ec.entity.find("org.apache.ofbiz.workeffort.workeffort.WorkEffort")
                .condition("workEffortTypeId", "RESOLVE_ONHOLD_ORDER").list()*.workEffortId).list()
                .find { it.findRelatedOne("OrderHeader", false, false).statusId == "ORDER_COMPLETED" }

        when:
        Map fact = buildHoldTask(link.workEffortId)

        then:
        fact.orderStatusId == "ORDER_COMPLETED"
        decide("HOLD_TASK_GROUP", fact) == "STALE"
    }

    Map triageOverSearch(Map overrides) {
        ec.message.clearAll()
        Map result = ec.service.sync().name("ai.rules.RuleServices.evaluate#RuleSetOverSearch").parameters([
                ruleSetId: "HOLD_TASK_GROUP", factTypeId: "holdTask", entityName: "org.apache.ofbiz.workeffort.workeffort.WorkEffort",
                searchParameters: [workEffortTypeId: "RESOLVE_ONHOLD_ORDER", statusId: "TASK_COMPLETED,TASK_CANCELLED",
                        statusId_op: "in", statusId_not: "Y"],
                groupByFieldNames: ["purposeId"]] + overrides).call()
        Map triaged = [result: result, errors: ec.message.errors.collect()]
        ec.message.clearAll()
        return triaged
    }

    def "one call triages every open hold task and counts the groups by purpose"() {
        given:
        List<EntityValue> tasks = openHoldTasks()
        Map<List, Integer> storyCounts = tasks.countBy { [it.workEffortPurposeTypeId, storyGroup(it)] }

        when:
        Map triaged = triageOverSearch([:])
        Map<List, Integer> counted = triaged.result.counts.collectEntries { [([it.purposeId, it.outcomeId]): it.count] }

        then:
        triaged.errors.isEmpty()
        triaged.result.rowCount == tasks.size()
        tasks.size() > 100
        counted == storyCounts
        triaged.result.results*.keys*.workEffortId as Set == tasks*.workEffortId as Set
        triaged.result.counts[0].count >= triaged.result.counts[-1].count
    }

    def "a search over an unknown entity, or counts by an unknown fact field, are refused"() {
        expect:
        triageOverSearch([entityName: "NoSuchEntity"]).errors == ["Entity NoSuchEntity does not exist"]
        triageOverSearch([groupByFieldNames: ["noSuchField"]]).errors == ["Fact type holdTask has no field noSuchField"]
    }

    def "the purpose #purposeId is resolved by #expectedResolver"() {
        expect:
        decide("HOLD_TASK_RESOLVER", [workEffortId: "T1", purposeId: purposeId, orderStatusId: "ORDER_APPROVED",
                netSuiteIdCount: 0L, taskAgeDays: 0L]) == expectedResolver

        where:
        purposeId              || expectedResolver
        "REVIEW_RISK_ORDER"    || "PURPOSE_OWNER"
        "INVALID_ADDRESS"      || "PURPOSE_OWNER"
        "ORD_HOLD_CUST_REQ"    || "PURPOSE_OWNER"
        "ORD_HOLD_MANUAL"      || "PURPOSE_OWNER"
        "ORD_HOLD_FUTURE_INV"  || "PURPOSE_OWNER"
        "SOMETHING_NEW"        || "ASK_SUPPORT_PERSON"
    }
}
