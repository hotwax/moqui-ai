import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.AiToolFactory
import org.moqui.ai.AgentRunner
import org.moqui.ai.provider.MockProvider
import org.moqui.entity.EntityValue

class AgentRunnerTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        // a dedicated test user so tools (authenticate=true) can dispatch as a real user
        ec.entity.makeValue("moqui.security.UserAccount")
            .setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "EchoAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "You echo.", maxIterations: 5,
            maxToolCallsPerTurn: 10, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgentTool")
            .setAll([agentName: "EchoAgent", toolName: "moqui.ai.test.TestServices.get#Echo"]).createOrUpdate()
        ec.artifactExecution.enableAuthz()
    }
    def cleanupSpec() {
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentName", "EchoAgent").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "EchoAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
        ec.destroy()
    }
    def setup() {
        ec.artifactExecution.disableAuthz()
        // tools dispatch as the caller (authenticate=true); log in per-method so it's active for the run
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
    }
    def cleanup() { MockProvider.reset(); ec.artifactExecution.enableAuthz() }

    private AgentRunner runner() { new AgentRunner(ec, ec.factory.getTool("AI", AiToolFactory.class)) }

    def "text-only response returns the assistant message"() {
        given: MockProvider.enqueue([assistantText: "hello", finishReason: "stop", tokensIn: 4L, tokensOut: 2L])
        when: def out = runner().run("EchoAgent", "hi")
        then:
        out.assistantMessage == "hello"
        out.truncated == false
        out.agentRunId != null
        ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one().statusId == "AI_RUN_COMPLETED"
    }

    def "tool call is dispatched and the result is fed back"() {
        given:
        MockProvider.enqueue([toolCalls: [[id: "c1",
            name: "moqui.ai.test.TestServices.get#Echo", arguments: [text: "boom"]]], finishReason: "tool_use"])
        MockProvider.enqueue([assistantText: "echoed boom", finishReason: "stop"])
        when: def out = runner().run("EchoAgent", "echo boom")
        then:
        out.assistantMessage == "echoed boom"
        def call = ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId).list()[0]
        call.toolName == "moqui.ai.test.TestServices.get#Echo"
        call.success == "Y"
        call.result.contains("boom")
    }

    def "hitting max-iterations returns truncated"() {
        given: 6.times { MockProvider.enqueue([toolCalls: [[id: "c$it",
            name: "moqui.ai.test.TestServices.get#Echo", arguments: [text: "x"]]], finishReason: "tool_use"]) }
        when: def out = runner().run("EchoAgent", "loop")
        then:
        out.truncated == true
        ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one().statusId == "AI_RUN_TRUNCATED"
    }

    def "records servedByModelId and providerRunId on the run"() {
        given:
        MockProvider.reset()
        MockProvider.enqueue([assistantText: "done", finishReason: "stop",
            toolCalls: [], tokensIn: 5L, tokensOut: 2L, providerRunId: "prov-xyz"])
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "CorrAgent", providerName: "mock",
            modelName: "mock-model-1", systemPrompt: "x", maxIterations: 3, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        when:
        Map out = runner().run("CorrAgent", "hi", null)
        EntityValue run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()
        then:
        out.statusId == "AI_RUN_COMPLETED"
        run.servedByModelId == "mock-model-1"
        run.providerRunId == "prov-xyz"
        cleanup:
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "CorrAgent").deleteAll()
    }

    def "returns structuredResult when the agent defines a responseSchema"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: null, finishReason: "stop",
            toolCalls: [], tokensIn: 4L, tokensOut: 3L, structuredResult: [sentiment: "positive", score: 9]])
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "SchemaAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "classify",
            responseSchema: '{"type":"object","properties":{"sentiment":{"type":"string"},"score":{"type":"integer"}},"required":["sentiment","score"],"additionalProperties":false}',
            maxIterations: 3, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        when:
        Map out = runner().run("SchemaAgent", "I love it", null)
        then:
        out.statusId == "AI_RUN_COMPLETED"
        out.structuredResult.sentiment == "positive"
        out.structuredResult.score == 9
        cleanup:
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "SchemaAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "uses the AiAgentModel chain (primary candidate) when present"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "ok", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "ChainAgent", providerName: "mock",
            modelName: "legacy-single", systemPrompt: "x", maxIterations: 3, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgentModel")
            .setAll([agentName: "ChainAgent", priority: 0, providerName: "mock", modelName: "primary-from-chain"]).createOrUpdate()
        when:
        Map out = runner().run("ChainAgent", "hi", null)
        EntityValue run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()
        then:
        out.statusId == "AI_RUN_COMPLETED"
        run.servedByModelId == "primary-from-chain"   // chain primary, not the legacy single field
        cleanup:
        ec.entity.find("moqui.ai.AiAgentModel").condition("agentName", "ChainAgent").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "ChainAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "a throwing tool feeds the error back instead of aborting the run"() {
        given:
        MockProvider.enqueue([toolCalls: [[id: "c1",
            name: "moqui.ai.test.TestServices.get#Echo", arguments: [repeat: -1]]], finishReason: "tool_use"])
        MockProvider.enqueue([assistantText: "recovered", finishReason: "stop"])
        when: def out = runner().run("EchoAgent", "bad")
        then:
        out.assistantMessage == "recovered"   // loop continued after the tool error
    }
}
