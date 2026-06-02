import org.junit.jupiter.api.AfterAll
import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite
import org.moqui.Moqui

@Suite
@SelectClasses([ AiToolFactoryBootTests.class, AiEntitiesTests.class,
        MockProviderTests.class, ToolSchemaBuilderTests.class, DefinitionLoaderTests.class,
        AgentRunnerTests.class ])
class MoquiSuite {
    @AfterAll
    static void destroyMoqui() { Moqui.destroyActiveExecutionContextFactory() }
}
