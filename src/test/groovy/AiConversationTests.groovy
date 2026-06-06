import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.provider.MockProvider

class AiConversationTests extends Specification {
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
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiConversationStatusData.xml").load()
            ensureTestUser()
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "ConvAgent", providerName: "mock",
                modelName: "mock-1", systemPrompt: "remember context", maxIterations: 5,
                statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        })
        ec.artifactExecution.enableAuthz()
    }
    def cleanupSpec() {
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "ConvAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
        ec.destroy()
    }
    def setup() {
        ec.artifactExecution.disableAuthz()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
    }
    def cleanup() { MockProvider.reset(); ec.artifactExecution.enableAuthz() }

    def "second turn replays the first turn's messages"() {
        given: "a conversation"
        Map t = ec.service.sync().name("ai.AgentServices.create#Conversation")
            .parameters([agentName: "ConvAgent", title: "t1"]).call()
        String conversationId = t.conversationId

        when: "turn 1"
        MockProvider.enqueue([assistantText: "hi there", finishReason: "stop", tokensOut: 2L])
        ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "ConvAgent", userMessage: "hello", conversationId: conversationId]).call()

        and: "turn 2"
        MockProvider.enqueue([assistantText: "yes, you said hello", finishReason: "stop", tokensOut: 4L])
        Map out2 = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "ConvAgent", userMessage: "what did I say?", conversationId: conversationId]).call()

        then: "conversation holds both turns' messages (user+assistant x2 = 4); two runs linked"
        out2.assistantMessage == "yes, you said hello"
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", conversationId).count() == 4L
        ec.entity.find("moqui.ai.AiAgentRun").condition("conversationId", conversationId).count() == 2L

        cleanup: // delete messages before the conversation (FK order); leave append-only audit runs
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", conversationId).deleteAll()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", conversationId).deleteAll()
    }

    def "no conversationId behaves as a stateless single turn (Phase 1)"() {
        given: MockProvider.enqueue([assistantText: "stateless", finishReason: "stop"])
        when: Map out = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "ConvAgent", userMessage: "hi"]).call()
        then:
        out.assistantMessage == "stateless"
        out.conversationId == null
    }
}
