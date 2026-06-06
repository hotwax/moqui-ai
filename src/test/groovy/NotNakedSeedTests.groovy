import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui

class NotNakedSeedTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() {
        ec = Moqui.getExecutionContext(); ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
    }
    def cleanupSpec() { if (ec != null) { ec.artifactExecution.enableAuthz(); ec.destroy() } }

    def "NotNaked ext-seed data wires the OMS assistant agent + tool + grant by id"() {
        when:
        ec.entity.makeDataLoader().location("component://notnaked/data/NotNakedAiData.xml").load()
        def tool = ec.entity.find("moqui.ai.AiTool").condition("toolId", "NN_TOOL_ORDER_SUMMARY").one()
        def agent = ec.entity.find("moqui.ai.AiAgent").condition("agentId", "NN_OMS_ASSISTANT").one()
        def grant = ec.entity.find("moqui.ai.AiAgentTool")
            .condition("agentId", "NN_OMS_ASSISTANT").condition("toolId", "NN_TOOL_ORDER_SUMMARY").one()
        then:
        tool != null && tool.toolName == "get_order_summary_list" && tool.serviceName == "notnaked.OmsAiServices.get#OrderSummaryList"
        agent != null && agent.agentName == "nn-oms-assistant" && agent.statusId == "AI_AGENT_ACTIVE"
        grant != null
    }
}
