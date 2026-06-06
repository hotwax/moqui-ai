import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.AiToolFactory

class DefinitionLoaderTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiTestToolData.xml").load()
        })
        ec.artifactExecution.enableAuthz()
    }
    def cleanupSpec() { if (ec != null) ec.destroy() }
    def setup() { ec.artifactExecution.disableAuthz() }
    def cleanup() { ec.artifactExecution.enableAuthz() }

    def "tool catalog is built from AiTool rows, with a schema generated on demand"() {
        when:
        AiToolFactory ai = ec.factory.getTool("AI", AiToolFactory.class)
        ai.refreshCatalog()                         // pick up the seeded test rows
        def tool = ai.getToolByName("get_echo")
        then:
        tool != null
        tool.toolId == "TL_ECHO"
        tool.toolName == "get_echo"                 // wire name is verb_noun, NOT the service FQN
        tool.serviceName == "moqui.ai.test.TestServices.get#Echo"
        tool.description.contains("Echoes")
        tool.schema.properties.text.type == "string"   // generated on demand from the live service
    }

    def "only active + exposable tools are grant-eligible in the catalog"() {
        given:
        ec.entity.makeValue("moqui.ai.AiTool").setAll([toolId: "TL_OFF", toolName: "get_off",
            verb: "get", noun: "off", serviceName: "moqui.ai.test.TestServices.get#Echo",
            description: "disabled tool", effectEnumId: "AI_TOOL_READ_ONLY", exposable: "Y",
            requiresApproval: "N", statusId: "AI_TOOL_DISABLED"]).createOrUpdate()
        when:
        AiToolFactory ai = ec.factory.getTool("AI", AiToolFactory.class); ai.refreshCatalog()
        then:
        ai.getToolByName("get_off") == null         // disabled tools are not served
        cleanup:
        ec.entity.find("moqui.ai.AiTool").condition("toolId", "TL_OFF").deleteAll()
        ai.refreshCatalog()
    }
}
