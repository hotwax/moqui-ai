import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.provider.OpenAiProvider
import groovy.json.JsonSlurper

class OpenAiProviderTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() { ec = Moqui.getExecutionContext() }
    def cleanupSpec() { if (ec != null) ec.destroy() }

    // The active Shiro realm (co.hotwax.auth.OfbizShiroRealm) authenticates against the OFBiz UserLogin
    // model, not moqui.security.UserAccount, so the test user needs Party/Person/UserLogin rows for
    // internalLoginUser("AiTestUser") to succeed. Must be called inside a committed (runRequireNew) tx.
    private void ensureTestUser() {
        ec.entity.makeValue("org.apache.ofbiz.party.party.Party").setAll([partyId: "AiTestUser", partyTypeId: "PERSON"]).createOrUpdate()
        ec.entity.makeValue("org.apache.ofbiz.party.party.Person").setAll([partyId: "AiTestUser", firstName: "AI", lastName: "Test User"]).createOrUpdate()
        ec.entity.makeValue("org.apache.ofbiz.security.login.UserLogin").setAll([userLoginId: "AiTestUser", partyId: "AiTestUser", enabled: "Y"]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
    }

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

    def "encodeRequest emits reasoning_effort when reasoning.effort is set"() {
        given: def p = new OpenAiProvider("k", "u", 60)
        when:
        def body = new groovy.json.JsonSlurper().parseText(p.encodeRequest(
            [model: "o4-mini", messages: [[role: "user", content: "hi"]], reasoning: [effort: "high"]]))
        then:
        body.reasoning_effort == "high"
    }

    def "encodeRequest omits reasoning_effort when no reasoning is set"() {
        given: def p = new OpenAiProvider("k", "u", 60)
        when:
        def body = new groovy.json.JsonSlurper().parseText(p.encodeRequest(
            [model: "gpt-4o-mini", messages: [[role: "user", content: "hi"]]]))
        then:
        !body.containsKey("reasoning_effort")
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

    def "round-trips a sanitized function name back to the raw Moqui service name"() {
        given: def p = new OpenAiProvider("k", "u", 60)
        // encodeRequest sanitizes tool names for the wire, so a real provider echoes the SANITIZED
        // name on its tool call. Catalog lookups (AiToolFactory.getTool, the approval gate, dispatch)
        // are keyed by the RAW service name, so chat() must de-sanitize before returning. Driving
        // decodeResponse + remapToolNames reproduces that round-trip without an HTTP call.
        Map request = [tools: [[name: "moqui.ai.test.TestServices.get#Echo", description: "echo",
            parameters: [type: "object", properties: [text: [type: "string"]], required: ["text"]]]]]
        String sanitizedResponse = '''{"choices":[{"finish_reason":"tool_calls","message":{"content":null,
            "tool_calls":[{"id":"call_1","type":"function","function":{"name":"moqui_ai_test_TestServices_get_Echo","arguments":"{\\"text\\":\\"hi\\"}"}}]}}],
            "usage":{"prompt_tokens":1,"completion_tokens":1}}'''
        when:
        Map decoded = p.decodeResponse(sanitizedResponse)
        p.remapToolNames(decoded, request)
        then:
        decoded.toolCalls[0].name == "moqui.ai.test.TestServices.get#Echo"   // de-sanitized for catalog lookup
        decoded.toolCalls[0].arguments.text == "hi"
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

    def "adds response_format json_schema when responseSchema is present"() {
        given: def p = new OpenAiProvider("k", "u", 60)
        Map schema = [type: "object", properties: [sentiment: [type: "string"]],
                      required: ["sentiment"], additionalProperties: false]
        when:
        Map body = new JsonSlurper().parseText(p.encodeRequest(
            [model: "m", messages: [[role: "user", content: "hi"]], responseSchema: schema])) as Map
        then:
        body.response_format.type == "json_schema"
        body.response_format.json_schema.strict == true
        body.response_format.json_schema.schema.properties.sentiment.type == "string"
    }

    def "applyStructured parses assistant JSON into structuredResult"() {
        given: def p = new OpenAiProvider("k", "u", 60)
        Map resp = [assistantText: '{"sentiment":"positive"}', toolCalls: []]
        when:
        p.applyStructured(resp, [responseSchema: [type: "object"]])
        then:
        resp.structuredResult.sentiment == "positive"
    }

    def "applyStructured leaves structuredResult unset on a tool-call turn"() {
        given: def p = new OpenAiProvider("k", "u", 60)
        Map resp = [assistantText: null, toolCalls: [[id: "t1", name: "x"]]]
        when:
        p.applyStructured(resp, [responseSchema: [type: "object"]])
        then:
        !resp.containsKey("structuredResult")
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
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ensureTestUser()
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "OpenAiEcho", providerName: "openai",
                modelName: "gpt-4o-mini", systemPrompt: "Use the get#Echo tool to echo the user's word, then report the result.",
                maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiAgentTool")
                .setAll([agentName: "OpenAiEcho", toolName: "moqui.ai.test.TestServices.get#Echo"]).createOrUpdate()
        })
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
        // dispatch actually executed on the real provider — proves the sanitized->raw tool-name
        // round-trip held end-to-end (a broken remap records success="N", serviceName=null
        // "Tool not in catalog", so this row would not exist).
        ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId)
            .condition("serviceName", "moqui.ai.test.TestServices.get#Echo").condition("success", "Y").list().size() >= 1
        cleanup:
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentName", "OpenAiEcho").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "OpenAiEcho").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    @Requires({ System.getenv("ai_openai_key") })
    def "live: OpenAI returns structured output matching the agent schema"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ensureTestUser()
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "OpenAiSentiment", providerName: "openai",
                modelName: "gpt-4o-mini", systemPrompt: "Classify the sentiment of the user's message.",
                responseSchema: '{"type":"object","properties":{"sentiment":{"type":"string"}},"required":["sentiment"],"additionalProperties":false}',
                maxIterations: 3, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        })
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        when:
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "OpenAiSentiment", userMessage: "This is wonderful, I love it!"]).call()
        then:
        out.statusId == "AI_RUN_COMPLETED"
        (out.structuredResult.sentiment as String)?.toLowerCase()?.contains("pos")
        cleanup:
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "OpenAiSentiment").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    @Requires({ System.getenv("ai_openai_key") })
    def "live: an OpenAI reasoning agent with reasoningEffort completes"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ensureTestUser()
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "OpenAiReason", providerName: "openai",
                modelName: "o4-mini", systemPrompt: "Answer briefly.", reasoningEffort: "low",
                maxIterations: 3, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        })
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        when:
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "OpenAiReason", userMessage: "What is 17 + 25? Reply with just the number."]).call()
        then:
        out.statusId == "AI_RUN_COMPLETED"
        (out.assistantMessage as String)?.contains("42")
        (out.tokensOut as long) > 0
        cleanup:
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "OpenAiReason").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
}
