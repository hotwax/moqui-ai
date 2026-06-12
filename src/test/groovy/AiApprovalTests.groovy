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
    private void ensureTestUser() { ensureUser("AiTestUser") }
    // Generic Party/Person/UserLogin/UserAccount so internalLoginUser(id) succeeds against the OFBiz realm.
    private void ensureUser(String id) {
        ec.entity.makeValue("org.apache.ofbiz.party.party.Party").setAll([partyId: id, partyTypeId: "PERSON"]).createOrUpdate()
        ec.entity.makeValue("org.apache.ofbiz.party.party.Person").setAll([partyId: id, firstName: id, lastName: "User"]).createOrUpdate()
        ec.entity.makeValue("org.apache.ofbiz.security.login.UserLogin").setAll([userLoginId: id, partyId: id, enabled: "Y"]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: id, username: id, userFullName: id]).createOrUpdate()
    }
    // A user in the AI_OPERATOR group, the role that may drive/decide ANY user's AI conversation. fromDate is
    // safely in the past so isInGroup's date filter includes the membership immediately.
    private void ensureOperator(String id) {
        ensureUser(id)
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: "AI_OPERATOR", description: "AI Operator"]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: "AI_OPERATOR", userId: id,
            fromDate: java.sql.Timestamp.valueOf("2000-01-01 00:00:00")]).createOrUpdate()
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
        (out.toolCallRequestIds as List).size() == 1
        // nothing executed yet: no AiToolCall for this run
        ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId).list().isEmpty()
        // pending approval recorded + pendingState persisted
        ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", out.agentRunId).condition("statusId", "AI_TCREQ_PENDING").list().size() == 1
        ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one().pendingState != null
        cleanup:
        ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", out.agentRunId).deleteAll()
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
        ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", out.agentRunId).updateAll([statusId: "AI_TCREQ_APPROVED", decidedByUserId: "AiTestUser"])
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
        ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", out.agentRunId).deleteAll()
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
        when: // resume WITHOUT deciding the approval (still AI_TCREQ_PENDING)
        Map r = new org.moqui.ai.AgentRunner(ec, ai).resume(out.agentRunId as String)
        EntityValue run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()
        then:
        r.statusId == "AI_RUN_SUSPENDED"                  // guard refused
        run.statusId == "AI_RUN_SUSPENDED"                // run untouched
        run.pendingState != null                          // state not cleared
        ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId).condition("toolCallId", "c1").list().isEmpty()  // gated tool did NOT execute
        cleanup:
        ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", out.agentRunId).deleteAll()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", "ApprAgent3").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "ApprAgent3").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "approve#ToolCallRequest decides the approval, resumes the run, and the gated tool executes"() {
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
        String toolCallRequestId = ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", out.agentRunId).list()[0].toolCallRequestId
        when:
        Map dec = ec.service.sync().name("ai.ToolCallRequestServices.approve#ToolCallRequest").parameters([toolCallRequestId: toolCallRequestId]).call()
        EntityValue run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()
        then:
        dec.runStatusId == "AI_RUN_COMPLETED"
        run.assistantMessage == "done after approval"
        run.pendingState == null
        // the gated tool actually executed (a successful AiToolCall for it)
        ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId).condition("toolCallId", "c1").one().success == "Y"
        cleanup:
        ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", out.agentRunId).deleteAll()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", "ApprAgent4").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "ApprAgent4").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "reject#ToolCallRequest decides the approval, resumes the run, and the gated call is denied"() {
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
        String toolCallRequestId = ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", out.agentRunId).list()[0].toolCallRequestId
        when:
        Map dec = ec.service.sync().name("ai.ToolCallRequestServices.reject#ToolCallRequest")
            .parameters([toolCallRequestId: toolCallRequestId, decisionNote: "not allowed"]).call()
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
        ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", out.agentRunId).deleteAll()
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
        List pending = ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", out.agentRunId).condition("statusId", "AI_TCREQ_PENDING").list()
        pending.size() == 1
        pending[0].toolCallId == "c2"
        // NEITHER tool ran while suspended (the ungated call did NOT slip through)
        ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId).list().isEmpty()

        when: // approve the one gated call via the service
        String toolCallRequestId = pending[0].toolCallRequestId
        Map dec = ec.service.sync().name("ai.ToolCallRequestServices.approve#ToolCallRequest").parameters([toolCallRequestId: toolCallRequestId]).call()
        EntityValue run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()
        then: // run completed and BOTH tools ran successfully
        dec.runStatusId == "AI_RUN_COMPLETED"
        run.assistantMessage == "both done"
        ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId).condition("toolCallId", "c1").one().success == "Y"
        ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId).condition("toolCallId", "c2").one().success == "Y"
        cleanup:
        ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", out.agentRunId).deleteAll()
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
        ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", out.agentRunId).deleteAll()
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", "ApprAgentA1").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "ApprAgentA1").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "a grant override N loosens a gated tool so the run completes without suspension"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiTestToolData.xml").load()
            ensureTestUser()
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "OvrAgentN", agentName: "OvrAgentN", providerName: "mock",
                modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiAgentTool").setAll([agentId: "OvrAgentN", toolId: "TL_GATED",
                requiresApprovalOverride: "N"]).createOrUpdate()
        })
        ai.refreshCatalog()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "c1", name: "get_gated_echo", arguments: [text: "hi"]]], tokensIn: 1L, tokensOut: 1L])
        MockProvider.enqueue([assistantText: "ran without approval", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        when:
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent").parameters([agentId: "OvrAgentN", userMessage: "go"]).call()
        then:
        out.statusId == "AI_RUN_COMPLETED"
        // never suspended: no approval request rows, and the gated tool executed
        ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", out.agentRunId).list().isEmpty()
        ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId).condition("toolCallId", "c1").one().success == "Y"
        cleanup:
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", "OvrAgentN").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "OvrAgentN").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "a grant override Y tightens a non-gated tool so the run suspends"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiTestToolData.xml").load()
            ensureTestUser()
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "OvrAgentY", agentName: "OvrAgentY", providerName: "mock",
                modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiAgentTool").setAll([agentId: "OvrAgentY", toolId: "TL_ECHO",
                requiresApprovalOverride: "Y"]).createOrUpdate()
        })
        ai.refreshCatalog()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "c1", name: "get_echo", arguments: [text: "hi"]]], tokensIn: 1L, tokensOut: 1L])
        MockProvider.enqueue([assistantText: "should not reach here", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        when:
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent").parameters([agentId: "OvrAgentY", userMessage: "go"]).call()
        then:
        out.statusId == "AI_RUN_SUSPENDED"
        out.awaitingApproval == true
        ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId).list().isEmpty()
        ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", out.agentRunId)
            .condition("statusId", "AI_TCREQ_PENDING").list().size() == 1
        cleanup:
        ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", out.agentRunId).deleteAll()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", "OvrAgentY").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "OvrAgentY").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "a preview run can never be resumed: its held call does not execute even once the request is approved"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiTestToolData.xml").load()
            ensureTestUser()
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "PrevAgent", agentName: "PrevAgent", providerName: "mock",
                modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_DRAFT"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiAgentTool").setAll([agentId: "PrevAgent", toolId: "TL_GATED"]).createOrUpdate()
        })
        ai.refreshCatalog()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "c1", name: "get_gated_echo", arguments: [text: "do it"]]], tokensIn: 1L, tokensOut: 1L])
        // a preview run HOLDS the would-be call and suspends with isPreview=Y
        Map out = new org.moqui.ai.AgentRunner(ec, ai).runPreview("PrevAgent", "go")
        // even if its held request is (mis)decided APPROVED out-of-band ...
        ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", out.agentRunId)
            .updateAll([statusId: "AI_TCREQ_APPROVED", decidedByUserId: "AiTestUser"])
        when: // ... resuming the preview run must be refused (fail-closed), never executing the held call
        Map r = new org.moqui.ai.AgentRunner(ec, ai).resume(out.agentRunId as String)
        EntityValue run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()
        then:
        out.statusId == "AI_RUN_SUSPENDED"
        run.isPreview == "Y"
        r.statusId == "AI_RUN_SUSPENDED"     // guard refused to resume
        run.pendingState != null             // suspended state left untouched
        // the held call NEVER executed: no AiToolCall row for the preview run
        ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId).list().isEmpty()
        cleanup:
        ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", out.agentRunId).deleteAll()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", "PrevAgent").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "PrevAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    // ===================== cross-user authorization scoping (owner OR AI_OPERATOR) =====================

    def "run#Conversation by a non-owner non-operator is denied: no run, no message, no LLM call"() {
        given:
        ec.artifactExecution.disableAuthz()
        MockProvider.reset()
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ensureTestUser()            // conversation owner
            ensureUser("AiTestUser2")   // an unrelated authenticated user
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "OwnAgent", agentName: "OwnAgent", providerName: "mock",
                modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiConversation").setAll([conversationId: "OwnConv", agentId: "OwnAgent",
                userId: "AiTestUser", title: "owner thread", createdDate: ec.user.nowTimestamp, statusId: "AI_CONV_ACTIVE"]).createOrUpdate()
        })
        // a model turn is enqueued ONLY to prove the gate throws BEFORE run#Agent ever consumes it
        MockProvider.enqueue([assistantText: "should never run", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser2")
        ec.message.clearErrors()
        when:
        ec.service.sync().name("ai.AgentServices.run#Conversation")
            .parameters([conversationId: "OwnConv", userMessage: "inject into another user's thread"]).call()
        then: // denied via a mini-lang <return error> (service error), not a thrown exception
        ec.message.hasError()
        ec.message.getErrorsString().toLowerCase().contains("not authorized")
        // nothing happened: no run started, no message persisted, the queued turn untouched
        ec.entity.find("moqui.ai.AiAgentRun").condition("conversationId", "OwnConv").list().isEmpty()
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", "OwnConv").list().isEmpty()
        cleanup:
        ec.message.clearErrors()
        MockProvider.reset()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", "OwnConv").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "OwnAgent").deleteAll()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.artifactExecution.enableAuthz()
    }

    def "run#Conversation by an AI_OPERATOR drives another user's conversation"() {
        given:
        ec.artifactExecution.disableAuthz()
        MockProvider.reset()
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ensureTestUser()
            ensureOperator("AiTestOperator")
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "OpAgent", agentName: "OpAgent", providerName: "mock",
                modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiConversation").setAll([conversationId: "OpConv", agentId: "OpAgent",
                userId: "AiTestUser", title: "owner thread", createdDate: ec.user.nowTimestamp, statusId: "AI_CONV_ACTIVE"]).createOrUpdate()
        })
        MockProvider.enqueue([assistantText: "operator drove it", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestOperator")
        ec.message.clearErrors()
        when:
        Map out = ec.service.sync().name("ai.AgentServices.run#Conversation")
            .parameters([conversationId: "OpConv", userMessage: "drive it as operator"]).call()
        then:
        out.statusId == "AI_RUN_COMPLETED"
        out.assistantMessage == "operator drove it"
        cleanup:
        // leave AiAgentRun/AiAgentRunStep/AiToolCall audit rows (FK children) as the other tests do; just clear
        // the conversation graph. AiAgentRun -> AiConversation is one-nofk, so dropping the conversation is safe.
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", "OpConv").deleteAll()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", "OpConv").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "OpAgent").deleteAll()
        ec.entity.find("moqui.security.UserGroupMember").condition("userId", "AiTestOperator").deleteAll()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.artifactExecution.enableAuthz()
    }

    def "approve#ToolCallRequest by a non-owner non-operator is denied: request stays pending, run suspended, tool never runs"() {
        given:
        ec.artifactExecution.disableAuthz()
        MockProvider.reset()
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ensureTestUser()
            ensureUser("AiTestUser2")
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "GateAgent", agentName: "GateAgent", providerName: "mock",
                modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiAgentTool").setAll([agentId: "GateAgent", toolId: "TL_GATED"]).createOrUpdate()
        })
        // the OWNER runs the agent; the gated call suspends with a pending request owned by AiTestUser
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "c1", name: "get_gated_echo", arguments: [text: "hi"]]], tokensIn: 1L, tokensOut: 1L])
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent").parameters([agentId: "GateAgent", userMessage: "go"]).call()
        String reqId = ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", out.agentRunId).list()[0].toolCallRequestId
        // a completion turn is enqueued only to prove the denied decision never resumes the run
        MockProvider.enqueue([assistantText: "should never run", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser2")
        ec.message.clearErrors()
        when:
        ec.service.sync().name("ai.ToolCallRequestServices.approve#ToolCallRequest").parameters([toolCallRequestId: reqId]).call()
        then: // denied via a mini-lang <return error> (service error), not a thrown exception
        ec.message.hasError()
        ec.message.getErrorsString().toLowerCase().contains("not authorized")
        // decision NOT recorded, run NOT resumed, gated tool did NOT execute
        ec.entity.find("moqui.ai.AiToolCallRequest").condition("toolCallRequestId", reqId).one().statusId == "AI_TCREQ_PENDING"
        ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one().statusId == "AI_RUN_SUSPENDED"
        ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId).list().isEmpty()
        cleanup:
        ec.message.clearErrors()
        MockProvider.reset()
        ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", out.agentRunId).deleteAll()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", "GateAgent").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "GateAgent").deleteAll()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.artifactExecution.enableAuthz()
    }

    def "approve#ToolCallRequest by an AI_OPERATOR decides another user's request and resumes the run"() {
        given:
        ec.artifactExecution.disableAuthz()
        MockProvider.reset()
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ensureTestUser()
            ensureOperator("AiTestOperator")
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "GateAgent2", agentName: "GateAgent2", providerName: "mock",
                modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiAgentTool").setAll([agentId: "GateAgent2", toolId: "TL_GATED"]).createOrUpdate()
        })
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "c1", name: "get_gated_echo", arguments: [text: "hi"]]], tokensIn: 1L, tokensOut: 1L])
        MockProvider.enqueue([assistantText: "done after operator approval", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent").parameters([agentId: "GateAgent2", userMessage: "go"]).call()
        String reqId = ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", out.agentRunId).list()[0].toolCallRequestId
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestOperator")
        ec.message.clearErrors()
        when:
        Map dec = ec.service.sync().name("ai.ToolCallRequestServices.approve#ToolCallRequest").parameters([toolCallRequestId: reqId]).call()
        then:
        dec.runStatusId == "AI_RUN_COMPLETED"
        ec.entity.find("moqui.ai.AiToolCallRequest").condition("toolCallRequestId", reqId).one().decidedByUserId == "AiTestOperator"
        ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId).condition("toolCallId", "c1").one().success == "Y"
        cleanup:
        ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", out.agentRunId).deleteAll()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", "GateAgent2").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "GateAgent2").deleteAll()
        ec.entity.find("moqui.security.UserGroupMember").condition("userId", "AiTestOperator").deleteAll()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.artifactExecution.enableAuthz()
    }
}
