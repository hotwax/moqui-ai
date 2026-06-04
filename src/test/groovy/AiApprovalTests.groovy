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

    def "resume after approval executes the gated call and completes"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
        org.moqui.ai.provider.MockProvider.reset()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "ApprAgent2", providerName: "mock",
            modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgentTool").setAll([agentName: "ApprAgent2",
            toolName: "moqui.ai.test.TestServices.get#GatedEcho"]).createOrUpdate()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "c1", name: "moqui.ai.test.TestServices.get#GatedEcho", arguments: [text: "hi"]]], tokensIn: 1L, tokensOut: 1L])
        MockProvider.enqueue([assistantText: "done after approval", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent").parameters([agentName: "ApprAgent2", userMessage: "go"]).call()
        // mark the approval APPROVED (the service layer is Task 4; here we set it directly)
        ec.entity.find("moqui.ai.AiToolApproval").condition("agentRunId", out.agentRunId).updateAll([statusId: "AI_APPR_APPROVED", decidedByUserId: "AiTestUser"])
        when:
        Map r = new org.moqui.ai.AgentRunner(ec, ai).resume(out.agentRunId as String)
        EntityValue run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()
        then:
        r.statusId == "AI_RUN_COMPLETED"
        run.assistantMessage == "done after approval"
        run.pendingState == null
        // the gated tool actually executed (a successful AiToolCall for it)
        ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId).condition("toolCallId", "c1").one().success == "Y"
        cleanup:
        ec.entity.find("moqui.ai.AiToolApproval").condition("agentRunId", out.agentRunId).deleteAll()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentName", "ApprAgent2").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "ApprAgent2").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "resume while an approval is still pending is a safe no-op (gated tool does not execute)"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
        org.moqui.ai.provider.MockProvider.reset()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "ApprAgent3", providerName: "mock",
            modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgentTool").setAll([agentName: "ApprAgent3",
            toolName: "moqui.ai.test.TestServices.get#GatedEcho"]).createOrUpdate()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "c1", name: "moqui.ai.test.TestServices.get#GatedEcho", arguments: [text: "hi"]]], tokensIn: 1L, tokensOut: 1L])
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent").parameters([agentName: "ApprAgent3", userMessage: "go"]).call()
        when: // resume WITHOUT deciding the approval (still AI_APPR_PENDING)
        Map r = new org.moqui.ai.AgentRunner(ec, ai).resume(out.agentRunId as String)
        EntityValue run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()
        then:
        r.statusId == "AI_RUN_SUSPENDED"                  // guard refused
        run.statusId == "AI_RUN_SUSPENDED"                // run untouched
        run.pendingState != null                          // state not cleared
        ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId).condition("toolCallId", "c1").list().isEmpty()  // gated tool did NOT execute
        cleanup:
        ec.entity.find("moqui.ai.AiToolApproval").condition("agentRunId", out.agentRunId).deleteAll()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentName", "ApprAgent3").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "ApprAgent3").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "approve#ToolCall decides the approval, resumes the run, and the gated tool executes"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
        org.moqui.ai.provider.MockProvider.reset()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "ApprAgent4", providerName: "mock",
            modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgentTool").setAll([agentName: "ApprAgent4",
            toolName: "moqui.ai.test.TestServices.get#GatedEcho"]).createOrUpdate()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "c1", name: "moqui.ai.test.TestServices.get#GatedEcho", arguments: [text: "hi"]]], tokensIn: 1L, tokensOut: 1L])
        MockProvider.enqueue([assistantText: "done after approval", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent").parameters([agentName: "ApprAgent4", userMessage: "go"]).call()
        String approvalId = ec.entity.find("moqui.ai.AiToolApproval").condition("agentRunId", out.agentRunId).list()[0].approvalId
        when:
        Map dec = ec.service.sync().name("ai.ApprovalServices.approve#ToolCall").parameters([approvalId: approvalId]).call()
        EntityValue run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()
        then:
        dec.runStatusId == "AI_RUN_COMPLETED"
        run.assistantMessage == "done after approval"
        run.pendingState == null
        // the gated tool actually executed (a successful AiToolCall for it)
        ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId).condition("toolCallId", "c1").one().success == "Y"
        cleanup:
        ec.entity.find("moqui.ai.AiToolApproval").condition("agentRunId", out.agentRunId).deleteAll()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentName", "ApprAgent4").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "ApprAgent4").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "reject#ToolCall decides the approval, resumes the run, and the gated call is denied"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
        org.moqui.ai.provider.MockProvider.reset()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "ApprAgent5", providerName: "mock",
            modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgentTool").setAll([agentName: "ApprAgent5",
            toolName: "moqui.ai.test.TestServices.get#GatedEcho"]).createOrUpdate()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "c1", name: "moqui.ai.test.TestServices.get#GatedEcho", arguments: [text: "hi"]]], tokensIn: 1L, tokensOut: 1L])
        MockProvider.enqueue([assistantText: "ok, skipped that", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent").parameters([agentName: "ApprAgent5", userMessage: "go"]).call()
        String approvalId = ec.entity.find("moqui.ai.AiToolApproval").condition("agentRunId", out.agentRunId).list()[0].approvalId
        when:
        Map dec = ec.service.sync().name("ai.ApprovalServices.reject#ToolCall")
            .parameters([approvalId: approvalId, decisionNote: "not allowed"]).call()
        EntityValue run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()
        EntityValue tc = ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId).condition("toolCallId", "c1").one()
        then:
        dec.runStatusId == "AI_RUN_COMPLETED"
        run.assistantMessage == "ok, skipped that"
        run.pendingState == null
        // the gated call was recorded as denied (not executed)
        tc.success == "N"
        (tc.result as String).contains("Denied")
        cleanup:
        ec.entity.find("moqui.ai.AiToolApproval").condition("agentRunId", out.agentRunId).deleteAll()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentName", "ApprAgent5").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "ApprAgent5").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
}
