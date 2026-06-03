import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.entity.EntityValue

class AiEntitiesTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
        // component install data isn't auto-loaded into a non-empty test DB; load it explicitly
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
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

    def "can create and read an AiAgent with a tool grant"() {
        when:
        ec.entity.makeValue("moqui.ai.AiAgent")
            .setAll([agentName: "T2Agent", providerName: "mock", modelName: "mock-1",
                     systemPrompt: "test", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiTool")
            .setAll([toolName: "get#Echo", serviceName: "moqui.ai.test.TestServices.get#Echo",
                     description: "echo", requiresApproval: "N"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgentTool")
            .setAll([agentName: "T2Agent", toolName: "get#Echo"]).createOrUpdate()
        then:
        EntityValue a = ec.entity.find("moqui.ai.AiAgent").condition("agentName", "T2Agent").one()
        a != null
        a.providerName == "mock"
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentName", "T2Agent").list().size() == 1

        cleanup:
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentName", "T2Agent").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "T2Agent").deleteAll()
        ec.entity.find("moqui.ai.AiTool").condition("toolName", "get#Echo").deleteAll()
    }

    def "AiAgentModel stores priority-ordered provider/model candidates"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeValue("moqui.ai.AiAgentModel")
            .setAll([agentName: "EntAgent", priority: 0, providerName: "openai", modelName: "gpt-4o-mini"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgentModel")
            .setAll([agentName: "EntAgent", priority: 1, providerName: "anthropic", modelName: "claude-sonnet-4-6"]).createOrUpdate()
        when:
        List rows = ec.entity.find("moqui.ai.AiAgentModel").condition("agentName", "EntAgent").orderBy("priority").list()
        then:
        rows.size() == 2
        rows[0].providerName == "openai"
        rows[1].modelName == "claude-sonnet-4-6"
        cleanup:
        ec.entity.find("moqui.ai.AiAgentModel").condition("agentName", "EntAgent").deleteAll()
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
}
