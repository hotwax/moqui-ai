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

    def "round-trips a sanitized tool_use name back to the raw Moqui service name"() {
        given: def p = new AnthropicProvider("k", "u", "v", 60)
        // encodeRequest sanitizes tool names for the wire, so a real provider echoes the SANITIZED
        // name on its tool_use block. Catalog lookups (AiToolFactory.getTool, the approval gate,
        // dispatch) are keyed by the RAW service name, so chat() must de-sanitize before returning.
        Map request = [tools: [[name: "moqui.ai.test.TestServices.get#Echo", description: "echo",
            parameters: [type: "object", properties: [text: [type: "string"]], required: ["text"]]]]]
        String sanitizedResponse = '''{"stop_reason":"tool_use","usage":{"input_tokens":1,"output_tokens":1},
            "content":[{"type":"tool_use","id":"tu_1","name":"moqui_ai_test_TestServices_get_Echo","input":{"text":"hi"}}]}'''
        when:
        Map decoded = p.decodeResponse(sanitizedResponse)
        p.remapToolNames(decoded, request)
        then:
        decoded.toolCalls[0].name == "moqui.ai.test.TestServices.get#Echo"   // de-sanitized for catalog lookup
        decoded.toolCalls[0].arguments.text == "hi"
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

    def "adds structured_output tool and forces tool_choice when there are no business tools"() {
        given: def p = new AnthropicProvider("k", "u", "v", 60)
        Map schema = [type: "object", properties: [sentiment: [type: "string"]], required: ["sentiment"]]
        when:
        Map body = new JsonSlurper().parseText(p.encodeRequest(
            [model: "m", messages: [[role: "user", content: "hi"]], responseSchema: schema])) as Map
        then:
        body.tools.find { it.name == "structured_output" }.input_schema.properties.sentiment.type == "string"
        body.tool_choice.type == "tool"
        body.tool_choice.name == "structured_output"
    }

    def "adds structured_output alongside business tools without forcing choice"() {
        given: def p = new AnthropicProvider("k", "u", "v", 60)
        when:
        Map body = new JsonSlurper().parseText(p.encodeRequest([model: "m",
            messages: [[role: "user", content: "hi"]],
            tools: [[name: "get#Echo", description: "echo", parameters: [type: "object", properties: [:]]]],
            responseSchema: [type: "object", properties: [sentiment: [type: "string"]], required: ["sentiment"]]])) as Map
        then:
        body.tools.size() == 2
        body.tools.find { it.name == "structured_output" } != null
        body.tools.find { it.name == "get_Echo" } != null   // business tool still sanitized
        body.tool_choice == null
    }

    def "applyStructured lifts the structured_output tool-call into structuredResult"() {
        given: def p = new AnthropicProvider("k", "u", "v", 60)
        Map resp = [toolCalls: [[id: "t1", name: "structured_output", arguments: [sentiment: "positive"]]],
                    assistantText: null, finishReason: "tool_use"]
        when:
        p.applyStructured(resp, [responseSchema: [type: "object"]])
        then:
        resp.structuredResult.sentiment == "positive"
        (resp.toolCalls as List).isEmpty()
        resp.finishReason == "structured_output"
    }

    def "applyStructured preserves a co-emitted business tool call"() {
        given: def p = new AnthropicProvider("k", "u", "v", 60)
        Map resp = [toolCalls: [[id: "b1", name: "moqui.ai.test.TestServices.get#Echo", arguments: [text: "hi"]],
                                [id: "s1", name: "structured_output", arguments: [sentiment: "positive"]]],
                    assistantText: null, finishReason: "tool_use"]
        when:
        p.applyStructured(resp, [responseSchema: [type: "object"]])
        then:
        resp.structuredResult.sentiment == "positive"
        (resp.toolCalls as List).size() == 1
        resp.toolCalls[0].name == "moqui.ai.test.TestServices.get#Echo"
        resp.finishReason == "structured_output"
    }

    @Requires({ System.getenv("ai_anthropic_key") })
    def "live: Anthropic returns structured output matching the agent schema"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        ec.entity.makeValue("moqui.security.UserAccount")
            .setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "AnthropicSentiment", providerName: "anthropic",
            modelName: "claude-sonnet-4-6", systemPrompt: "Classify the sentiment of the user's message.",
            responseSchema: '{"type":"object","properties":{"sentiment":{"type":"string"}},"required":["sentiment"]}',
            maxIterations: 3, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        when:
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "AnthropicSentiment", userMessage: "This is wonderful, I love it!"]).call()
        if (out.statusId == "AI_RUN_FAILED") {
            def err = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()?.errorText
            if (noCredits(err as String)) throw new org.opentest4j.TestAbortedException("Anthropic account has no credits — skipping")
        }
        then:
        out.statusId == "AI_RUN_COMPLETED"
        (out.structuredResult.sentiment as String)?.toLowerCase()?.contains("pos")
        cleanup:
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "AnthropicSentiment").deleteAll()
        ec.artifactExecution.enableAuthz()
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
        // dispatch actually executed on the real provider — proves the sanitized->raw tool-name
        // round-trip held end-to-end (a broken remap records success="N", serviceName=null
        // "Tool not in catalog", so this row would not exist).
        ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId)
            .condition("serviceName", "moqui.ai.test.TestServices.get#Echo").condition("success", "Y").list().size() >= 1
        cleanup:
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentName", "AnthropicEcho").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "AnthropicEcho").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
}
