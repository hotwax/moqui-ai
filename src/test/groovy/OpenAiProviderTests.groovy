import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.provider.OpenAiProvider
import groovy.json.JsonSlurper

class OpenAiProviderTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() { ec = Moqui.getExecutionContext() }
    def cleanupSpec() { if (ec != null) ec.destroy() }

    def "encodes system as a message, tools as functions"() {
        given:
        def p = new OpenAiProvider("k", "https://api.openai.com/v1", 60)
        Map req = [model: "gpt-4o-mini", systemContext: "be terse",
            messages: [[role: "user", content: "hi"]],
            tools: [[name: "get#Echo", description: "echo",
                parameters: [type: "object", properties: [text: [type: "string"]], required: ["text"]]]]]
        when:
        Map body = new JsonSlurper().parseText(p.encodeRequest(req)) as Map
        then:
        body.model == "gpt-4o-mini"
        body.messages[0].role == "system"          // system is a message, not a field
        body.messages[1].role == "user"
        body.tools[0].type == "function"
        body.tools[0].function.name == "get_Echo"   // sanitized: OpenAI names must match ^[a-zA-Z0-9_-]+$
        body.tools[0].function.parameters.properties.text.type == "string"
    }

    def "encodes an assistant tool-call turn with arguments as a JSON string"() {
        given: def p = new OpenAiProvider("k", "u", 60)
        when:
        Map body = new JsonSlurper().parseText(p.encodeRequest([model: "m", messages: [
            [role: "assistant", toolCalls: [[id: "c1", name: "get#Echo", arguments: [text: "boom"]]]],
            [role: "tool", toolCallId: "c1", content: '{"echoed":"boom"}']]])) as Map
        then:
        body.messages[0].tool_calls[0].id == "c1"
        body.messages[0].tool_calls[0].function.arguments == '{"text":"boom"}'   // stringified
        body.messages[1].role == "tool"
        body.messages[1].tool_call_id == "c1"
    }

    def "decodes a tool_calls response (arguments JSON string -> Map)"() {
        given: def p = new OpenAiProvider("k", "u", 60)
        String raw = '''{"choices":[{"finish_reason":"tool_calls","message":{"content":null,
            "tool_calls":[{"id":"call_1","type":"function","function":{"name":"get#Echo","arguments":"{\\"text\\":\\"hi\\"}"}}]}}],
            "usage":{"prompt_tokens":11,"completion_tokens":6}}'''
        when: Map r = p.decodeResponse(raw)
        then:
        r.finishReason == "tool_calls"
        r.tokensIn == 11L && r.tokensOut == 6L
        r.toolCalls.size() == 1
        r.toolCalls[0].name == "get#Echo"
        r.toolCalls[0].arguments.text == "hi"
    }

    def "decodes a plain text response (with providerRunId)"() {
        given: def p = new OpenAiProvider("k", "u", 60)
        when:
        Map r = p.decodeResponse('{"id":"chatcmpl-abc","choices":[{"finish_reason":"stop","message":{"content":"hello"}}],"usage":{"prompt_tokens":3,"completion_tokens":2}}')
        then:
        r.assistantText == "hello"
        r.providerRunId == "chatcmpl-abc"
        r.finishReason == "stop"
    }

    @Requires({ System.getenv("ai_openai_key") })
    def "live: a real OpenAI call returns text"() {
        given: def p = new OpenAiProvider(System.getenv("ai_openai_key"), "https://api.openai.com/v1", 60)
        when:
        Map r = p.chat([model: "gpt-4o-mini", messages: [[role: "user", content: "Reply with the single word: pong"]]])
        then:
        (r.assistantText as String)?.toLowerCase()?.contains("pong")
    }

    @Requires({ System.getenv("ai_openai_key") })
    def "live: full agent loop calls a tool via OpenAI and returns an answer"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        ec.entity.makeValue("moqui.security.UserAccount")
            .setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "OpenAiEcho", providerName: "openai",
            modelName: "gpt-4o-mini", systemPrompt: "Use the get#Echo tool to echo the user's word, then report the result.",
            maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgentTool")
            .setAll([agentName: "OpenAiEcho", toolName: "moqui.ai.test.TestServices.get#Echo"]).createOrUpdate()
        // keep authz disabled through the run#Agent call (the test user has no service permission);
        // login supplies the authenticated user the tool needs (authenticate=true) — distinct from authz
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        when:
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "OpenAiEcho", userMessage: "Echo the word: marigold"]).call()
        then:
        out.statusId == "AI_RUN_COMPLETED"
        (out.assistantMessage as String)?.toLowerCase()?.contains("marigold")
        cleanup:
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentName", "OpenAiEcho").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "OpenAiEcho").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
}
