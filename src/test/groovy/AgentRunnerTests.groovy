import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.AiToolFactory
import org.moqui.ai.AgentRunner
import org.moqui.ai.provider.MockProvider
import org.moqui.entity.EntityValue

class AgentRunnerTests extends Specification {
    @Shared ExecutionContext ec

    // The active Shiro realm (co.hotwax.auth.OfbizShiroRealm) authenticates against the OFBiz UserLogin
    // model, not moqui.security.UserAccount, so the test user needs Party/Person/UserLogin rows for
    // internalLoginUser("AiTestUser") to succeed. Must be called inside a committed (runRequireNew) tx.
    private void ensureTestUser() {
        ec.entity.makeValue("org.apache.ofbiz.party.party.Party").setAll([partyId: "AiTestUser", partyTypeId: "PERSON"]).createOrUpdate()
        ec.entity.makeValue("org.apache.ofbiz.party.party.Person").setAll([partyId: "AiTestUser", firstName: "AI", lastName: "Test User"]).createOrUpdate()
        ec.entity.makeValue("org.apache.ofbiz.security.login.UserLogin").setAll([userLoginId: "AiTestUser", partyId: "AiTestUser", enabled: "Y"]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
    }

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            // seeded test tools (replaces the deleted ai/*.tools.xml): get_echo + get_gated_echo
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiTestToolData.xml").load()
            // a dedicated test user so tools (authenticate=true) can dispatch as a real user
            ensureTestUser()
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "AG_ECHO", agentName: "EchoAgent",
                providerName: "mock", modelName: "mock-1", systemPrompt: "You echo.", maxIterations: 5,
                maxToolCallsPerTurn: 10, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiAgentTool")
                .setAll([agentId: "AG_ECHO", toolId: "TL_ECHO"]).createOrUpdate()
        })
        ec.factory.getTool("AI", AiToolFactory.class).refreshCatalog()
        ec.artifactExecution.enableAuthz()
    }
    def cleanupSpec() {
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", "AG_ECHO").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "AG_ECHO").deleteAll()
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

    private AgentRunner runner() { new AgentRunner(ec) }

    def "text-only response returns the assistant message"() {
        given: MockProvider.enqueue([assistantText: "hello", finishReason: "stop", tokensIn: 4L, tokensOut: 2L])
        when: def out = runner().run("AG_ECHO", "hi")
        then:
        out.assistantMessage == "hello"
        out.truncated == false
        out.agentRunId != null
        ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one().statusId == "AI_RUN_COMPLETED"
    }

    def "tool call is dispatched and the result is fed back"() {
        given:
        MockProvider.enqueue([toolCalls: [[id: "c1",
            name: "get_echo", arguments: [text: "boom"]]], finishReason: "tool_use"])
        MockProvider.enqueue([assistantText: "echoed boom", finishReason: "stop"])
        when: def out = runner().run("AG_ECHO", "echo boom")
        then:
        out.assistantMessage == "echoed boom"
        def call = ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId).list()[0]
        call.toolName == "get_echo"
        call.toolId == "TL_ECHO"
        call.success == "Y"
        call.result.contains("boom")
    }

    def "hitting max-iterations returns truncated"() {
        given: 6.times { MockProvider.enqueue([toolCalls: [[id: "c$it",
            name: "get_echo", arguments: [text: "x"]]], finishReason: "tool_use"]) }
        when: def out = runner().run("AG_ECHO", "loop")
        then:
        out.truncated == true
        ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one().statusId == "AI_RUN_TRUNCATED"
    }

    def "records servedByModelId and providerRunId on the run"() {
        given:
        MockProvider.reset()
        MockProvider.enqueue([assistantText: "done", finishReason: "stop",
            toolCalls: [], tokensIn: 5L, tokensOut: 2L, providerRunId: "prov-xyz"])
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "CorrAgent", agentName: "CorrAgent", providerName: "mock",
                modelName: "mock-model-1", systemPrompt: "x", maxIterations: 3, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        })
        when:
        Map out = runner().run("CorrAgent", "hi", null)
        EntityValue run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()
        then:
        out.statusId == "AI_RUN_COMPLETED"
        run.servedByModelId == "mock-model-1"
        run.providerRunId == "prov-xyz"
        cleanup:
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "CorrAgent").deleteAll()
    }

    def "returns structuredResult when the agent defines a responseSchema"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: null, finishReason: "stop",
            toolCalls: [], tokensIn: 4L, tokensOut: 3L, structuredResult: [sentiment: "positive", score: 9]])
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "SchemaAgent", agentName: "SchemaAgent", providerName: "mock",
                modelName: "mock-1", systemPrompt: "classify",
                responseSchema: '{"type":"object","properties":{"sentiment":{"type":"string"},"score":{"type":"integer"}},"required":["sentiment","score"],"additionalProperties":false}',
                maxIterations: 3, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        })
        when:
        Map out = runner().run("SchemaAgent", "I love it", null)
        then:
        out.statusId == "AI_RUN_COMPLETED"
        out.structuredResult.sentiment == "positive"
        out.structuredResult.score == 9
        cleanup:
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "SchemaAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "uses the AiAgentModel chain (primary candidate) when present"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "ok", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "ChainAgent", agentName: "ChainAgent", providerName: "mock",
                modelName: "legacy-single", systemPrompt: "x", maxIterations: 3, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiAgentModel")
                .setAll([agentId: "ChainAgent", priority: 0, providerName: "mock", modelName: "primary-from-chain"]).createOrUpdate()
        })
        when:
        Map out = runner().run("ChainAgent", "hi", null)
        EntityValue run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()
        then:
        out.statusId == "AI_RUN_COMPLETED"
        run.servedByModelId == "primary-from-chain"   // chain primary, not the legacy single field
        cleanup:
        ec.entity.find("moqui.ai.AiAgentModel").condition("agentId", "ChainAgent").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "ChainAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "fails over to the next candidate when the primary provider call throws"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.enqueue([__error: "primary down (503)"])
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "ok", finishReason: "stop", toolCalls: [], tokensIn: 2L, tokensOut: 1L])
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "FailoverAgent", agentName: "FailoverAgent", providerName: "mock",
                modelName: "ignored", systemPrompt: "x", maxIterations: 3, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiAgentModel").setAll([agentId: "FailoverAgent", priority: 0, providerName: "mock", modelName: "primary"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiAgentModel").setAll([agentId: "FailoverAgent", priority: 1, providerName: "mock", modelName: "backup"]).createOrUpdate()
        })
        when:
        Map out = runner().run("FailoverAgent", "hi", null)
        EntityValue run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()
        then:
        out.statusId == "AI_RUN_COMPLETED"
        run.servedByModelId == "backup"        // primary failed, backup answered
        run.providerName == "mock"
        ec.entity.find("moqui.ai.AiAgentRunStep").condition("agentRunId", out.agentRunId)
            .condition("stepType", "llm_call").condition("success", "N").list().size() == 1
        cleanup:
        ec.entity.find("moqui.ai.AiAgentModel").condition("agentId", "FailoverAgent").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "FailoverAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "fails the run when all candidates are exhausted"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.enqueue([__error: "down-1"])
        org.moqui.ai.provider.MockProvider.enqueue([__error: "down-2"])
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "AllDownAgent", agentName: "AllDownAgent", providerName: "mock",
                modelName: "ignored", systemPrompt: "x", maxIterations: 3, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiAgentModel").setAll([agentId: "AllDownAgent", priority: 0, providerName: "mock", modelName: "m1"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiAgentModel").setAll([agentId: "AllDownAgent", priority: 1, providerName: "mock", modelName: "m2"]).createOrUpdate()
        })
        when:
        Map out = runner().run("AllDownAgent", "hi", null)
        EntityValue run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()
        then:
        out.statusId == "AI_RUN_FAILED"
        (run.errorText as String)?.contains("down-2")    // last error surfaced
        cleanup:
        ec.entity.find("moqui.ai.AiAgentModel").condition("agentId", "AllDownAgent").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "AllDownAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "failover is sticky: once advanced, stays on the working candidate"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.enqueue([__error: "primary down"])
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "c1", name: "no.such.Tool", arguments: [:]]], tokensIn: 1L, tokensOut: 1L])
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "done", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "StickyAgent", agentName: "StickyAgent", providerName: "mock",
                modelName: "ignored", systemPrompt: "x", maxIterations: 4, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiAgentModel").setAll([agentId: "StickyAgent", priority: 0, providerName: "mock", modelName: "primary"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiAgentModel").setAll([agentId: "StickyAgent", priority: 1, providerName: "mock", modelName: "backup"]).createOrUpdate()
        })
        when:
        Map out = runner().run("StickyAgent", "hi", null)
        EntityValue run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()
        then:
        out.statusId == "AI_RUN_COMPLETED"
        run.servedByModelId == "backup"
        ec.entity.find("moqui.ai.AiAgentRunStep").condition("agentRunId", out.agentRunId)
            .condition("stepType", "llm_call").condition("success", "N").list().size() == 1
        cleanup:
        ec.entity.find("moqui.ai.AiAgentModel").condition("agentId", "StickyAgent").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "StickyAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "a throwing tool feeds the error back instead of aborting the run"() {
        given:
        MockProvider.enqueue([toolCalls: [[id: "c1",
            name: "get_echo", arguments: [repeat: -1]]], finishReason: "tool_use"])
        MockProvider.enqueue([assistantText: "recovered", finishReason: "stop"])
        when: def out = runner().run("AG_ECHO", "bad")
        then:
        out.assistantMessage == "recovered"   // loop continued after the tool error
    }
}
