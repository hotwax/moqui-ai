import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.AiToolFactory

class AiToolFactoryBootTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() { ec = Moqui.getExecutionContext() }
    def cleanupSpec() { if (ec != null) ec.destroy() }

    def "AI tool factory is registered and returns a singleton"() {
        when:
        AiToolFactory ai = ec.factory.getTool("AI", AiToolFactory.class)
        then:
        ai != null
        ec.factory.getTool("AI", AiToolFactory.class).is(ai)   // same singleton instance
    }
}
