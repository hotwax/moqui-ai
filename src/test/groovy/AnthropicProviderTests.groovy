import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.provider.AnthropicProvider
import groovy.json.JsonSlurper

class AnthropicProviderTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() { ec = Moqui.getExecutionContext() }
    def cleanupSpec() { if (ec != null) ec.destroy() }

    def "encodes a request body with system, messages, and tools"() {
        given:
        def p = new AnthropicProvider("k", "https://api.anthropic.com", "2023-06-01", 60)
        Map req = [model: "claude-sonnet-4-6", systemContext: "be terse",
            messages: [[role: "user", content: "hi"]],
            tools: [[name: "get#Echo", description: "echo",
                parameters: [type: "object", properties: [text: [type: "string"]], required: ["text"]]]]]
        when:
        Map body = new JsonSlurper().parseText(p.encodeRequest(req)) as Map
        then:
        body.model == "claude-sonnet-4-6"
        body.system == "be terse"
        body.messages[0].role == "user"
        body.tools[0].name == "get_Echo"   // sanitized: Anthropic names must match ^[a-zA-Z0-9_-]{1,128}$
        body.tools[0].input_schema.properties.text.type == "string"
    }

    def "decodes a tool_use response into tool-call Maps"() {
        given:
        def p = new AnthropicProvider("k", "https://api.anthropic.com", "2023-06-01", 60)
        String raw = '''{"stop_reason":"tool_use","usage":{"input_tokens":12,"output_tokens":7},
            "content":[{"type":"text","text":"calling"},
            {"type":"tool_use","id":"tu_1","name":"get#Echo","input":{"text":"hi"}}]}'''
        when:
        Map r = p.decodeResponse(raw)
        then:
        r.finishReason == "tool_use"
        r.tokensIn == 12L && r.tokensOut == 7L
        r.toolCalls.size() == 1
        r.toolCalls[0].name == "get#Echo"
        r.toolCalls[0].arguments.text == "hi"
    }

    def "decodes a plain text response (with providerRunId)"() {
        given: def p = new AnthropicProvider("k", "u", "v", 60)
        when:
        Map r = p.decodeResponse('{"id":"msg_123","stop_reason":"end_turn","usage":{"input_tokens":3,"output_tokens":2},"content":[{"type":"text","text":"hello"}]}')
        then:
        r.assistantText == "hello"
        r.providerRunId == "msg_123"
        r.finishReason == "end_turn"
    }

    /** Live tests run against the real account; skip (don't fail) when it's just out of credits. */
    private static boolean noCredits(String msg) { msg?.toLowerCase()?.contains("credit balance") }

    @Requires({ System.getenv("ai_anthropic_key") })
    def "live: a real Anthropic call returns text"() {
        given:
        def key = System.getenv("ai_anthropic_key")
        def p = new AnthropicProvider(key, "https://api.anthropic.com", "2023-06-01", 60)
        when:
        Map r
        try { r = p.chat([model: "claude-sonnet-4-6", messages: [[role: "user", content: "Reply with the single word: pong"]]]) }
        catch (RuntimeException e) {
            if (noCredits(e.message)) throw new org.opentest4j.TestAbortedException("Anthropic account has no credits — skipping live call")
            throw e
        }
        then:
        (r.assistantText as String)?.toLowerCase()?.contains("pong")
    }

    @Requires({ System.getenv("ai_anthropic_key") })
    def "live: full agent loop calls a tool via Anthropic and returns an answer"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        ec.entity.makeValue("moqui.security.UserAccount")
            .setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "AnthropicEcho", providerName: "anthropic",
            modelName: "claude-sonnet-4-6", systemPrompt: "Use the get#Echo tool to echo the user's word, then report the result.",
            maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgentTool")
            .setAll([agentName: "AnthropicEcho", toolName: "moqui.ai.test.TestServices.get#Echo"]).createOrUpdate()
        // keep authz disabled through the run#Agent call; login supplies the authenticated user
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        when:
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "AnthropicEcho", userMessage: "Echo the word: marigold"]).call()
        // skip (don't fail) if the run failed only because the account is out of credits
        if (out.statusId == "AI_RUN_FAILED") {
            def err = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()?.errorText
            if (noCredits(err as String)) throw new org.opentest4j.TestAbortedException("Anthropic account has no credits — skipping live loop")
        }
        then:
        out.statusId == "AI_RUN_COMPLETED"
        (out.assistantMessage as String)?.toLowerCase()?.contains("marigold")
        cleanup:
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentName", "AnthropicEcho").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "AnthropicEcho").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
}
