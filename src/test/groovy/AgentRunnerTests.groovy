import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.AiToolFactory
import org.moqui.ai.AgentRunner
import org.moqui.ai.provider.MockProvider

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
