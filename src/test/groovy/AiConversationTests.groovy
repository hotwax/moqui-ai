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
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "ConvAgent", agentName: "ConvAgent", providerName: "mock",
                modelName: "mock-1", systemPrompt: "remember context", maxIterations: 5,
                statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        })
        ec.artifactExecution.enableAuthz()
    }
    def cleanupSpec() {
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "ConvAgent").deleteAll()
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
            .parameters([agentId: "ConvAgent", title: "t1"]).call()
        String conversationId = t.conversationId

        expect: "conversation is keyed to the agent by id"
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", conversationId).one().agentId == "ConvAgent"

        when: "turn 1"
        MockProvider.enqueue([assistantText: "hi there", finishReason: "stop", tokensOut: 2L])
        ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentId: "ConvAgent", userMessage: "hello", conversationId: conversationId]).call()

        and: "turn 2"
        MockProvider.enqueue([assistantText: "yes, you said hello", finishReason: "stop", tokensOut: 4L])
        Map out2 = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentId: "ConvAgent", userMessage: "what did I say?", conversationId: conversationId]).call()

        then: "conversation holds both turns' messages (user+assistant x2 = 4); two runs linked"
        out2.assistantMessage == "yes, you said hello"
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", conversationId).count() == 4L
        ec.entity.find("moqui.ai.AiAgentRun").condition("conversationId", conversationId).count() == 2L

        cleanup: // delete messages before the conversation (FK order); leave append-only audit runs
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", conversationId).deleteAll()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", conversationId).deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "no conversationId behaves as a stateless single turn (Phase 1)"() {
        given: MockProvider.enqueue([assistantText: "stateless", finishReason: "stop"])
        when: Map out = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentId: "ConvAgent", userMessage: "hi"]).call()
        then:
        out.assistantMessage == "stateless"
        out.conversationId == null
    }

    def "conversation is auto-named when title is not provided"() {
        given: "a conversation without a title"
        Map t = ec.service.sync().name("ai.AgentServices.create#Conversation")
            .parameters([agentId: "ConvAgent"]).call()
        String conversationId = t.conversationId

        and: "mock provider enqueued response"
        MockProvider.enqueue([assistantText: "sure, I can help with orders", finishReason: "stop"])
        MockProvider.enqueue([assistantText: "Order Summary Request", finishReason: "stop"]) // for the auto-naming LLM call

        when: "running the agent turn"
        ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentId: "ConvAgent", userMessage: "summarize order 10023", conversationId: conversationId]).call()

        then: "the title is auto-generated"
        long start = System.currentTimeMillis()
        String title = null
        while (System.currentTimeMillis() - start < 5000) {
            title = ec.entity.find("moqui.ai.AiConversation").condition("conversationId", conversationId).one()?.title
            if (title) break
            Thread.sleep(100)
        }
        title == "Order Summary Request"

        cleanup:
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", conversationId).deleteAll()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", conversationId).deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "running agent on a conversation of a different agent throws exception"() {
        given: "an agent named OtherAgent"
        ec.artifactExecution.disableAuthz()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "OtherAgent", agentName: "OtherAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "other", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ec.artifactExecution.enableAuthz()

        and: "a conversation created for ConvAgent"
        Map t = ec.service.sync().name("ai.AgentServices.create#Conversation")
            .parameters([agentId: "ConvAgent", title: "t1"]).call()
        String conversationId = t.conversationId

        when: "running OtherAgent on ConvAgent's conversation"
        ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentId: "OtherAgent", userMessage: "hi", conversationId: conversationId]).call()

        then: "it fails with validation error"
        ec.message.hasError()
        ec.message.errorsString.contains("belongs to a different agent")

        cleanup:
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", conversationId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "OtherAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "running conversation with run#Conversation resolves agentId and delegates to run#Agent"() {
        given: "a conversation created for ConvAgent"
        Map t = ec.service.sync().name("ai.AgentServices.create#Conversation")
            .parameters([agentId: "ConvAgent", title: "t1"]).call()
        String conversationId = t.conversationId

        and: "mock provider enqueued response"
        MockProvider.enqueue([assistantText: "resolved automatically", finishReason: "stop"])

        when: "running the conversation turn with run#Conversation"
        Map out = ec.service.sync().name("ai.AgentServices.run#Conversation")
            .parameters([userMessage: "hi", conversationId: conversationId]).call()

        then: "it succeeds using the conversation's agentId"
        !ec.message.hasError()
        out.assistantMessage == "resolved automatically"

        cleanup:
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", conversationId).deleteAll()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", conversationId).deleteAll()
        ec.artifactExecution.enableAuthz()
    }
}
