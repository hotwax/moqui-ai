import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.entity.EntityValue

class AiRegistryTests extends Specification {
    @Shared ExecutionContext ec

    // The active Shiro realm (co.hotwax.auth.OfbizShiroRealm) authenticates against the OFBiz UserLogin
    // model, not moqui.security.UserAccount, so the test user needs Party/Person/UserLogin rows for
    // internalLoginUser("AiTestUser") to succeed. Must be called inside a committed (runRequireNew) tx.
    private void ensureTestUser() {
        ec.entity.makeValue("org.apache.ofbiz.party.party.Party").setAll([partyId: "AiTestUser", partyTypeId: "PERSON"]).createOrUpdate()
        ec.entity.makeValue("org.apache.ofbiz.party.party.Person").setAll([partyId: "AiTestUser", firstName: "AI", lastName: "Test User"]).createOrUpdate()
        ec.entity.makeValue("org.apache.ofbiz.security.login.UserLogin").setAll([userLoginId: "AiTestUser", partyId: "AiTestUser", enabled: "Y"]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
    }

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ensureTestUser()
        })
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.artifactExecution.enableAuthz()
    }
    def cleanupSpec() { if (ec != null) ec.destroy() }
    def setup() {
        ec.artifactExecution.disableAuthz()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        // Self-isolation: other specs seed AiTool rows (get_echo / get_gated_echo) into the shared DB.
        // Clear the toolNames this spec derives so store#AiTool's uniqueness check tests THIS spec's rows.
        for (String n in ["get_echo", "update_thing", "get_echo2", "list_echoes"])
            ec.entity.find("moqui.ai.AiTool").condition("toolName", n).deleteAll()
    }
    def cleanup() { ec.artifactExecution.enableAuthz() }

    // ---- store#AiTool (spec §6.1–6.5) ----

    def "store#AiTool validates the service exists (fail-loud on unknown)"() {
        when:
        ec.service.sync().name("ai.ToolServices.store#AiTool")
            .parameters([verb: "get", noun: "nope", serviceName: "no.such.Service.get#Nope",
                         description: "x"]).call()
        then:
        ec.message.hasError()
        ec.message.errorsString.toLowerCase().contains("service")
        cleanup: ec.message.clearErrors()
    }

    def "store#AiTool derives toolName=verb_noun, effect=READ_ONLY for a read verb, exposable=Y/approval=N"() {
        when:
        Map out = ec.service.sync().name("ai.ToolServices.store#AiTool")
            .parameters([verb: "get", noun: "echo", serviceName: "moqui.ai.test.TestServices.get#Echo",
                         description: "echo it", sourceComponent: "moqui-ai"]).call()
        EntityValue t = ec.entity.find("moqui.ai.AiTool").condition("toolId", out.toolId).one()
        then:
        out.toolId != null
        t.toolName == "get_echo"
        t.effectEnumId == "AI_TOOL_READ_ONLY"
        t.exposable == "Y"
        t.requiresApproval == "N"
        t.statusId == "AI_TOOL_ACTIVE"
        t.createdByUserId == "AiTestUser"
        cleanup: ec.entity.find("moqui.ai.AiTool").condition("toolId", out.toolId).deleteAll()
    }

    def "store#AiTool derives effect=MUTATING for a write verb; defaults exposable=N + approval=Y"() {
        when:
        Map out = ec.service.sync().name("ai.ToolServices.store#AiTool")
            .parameters([verb: "update", noun: "thing", serviceName: "moqui.ai.test.TestServices.update#Noop",
                         description: "mutate"]).call()
        EntityValue t = ec.entity.find("moqui.ai.AiTool").condition("toolId", out.toolId).one()
        then:
        t.effectEnumId == "AI_TOOL_MUTATING"
        t.exposable == "N"            // Curator must bless mutating tools
        t.requiresApproval == "Y"
        cleanup: ec.entity.find("moqui.ai.AiTool").condition("toolId", out.toolId).deleteAll()
    }

    def "store#AiTool applies the denylist floor: a denied service is forced exposable=N even if requested Y"() {
        given:
        ec.entity.makeValue("moqui.ai.AiToolDenylist").setAll([servicePattern: ".*get#Echo",
            reason: "test floor"]).createOrUpdate()
        when:
        Map out = ec.service.sync().name("ai.ToolServices.store#AiTool")
            .parameters([verb: "get", noun: "echo2", serviceName: "moqui.ai.test.TestServices.get#Echo",
                         description: "x", exposable: "Y"]).call()
        EntityValue t = ec.entity.find("moqui.ai.AiTool").condition("toolId", out.toolId).one()
        then:
        t.exposable == "N"
        cleanup:
        ec.entity.find("moqui.ai.AiTool").condition("toolId", out.toolId).deleteAll()
        ec.entity.find("moqui.ai.AiToolDenylist").condition("servicePattern", ".*get#Echo").deleteAll()
    }

    def "store#AiTool rejects a duplicate toolName with a clear message"() {
        given:
        Map first = ec.service.sync().name("ai.ToolServices.store#AiTool")
            .parameters([verb: "get", noun: "echo", serviceName: "moqui.ai.test.TestServices.get#Echo",
                         description: "x"]).call()
        when:
        ec.service.sync().name("ai.ToolServices.store#AiTool")
            .parameters([verb: "get", noun: "echo", serviceName: "moqui.ai.test.TestServices.get#Echo",
                         description: "dup"]).call()
        then:
        ec.message.hasError()
        ec.message.errorsString.toLowerCase().contains("get_echo")
        cleanup:
        ec.message.clearErrors()
        ec.entity.find("moqui.ai.AiTool").condition("toolId", first.toolId).deleteAll()
    }

    def "store#AiTool updates an existing tool by toolId (rename verb/noun -> new toolName)"() {
        given:
        Map made = ec.service.sync().name("ai.ToolServices.store#AiTool")
            .parameters([verb: "get", noun: "echo", serviceName: "moqui.ai.test.TestServices.get#Echo",
                         description: "x"]).call()
        when:
        ec.service.sync().name("ai.ToolServices.store#AiTool")
            .parameters([toolId: made.toolId, verb: "list", noun: "echoes",
                         serviceName: "moqui.ai.test.TestServices.get#Echo", description: "renamed"]).call()
        EntityValue t = ec.entity.find("moqui.ai.AiTool").condition("toolId", made.toolId).one()
        then:
        t.toolName == "list_echoes"     // identity stable, name changed
        t.createdByUserId == "AiTestUser"   // preserved on update (not nulled)
        cleanup: ec.entity.find("moqui.ai.AiTool").condition("toolId", made.toolId).deleteAll()
    }

    // ---- store#AiAgent (spec §6) ----

    def "store#AiAgent sequences agentId, enforces unique agentName, defaults DRAFT"() {
        when:
        Map out = ec.service.sync().name("ai.AgentServices.store#AiAgent")
            .parameters([agentName: "reg-agent", description: "d", providerName: "mock",
                         modelName: "mock-1", systemPrompt: "x", maxIterations: 5]).call()
        EntityValue a = ec.entity.find("moqui.ai.AiAgent").condition("agentId", out.agentId).one()
        then:
        out.agentId != null
        a.agentName == "reg-agent"
        a.statusId == "AI_AGENT_DRAFT"          // default lifecycle start
        when: "a second agent with the same name"
        ec.service.sync().name("ai.AgentServices.store#AiAgent")
            .parameters([agentName: "reg-agent", providerName: "mock", modelName: "mock-1", systemPrompt: "y"]).call()
        then:
        ec.message.hasError()
        ec.message.errorsString.toLowerCase().contains("reg-agent")
        cleanup:
        ec.message.clearErrors()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", out.agentId).deleteAll()
    }

    def "store#AiAgent updates in place by agentId (rename keeps id)"() {
        given:
        Map made = ec.service.sync().name("ai.AgentServices.store#AiAgent")
            .parameters([agentName: "before", providerName: "mock", modelName: "mock-1", systemPrompt: "x"]).call()
        when:
        ec.service.sync().name("ai.AgentServices.store#AiAgent")
            .parameters([agentId: made.agentId, agentName: "after", statusId: "AI_AGENT_ACTIVE"]).call()
        EntityValue a = ec.entity.find("moqui.ai.AiAgent").condition("agentId", made.agentId).one()
        then:
        a.agentName == "after"
        a.statusId == "AI_AGENT_ACTIVE"
        cleanup: ec.entity.find("moqui.ai.AiAgent").condition("agentId", made.agentId).deleteAll()
    }
}
