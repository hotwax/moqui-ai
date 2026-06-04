import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.AiToolFactory
import org.moqui.ai.provider.MockProvider
import org.moqui.entity.EntityValue

class AiApprovalTests extends Specification {
    @Shared ExecutionContext ec
    @Shared AiToolFactory ai

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ai = ec.factory.getTool("AI", AiToolFactory.class)
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        ec.entity.makeValue("moqui.security.UserAccount")
            .setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.artifactExecution.enableAuthz()
    }
    def cleanupSpec() {
        if (ec == null) return
        ec.destroy()
    }
    def setup() {
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
    }
    def cleanup() { MockProvider.reset() }

    def "a requiresApproval tool suspends the run before executing"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
        org.moqui.ai.provider.MockProvider.reset()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "ApprAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgentTool").setAll([agentName: "ApprAgent",
            toolName: "moqui.ai.test.TestServices.get#GatedEcho"]).createOrUpdate()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "c1", name: "moqui.ai.test.TestServices.get#GatedEcho", arguments: [text: "do it"]]], tokensIn: 1L, tokensOut: 1L])
        MockProvider.enqueue([assistantText: "done after approval", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        when:
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent").parameters([agentName: "ApprAgent", userMessage: "go"]).call()
        then:
        out.statusId == "AI_RUN_SUSPENDED"
        out.awaitingApproval == true
        (out.approvalIds as List).size() == 1
        // nothing executed yet: no AiToolCall for this run
        ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId).list().isEmpty()
        // pending approval recorded + pendingState persisted
        ec.entity.find("moqui.ai.AiToolApproval").condition("agentRunId", out.agentRunId).condition("statusId", "AI_APPR_PENDING").list().size() == 1
        ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one().pendingState != null
        cleanup:
        ec.entity.find("moqui.ai.AiToolApproval").condition("agentRunId", out.agentRunId).deleteAll()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentName", "ApprAgent").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "ApprAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
}
