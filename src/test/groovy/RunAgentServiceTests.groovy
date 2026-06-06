import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.provider.MockProvider

class RunAgentServiceTests extends Specification {
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
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "AG_SVC", agentName: "SvcAgent", providerName: "mock",
                modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        })
        ec.artifactExecution.enableAuthz()
    }
    def cleanupSpec() {
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "AG_SVC").deleteAll()
        ec.artifactExecution.enableAuthz()
        ec.destroy()
    }
    def setup() {
        ec.artifactExecution.disableAuthz()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
    }
    def cleanup() { MockProvider.reset(); ec.artifactExecution.enableAuthz() }

    def "run#Agent returns the assistant message and run id"() {
        given: MockProvider.enqueue([assistantText: "service ok", finishReason: "stop", tokensOut: 3L])
        when:
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "SvcAgent", userMessage: "ping"]).call()
        then:
        out.assistantMessage == "service ok"
        out.agentRunId != null
        out.truncated == false
    }

    def "run#Agent surfaces structuredResult for a schema-bound agent"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: null, finishReason: "stop",
            toolCalls: [], tokensIn: 1L, tokensOut: 1L, structuredResult: [answer: "42"]])
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "SvcSchemaAgent", agentName: "SvcSchemaAgent", providerName: "mock",
                modelName: "mock-1", systemPrompt: "x",
                responseSchema: '{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"],"additionalProperties":false}',
                maxIterations: 2, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        })
        ec.message.clearErrors()
        when:
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "SvcSchemaAgent", userMessage: "q"]).call()
        then:
        out.statusId == "AI_RUN_COMPLETED"
        out.structuredResult.answer == "42"
        cleanup:
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "SvcSchemaAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "run#Agent surfaces the served providerName"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "ok", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "SvcProvAgent", agentName: "SvcProvAgent", providerName: "mock",
                modelName: "m1", systemPrompt: "x", maxIterations: 2, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        })
        ec.message.clearErrors()
        when:
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "SvcProvAgent", userMessage: "q"]).call()
        then:
        out.statusId == "AI_RUN_COMPLETED"
        out.providerName == "mock"
        out.servedByModelId == "m1"
        cleanup:
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "SvcProvAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "run#Agent accepts agentId directly"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "AG_BYID", agentName: "ById",
            providerName: "mock", modelName: "mock-1", systemPrompt: "x", maxIterations: 3,
            statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "by id ok", finishReason: "stop", tokensOut: 1L])
        when:
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentId: "AG_BYID", userMessage: "hi"]).call()
        then:
        out.assistantMessage == "by id ok"
        ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one().agentId == "AG_BYID"
        cleanup:
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "AG_BYID").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
}
