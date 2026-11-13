import org.junit.jupiter.api.AfterAll
import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite
import org.moqui.Moqui

@Suite
@SelectClasses([ AiToolFactoryBootTests.class, AiEntitiesTests.class,
        MockProviderTests.class, ServiceSchemasTests.class, AuditRedactorTests.class, DefinitionLoaderTests.class,
        AgentRunnerTests.class, RunAgentServiceTests.class, AnthropicProviderTests.class,
        AiConversationTests.class, OpenAiProviderTests.class, AiCostTests.class,
        AiContextTests.class, AiApprovalTests.class, AiReasoningTests.class,
        AiRegistryTests.class, NotNakedSeedTests.class, AiComposerTests.class,
        AiGlossaryTests.class, AiKnowledgeTests.class, AiCapabilityTests.class,
        AiPwaApiTests.class, McpDispatchTests.class, McpToolsTests.class, McpCallTests.class, McpConformanceTests.class, McpTarpitTests.class,
        DroolsRuleTests.class, RulesToolBootTests.class, StoreRuleTests.class, BuildFactTests.class, EvaluateRuleSetTests.class,
        ListFactTests.class, LearnFileFieldsTests.class, UpdateFactFieldTests.class, CheckFileTests.class, LineKindTests.class,
        FactTreeRefusalTests.class, HoldTaskTriageTests.class, FactValueTests.class ])
class MoquiSuite {
    @AfterAll
    static void destroyMoqui() { Moqui.destroyActiveExecutionContextFactory() }
}
