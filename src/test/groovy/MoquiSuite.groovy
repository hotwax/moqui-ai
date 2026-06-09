import org.junit.jupiter.api.AfterAll
import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite
import org.moqui.Moqui

@Suite
@SelectClasses([ AiToolFactoryBootTests.class, AiEntitiesTests.class,
        MockProviderTests.class, ToolSchemaBuilderTests.class, DefinitionLoaderTests.class,
        AgentRunnerTests.class, RunAgentServiceTests.class, AnthropicProviderTests.class,
        AiConversationTests.class, OpenAiProviderTests.class, AiCostTests.class,
        AiContextTests.class, AiApprovalTests.class, AiReasoningTests.class,
        AiRegistryTests.class, NotNakedSeedTests.class, AiComposerTests.class,
        AiGlossaryTests.class, AiKnowledgeTests.class ])
class MoquiSuite {
    @AfterAll
    static void destroyMoqui() { Moqui.destroyActiveExecutionContextFactory() }
}
