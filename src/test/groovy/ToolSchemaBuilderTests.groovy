import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.ToolSchemaBuilder

class ToolSchemaBuilderTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() { ec = Moqui.getExecutionContext() }
    def cleanupSpec() { if (ec != null) ec.destroy() }
    def setup() { ec.artifactExecution.disableAuthz() }
    def cleanup() { ec.artifactExecution.enableAuthz() }

    def "builds a JSON schema from a service's in-parameters"() {
        when:
        Map schema = ToolSchemaBuilder.build(ec.factory, "moqui.ai.test.TestServices.get#Echo")
        then:
        schema.type == "object"
        schema.properties.text.type == "string"
        schema.properties.repeat.type == "integer"
        schema.required == ["text"]
    }
}
