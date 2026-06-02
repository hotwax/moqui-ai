import spock.lang.*
import org.moqui.ai.provider.AnthropicProvider
import groovy.json.JsonSlurper

class AnthropicProviderTests extends Specification {

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
        body.tools[0].name == "get#Echo"
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

    def "decodes a plain text response"() {
        given: def p = new AnthropicProvider("k", "u", "v", 60)
        when:
        Map r = p.decodeResponse('{"stop_reason":"end_turn","usage":{"input_tokens":3,"output_tokens":2},"content":[{"type":"text","text":"hello"}]}')
        then:
        r.assistantText == "hello"
        (r.toolCalls ?: []).isEmpty()
        r.finishReason == "end_turn"
    }

    @Requires({ System.getenv("ai_anthropic_key") })
    def "live: a real Anthropic call returns text"() {
        given:
        def key = System.getenv("ai_anthropic_key")
        def p = new AnthropicProvider(key, "https://api.anthropic.com", "2023-06-01", 60)
        when:
        Map r = p.chat([model: "claude-sonnet-4-6",
            messages: [[role: "user", content: "Reply with the single word: pong"]]])
        then:
        (r.assistantText as String)?.toLowerCase()?.contains("pong")
    }
}
