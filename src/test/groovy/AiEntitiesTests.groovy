import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.entity.EntityValue

class AiEntitiesTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
        ec.transaction.runRequireNew(30, "ai test setup", {
            // component install data isn't auto-loaded into a non-empty test DB; load it explicitly
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        })
        ec.artifactExecution.enableAuthz()
    }
    def cleanupSpec() { if (ec != null) ec.destroy() }
    def setup() { ec.artifactExecution.disableAuthz() }
    def cleanup() { ec.artifactExecution.enableAuthz() }

    def "status install data is present"() {
        expect:
        ec.entity.find("moqui.basic.StatusItem").condition("statusId", "AI_AGENT_ACTIVE").one() != null
        ec.entity.find("moqui.basic.StatusItem").condition("statusId", "AI_RUN_RUNNING").one() != null
    }

    def "AiTool has opaque toolId PK with verb/noun/effect/exposable + AiToolDenylist + effect enum"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        when:
        ec.entity.makeValue("moqui.ai.AiTool").setAll([toolId: "TOOL_T1", toolName: "list_orders",
            verb: "list", noun: "orders", description: "List orders",
            serviceName: "moqui.ai.test.TestServices.get#Echo", effectEnumId: "AI_TOOL_READ_ONLY",
            exposable: "Y", requiresApproval: "N", sourceComponent: "moqui-ai",
            statusId: "AI_TOOL_ACTIVE"]).create()
        ec.entity.makeValue("moqui.ai.AiToolDenylist").setAll([servicePattern: ".*\\.delete#.*",
            reason: "no deletes via AI"]).createOrUpdate()
        EntityValue t = ec.entity.find("moqui.ai.AiTool").condition("toolId", "TOOL_T1").one()
        then:
        t.toolName == "list_orders"
        t.verb == "list"
        t.effectEnumId == "AI_TOOL_READ_ONLY"
        t.serviceName == "moqui.ai.test.TestServices.get#Echo"
        ec.entity.find("moqui.basic.Enumeration").condition("enumId", "AI_TOOL_READ_ONLY").one() != null
        ec.entity.find("moqui.basic.Enumeration").condition("enumId", "AI_TOOL_MUTATING").one() != null
        ec.entity.find("moqui.basic.StatusItem").condition("statusId", "AI_TOOL_ACTIVE").one() != null
        ec.entity.find("moqui.ai.AiToolDenylist").condition("servicePattern", ".*\\.delete#.*").one().reason == "no deletes via AI"
        cleanup:
        ec.entity.find("moqui.ai.AiTool").condition("toolId", "TOOL_T1").deleteAll()
        ec.entity.find("moqui.ai.AiToolDenylist").condition("servicePattern", ".*\\.delete#.*").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "AiAgent has opaque agentId PK with unique agentName + DRAFT status"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        when:
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "AGENT_A1", agentName: "demo-agent",
            description: "demo", providerName: "mock", modelName: "mock-1", systemPrompt: "x",
            maxIterations: 5, statusId: "AI_AGENT_DRAFT"]).create()
        EntityValue a = ec.entity.find("moqui.ai.AiAgent").condition("agentId", "AGENT_A1").one()
        then:
        a.agentName == "demo-agent"
        a.statusId == "AI_AGENT_DRAFT"
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "demo-agent").one().agentId == "AGENT_A1"
        ec.entity.find("moqui.basic.StatusItem").condition("statusId", "AI_AGENT_DRAFT").one() != null
        cleanup:
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "AGENT_A1").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "can create and read an AiAgent with a tool grant (id-keyed)"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        when:
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "AG_T2", agentName: "T2Agent",
            providerName: "mock", modelName: "mock-1", systemPrompt: "test", maxIterations: 5,
            statusId: "AI_AGENT_ACTIVE"]).create()
        ec.entity.makeValue("moqui.ai.AiTool").setAll([toolId: "TL_ECHO", toolName: "get_echo",
            verb: "get", noun: "echo", serviceName: "moqui.ai.test.TestServices.get#Echo",
            description: "echo", effectEnumId: "AI_TOOL_READ_ONLY", exposable: "Y",
            requiresApproval: "N", statusId: "AI_TOOL_ACTIVE"]).create()
        ec.entity.makeValue("moqui.ai.AiAgentTool").setAll([agentId: "AG_T2", toolId: "TL_ECHO"]).create()
        then:
        EntityValue a = ec.entity.find("moqui.ai.AiAgent").condition("agentId", "AG_T2").one()
        a.providerName == "mock"
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", "AG_T2").list().size() == 1
        cleanup:
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", "AG_T2").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "AG_T2").deleteAll()
        ec.entity.find("moqui.ai.AiTool").condition("toolId", "TL_ECHO").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "AiAgentModel stores priority-ordered provider/model candidates"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeValue("moqui.ai.AiAgentModel")
            .setAll([agentId: "EntAgent", priority: 0, providerName: "openai", modelName: "gpt-4o-mini"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgentModel")
            .setAll([agentId: "EntAgent", priority: 1, providerName: "anthropic", modelName: "claude-sonnet-4-6"]).createOrUpdate()
        when:
        List rows = ec.entity.find("moqui.ai.AiAgentModel").condition("agentId", "EntAgent").orderBy("priority").list()
        then:
        rows.size() == 2
        rows[0].providerName == "openai"
        rows[1].modelName == "claude-sonnet-4-6"
        cleanup:
        ec.entity.find("moqui.ai.AiAgentModel").condition("agentId", "EntAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "AiModelPrice stores an effective-dated per-model price"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeValue("moqui.ai.AiModelPrice").setAll([providerName: "openai", modelName: "gpt-4o-mini",
            fromDate: ec.user.nowTimestamp, inputPricePerMillion: 0.150G, outputPricePerMillion: 0.600G,
            currencyUomId: "USD"]).create()
        when:
        def p = ec.entity.find("moqui.ai.AiModelPrice").condition("providerName", "openai")
            .condition("modelName", "gpt-4o-mini").list().getFirst()
        then:
        (p.inputPricePerMillion as BigDecimal) == 0.150G
        (p.outputPricePerMillion as BigDecimal) == 0.600G
        p.currencyUomId == "USD"
        cleanup:
        ec.entity.find("moqui.ai.AiModelPrice").condition("providerName", "openai").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "AiConversationFact stores a conversation-scoped keyed fact"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeValue("moqui.ai.AiConversationFact").setAll([conversationId: "CONVFACT1", factKey: "order_total",
            factValue: "\$4,812.50", agentRunId: "RUN1", createdDate: ec.user.nowTimestamp]).create()
        when:
        def f = ec.entity.find("moqui.ai.AiConversationFact")
            .condition("conversationId", "CONVFACT1").condition("factKey", "order_total").one()
        then:
        f.factValue == "\$4,812.50"
        f.agentRunId == "RUN1"
        cleanup:
        ec.entity.find("moqui.ai.AiConversationFact").condition("conversationId", "CONVFACT1").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "AiConversation stores a rolling summary + watermark"() {
        given:
        ec.artifactExecution.disableAuthz()
        String cid = "CONVSUM1"
        ec.entity.makeValue("moqui.ai.AiConversation").setAll([conversationId: cid, agentId: "A",
            fromDate: ec.user.nowTimestamp, statusId: "AI_CONV_ACTIVE",
            summaryText: "earlier: customer wants 3 units", summaryThruMessageSeqId: "00007"]).create()
        when:
        def c = ec.entity.find("moqui.ai.AiConversation").condition("conversationId", cid).one()
        then:
        c.summaryText == "earlier: customer wants 3 units"
        c.summaryThruMessageSeqId == "00007"
        cleanup:
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", cid).deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "AiToolApproval + AiAgentRun.pendingState + AI_RUN_SUSPENDED status exist"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        ec.entity.makeValue("moqui.ai.AiAgentRun").setAll([agentRunId: "RUNAPPR1", agentId: "RUNAPPR_AG", agentName: "A",
            statusId: "AI_RUN_SUSPENDED", pendingState: '{"messages":[]}', fromDate: ec.user.nowTimestamp]).create()
        ec.entity.makeValue("moqui.ai.AiToolApproval").setAll([approvalId: "APPR1", agentRunId: "RUNAPPR1",
            toolCallId: "c1", toolName: "x", arguments: "{}", statusId: "AI_APPR_PENDING",
            requestedDate: ec.user.nowTimestamp]).create()
        when:
        def run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", "RUNAPPR1").one()
        def appr = ec.entity.find("moqui.ai.AiToolApproval").condition("approvalId", "APPR1").one()
        def st = ec.entity.find("moqui.basic.StatusItem").condition("statusId", "AI_RUN_SUSPENDED").one()
        then:
        run.pendingState == '{"messages":[]}'
        appr.statusId == "AI_APPR_PENDING"
        st != null
        cleanup:
        ec.entity.find("moqui.ai.AiToolApproval").condition("approvalId", "APPR1").deleteAll()
        ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", "RUNAPPR1").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
}
