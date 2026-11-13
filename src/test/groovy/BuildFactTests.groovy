import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityValue

class BuildFactTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String runningFactTypeId = "RULES_TEST_JOB_RUNNING"
    @Shared String refusedLogFactTypeId = "RULES_TEST_REFUSED_LOG"
    @Shared String brokenFactTypeId = "RULES_TEST_BROKEN"
    @Shared String currentIdsFactTypeId = "RULES_TEST_CURRENT_IDS"

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.user.internalLoginUser("john.doe")
        ec.artifactExecution.disableAuthz()
        removeTestFactType()
        ec.entity.makeValue("moqui.ai.FactType").setAll([factTypeId: runningFactTypeId]).create()
        ec.entity.makeValue("moqui.ai.FactField").setAll([factTypeId: runningFactTypeId, fieldName: "runningCount",
                valueTypeEnumId: "FVT_INTEGER"]).create()
        ec.entity.makeValue("moqui.ai.FactNode").setAll([factTypeId: runningFactTypeId, nodeSeqId: "01", sequenceNum: 1,
                nodeTypeEnumId: "FNT_COUNT", fieldName: "runningCount", entityName: "moqui.service.job.ServiceJobRun"]).create()
        ec.entity.makeValue("moqui.ai.FactNodeCondition").setAll([factTypeId: runningFactTypeId, nodeSeqId: "01",
                conditionSeqId: "01", entityFieldName: "jobName", operatorEnumId: "ENTCO_EQUALS", parameterName: "jobName"]).create()
        ec.entity.makeValue("moqui.ai.FactNodeCondition").setAll([factTypeId: runningFactTypeId, nodeSeqId: "01",
                conditionSeqId: "02", entityFieldName: "endTime", operatorEnumId: "ENTCO_IS_NULL"]).create()
        ec.entity.makeValue("moqui.ai.FactType").setAll([factTypeId: refusedLogFactTypeId]).create()
        ec.entity.makeValue("moqui.ai.FactField").setAll([factTypeId: refusedLogFactTypeId, fieldName: "refusedLogCount",
                valueTypeEnumId: "FVT_INTEGER"]).create()
        ec.entity.makeValue("moqui.ai.FactNode").setAll([factTypeId: refusedLogFactTypeId, nodeSeqId: "01", sequenceNum: 1,
                nodeTypeEnumId: "FNT_COUNT", fieldName: "refusedLogCount", entityName: "co.hotwax.datamanager.DataManagerLog"]).create()
        ec.entity.makeValue("moqui.ai.FactNodeCondition").setAll([factTypeId: refusedLogFactTypeId, nodeSeqId: "01",
                conditionSeqId: "01", entityFieldName: "logId", operatorEnumId: "ENTCO_EQUALS", parameterName: "logId"]).create()
        ec.entity.makeValue("moqui.ai.FactNodeCondition").setAll([factTypeId: refusedLogFactTypeId, nodeSeqId: "01",
                conditionSeqId: "02", entityFieldName: "failedRecordCount", operatorEnumId: "ENTCO_GREATER", fixedValue: "0"]).create()
        ec.entity.makeValue("moqui.ai.FactType").setAll([factTypeId: brokenFactTypeId]).create()
        ec.entity.makeValue("moqui.ai.FactField").setAll([factTypeId: brokenFactTypeId, fieldName: "status",
                valueTypeEnumId: "FVT_TEXT"]).create()
        ec.entity.makeValue("moqui.ai.FactNode").setAll([factTypeId: brokenFactTypeId, nodeSeqId: "01", sequenceNum: 1,
                nodeTypeEnumId: "FNT_RECORD", entityName: "co.hotwax.datamanager.DataManagerLog"]).create()
        ec.entity.makeValue("moqui.ai.FactNode").setAll([factTypeId: brokenFactTypeId, nodeSeqId: "02", parentNodeSeqId: "01",
                sequenceNum: 2, nodeTypeEnumId: "FNT_VALUE", fieldName: "status", entityFieldName: "noSuchField"]).create()
        ec.entity.makeValue("moqui.ai.FactNode").setAll([factTypeId: brokenFactTypeId, nodeSeqId: "03", parentNodeSeqId: "01",
                sequenceNum: 3, nodeTypeEnumId: "FNT_COUNT", fieldName: "status", relationshipName: "NoSuchRelationship"]).create()
        ec.entity.makeValue("moqui.ai.FactType").setAll([factTypeId: currentIdsFactTypeId]).create()
        ec.entity.makeValue("moqui.ai.FactField").setAll([factTypeId: currentIdsFactTypeId, fieldName: "netSuiteIdCount",
                valueTypeEnumId: "FVT_INTEGER"]).create()
        ec.entity.makeValue("moqui.ai.FactNode").setAll([factTypeId: currentIdsFactTypeId, nodeSeqId: "01", sequenceNum: 1,
                nodeTypeEnumId: "FNT_COUNT", fieldName: "netSuiteIdCount", entityName: "co.hotwax.order.OrderIdentification",
                filterByDate: "Y"]).create()
        ec.entity.makeValue("moqui.ai.FactNodeCondition").setAll([factTypeId: currentIdsFactTypeId, nodeSeqId: "01",
                conditionSeqId: "01", entityFieldName: "orderId", operatorEnumId: "ENTCO_EQUALS", parameterName: "orderId"]).create()
        ec.entity.makeValue("moqui.ai.FactNodeCondition").setAll([factTypeId: currentIdsFactTypeId, nodeSeqId: "01",
                conditionSeqId: "02", entityFieldName: "orderIdentificationTypeId", operatorEnumId: "ENTCO_EQUALS",
                fixedValue: "NETSUITE_ORDER_ID"]).create()
    }

    def cleanupSpec() {
        removeTestFactType()
        ec.artifactExecution.enableAuthz()
        ec.user.logoutUser()
        ec.destroy()
    }

    void removeTestFactType() {
        for (String factTypeId in [runningFactTypeId, refusedLogFactTypeId, brokenFactTypeId, currentIdsFactTypeId]) {
            ec.entity.find("moqui.ai.FactNodeCondition").condition("factTypeId", factTypeId).deleteAll()
            ec.entity.find("moqui.ai.FactNode").condition("factTypeId", factTypeId)
                    .condition("parentNodeSeqId", EntityCondition.ComparisonOperator.IS_NOT_NULL, null).deleteAll()
            for (String entityName in ["FactNode", "FactField", "FactType"])
                ec.entity.find("moqui.ai." + entityName).condition("factTypeId", factTypeId).deleteAll()
        }
    }

    Map buildFact(String factTypeId, Map parameters) {
        ec.message.clearAll()
        Map result = ec.service.sync().name("ai.rules.RuleServices.build#Fact")
                .parameters([factTypeId: factTypeId, parameters: parameters]).call()
        Map built = [fact: result?.fact, errors: ec.message.errors.collect()]
        ec.message.clearAll()
        return built
    }

    def "checkResult is built from a real Data Manager log with refused records"() {
        given:
        EntityValue log = ec.entity.find("co.hotwax.datamanager.DataManagerLog")
                .condition("failedRecordCount", EntityCondition.GREATER_THAN, 0).orderBy("-createdDate").limit(1).list().getFirst()

        when:
        Map built = buildFact("checkResult", [logId: log.logId, tryCount: 2])

        then:
        log != null
        built.errors.isEmpty()
        built.fact == [refusedCount: log.failedRecordCount as Long, logStatusId: log.statusId, tryCount: 2L]
        built.fact.tryCount instanceof Long
        built.fact.refusedCount > 0
    }

    def "a log id with no row gives empty log values and keeps the caller value"() {
        when:
        Map built = buildFact("checkResult", [logId: "NO_SUCH_LOG", tryCount: 1])

        then:
        built.errors.isEmpty()
        built.fact == [refusedCount: null, logStatusId: null, tryCount: 1L]
    }

    def "a count node returns 0 when no row matches"() {
        when:
        Map built = buildFact(runningFactTypeId, [jobName: "NO_SUCH_JOB"])

        then:
        built.errors.isEmpty()
        built.fact == [runningCount: 0L]
    }

    def "a count node counts only the rows its conditions match"() {
        given:
        EntityValue runningRun = ec.entity.find("moqui.service.job.ServiceJobRun").condition("endTime", null)
                .limit(1).list().getFirst()
        long expectedCount = ec.entity.find("moqui.service.job.ServiceJobRun").condition("jobName", runningRun.jobName)
                .condition("endTime", null).count()
        long allRunsOfJob = ec.entity.find("moqui.service.job.ServiceJobRun").condition("jobName", runningRun.jobName).count()

        when:
        Map built = buildFact(runningFactTypeId, [jobName: runningRun.jobName])

        then:
        runningRun != null
        built.errors.isEmpty()
        built.fact.runningCount == expectedCount
        built.fact.runningCount > 0
        built.fact.runningCount <= allRunsOfJob
    }

    def "a fixed number value in a node condition is compared as a number"() {
        given:
        EntityValue refusedLog = ec.entity.find("co.hotwax.datamanager.DataManagerLog")
                .condition("failedRecordCount", EntityCondition.GREATER_THAN, 9).limit(1).list().getFirst()
        EntityValue cleanLog = ec.entity.find("co.hotwax.datamanager.DataManagerLog")
                .condition("failedRecordCount", 0).limit(1).list().getFirst()

        expect:
        refusedLog != null
        cleanLog != null
        buildFact(refusedLogFactTypeId, [logId: refusedLog.logId]).fact == [refusedLogCount: 1L]
        buildFact(refusedLogFactTypeId, [logId: cleanLog.logId]).fact == [refusedLogCount: 0L]
    }

    def "a fact tree with a wrong field and a wrong relationship is refused before any row is read"() {
        when:
        Map built = buildFact(brokenFactTypeId, [:])

        then:
        built.fact == null
        built.errors.any { it.contains("Node 02 of RULES_TEST_BROKEN reads field noSuchField, which its parent row does not have") }
        built.errors.any { it.contains("Node 03 of RULES_TEST_BROKEN follows relationship NoSuchRelationship, which its parent row does not have") }
        !built.errors.any { it.contains("reads more than one") }
    }

    def "a count node with a date filter counts only the rows current now"() {
        given:
        EntityValue endedId = ec.entity.find("co.hotwax.order.OrderIdentification")
                .condition("orderIdentificationTypeId", "NETSUITE_ORDER_ID")
                .condition("thruDate", EntityCondition.LESS_THAN, ec.user.nowTimestamp).limit(1).list().getFirst()
        def netSuiteIds = { -> ec.entity.find("co.hotwax.order.OrderIdentification").condition("orderId", endedId.orderId)
                .condition("orderIdentificationTypeId", "NETSUITE_ORDER_ID") }
        long allIds = netSuiteIds().count()
        long currentIds = netSuiteIds().conditionDate("fromDate", "thruDate", ec.user.nowTimestamp).count()

        when:
        Map built = buildFact(currentIdsFactTypeId, [orderId: endedId.orderId])

        then:
        endedId != null
        built.errors.isEmpty()
        built.fact.netSuiteIdCount == currentIds
        currentIds < allIds
    }

    def "an unknown fact type is refused"() {
        when:
        Map built = buildFact("noSuchFact", [:])

        then:
        built.fact == null
        built.errors.any { it.contains("Fact type noSuchFact does not exist") }
    }

    def "the actions are written once and written again after a fact tree row changes"() {
        given:
        EntityValue refusedLog = ec.entity.find("co.hotwax.datamanager.DataManagerLog")
                .condition("failedRecordCount", EntityCondition.GREATER_THAN, 9).limit(1).list().getFirst()
        def builtFactCache = ec.cache.getCache("ai.rules.built.fact")
        EntityValue minimumCondition = ec.entity.find("moqui.ai.FactNodeCondition")
                .condition([factTypeId: refusedLogFactTypeId, nodeSeqId: "01", conditionSeqId: "02"]).useCache(false).one()

        when:
        Map firstFact = buildFact(refusedLogFactTypeId, [logId: refusedLog.logId]).fact
        Object firstBuilt = builtFactCache.get(refusedLogFactTypeId)
        buildFact(refusedLogFactTypeId, [logId: refusedLog.logId])
        Object secondBuilt = builtFactCache.get(refusedLogFactTypeId)
        minimumCondition.set("fixedValue", refusedLog.failedRecordCount.toString())
        minimumCondition.update()
        Map changedFact = buildFact(refusedLogFactTypeId, [logId: refusedLog.logId]).fact
        minimumCondition.set("fixedValue", "0")
        minimumCondition.update()

        then:
        firstFact == [refusedLogCount: 1L]
        firstBuilt != null
        secondBuilt.is(firstBuilt)
        changedFact == [refusedLogCount: 0L]
    }
}
