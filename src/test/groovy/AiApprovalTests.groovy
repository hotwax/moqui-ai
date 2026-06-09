import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.AiToolFactory
import org.moqui.ai.provider.MockProvider
import org.moqui.entity.EntityValue

class AiApprovalTests extends Specification {
    @Shared ExecutionContext ec
    @Shared AiToolFactory ai

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
        ai = ec.factory.getTool("AI", AiToolFactory.class)
        ec.artifactExecution.disableAuthz()
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            // seeded test tools (replaces the deleted ai/*.tools.xml): get_echo + get_gated_echo
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiTestToolData.xml").load()
            ensureTestUser()
        })
        ai.refreshCatalog()
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
        org.moqui.ai.provider.MockProvider.reset()
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ensureTestUser()
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "ApprAgent", agentName: "ApprAgent", providerName: "mock",
                modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiAgentTool").setAll([agentId: "ApprAgent",
                toolId: "TL_GATED"]).createOrUpdate()
        })
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "c1", name: "get_gated_echo", arguments: [text: "do it"]]], tokensIn: 1L, tokensOut: 1L])
        MockProvider.enqueue([assistantText: "done after approval", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        when:
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent").parameters([agentId: "ApprAgent", userMessage: "go"]).call()
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
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", "ApprAgent").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "ApprAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "resume after approval executes the gated call and completes"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ensureTestUser()
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "ApprAgent2", agentName: "ApprAgent2", providerName: "mock",
                modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiAgentTool").setAll([agentId: "ApprAgent2",
                toolId: "TL_GATED"]).createOrUpdate()
        })
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "c1", name: "get_gated_echo", arguments: [text: "hi"]]], tokensIn: 1L, tokensOut: 1L])
        MockProvider.enqueue([assistantText: "done after approval", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent").parameters([agentId: "ApprAgent2", userMessage: "go"]).call()
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
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", "ApprAgent2").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "ApprAgent2").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "resume while an approval is still pending is a safe no-op (gated tool does not execute)"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ensureTestUser()
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "ApprAgent3", agentName: "ApprAgent3", providerName: "mock",
                modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiAgentTool").setAll([agentId: "ApprAgent3",
                toolId: "TL_GATED"]).createOrUpdate()
        })
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "c1", name: "get_gated_echo", arguments: [text: "hi"]]], tokensIn: 1L, tokensOut: 1L])
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent").parameters([agentId: "ApprAgent3", userMessage: "go"]).call()
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
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", "ApprAgent3").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "ApprAgent3").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "approve#ToolCall decides the approval, resumes the run, and the gated tool executes"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ensureTestUser()
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "ApprAgent4", agentName: "ApprAgent4", providerName: "mock",
                modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiAgentTool").setAll([agentId: "ApprAgent4",
                toolId: "TL_GATED"]).createOrUpdate()
        })
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "c1", name: "get_gated_echo", arguments: [text: "hi"]]], tokensIn: 1L, tokensOut: 1L])
        MockProvider.enqueue([assistantText: "done after approval", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent").parameters([agentId: "ApprAgent4", userMessage: "go"]).call()
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
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", "ApprAgent4").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "ApprAgent4").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "reject#ToolCall decides the approval, resumes the run, and the gated call is denied"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ensureTestUser()
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "ApprAgent5", agentName: "ApprAgent5", providerName: "mock",
                modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiAgentTool").setAll([agentId: "ApprAgent5",
                toolId: "TL_GATED"]).createOrUpdate()
        })
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "c1", name: "get_gated_echo", arguments: [text: "hi"]]], tokensIn: 1L, tokensOut: 1L])
        MockProvider.enqueue([assistantText: "ok, skipped that", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent").parameters([agentId: "ApprAgent5", userMessage: "go"]).call()
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
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", "ApprAgent5").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "ApprAgent5").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "a mixed turn (one gated + one non-gated call) suspends the WHOLE turn; approving runs both"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ensureTestUser()
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "ApprAgentMix", agentName: "ApprAgentMix", providerName: "mock",
                modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
            // grant BOTH tools: one ungated, one approval-gated
            ec.entity.makeValue("moqui.ai.AiAgentTool").setAll([agentId: "ApprAgentMix",
                toolId: "TL_ECHO"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiAgentTool").setAll([agentId: "ApprAgentMix",
                toolId: "TL_GATED"]).createOrUpdate()
        })
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        // one model turn proposing BOTH calls
        MockProvider.enqueue([assistantText: null, finishReason: "tool_use", toolCalls: [
            [id: "c1", name: "get_echo", arguments: [text: "a"]],
            [id: "c2", name: "get_gated_echo", arguments: [text: "b"]]], tokensIn: 1L, tokensOut: 1L])
        MockProvider.enqueue([assistantText: "both done", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        when: // stateless run (no conversationId)
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent").parameters([agentId: "ApprAgentMix", userMessage: "go"]).call()
        then: // whole turn suspended: exactly ONE pending approval, for the gated call c2; nothing executed yet
        out.statusId == "AI_RUN_SUSPENDED"
        out.awaitingApproval == true
        List pending = ec.entity.find("moqui.ai.AiToolApproval").condition("agentRunId", out.agentRunId).condition("statusId", "AI_APPR_PENDING").list()
        pending.size() == 1
        pending[0].toolCallId == "c2"
        // NEITHER tool ran while suspended (the ungated call did NOT slip through)
        ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId).list().isEmpty()

        when: // approve the one gated call via the service
        String approvalId = pending[0].approvalId
        Map dec = ec.service.sync().name("ai.ApprovalServices.approve#ToolCall").parameters([approvalId: approvalId]).call()
        EntityValue run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()
        then: // run completed and BOTH tools ran successfully
        dec.runStatusId == "AI_RUN_COMPLETED"
        run.assistantMessage == "both done"
        ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId).condition("toolCallId", "c1").one().success == "Y"
        ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId).condition("toolCallId", "c2").one().success == "Y"
        cleanup:
        ec.entity.find("moqui.ai.AiToolApproval").condition("agentRunId", out.agentRunId).deleteAll()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", "ApprAgentMix").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "ApprAgentMix").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "A1 regression: suspend on a conversation leaves no dangling tool_call; a later run replays cleanly"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ensureTestUser()
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "ApprAgentA1", agentName: "ApprAgentA1", providerName: "mock",
                modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiAgentTool").setAll([agentId: "ApprAgentA1",
                toolId: "TL_GATED"]).createOrUpdate()
        })
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        Map c = ec.service.sync().name("ai.AgentServices.create#Conversation").parameters([agentId: "ApprAgentA1"]).call()
        String convId = c.conversationId
        // first run: gated tool_use → suspends
        MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "c1", name: "get_gated_echo", arguments: [text: "x"]]], tokensIn: 1L, tokensOut: 1L])
        when:
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentId: "ApprAgentA1", userMessage: "go", conversationId: convId]).call()
        then: // suspended
        out.statusId == "AI_RUN_SUSPENDED"
        // deterministic A1 guard: the suspended assistant tool-call turn was withheld (refinement 1),
        // so NO persisted conversation message carries toolCalls — only the user message was persisted
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", convId).list().findAll { it.toolCalls != null }.isEmpty()

        when: // behavioral guard: a SECOND run on the SAME conversation WITHOUT deciding the approval, scripted to a plain stop
        MockProvider.enqueue([assistantText: "hello again", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        Map out2 = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentId: "ApprAgentA1", userMessage: "again", conversationId: convId]).call()
        then: // the replayed history has no malformed (dangling tool_call) turn → completes cleanly
        out2.statusId == "AI_RUN_COMPLETED"
        cleanup:
        ec.entity.find("moqui.ai.AiToolApproval").condition("agentRunId", out.agentRunId).deleteAll()
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", "ApprAgentA1").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "ApprAgentA1").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
}
