import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.AiToolFactory
import java.sql.Timestamp

/** Capability-request curator workflow: list / dismiss / fulfill / provision over AiCapabilityRequest.
 *  The Composer files these (request#Capability) when it hits a missing tool; this exercises the
 *  read + triage + resolve half built in CapabilityServices.
 *
 *  Harness: mirrors AiKnowledgeTests — setupSpec in runRequireNew + ensureTestUser, fixed PKs prefixed
 *  CAPREQ_TEST_ so the suite is rerun-safe. cleanupSpec removes all rows this class may create. */
class AiCapabilityTests extends Specification {
    @Shared ExecutionContext ec

    static final String VALID_SVC = "moqui.ai.test.TestServices.get#Echo"
    static final String BOGUS_SVC = "no.such.Service.get#Nope"

    private void ensureTestUser() {
        ec.entity.makeValue("org.apache.ofbiz.party.party.Party").setAll([partyId: "AiTestUser", partyTypeId: "PERSON"]).createOrUpdate()
        ec.entity.makeValue("org.apache.ofbiz.party.party.Person").setAll([partyId: "AiTestUser", firstName: "AI", lastName: "Test User"]).createOrUpdate()
        ec.entity.makeValue("org.apache.ofbiz.security.login.UserLogin").setAll([userLoginId: "AiTestUser", partyId: "AiTestUser", enabled: "Y"]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
    }

    /** Create (or reset) an OPEN capability request with a fixed PK. */
    private void makeOpenRequest(String id, Map extra = [:]) {
        ec.entity.find("moqui.ai.AiCapabilityRequest").condition("capabilityRequestId", id).deleteAll()
        Map vals = [capabilityRequestId: id, intent: "Need a tool for ${id}".toString(),
                    suggestedVerb: "get", suggestedNoun: "thing",
                    requestedByUserId: "AiTestUser", requestedDate: ec.user.nowTimestamp,
                    statusId: "AI_CAPREQ_OPEN"]
        vals.putAll(extra)
        ec.entity.makeValue("moqui.ai.AiCapabilityRequest").setAll(vals).create()
    }

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
        ec.transaction.runRequireNew(30, "ai capability test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiSecurityData.xml").load()
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiTestToolData.xml").load()
            ensureTestUser()
        })
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.factory.getTool("AI", AiToolFactory.class).refreshCatalog()
        ec.artifactExecution.enableAuthz()
    }

    def cleanupSpec() {
        if (ec == null) return
        ec.artifactExecution.disableAuthz()
        ec.transaction.runRequireNew(30, "ai capability test teardown", {
            ec.entity.find("moqui.ai.AiCapabilityRequest").condition("capabilityRequestId", org.moqui.entity.EntityCondition.LIKE, "CAPREQ_TEST%").deleteAll()
            // tools provisioned by the happy-path test (verb=get, noun=provisioned -> toolName get_provisioned)
            ec.entity.find("moqui.ai.AiTool").condition("toolName", "get_provisioned").deleteAll()
        })
        ec.artifactExecution.enableAuthz()
        ec.destroy()
    }

    def setup() {
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.artifactExecution.disableAuthz()
        ec.message.clearErrors()
    }

    def cleanup() {
        ec.artifactExecution.enableAuthz()
    }

    // ---- list#CapabilityRequest ----

    def "list#CapabilityRequest returns rows and filters by statusId"() {
        given:
        makeOpenRequest("CAPREQ_TEST_L1")
        makeOpenRequest("CAPREQ_TEST_L2", [statusId: "AI_CAPREQ_DISMISSED"])
        when:
        Map all = ec.service.sync().name("ai.CapabilityServices.list#CapabilityRequest").parameters([:]).call()
        Map open = ec.service.sync().name("ai.CapabilityServices.list#CapabilityRequest")
            .parameters([statusId: "AI_CAPREQ_OPEN"]).call()
        then:
        !ec.message.hasError()
        (all.requests as List).find { it.capabilityRequestId == "CAPREQ_TEST_L1" } != null
        (all.requests as List).find { it.capabilityRequestId == "CAPREQ_TEST_L2" } != null
        (open.requests as List).find { it.capabilityRequestId == "CAPREQ_TEST_L1" } != null
        (open.requests as List).find { it.capabilityRequestId == "CAPREQ_TEST_L2" } == null
        cleanup:
        ec.entity.find("moqui.ai.AiCapabilityRequest").condition("capabilityRequestId", "CAPREQ_TEST_L1").deleteAll()
        ec.entity.find("moqui.ai.AiCapabilityRequest").condition("capabilityRequestId", "CAPREQ_TEST_L2").deleteAll()
    }

    // ---- dismiss#CapabilityRequest ----

    def "dismiss#CapabilityRequest transitions OPEN to DISMISSED and stamps audit"() {
        given:
        makeOpenRequest("CAPREQ_TEST_D1")
        when:
        ec.service.sync().name("ai.CapabilityServices.dismiss#CapabilityRequest")
            .parameters([capabilityRequestId: "CAPREQ_TEST_D1", resolutionNote: "not actionable"]).call()
        then:
        !ec.message.hasError()
        def row = ec.entity.find("moqui.ai.AiCapabilityRequest").condition("capabilityRequestId", "CAPREQ_TEST_D1").one()
        row.statusId == "AI_CAPREQ_DISMISSED"
        row.resolvedByUserId == "AiTestUser"
        row.resolvedDate != null
        row.resolutionNote == "not actionable"
        cleanup:
        ec.entity.find("moqui.ai.AiCapabilityRequest").condition("capabilityRequestId", "CAPREQ_TEST_D1").deleteAll()
    }

    def "dismiss#CapabilityRequest rejects a non-OPEN request"() {
        given:
        makeOpenRequest("CAPREQ_TEST_D2", [statusId: "AI_CAPREQ_DONE"])
        when:
        ec.service.sync().name("ai.CapabilityServices.dismiss#CapabilityRequest")
            .parameters([capabilityRequestId: "CAPREQ_TEST_D2"]).call()
        then:
        ec.message.hasError()
        ec.entity.find("moqui.ai.AiCapabilityRequest").condition("capabilityRequestId", "CAPREQ_TEST_D2").one().statusId == "AI_CAPREQ_DONE"
        cleanup:
        ec.message.clearErrors()
        ec.entity.find("moqui.ai.AiCapabilityRequest").condition("capabilityRequestId", "CAPREQ_TEST_D2").deleteAll()
    }

    // ---- fulfill#CapabilityRequest ----

    def "fulfill#CapabilityRequest transitions OPEN to DONE and stamps audit + fulfilledToolId"() {
        given:
        makeOpenRequest("CAPREQ_TEST_F1")
        when:
        ec.service.sync().name("ai.CapabilityServices.fulfill#CapabilityRequest")
            .parameters([capabilityRequestId: "CAPREQ_TEST_F1", resolutionNote: "linked existing tool", fulfilledToolId: "TL_ECHO"]).call()
        then:
        !ec.message.hasError()
        def row = ec.entity.find("moqui.ai.AiCapabilityRequest").condition("capabilityRequestId", "CAPREQ_TEST_F1").one()
        row.statusId == "AI_CAPREQ_DONE"
        row.resolvedByUserId == "AiTestUser"
        row.resolvedDate != null
        row.resolutionNote == "linked existing tool"
        row.fulfilledToolId == "TL_ECHO"
        cleanup:
        ec.entity.find("moqui.ai.AiCapabilityRequest").condition("capabilityRequestId", "CAPREQ_TEST_F1").deleteAll()
    }

    // ---- provision#CapabilityRequest ----

    def "provision#CapabilityRequest creates a tool and marks the request DONE"() {
        given:
        ec.entity.find("moqui.ai.AiTool").condition("toolName", "get_provisioned").deleteAll()
        makeOpenRequest("CAPREQ_TEST_P1")
        when:
        Map r = ec.service.sync().name("ai.CapabilityServices.provision#CapabilityRequest")
            .parameters([capabilityRequestId: "CAPREQ_TEST_P1", serviceName: VALID_SVC,
                         verb: "get", noun: "provisioned", description: "provisioned tool",
                         resolutionNote: "built inline"]).call()
        then:
        !ec.message.hasError()
        r.toolId != null
        r.toolName == "get_provisioned"
        def tool = ec.entity.find("moqui.ai.AiTool").condition("toolId", r.toolId).one()
        tool != null
        tool.serviceName == VALID_SVC
        def row = ec.entity.find("moqui.ai.AiCapabilityRequest").condition("capabilityRequestId", "CAPREQ_TEST_P1").one()
        row.statusId == "AI_CAPREQ_DONE"
        row.fulfilledToolId == r.toolId
        row.resolvedByUserId == "AiTestUser"
        cleanup:
        ec.entity.find("moqui.ai.AiCapabilityRequest").condition("capabilityRequestId", "CAPREQ_TEST_P1").deleteAll()
        ec.entity.find("moqui.ai.AiTool").condition("toolName", "get_provisioned").deleteAll()
    }

    def "provision#CapabilityRequest with a bogus serviceName errors and leaves the request OPEN"() {
        given:
        makeOpenRequest("CAPREQ_TEST_P2")
        when:
        ec.service.sync().name("ai.CapabilityServices.provision#CapabilityRequest")
            .parameters([capabilityRequestId: "CAPREQ_TEST_P2", serviceName: BOGUS_SVC,
                         verb: "get", noun: "nope"]).call()
        then:
        ec.message.hasError()
        // no tool created
        ec.entity.find("moqui.ai.AiTool").condition("toolName", "get_nope").one() == null
        // request stays OPEN (whole provision rolled back)
        ec.entity.find("moqui.ai.AiCapabilityRequest").condition("capabilityRequestId", "CAPREQ_TEST_P2").one().statusId == "AI_CAPREQ_OPEN"
        cleanup:
        ec.message.clearErrors()
        ec.entity.find("moqui.ai.AiCapabilityRequest").condition("capabilityRequestId", "CAPREQ_TEST_P2").deleteAll()
    }
}
