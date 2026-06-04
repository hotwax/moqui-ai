import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.AiToolFactory

class DefinitionLoaderTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() { ec = Moqui.getExecutionContext() }
    def cleanupSpec() { if (ec != null) ec.destroy() }
    def setup() { ec.artifactExecution.disableAuthz() }
    def cleanup() { ec.artifactExecution.enableAuthz() }

    def "tool catalog is loaded from ai/*.tools.xml at boot, with a generated schema"() {
        when:
        AiToolFactory ai = ec.factory.getTool("AI", AiToolFactory.class)
        def tool = ai.getTool("moqui.ai.test.TestServices.get#Echo")
        then:
        tool != null
        tool.toolName == "moqui.ai.test.TestServices.get#Echo"
        tool.description.contains("Echoes")
        tool.schema.properties.text.type == "string"
    }

    def "unknown service reference in a tools file fails loud"() {
        when:
        ec.factory.getTool("AI", AiToolFactory.class)
            .loadToolsFromText('<tools><tool service="no.such.Service.get#Nope" description="x"/></tools>')
        then:
        thrown(IllegalArgumentException)
    }
}
