# Revision: Anthropic Tool-Name Fix + Structured Output + Correlation Fields — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the next `moqui-ai` revision: fix the Anthropic tool-name 400, add agent-defined structured output across OpenAI/Anthropic, and add two populated run-correlation fields.

**Architecture:** Three independent slices on the existing provider-agnostic loop. (A) Lift OpenAI's tool-name sanitization into the shared base class so every HTTP provider gets it, fixing Anthropic. (B) Add `AiAgentRun.servedByModelId` + `providerRunId`, populated from each adapter's decode. (C) Add `AiAgent.responseSchema`; each adapter translates that one stored JSON Schema to its native mechanism (OpenAI `response_format`, Anthropic forced-tool), and the loop returns a normalized `structuredResult` Map. No framework-core changes; pure component.

**Tech Stack:** Groovy 3 / JDK 11, Moqui (HotWax fork), Spock + JUnit Platform Suite, H2 dev DB.

---

## Decision record this implements

[2026-06-03-enterprise-decisions-gap-report.md](../specs/2026-06-03-enterprise-decisions-gap-report.md) — v1 = current framework + structured output. This plan also folds in the Anthropic tool-name bug (surfaced when the funded account let the live tool-loop run) and the two correlation fields agreed in discussion.

## Conventions you must follow (from the project's confirmed practices)

- **Tests:** Spock classes named `*Tests.groovy` under `src/test/groovy/`, registered in `MoquiSuite.groovy` `@SelectClasses`. This plan adds methods to **existing** test classes, so no new suite registration is needed.
- **Run the suite:** from the moqui root (`/Users/anilpatel/maarg-sd/moqui`):
  ```bash
  source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
  ```
  `--no-daemon` so a stale daemon env doesn't mask the keys. Live tests are `@Requires({ System.getenv("ai_openai_key") })` / `ai_anthropic_key` and are skipped without keys.
- **Entities are ours — edit directly, do NOT use `extend-entity`.** Adding a *nullable field* only needs a reboot (Moqui auto-adds the column). The test task boots Moqui, so no manual DB step is required for these additions.
- **Status values** come from `StatusItem`; do not introduce freeform status strings.
- **Maps only** for LLM request/response data (Moqui idiom; no data-holder POJOs).
- **Show a diff before saving** each file change (moqui CLAUDE.md rule 3).
- **Never run a gradle *build*** unless asked; running the *test* task is expected here.

## Canonical Map shapes (already in use; do not change)

- **Request** (AgentRunner → `provider.chat`): `[model, systemContext, messages, tools, responseSchema]`. `responseSchema` is NEW (a JSON-Schema Map, or null).
- **Response** (`provider.chat` → AgentRunner): `[assistantText, toolCalls, finishReason, tokensIn, tokensOut, providerRunId, structuredResult]`. `providerRunId` + `structuredResult` are NEW (nullable).
- **toolCall Map:** `[id, name, arguments(Map)]`.

---

## File Structure

| File | Change |
|---|---|
| `src/main/groovy/org/moqui/ai/provider/AbstractLlmProvider.groovy` | Lift `sanitizeName` + tool-name reverse-map into shared `chat()`; add no-op `applyStructured` hook |
| `src/main/groovy/org/moqui/ai/provider/OpenAiProvider.groovy` | Remove now-inherited `chat()` override + `sanitizeName`; add `response_format` encode + `applyStructured`; add `providerRunId` to decode |
| `src/main/groovy/org/moqui/ai/provider/AnthropicProvider.groovy` | Sanitize tool names on encode; add `structured_output` tool + `tool_choice` encode + `applyStructured`; add `providerRunId` to decode |
| `entity/AiEntities.xml` | `AiAgent.responseSchema`; `AiAgentRun.servedByModelId` + `providerRunId` |
| `src/main/groovy/org/moqui/ai/AgentRunner.groovy` | Thread `responseSchema` into the request; capture `structuredResult` + `providerRunId` + `servedByModelId`; persist + return them |
| `service/ai/AgentServices.xml` | Add `structuredResult`, `servedByModelId`, `providerRunId` out-parameters to `run#Agent` |
| `src/test/groovy/AnthropicProviderTests.groovy` | Update sanitize expectation; add structured-output encode/decode + live tests |
| `src/test/groovy/OpenAiProviderTests.groovy` | Add `response_format` encode + `applyStructured` + `providerRunId` + live structured tests |
| `src/test/groovy/AgentRunnerTests.groovy` | Add structured-path + correlation-field tests (via MockProvider) |
| `src/test/groovy/RunAgentServiceTests.groovy` | Assert `structuredResult` out-param surfaces |

---

# PART A — Fix Anthropic tool-name 400 (DRY the sanitizer into the base class)

The live Anthropic tool loop fails with `tools.0.custom.name: String should match pattern '^[a-zA-Z0-9_-]{1,128}$'` because our tool name `moqui.ai.test.TestServices.get#Echo` contains `.`/`#`. OpenAI already sanitizes + reverse-maps; Anthropic doesn't. We lift the shared behavior into `AbstractLlmProvider` so both (and any future HTTP provider) get it.

### Task 1: Lift sanitize + reverse-map into AbstractLlmProvider; apply in Anthropic

**Files:**
- Modify: `src/main/groovy/org/moqui/ai/provider/AbstractLlmProvider.groovy`
- Modify: `src/main/groovy/org/moqui/ai/provider/OpenAiProvider.groovy`
- Modify: `src/main/groovy/org/moqui/ai/provider/AnthropicProvider.groovy`
- Test: `src/test/groovy/AnthropicProviderTests.groovy`

- [ ] **Step 1: Update the Anthropic encode test to expect a sanitized name (failing test)**

In `AnthropicProviderTests.groovy`, the existing test `"encodes a request body with system, messages, and tools"` asserts the raw name. Change that one assertion:

```groovy
        body.tools[0].name == "get_Echo"   // sanitized: Anthropic names must match ^[a-zA-Z0-9_-]{1,128}$
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — `AnthropicProviderTests` encode test sees `get#Echo`, expected `get_Echo`.

- [ ] **Step 3: Move `sanitizeName` + the reverse-map into `AbstractLlmProvider.chat()`**

In `AbstractLlmProvider.groovy`, add the static helper and replace the `chat()` body so it builds the sanitized→real map, runs the transport, then remaps returned tool-call names:

```groovy
    /** Provider function/tool names must match ^[a-zA-Z0-9_-]+$, but Moqui service names contain
     *  '.' and '#'. Sanitize for the wire; map back when a tool call returns. Shared by all HTTP
     *  providers (OpenAI, Anthropic, ...). */
    static String sanitizeName(String n) { n == null ? null : n.replaceAll('[^a-zA-Z0-9_-]', '_') }

    /** Optional hook: after decode, a provider may normalize a structured-output answer into
     *  resp.structuredResult. Default no-op. Called only when request.responseSchema is set. */
    protected void applyStructured(Map resp, Map request) { }

    @Override
    Map chat(Map request) {
        Map<String, String> backToReal = [:]
        for (Map t in (request.tools ?: []) as List<Map>) backToReal[sanitizeName(t.name as String)] = t.name as String

        String body = encodeRequest(request)
        RestClient rc = new RestClient().uri(baseUrl + endpointPath())
            .method('POST').contentType("application/json").timeout(timeoutSeconds).text(body)
        authHeaders().each { k, v -> rc.addHeader(k, v) }
        def resp
        try {
            resp = rc.call()
        } catch (Exception e) {
            throw new RuntimeException("LLM provider ${name} HTTP error: ${e.message}", e)
        }
        int sc = resp.getStatusCode()
        String text = resp.text()
        // Fail loudly on a non-2xx — otherwise an error body parses as an empty completion and the
        // run silently "completes" with no answer (masking real errors, e.g. a 400 bad request).
        if (sc < 200 || sc >= 300) throw new RuntimeException("LLM provider ${name} HTTP ${sc}: ${text}")

        Map decoded = decodeResponse(text)
        for (Map tc in (decoded.toolCalls ?: []) as List<Map>) tc.name = backToReal[tc.name as String] ?: tc.name
        if (request.responseSchema) applyStructured(decoded, request)
        return decoded
    }
```

- [ ] **Step 4: Simplify `OpenAiProvider` — drop the now-inherited override and helper**

In `OpenAiProvider.groovy`, **delete** the `chat(Map)` override (lines doing `backToReal`) and **delete** the local `static String sanitizeName(...)`. The unqualified `sanitizeName(...)` calls inside `encodeRequest` now resolve to the inherited static — leave them as-is. Result: `OpenAiProvider` keeps `getName`, `endpointPath`, `authHeaders`, `encodeRequest`, `decodeResponse` only.

- [ ] **Step 5: Sanitize tool names in `AnthropicProvider.encodeRequest`**

In `AnthropicProvider.groovy`, sanitize both the assistant `tool_use` name and the tool declaration name:

```groovy
            } else if (m.role == "assistant" && m.toolCalls) {
                apiMessages.add([role: "assistant", content: (m.toolCalls as List<Map>).collect { tc ->
                    [type: "tool_use", id: tc.id, name: sanitizeName(tc.name as String), input: tc.arguments] }])
```

```groovy
        if (request.tools) body.tools = (request.tools as List<Map>).collect { t ->
            [name: sanitizeName(t.name as String), description: t.description, input_schema: t.parameters] }
```

- [ ] **Step 6: Run the suite — encode test passes; the live Anthropic tool loop now goes green**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS. `AnthropicProviderTests` encode test passes; `live: full agent loop calls a tool via Anthropic and returns an answer` now reports `AI_RUN_COMPLETED` with "marigold". OpenAI tests remain green (behavior unchanged — the override only moved up).

- [ ] **Step 7: Commit**

```bash
git add runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/provider/AbstractLlmProvider.groovy \
        runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/provider/OpenAiProvider.groovy \
        runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/provider/AnthropicProvider.groovy \
        runtime/component/moqui-ai/src/test/groovy/AnthropicProviderTests.groovy
git commit -m "fix(ai): sanitize tool names for Anthropic; lift sanitize+remap into AbstractLlmProvider"
```

---

# PART B — Run-correlation fields (`servedByModelId`, `providerRunId`)

Both populated now: `servedByModelId` = the configured model (until fallback exists, it equals it — but the write path is in place); `providerRunId` = the provider's response id, surfaced from decode.

### Task 2: Add and populate the two correlation fields

**Files:**
- Modify: `entity/AiEntities.xml`
- Modify: `src/main/groovy/org/moqui/ai/provider/OpenAiProvider.groovy`
- Modify: `src/main/groovy/org/moqui/ai/provider/AnthropicProvider.groovy`
- Modify: `src/main/groovy/org/moqui/ai/AgentRunner.groovy`
- Modify: `service/ai/AgentServices.xml`
- Test: `src/test/groovy/OpenAiProviderTests.groovy`, `AnthropicProviderTests.groovy`, `AgentRunnerTests.groovy`

- [ ] **Step 1: Write failing decode tests for `providerRunId`**

In `OpenAiProviderTests.groovy`, extend the existing `"decodes a plain text response"` expectations by adding an `id` to the raw JSON and asserting it. Replace that test's `given`/`when`/`then` with:

```groovy
    def "decodes a plain text response (with providerRunId)"() {
        given: def p = new OpenAiProvider("k", "u", 60)
        when:
        Map r = p.decodeResponse('{"id":"chatcmpl-abc","choices":[{"finish_reason":"stop","message":{"content":"hello"}}],"usage":{"prompt_tokens":3,"completion_tokens":2}}')
        then:
        r.assistantText == "hello"
        r.providerRunId == "chatcmpl-abc"
        r.finishReason == "stop"
    }
```

In `AnthropicProviderTests.groovy`, replace `"decodes a plain text response"` with:

```groovy
    def "decodes a plain text response (with providerRunId)"() {
        given: def p = new AnthropicProvider("k", "u", "v", 60)
        when:
        Map r = p.decodeResponse('{"id":"msg_123","stop_reason":"end_turn","usage":{"input_tokens":3,"output_tokens":2},"content":[{"type":"text","text":"hello"}]}')
        then:
        r.assistantText == "hello"
        r.providerRunId == "msg_123"
        r.finishReason == "end_turn"
    }
```

- [ ] **Step 2: Run — confirm both fail (`providerRunId` is null)**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — `r.providerRunId` is `null`.

- [ ] **Step 3: Surface `providerRunId` from both decoders**

In `OpenAiProvider.decodeResponse`, add `providerRunId` to the returned Map:

```groovy
        return [finishReason: choice?.finish_reason,
                tokensIn: (usage.prompt_tokens ?: 0L) as long,
                tokensOut: (usage.completion_tokens ?: 0L) as long,
                providerRunId: json.id,
                toolCalls: toolCalls,
                assistantText: msg.content ?: null]
```

In `AnthropicProvider.decodeResponse`:

```groovy
        return [finishReason: json.stop_reason,
                tokensIn: (usage.input_tokens ?: 0L) as long,
                tokensOut: (usage.output_tokens ?: 0L) as long,
                providerRunId: json.id,
                toolCalls: toolCalls,
                assistantText: text.length() > 0 ? text.toString() : null]
```

- [ ] **Step 4: Add the entity fields**

In `entity/AiEntities.xml`, inside `<entity entity-name="AiAgentRun" ...>`, add after the `modelName` field (line 46):

```xml
        <field name="servedByModelId" type="text-medium"><description>The model that actually served the run (= modelName until fallback exists)</description></field>
        <field name="providerRunId" type="text-medium"><description>The provider's own response id (OpenAI id / Anthropic message id), for support correlation</description></field>
```

- [ ] **Step 5: Capture + persist them in `AgentRunner`**

In `AgentRunner.run(...)`, initialize on the result Map (add keys to the existing `result = [...]` literal):

```groovy
        Map result = [agentRunId: runId, conversationId: conversationId, assistantMessage: null,
                      tokensIn: 0L, tokensOut: 0L, iterations: 0, truncated: false, statusId: "AI_RUN_RUNNING",
                      structuredResult: null, servedByModelId: agent.modelName as String, providerRunId: null]
```

Inside the loop, right after `result.tokensIn += inTok; result.tokensOut += outTok`, capture the provider id:

```groovy
                if (resp.providerRunId) result.providerRunId = resp.providerRunId
```

In `finish(...)`, include both in the `update#moqui.ai.AiAgentRun` params:

```groovy
        persist("update#moqui.ai.AiAgentRun", [agentRunId: runId, thruDate: ec.user.nowTimestamp,
            statusId: statusId, assistantMessage: result.assistantMessage, iterations: result.iterations,
            tokensIn: result.tokensIn, tokensOut: result.tokensOut, errorText: errorText,
            servedByModelId: result.servedByModelId, providerRunId: result.providerRunId])
```

- [ ] **Step 6: Surface them on the service (optional but cheap correlation for callers)**

In `service/ai/AgentServices.xml` `run#Agent`, add to `<out-parameters>`:

```xml
            <parameter name="servedByModelId"/>
            <parameter name="providerRunId"/>
```

and in the `<script>` actions, after `statusId = r.statusId`:

```groovy
                servedByModelId = r.servedByModelId
                providerRunId = r.providerRunId
```

- [ ] **Step 7: Add an AgentRunner test that the run row records both fields**

In `AgentRunnerTests.groovy`, add a test that enqueues a mock response carrying a `providerRunId` and asserts the persisted run. Match the existing setup style in that file (mock agent named for this test). Add:

```groovy
    def "records servedByModelId and providerRunId on the run"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "done", finishReason: "stop",
            toolCalls: [], tokensIn: 5L, tokensOut: 2L, providerRunId: "prov-xyz"])
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "CorrAgent", providerName: "mock",
            modelName: "mock-model-1", systemPrompt: "x", maxIterations: 3, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        when:
        Map out = new org.moqui.ai.AgentRunner(ec, ai).run("CorrAgent", "hi", null)
        EntityValue run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()
        then:
        out.statusId == "AI_RUN_COMPLETED"
        run.servedByModelId == "mock-model-1"
        run.providerRunId == "prov-xyz"
        cleanup:
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "CorrAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```

> Note: `AgentRunnerTests` already obtains `ai` (the `AiToolFactory`) and `ec`; reuse its existing `@Shared`/`setupSpec` members. If `EntityValue` isn't imported there, add `import org.moqui.entity.EntityValue`.

- [ ] **Step 8: Run the suite — all green**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS. Decode tests see the ids; the new AgentRunner test confirms both fields persist.

- [ ] **Step 9: Commit**

```bash
git add runtime/component/moqui-ai/entity/AiEntities.xml \
        runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/provider/OpenAiProvider.groovy \
        runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/provider/AnthropicProvider.groovy \
        runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/AgentRunner.groovy \
        runtime/component/moqui-ai/service/ai/AgentServices.xml \
        runtime/component/moqui-ai/src/test/groovy/OpenAiProviderTests.groovy \
        runtime/component/moqui-ai/src/test/groovy/AnthropicProviderTests.groovy \
        runtime/component/moqui-ai/src/test/groovy/AgentRunnerTests.groovy
git commit -m "feat(ai): add servedByModelId and providerRunId run-correlation fields"
```

---

# PART C — Structured output (agent-defined, locked)

The agent definition owns the contract. When `AiAgent.responseSchema` is set, `run#Agent` returns a typed `structuredResult` Map; when null, behavior is unchanged (free-text `assistantMessage`). Build the loop contract first (Task 3, Mock-verified), then each provider's translation (Tasks 4–5).

### Task 3: `AiAgent.responseSchema` + the loop contract (Mock-verified)

**Files:**
- Modify: `entity/AiEntities.xml`
- Modify: `src/main/groovy/org/moqui/ai/AgentRunner.groovy`
- Modify: `service/ai/AgentServices.xml`
- Test: `src/test/groovy/AgentRunnerTests.groovy`, `RunAgentServiceTests.groovy`

- [ ] **Step 1: Write a failing AgentRunner structured-path test (MockProvider)**

MockProvider returns whatever is enqueued, so we enqueue a response that already carries `structuredResult` (as a real provider's `applyStructured` would). Add to `AgentRunnerTests.groovy`:

```groovy
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
        Map out = new org.moqui.ai.AgentRunner(ec, ai).run("SchemaAgent", "I love it", null)
        then:
        out.statusId == "AI_RUN_COMPLETED"
        out.structuredResult.sentiment == "positive"
        out.structuredResult.score == 9
        cleanup:
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "SchemaAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```

- [ ] **Step 2: Run — confirm it fails**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — `out.structuredResult` is `null` (runner doesn't read it yet).

- [ ] **Step 3: Add the entity field**

In `entity/AiEntities.xml`, inside `<entity entity-name="AiAgent" ...>`, add after `systemPrompt` (line 10):

```xml
        <field name="responseSchema" type="text-very-long"><description>Optional JSON Schema (as text). When set, run#Agent returns a typed structuredResult Map; adapters translate to the provider's native structured-output mechanism.</description></field>
```

- [ ] **Step 4: Thread `responseSchema` in and capture `structuredResult` in `AgentRunner`**

Near the top of `run(...)`, after loading `agent`, parse the schema:

```groovy
        Map responseSchema = agent.responseSchema ?
            new groovy.json.JsonSlurper().parseText(agent.responseSchema as String) as Map : null
```

Pass it on the request Map in the loop (extend the existing `provider.chat([...])` call):

```groovy
                Map resp = provider.chat([model: agent.modelName, systemContext: agent.systemPrompt,
                        messages: messages, tools: toolSchemas, responseSchema: responseSchema])
```

Capture the structured answer just after the token accounting line (next to the `providerRunId` capture from Task 2):

```groovy
                if (resp.structuredResult != null) result.structuredResult = resp.structuredResult
```

In the no-tool-calls completion branch, ensure the run record still has a textual `assistantMessage` for audit when only structured data came back:

```groovy
                if (!toolCalls) {
                    result.assistantMessage = resp.assistantText ?:
                        (result.structuredResult != null ? groovy.json.JsonOutput.toJson(result.structuredResult) : "")
                    if (conversationId) persistConversationMessage(conversationId, runId,
                        [role: "assistant", content: result.assistantMessage])
                    return finish(result, runId, conversationId, "AI_RUN_COMPLETED", null)
                }
```

> `result.structuredResult` was already initialized to `null` in the result literal in Task 2 Step 5. If Task 2 was skipped, add `structuredResult: null` to that literal.

- [ ] **Step 5: Add the `structuredResult` out-parameter on the service**

In `service/ai/AgentServices.xml` `run#Agent` `<out-parameters>`:

```xml
            <parameter name="structuredResult" type="Map"/>
```

and in the `<script>` actions, after `statusId = r.statusId`:

```groovy
                structuredResult = r.structuredResult
```

- [ ] **Step 6: Add a service-level assertion**

In `RunAgentServiceTests.groovy`, add a test mirroring the existing service-call style, using MockProvider:

```groovy
    def "run#Agent surfaces structuredResult for a schema-bound agent"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: null, finishReason: "stop",
            toolCalls: [], tokensIn: 1L, tokensOut: 1L, structuredResult: [answer: "42"]])
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "SvcSchemaAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "x",
            responseSchema: '{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"],"additionalProperties":false}',
            maxIterations: 2, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserAccount")
            .setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        when:
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "SvcSchemaAgent", userMessage: "q"]).call()
        then:
        out.statusId == "AI_RUN_COMPLETED"
        out.structuredResult.answer == "42"
        cleanup:
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "SvcSchemaAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```

> Reuse the file's existing imports/`@Shared ec`/`setupSpec`. If it has no test `UserAccount` helper, the inline `createOrUpdate` + `internalLoginUser` above is self-contained (matches the pattern in `OpenAiProviderTests`).

- [ ] **Step 7: Run — all green (loop contract proven via Mock)**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add runtime/component/moqui-ai/entity/AiEntities.xml \
        runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/AgentRunner.groovy \
        runtime/component/moqui-ai/service/ai/AgentServices.xml \
        runtime/component/moqui-ai/src/test/groovy/AgentRunnerTests.groovy \
        runtime/component/moqui-ai/src/test/groovy/RunAgentServiceTests.groovy
git commit -m "feat(ai): agent-defined responseSchema returns normalized structuredResult"
```

### Task 4: OpenAI structured-output translation (`response_format` json_schema)

OpenAI Chat Completions structured outputs use `response_format: {type:"json_schema", json_schema:{name, schema, strict:true}}`. The final assistant `content` is the JSON; `applyStructured` parses it into `structuredResult`. Coexists with function tools (parse only when content is present, i.e. not a tool-call turn). **Strict-mode constraint:** every property must be listed in `required` and the schema must set `additionalProperties:false` — the test schema below complies.

**Files:**
- Modify: `src/main/groovy/org/moqui/ai/provider/OpenAiProvider.groovy`
- Test: `src/test/groovy/OpenAiProviderTests.groovy`

- [ ] **Step 1: Write failing encode + applyStructured tests**

Add to `OpenAiProviderTests.groovy`:

```groovy
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
```

- [ ] **Step 2: Run — confirm both fail**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — no `response_format`; `applyStructured` not defined on OpenAiProvider.

- [ ] **Step 3: Implement encode + applyStructured**

In `OpenAiProvider.encodeRequest`, after the `if (request.tools) ...` block and before `return`:

```groovy
        if (request.responseSchema) body.response_format = [type: "json_schema",
            json_schema: [name: "structured_output", schema: request.responseSchema, strict: true]]
```

Add the override (anywhere in the class body):

```groovy
    @Override
    protected void applyStructured(Map resp, Map request) {
        String t = resp.assistantText as String
        if (t) {
            try { resp.structuredResult = new JsonSlurper().parseText(t) }
            catch (Exception ignored) { }   // tool-call turns / non-JSON content: leave structuredResult unset
        }
    }
```

- [ ] **Step 4: Add a live end-to-end structured test**

```groovy
    @Requires({ System.getenv("ai_openai_key") })
    def "live: OpenAI returns structured output matching the agent schema"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        ec.entity.makeValue("moqui.security.UserAccount")
            .setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "OpenAiSentiment", providerName: "openai",
            modelName: "gpt-4o-mini", systemPrompt: "Classify the sentiment of the user's message.",
            responseSchema: '{"type":"object","properties":{"sentiment":{"type":"string"}},"required":["sentiment"],"additionalProperties":false}',
            maxIterations: 3, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
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
```

- [ ] **Step 5: Run — unit + live pass**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS (OpenAI funded).

- [ ] **Step 6: Commit**

```bash
git add runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/provider/OpenAiProvider.groovy \
        runtime/component/moqui-ai/src/test/groovy/OpenAiProviderTests.groovy
git commit -m "feat(ai): OpenAI structured output via response_format json_schema"
```

### Task 5: Anthropic structured-output translation (forced tool)

Anthropic has no native JSON-schema response mode; the idiom is a **forced tool call**. We append a synthetic `structured_output` tool whose `input_schema` is the agent's schema. With **no business tools**, force `tool_choice` to it for a one-shot structured answer. With business tools present, leave `tool_choice` default (auto) so the model can call business tools across the loop, then call `structured_output` to deliver the final answer (nudged by the agent's system prompt). `applyStructured` lifts that tool-call's `arguments` into `structuredResult` and removes it from `toolCalls` so the loop sees a clean completion.

**Files:**
- Modify: `src/main/groovy/org/moqui/ai/provider/AnthropicProvider.groovy`
- Test: `src/test/groovy/AnthropicProviderTests.groovy`

- [ ] **Step 1: Write failing encode + applyStructured tests**

Add to `AnthropicProviderTests.groovy`:

```groovy
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
```

- [ ] **Step 2: Run — confirm they fail**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — no `structured_output` tool; `applyStructured` undefined on AnthropicProvider.

- [ ] **Step 3: Implement encode + applyStructured**

In `AnthropicProvider.encodeRequest`, after the `if (request.tools) body.tools = ...` block and before `return`:

```groovy
        if (request.responseSchema) {
            List<Map> toolsList = (body.tools ?: []) as List<Map>
            toolsList.add([name: "structured_output",
                description: "Return your final answer as structured data matching this schema. Call this tool exactly once, only when you have the final answer.",
                input_schema: request.responseSchema])
            body.tools = toolsList
            // No business tools => force the structured tool for a deterministic one-shot answer.
            // With business tools, leave tool_choice auto so the model can use them first.
            if (!request.tools) body.tool_choice = [type: "tool", name: "structured_output"]
        }
```

Add the override:

```groovy
    @Override
    protected void applyStructured(Map resp, Map request) {
        List<Map> tcs = (resp.toolCalls ?: []) as List<Map>
        Map structured = tcs.find { it.name == "structured_output" }
        if (structured != null) {
            resp.structuredResult = structured.arguments
            resp.toolCalls = tcs.findAll { it.name != "structured_output" }
            resp.finishReason = "structured_output"
        }
    }
```

> `applyStructured` runs after the base-class tool-name reverse-map. `structured_output` is synthetic (never in `backToReal`), so its name is untouched and the `find` matches.

- [ ] **Step 4: Add a live end-to-end structured test (skip on no-credits, per existing pattern)**

```groovy
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
```

- [ ] **Step 5: Run — unit pass; live passes now the account is funded**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/provider/AnthropicProvider.groovy \
        runtime/component/moqui-ai/src/test/groovy/AnthropicProviderTests.groovy
git commit -m "feat(ai): Anthropic structured output via forced structured_output tool"
```

### Task 6: Full-suite verification + decision-record doc note

**Files:**
- Modify: `docs/specs/2026-06-03-enterprise-decisions-gap-report.md`

- [ ] **Step 1: Run the entire suite once more, clean**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS — all prior tests plus the new encode/decode/applyStructured/runner/service/live tests for both providers. Confirm the Anthropic live tool-loop and both structured live tests are green (not skipped), proving the funded account.

- [ ] **Step 2: Update the decision record to "implemented"**

In `docs/specs/2026-06-03-enterprise-decisions-gap-report.md`, under Decision 5's row/section, change the v1 action note to record that structured output is implemented (agent-defined `responseSchema` → normalized `structuredResult`, OpenAI `response_format` / Anthropic forced-tool), and note the two correlation fields shipped. Add one line documenting that **OpenAI reasoning works today via `modelName`** (o-series) with no framework change — closing Decision 6 for v1.

- [ ] **Step 3: Commit**

```bash
git add runtime/component/moqui-ai/docs/specs/2026-06-03-enterprise-decisions-gap-report.md
git commit -m "docs(ai): record structured output + correlation fields shipped in v1"
```

---

## Self-Review

**Spec coverage** (vs. the decision record's v1 scope):
- Decision 5 structured output → Tasks 3 (contract), 4 (OpenAI), 5 (Anthropic). ✅
- Anthropic tool-name bug (found during validation) → Task 1. ✅
- `servedByModelId` + `providerRunId` (discussion add) → Task 2. ✅
- Decision 6 OpenAI reasoning "free via modelName" → documented in Task 6 (no code, correct). ✅
- Out of scope by decision (Responses API, built-in tools, streaming, fallback, context-mgmt, masking, tenantId) → not touched. ✅

**Placeholder scan:** every code step has complete code; every run step has the exact command + expected outcome. No TBD/TODO. ✅

**Type/name consistency:**
- New request key `responseSchema` (Map) — set in `AgentRunner` (Task 3.4), read in OpenAI encode (4.3) + Anthropic encode (5.3), and gates `applyStructured` in base `chat()` (1.3). ✅
- New response keys `structuredResult` + `providerRunId` — produced by `applyStructured` (4.3/5.3) and decode (2.3), consumed by `AgentRunner` (2.5/3.4), surfaced by the service (2.6/3.5). ✅
- `applyStructured(Map resp, Map request)` — declared no-op in `AbstractLlmProvider` (1.3), overridden identically-signed in both providers (4.3, 5.3). ✅
- `sanitizeName` — single static on `AbstractLlmProvider` (1.3); OpenAI's local copy removed (1.4); Anthropic uses it (1.5). ✅
- Synthetic tool name `structured_output` — written in Anthropic encode (5.3), matched in Anthropic `applyStructured` (5.3); never collides with `backToReal` remap (synthetic, documented). ✅
- Entity fields `AiAgent.responseSchema`, `AiAgentRun.servedByModelId`/`providerRunId` — added (3.3, 2.4), written by `AgentRunner.finish` (2.5) and read in tests. ✅

**Ordering / independent deployability:** Task 1 (fix) green baseline → Task 2 (correlation, independent) → Task 3 (structured contract, Mock) → Tasks 4–5 (per-provider translation) → Task 6 (verify+docs). Each task ends green and committed.
