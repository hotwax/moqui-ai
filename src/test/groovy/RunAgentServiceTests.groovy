import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.provider.MockProvider

class RunAgentServiceTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        ec.entity.makeValue("moqui.security.UserAccount")
            .setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "SvcAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ec.artifactExecution.enableAuthz()
    }
    def cleanupSpec() {
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "SvcAgent").deleteAll()
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
}
