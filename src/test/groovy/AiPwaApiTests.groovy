import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.AiToolFactory
import org.moqui.ai.provider.MockProvider

/** Services added for the company-app PWA REST facade (service/ai.rest.xml): agent detail,
 *  model list, capability-search approval flag, instruction enhancement, workforce inbox/detail. */
class AiPwaApiTests extends Specification {
    @Shared ExecutionContext ec
    @Shared AiToolFactory ai

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
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiTestToolData.xml").load()
            ensureTestUser()
        })
        ai.refreshCatalog()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.artifactExecution.enableAuthz()
    }
    def cleanupSpec() { if (ec != null) ec.destroy() }
    def setup() {
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
    }
    def cleanup() { MockProvider.reset() }

    def "AiAgent 'detail' master returns the agent plus grants with each grant's tool"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.transaction.runRequireNew(30, "setup", {
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "PwaDetailAgent", agentName: "PwaDetailAgent",
                providerName: "mock", modelName: "mock-1", systemPrompt: "x", statusId: "AI_AGENT_DRAFT"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiAgentTool").setAll([agentId: "PwaDetailAgent", toolId: "TL_GATED",
                requiresApprovalOverride: "N"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiAgentTool").setAll([agentId: "PwaDetailAgent", toolId: "TL_ECHO"]).createOrUpdate()
        })
        when:
        // GET /agents/{id} is served by this master; the client derives
        // effectiveRequiresApproval = grant.requiresApprovalOverride ?: tool.requiresApproval
        Map out = ec.entity.find("moqui.ai.AiAgent").condition("agentId", "PwaDetailAgent").oneMaster("detail")
        then:
        out.agentName == "PwaDetailAgent"
        (out.grants as List).size() == 2
        with((out.grants as List).find { it.toolId == "TL_GATED" }) {
            requiresApprovalOverride == "N"; tool.requiresApproval == "Y"
        }
        with((out.grants as List).find { it.toolId == "TL_ECHO" }) {
            requiresApprovalOverride == null; tool.requiresApproval == "N"
        }
        cleanup:
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", "PwaDetailAgent").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "PwaDetailAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "list#Model returns options and reasoning efforts"() {
        when:
        Map out = ec.service.sync().name("ai.AgentServices.list#Model").parameters([:]).call()
        then:
        out.reasoningEffortList == ["none", "low", "medium", "high"]
        out.modelList instanceof List
        !(out.modelList as List).isEmpty()
        out.defaultModelName != null
    }

    def "enhance#Instructions rewrites via the data-defined enhancement agent"() {
        given:
        ec.artifactExecution.disableAuthz()
        MockProvider.reset()
        // the enhancer ships in data (AiComposerData: AICMP_ENHANCE_AGENT); route it through mock here
        ec.transaction.runRequireNew(30, "setup", {
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "AICMP_ENHANCE_AGENT", agentName: "instruction-enhancer",
                providerName: "mock", modelName: "mock-1", systemPrompt: "x", maxIterations: 1, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        })
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: "ENHANCED PROMPT", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        when:
        Map out = ec.service.sync().name("ai.ComposerServices.enhance#Instructions")
            .parameters([instructions: "help with orders"]).call()
        then:
        out.enhancedInstructions == "ENHANCED PROMPT"
        cleanup:
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "AICMP_ENHANCE_AGENT").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "find#Capability exposes the requiresApproval default"() {
        when:
        Map out = ec.service.sync().name("ai.ComposerServices.find#Capability").parameters([query: "gated"]).call()
        then:
        def gated = (out.capabilityList as List).find { it.toolId == "TL_GATED" }
        gated != null
        gated.requiresApproval == "Y"
    }

    def "workforce list and detail derive statuses and parse tool calls"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.transaction.runRequireNew(30, "setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiConversationStatusData.xml").load()
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "WfAgent", agentName: "WfAgent", providerName: "mock",
                modelName: "mock-1", systemPrompt: "x", statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
            // conversation 1: completed run, user + tool-call + assistant messages
            ec.entity.makeValue("moqui.ai.AiConversation").setAll([conversationId: "WfConv1", agentId: "WfAgent",
                userId: "AiTestUser", title: "First", createdDate: ec.user.nowTimestamp, statusId: "AI_CONV_ACTIVE"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiAgentRun").setAll([agentRunId: "WfRun1", agentId: "WfAgent", agentName: "WfAgent",
                conversationId: "WfConv1", userId: "AiTestUser", startedDate: ec.user.nowTimestamp, statusId: "AI_RUN_COMPLETED"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiConversationMessage").setAll([conversationId: "WfConv1", messageSeqId: "01",
                role: "user", content: "hello", agentRunId: "WfRun1", createdDate: ec.user.nowTimestamp]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiConversationMessage").setAll([conversationId: "WfConv1", messageSeqId: "02",
                role: "assistant", toolCalls: '[{"id":"c1","name":"get_echo","arguments":{"text":"hi"}}]',
                agentRunId: "WfRun1", createdDate: ec.user.nowTimestamp]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiConversationMessage").setAll([conversationId: "WfConv1", messageSeqId: "03",
                role: "assistant", content: "done", agentRunId: "WfRun1", createdDate: ec.user.nowTimestamp]).createOrUpdate()
            // conversation 2: suspended run with one pending request
            ec.entity.makeValue("moqui.ai.AiConversation").setAll([conversationId: "WfConv2", agentId: "WfAgent",
                userId: "AiTestUser", title: "Second", createdDate: ec.user.nowTimestamp, statusId: "AI_CONV_ACTIVE"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiAgentRun").setAll([agentRunId: "WfRun2", agentId: "WfAgent", agentName: "WfAgent",
                conversationId: "WfConv2", userId: "AiTestUser", startedDate: ec.user.nowTimestamp, statusId: "AI_RUN_SUSPENDED"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiToolCallRequest").setAll([toolCallRequestId: "WfReq1", agentRunId: "WfRun2",
                stepSeqId: "1", toolCallId: "c9", toolName: "get_gated_echo", serviceName: "moqui.ai.test.TestServices.get#GatedEcho",
                arguments: '{"text":"x"}', statusId: "AI_TCREQ_PENDING", requestedByUserId: "AiTestUser",
                requestedDate: ec.user.nowTimestamp]).createOrUpdate()
        })
        when:
        Map listOut = ec.service.sync().name("ai.WorkforceServices.list#Conversation").parameters([:]).call()
        Map pendingOnly = ec.service.sync().name("ai.WorkforceServices.list#Conversation").parameters([derivedStatus: "pending"]).call()
        // GET /conversations/{id} is served by the AiConversation 'detail' master (entity REST)
        Map detail = ec.entity.find("moqui.ai.AiConversation").condition("conversationId", "WfConv1").oneMaster("detail")
        Map detail2 = ec.entity.find("moqui.ai.AiConversation").condition("conversationId", "WfConv2").oneMaster("detail")
        then:
        def row1 = (listOut.conversationList as List).find { it.conversationId == "WfConv1" }
        def row2 = (listOut.conversationList as List).find { it.conversationId == "WfConv2" }
        row1.derivedStatus == "idle"
        row1.agentName == "WfAgent"
        row2.derivedStatus == "pending"
        row2.pendingToolName == "get_gated_echo"
        (pendingOnly.conversationList as List).every { it.derivedStatus == "pending" }
        // master nests messages (toolCalls kept as raw JSON; the client parses), runs, runs -> tool-call requests
        (detail.messages as List).size() == 3
        (detail.messages as List).find { it.messageSeqId == "02" }.toolCalls.contains("get_echo")
        (detail.runs as List).find { it.agentRunId == "WfRun1" }.statusId == "AI_RUN_COMPLETED"
        ((detail.runs as List).collectMany { it.toolCallRequests ?: [] }).findAll { it.statusId == "AI_TCREQ_PENDING" }.isEmpty()
        ((detail2.runs as List).collectMany { it.toolCallRequests ?: [] }).find { it.toolCallRequestId == "WfReq1" } != null
        cleanup:
        ec.entity.find("moqui.ai.AiToolCallRequest").condition("toolCallRequestId", "WfReq1").deleteAll()
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", "in", ["WfConv1", "WfConv2"]).deleteAll()
        ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", "in", ["WfRun1", "WfRun2"]).deleteAll()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", "in", ["WfConv1", "WfConv2"]).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "WfAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
}
