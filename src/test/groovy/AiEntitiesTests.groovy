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
}
