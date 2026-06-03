# OpenAI Provider Adapter — Implementation Plan (Phase 1 fast-follow)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an `openai` provider so agents with `providerName="openai"` run against the OpenAI
Chat Completions API. This is the second real provider after Anthropic (eng-review sequencing:
abstraction + Mock + Anthropic first, then OpenAI + Google as fast-follows). Unblocks end-to-end
testing with a real OpenAI key.

**Architecture:** A new `OpenAiProvider extends AbstractLlmProvider` implementing only the
wire-format `encodeRequest(Map)`/`decodeResponse(String)`. Transport, error mapping, and timeout
are inherited (already proven by `AnthropicProvider`). Key-gated registration in
`AiToolFactory.init` (mirrors Anthropic). All data Map-based.

**Depends on:** Phase 1 (committed, green) — `AbstractLlmProvider`, `LlmProvider`, `AgentRunner`,
`AiToolFactory`, the `prop()` config reader, the test harness pattern.

**Conventions (binding):** Maps not data classes; `System.getProperty`/`getenv` for config at
`init` (no `ec` yet); `*Tests.groovy` + `MoquiSuite`; tests `disableAuthz` + dedicated test user
via `internalLoginUser` (tool services are `authenticate=true`); no Java/Moqui name conflicts.

---

## OpenAI Chat Completions — wire format (the only real differences from Anthropic)

| Concern | Anthropic (built) | OpenAI (this plan) |
|---|---|---|
| Endpoint | `POST /v1/messages` | `POST /v1/chat/completions` |
| Auth header | `x-api-key` + `anthropic-version` | `Authorization: Bearer <key>` |
| System prompt | top-level `system` field | a `{role:"system", content}` **message** (prepended) |
| Tools | `tools:[{name,description,input_schema}]` | `tools:[{type:"function", function:{name,description,parameters}}]` |
| Assistant tool request | content block `{type:tool_use, id,name,input(obj)}` | `message.tool_calls:[{id, type:"function", function:{name, arguments(**JSON string**)}}]` |
| Tool result message | `{role:user, content:[{type:tool_result, tool_use_id, content}]}` | `{role:"tool", tool_call_id, content}` |
| Response text | `content[].text` | `choices[0].message.content` |
| Tool calls in response | `content[].tool_use` (input = object) | `choices[0].message.tool_calls` (`function.arguments` = **JSON string** → parse) |
| Finish reason | `stop_reason` | `choices[0].finish_reason` (`stop`/`tool_calls`/`length`) |
| Tokens | `usage.input_tokens`/`output_tokens` | `usage.prompt_tokens`/`completion_tokens` |

The normalized request/response Maps (the `LlmProvider` contract) are unchanged — only the
encode/decode differs. The two gotchas: **system is a message** (not a field), and **tool-call
arguments are a JSON string** (stringify on encode, parse on decode).

---

## Task 1: Config + key-gated registration

**Files:**
- Modify: `runtime/component/moqui-ai/MoquiConf.xml`
- Modify: `runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/AiToolFactory.groovy`

- [ ] **Step 1: Add OpenAI config to MoquiConf**

Add inside `<moqui-conf>` (next to the Anthropic props):
```xml
    <default-property name="ai_openai_key" value="" is-secret="true"/>
    <default-property name="ai_openai_base_url" value="https://api.openai.com/v1"/>
```
(`ai_timeout_seconds` already exists and is reused.)

- [ ] **Step 2: Register OpenAI in AiToolFactory.init when keyed**

In `AiToolFactory.init`, after the Anthropic block, add (same guarded pattern):
```groovy
        try {
            String openaiKey = prop("ai_openai_key")
            if (openaiKey) {
                registerProvider(new org.moqui.ai.provider.OpenAiProvider(openaiKey,
                    prop("ai_openai_base_url") ?: "https://api.openai.com/v1",
                    (prop("ai_timeout_seconds") ?: "60") as int))
                logger.info("AI: registered OpenAI provider")
            }
        } catch (Throwable t) { logger.warn("AI: skipped OpenAI provider init: ${t.message}") }
```

- [ ] **Step 3: Compile**

Run: `./gradlew :runtime:component:moqui-ai:compileGroovy`  → BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add runtime/component/moqui-ai/MoquiConf.xml runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/AiToolFactory.groovy
git commit -m "feat(moqui-ai): OpenAI provider config + key-gated registration"
```

---

## Task 2: OpenAiProvider (encode/decode)

**Files:**
- Create: `runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/provider/OpenAiProvider.groovy`

- [ ] **Step 1: Implement the adapter**

`runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/provider/OpenAiProvider.groovy`:
```groovy
package org.moqui.ai.provider

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/** OpenAI Chat Completions adapter. Maps the normalized request/response Maps to/from
 *  OpenAI's messages + function tool-calls wire format. */
class OpenAiProvider extends AbstractLlmProvider {
    OpenAiProvider(String apiKey, String baseUrl, int timeoutSeconds) {
        super(apiKey, baseUrl, timeoutSeconds)
    }

    @Override String getName() { return "openai" }
    @Override protected String endpointPath() { return "/chat/completions" }
    @Override protected Map<String, String> authHeaders() { return ["Authorization": "Bearer ${apiKey}".toString()] }

    @Override
    String encodeRequest(Map request) {
        List<Map> apiMessages = []
        // OpenAI: system prompt is a message, prepended
        if (request.systemContext) apiMessages.add([role: "system", content: request.systemContext])
        for (Map m in (request.messages ?: []) as List<Map>) {
            if (m.role == "tool") {
                apiMessages.add([role: "tool", tool_call_id: m.toolCallId, content: m.content])
            } else if (m.role == "assistant" && m.toolCalls) {
                apiMessages.add([role: "assistant", content: null,
                    tool_calls: (m.toolCalls as List<Map>).collect { tc ->
                        [id: tc.id, type: "function",
                         function: [name: tc.name, arguments: JsonOutput.toJson(tc.arguments ?: [:])]] }])
            } else {
                apiMessages.add([role: m.role, content: m.content])
            }
        }
        Map body = [model: request.model, messages: apiMessages]
        if (request.tools) body.tools = (request.tools as List<Map>).collect { t ->
            [type: "function", function: [name: t.name, description: t.description, parameters: t.parameters]] }
        return JsonOutput.toJson(body)
    }

    @Override
    Map decodeResponse(String responseText) {
        Map json = new JsonSlurper().parseText(responseText) as Map
        Map choice = ((json.choices ?: []) as List)[0] as Map
        Map msg = (choice?.message ?: [:]) as Map
        Map usage = (json.usage ?: [:]) as Map
        List<Map> toolCalls = []
        for (Map tc in (msg.tool_calls ?: []) as List<Map>) {
            Map fn = (tc.function ?: [:]) as Map
            Map args = [:]
            try { args = (fn.arguments ? new JsonSlurper().parseText(fn.arguments as String) : [:]) as Map }
            catch (Exception ignored) { args = [:] }   // model occasionally emits malformed args
            toolCalls.add([id: tc.id, name: fn.name, arguments: args])
        }
        return [finishReason: choice?.finish_reason,
                tokensIn: (usage.prompt_tokens ?: 0L) as long,
                tokensOut: (usage.completion_tokens ?: 0L) as long,
                toolCalls: toolCalls,
                assistantText: msg.content ?: null]
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/provider/OpenAiProvider.groovy
git commit -m "feat(moqui-ai): OpenAiProvider Chat Completions adapter"
```

---

## Task 3: Tests (encode/decode + guarded live + guarded end-to-end)

**Files:**
- Create: `runtime/component/moqui-ai/src/test/groovy/OpenAiProviderTests.groovy`
- Modify: `runtime/component/moqui-ai/src/test/groovy/MoquiSuite.groovy` (add `OpenAiProviderTests.class`)

- [ ] **Step 1: Write the tests**

Pure encode/decode always run. Two **opt-in** tests run only when `ai_openai_key` is set
(`@Requires`) — the second is the real reason for this work: drive the whole loop against the
live OpenAI API with your key.

`runtime/component/moqui-ai/src/test/groovy/OpenAiProviderTests.groovy`:
```groovy
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
        body.tools[0].function.name == "get#Echo"
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

    def "decodes a plain text response"() {
        given: def p = new OpenAiProvider("k", "u", 60)
        when:
        Map r = p.decodeResponse('{"choices":[{"finish_reason":"stop","message":{"content":"hello"}}],"usage":{"prompt_tokens":3,"completion_tokens":2}}')
        then:
        r.assistantText == "hello"
        (r.toolCalls ?: []).isEmpty()
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
        ec.artifactExecution.enableAuthz()
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
```

- [ ] **Step 2: Add to suite, run, commit**

Add `OpenAiProviderTests.class` to `MoquiSuite`'s `@SelectClasses`, then:
```bash
./gradlew :runtime:component:moqui-ai:test    # encode/decode pass; live tests skip without ai_openai_key
# to exercise the live + end-to-end tests with your key:
ai_openai_key=sk-... ./gradlew :runtime:component:moqui-ai:test
git add runtime/component/moqui-ai/src/test/groovy/OpenAiProviderTests.groovy runtime/component/moqui-ai/src/test/groovy/MoquiSuite.groovy
git commit -m "test(moqui-ai): OpenAI encode/decode + guarded live + end-to-end loop"
```

How to run with your key (the actual validation you want): export `ai_openai_key` (and it can
also be set in a runtime `MoquiConf.xml`/env for the running app). The end-to-end test creates an
`openai` agent, grants it the echo tool, and drives the real loop — proving your key works through
tool calling, not just a bare completion.

---

## Done — Definition of Done
- `OpenAiProvider` registered when `ai_openai_key` is set; encode/decode unit tests green.
- With a real key: a bare completion returns text, and the **full agent loop dispatches a tool via
  OpenAI and returns an answer** (`AI_RUN_COMPLETED`).
- No changes to the loop, entities, or services — provider-only addition (the abstraction holds).

## NOT in scope
- Google/Gemini adapter (the remaining fast-follow — mirror this plan).
- OpenAI structured-output / JSON mode (Phase 1 structured-output is a separate slice).
- Azure OpenAI / other OpenAI-compatible gateways (work via `ai_openai_base_url`, but untested here).
- Streaming.
