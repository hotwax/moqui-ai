import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.ai.provider.MockProvider
import spock.lang.Shared
import spock.lang.Specification

class AiReasoningTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() { ec = org.moqui.Moqui.getExecutionContext() }
    def cleanupSpec() { if (ec != null) ec.destroy() }

    def "reasoningEffort on the agent flows into the provider request as reasoning.effort"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
        MockProvider.reset()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "ReasonAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "x", maxIterations: 3, reasoningEffort: "medium", statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ((UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: "ok", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        when:
        ec.service.sync().name("ai.AgentServices.run#Agent").parameters([agentName: "ReasonAgent", userMessage: "hi"]).call()
        then:
        (MockProvider.LAST_REQUEST?.reasoning as Map)?.effort == "medium"
        cleanup:
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "ReasonAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "no reasoningEffort means no reasoning key in the request (backward-compatible)"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
        MockProvider.reset()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "PlainAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "x", maxIterations: 3, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ((UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: "ok", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        when:
        ec.service.sync().name("ai.AgentServices.run#Agent").parameters([agentName: "PlainAgent", userMessage: "hi"]).call()
        then:
        MockProvider.LAST_REQUEST?.reasoning == null
        cleanup:
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "PlainAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
}
