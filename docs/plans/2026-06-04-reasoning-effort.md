# Reasoning Effort (cross-provider) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give agents a single, provider-agnostic reasoning knob — `AiAgent.reasoningEffort` (`none`|`low`|`medium`|`high`) — that maps to each provider's native reasoning control.

**Architecture:** The effort level lives on the agent definition (like `contextStrategy`/`responseSchema`). `AgentRunner.continueAgent` reads it and adds a normalized `reasoning:[effort:…]` to the request Map; each provider adapter translates it in `encodeRequest`. OpenAI → `reasoning_effort`. Anthropic → `thinking{budget_tokens}` with `max_tokens` bumped. Mock ignores it. Default unset → nothing sent → byte-for-byte current behavior.

**Tech Stack:** Groovy 3 / JDK 11, Moqui HotWax fork. Spock tests in `MoquiSuite`. Live OpenAI + Anthropic tests run against funded accounts (`source runtime/component/moqui-ai/dev.env`).

**Locked decisions (user, 2026-06-04):**
- **Shape = effort level** (`none|low|medium|high`), not a token budget — provider-agnostic, maps cleanly to OpenAI's native control and survives failover across providers.
- **Anthropic scope = no-tools only for v1.** OpenAI reasoning works everywhere (incl. tools, multi-turn) — Chat Completions reasons server-side, nothing to preserve. Anthropic extended thinking + tools requires preserving "thinking blocks" across `tool_result` turns, which our shared message shape (conversations / context windowing / approval `pendingState`) does not carry — **deferred**. So the Anthropic adapter sends `thinking` ONLY when the request has no business tools.
- **Anthropic thinking ⊗ forced structured-output:** Anthropic forbids a *forced* `tool_choice` while thinking is on. When reasoning is on, the structured-output synthetic tool is offered with `tool_choice: auto` (best-effort) instead of forced. Safe because structured output is terminal (no `tool_result` round-trip).
- **Not a full ADR** (provider-adapter-contained, like cost/fallback) — record as a decision note in the gap report.

**Run from the Moqui root** `/Users/anilpatel/maarg-sd/moqui`:
```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
(There is no working `--tests` filter — the suite runs via the `MoquiSuite` include rule; run the full suite.)

---

## Task 1: `AiAgent.reasoningEffort` field + request assembly in AgentRunner

**Files:**
- Modify: `entity/AiEntities.xml`
- Modify: `src/main/groovy/org/moqui/ai/AgentRunner.groovy`
- Create: `src/test/groovy/AiReasoningTests.groovy`
- Modify: `src/test/groovy/MoquiSuite.groovy`

- [ ] **Step 1: Add the field to `AiAgent`**

In `entity/AiEntities.xml`, inside `<entity entity-name="AiAgent" …>`, after the `contextWindowChars` field (line ~14), add:
```xml
        <field name="reasoningEffort" type="text-short"><description>none (default/unset) | low | medium | high. Provider-agnostic reasoning depth. OpenAI → reasoning_effort (reasoning-capable models only). Anthropic → extended thinking budget; v1 applies it ONLY to agents WITHOUT tool grants (reasoning+tools on Anthropic is deferred — needs thinking-block preservation across tool turns). No effect on providers/models that don't support reasoning.</description></field>
```
(Adding a field only needs a reboot — no dev-DB drop. We own this entity, so edit it directly; do NOT use extend-entity.)

- [ ] **Step 2: Write the failing test**

Create `src/test/groovy/AiReasoningTests.groovy`:
```groovy
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.ai.provider.MockProvider
import spock.lang.Shared
import spock.lang.Specification

class AiReasoningTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() { ec = org.moqui.Moqui.getExecutionContext() }
    def cleanupSpec() { if (ec != null) ec.destroy() }

    def "reasoningEffort on the agent flows into the provider request as reasoning.effort"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
        MockProvider.reset()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "ReasonAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "x", maxIterations: 3, reasoningEffort: "medium", statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ((UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: "ok", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        when:
        ec.service.sync().name("ai.AgentServices.run#Agent").parameters([agentName: "ReasonAgent", userMessage: "hi"]).call()
        then:
        (MockProvider.LAST_REQUEST?.reasoning as Map)?.effort == "medium"
        cleanup:
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "ReasonAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "no reasoningEffort means no reasoning key in the request (backward-compatible)"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
        MockProvider.reset()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "PlainAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "x", maxIterations: 3, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ((UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: "ok", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        when:
        ec.service.sync().name("ai.AgentServices.run#Agent").parameters([agentName: "PlainAgent", userMessage: "hi"]).call()
        then:
        MockProvider.LAST_REQUEST?.reasoning == null
        cleanup:
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "PlainAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
}
```
Register it in `src/test/groovy/MoquiSuite.groovy` — add `AiReasoningTests.class` to the `@SelectClasses([...])` list.

- [ ] **Step 3: Run to confirm it fails**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — the first test sees `reasoning == null` (nothing assembles it yet); the field may also be unknown until Step 1 is loaded.

- [ ] **Step 4: Assemble `reasoning` in `continueAgent`**

In `src/main/groovy/org/moqui/ai/AgentRunner.groovy`, in `continueAgent`'s config-derivation block (the lines deriving `maxIter`/`ctxOn`/etc., ~85–92), add after `Map primary = candidates[0]`:
```groovy
        String reasoningEffort = agent.reasoningEffort as String
        Map reasoning = (reasoningEffort in ['low', 'medium', 'high']) ? [effort: reasoningEffort] : null
```
Then in the `callWithFailover(...)` request Map (the `[systemContext: sysCtx, messages: sendMessages, tools: toolSchemas, responseSchema: responseSchema]` argument), add the `reasoning` key:
```groovy
                Map call = callWithFailover(candidates, candIdx,
                        [systemContext: sysCtx, messages: sendMessages, tools: toolSchemas,
                         responseSchema: responseSchema, reasoning: reasoning], runId)
```
(`reasoning` is `null` when unset → providers check `request.reasoning?.effort`, so a null is ignored and nothing is sent. Anything other than low/medium/high — including `none`, empty, or a typo — yields `null`, i.e. no reasoning.)

- [ ] **Step 5: Run — Task 1 tests pass + full suite green**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS. The Mock captures `reasoning.effort == "medium"`; the plain agent sends no `reasoning`.

- [ ] **Step 6: Commit**
```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add entity/AiEntities.xml src/main/groovy/org/moqui/ai/AgentRunner.groovy src/test/groovy/AiReasoningTests.groovy src/test/groovy/MoquiSuite.groovy && \
git commit -m "feat(ai): AiAgent.reasoningEffort + normalized reasoning in the request (reasoning)"
```

---

## Task 2: OpenAI adapter — `reasoning_effort`

**Files:**
- Modify: `src/main/groovy/org/moqui/ai/provider/OpenAiProvider.groovy`
- Modify: `src/test/groovy/OpenAiProviderTests.groovy`

- [ ] **Step 1: Write the failing unit test**

In `OpenAiProviderTests.groovy`, add (mirroring the existing `encodeRequest` tests that build a request, call `encodeRequest`, and parse the JSON body):
```groovy
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
```

- [ ] **Step 2: Run to confirm it fails**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — `reasoning_effort` not emitted.

- [ ] **Step 3: Implement**

In `OpenAiProvider.encodeRequest`, immediately before `return JsonOutput.toJson(body)`, add:
```groovy
        // Reasoning depth — reasoning-capable models (o-series / GPT-5-class) only; a non-reasoning
        // model will reject this (operator sets reasoningEffort only on a capable model).
        if (request.reasoning?.effort) body.reasoning_effort = request.reasoning.effort
```

- [ ] **Step 4: Add a live end-to-end test (gated)**

In `OpenAiProviderTests.groovy`, add a live test mirroring the existing `@Requires({ System.getenv("ai_openai_key") })` agent tests. Use a current OpenAI **reasoning** model (the existing tests use `gpt-4o-mini`, which is NOT a reasoning model — pick a reasoning model such as `o4-mini`; **if the live API rejects that id as unavailable, use the current lowest-cost reasoning model and note which in the commit** — model availability is external, the behavior under test is fixed):
```groovy
    @Requires({ System.getenv("ai_openai_key") })
    def "live: an OpenAI reasoning agent with reasoningEffort completes"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "OpenAiReason", providerName: "openai",
            modelName: "o4-mini", systemPrompt: "Answer briefly.", reasoningEffort: "low",
            maxIterations: 3, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
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
```

- [ ] **Step 5: Run — unit + live pass, full suite green**; commit
```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add src/main/groovy/org/moqui/ai/provider/OpenAiProvider.groovy src/test/groovy/OpenAiProviderTests.groovy && \
git commit -m "feat(ai): OpenAI reasoning_effort from normalized reasoning.effort (reasoning)"
```

---

## Task 3: Anthropic adapter — extended thinking (no-tools v1) + structured-output coexistence

**Files:**
- Modify: `src/main/groovy/org/moqui/ai/provider/AnthropicProvider.groovy`
- Modify: `src/test/groovy/AnthropicProviderTests.groovy`

- [ ] **Step 1: Write the failing unit tests**

In `AnthropicProviderTests.groovy`, add (mirror the existing `encodeRequest` parse-the-body tests):
```groovy
    def "encodeRequest enables thinking + bumps max_tokens when reasoning set and NO business tools"() {
        given: def p = new AnthropicProvider("k", "u", "v", 60)
        when:
        def body = new groovy.json.JsonSlurper().parseText(p.encodeRequest(
            [model: "claude-sonnet-4-6", messages: [[role: "user", content: "hi"]], reasoning: [effort: "high"]]))
        then:
        body.thinking.type == "enabled"
        body.thinking.budget_tokens == 24576
        (body.max_tokens as int) >= 24576 + 4096
    }

    def "encodeRequest does NOT enable thinking when business tools are present (v1 limit)"() {
        given: def p = new AnthropicProvider("k", "u", "v", 60)
        when:
        def body = new groovy.json.JsonSlurper().parseText(p.encodeRequest(
            [model: "claude-sonnet-4-6", messages: [[role: "user", content: "hi"]], reasoning: [effort: "high"],
             tools: [[name: "x.y#z", description: "d", parameters: [type: "object", properties: [:]]]]]))
        then:
        body.thinking == null
        body.tools.size() == 1   // the business tool is still sent
    }

    def "reasoning + responseSchema (no business tools): thinking on AND structured tool NOT forced"() {
        given: def p = new AnthropicProvider("k", "u", "v", 60)
        when:
        def body = new groovy.json.JsonSlurper().parseText(p.encodeRequest(
            [model: "claude-sonnet-4-6", messages: [[role: "user", content: "hi"]], reasoning: [effort: "low"],
             responseSchema: [type: "object", properties: [answer: [type: "string"]]]]))
        then:
        body.thinking.type == "enabled"
        body.tool_choice == null                                   // NOT forced (auto) — thinking forbids forcing
        body.tools.find { it.name == "structured_output" } != null // structured tool still offered
    }

    def "no reasoning: structured output still forces the synthetic tool (unchanged)"() {
        given: def p = new AnthropicProvider("k", "u", "v", 60)
        when:
        def body = new groovy.json.JsonSlurper().parseText(p.encodeRequest(
            [model: "claude-sonnet-4-6", messages: [[role: "user", content: "hi"]],
             responseSchema: [type: "object", properties: [answer: [type: "string"]]]]))
        then:
        body.thinking == null
        body.tool_choice.type == "tool"
        body.tool_choice.name == "structured_output"
    }
```

- [ ] **Step 2: Run to confirm it fails**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — no `thinking`; `tool_choice` still forced when reasoning set.

- [ ] **Step 3: Implement**

In `AnthropicProvider.groovy`, add a private helper (near `STRUCTURED_TOOL_NAME`):
```groovy
    /** Effort level → Anthropic thinking budget_tokens (min 1024). Tunable. */
    private static int effortToBudget(String effort) {
        switch (effort) {
            case "low": return 1024
            case "medium": return 8192
            case "high": return 24576
            default: return 0
        }
    }
```
In `encodeRequest`, change the structured-output force line so it does NOT force when reasoning is on:
```groovy
            // No business tools => force the structured tool for a deterministic one-shot answer —
            // UNLESS reasoning is on (Anthropic forbids a forced tool_choice while thinking; offer it auto).
            if (!request.tools && !request.reasoning?.effort) body.tool_choice = [type: "tool", name: STRUCTURED_TOOL_NAME]
```
Then, immediately before `return JsonOutput.toJson(body)`, add the thinking block:
```groovy
        // Extended thinking. v1: ONLY when there are no business tools — Anthropic requires preserving
        // thinking blocks across tool_result turns, which our message shape does not carry yet (deferred).
        // (responseSchema's synthetic tool is terminal — no tool_result round-trip — so thinking is safe with it.)
        if (request.reasoning?.effort && !request.tools) {
            int budget = effortToBudget(request.reasoning.effort as String)
            if (budget > 0) {
                body.thinking = [type: "enabled", budget_tokens: budget]
                body.max_tokens = Math.max((body.max_tokens as int), budget + 4096)
            }
        }
```

- [ ] **Step 4: Add a live end-to-end test (gated, no tools)**

In `AnthropicProviderTests.groovy`, add (mirror the existing `@Requires` Anthropic agent tests, including their no-credits-graceful handling — read an existing live agent test in this file and copy its credit-skip guard if present). Reuse `claude-sonnet-4-6` (supports extended thinking). NO tools granted:
```groovy
    @Requires({ System.getenv("ai_anthropic_key") })
    def "live: an Anthropic agent (no tools) with reasoningEffort completes"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "AnthropicReason", providerName: "anthropic",
            modelName: "claude-sonnet-4-6", systemPrompt: "Answer briefly.", reasoningEffort: "low",
            maxIterations: 3, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        when:
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "AnthropicReason", userMessage: "What is 17 + 25? Reply with just the number."]).call()
        then:
        // tolerate a no-credits environment the same way the other live Anthropic tests do
        out.statusId == "AI_RUN_COMPLETED" || (ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()?.errorText as String)?.toLowerCase()?.contains("credit")
        cleanup:
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "AnthropicReason").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```
(If the existing live Anthropic tests use a `TestAbortedException`/skip on no-credits rather than the `||` tolerance above, follow THAT pattern instead — match the file.)

- [ ] **Step 5: Run — unit + live pass, full suite green**; commit
```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add src/main/groovy/org/moqui/ai/provider/AnthropicProvider.groovy src/test/groovy/AnthropicProviderTests.groovy && \
git commit -m "feat(ai): Anthropic extended thinking (no-tools v1) + structured-output coexistence (reasoning)"
```

---

## Task 4: Decision-record note + full-suite verify

**Files:**
- Modify: `docs/specs/2026-06-03-enterprise-decisions-gap-report.md`

- [ ] **Step 1: Record shipped in the gap report**

Add a concise entry (match the file's existing style — read it first; this is roadmap-adjacent like the cost/context notes). Cover:
- **Shipped:** `AiAgent.reasoningEffort` (`none|low|medium|high`) → normalized `reasoning.effort` in the request → OpenAI `reasoning_effort`; Anthropic `thinking{budget_tokens}` (low/med/high = 1024/8192/24576) with `max_tokens` bumped to `budget+4096`. Default unset = unchanged. This closes Decision 6 (reasoning) for OpenAI + Anthropic.
- **v1 limitation:** on Anthropic, thinking is applied ONLY to requests without business tools (reasoning+tools deferred — needs thinking-block preservation across `tool_result` turns, which the shared message shape doesn't carry; it ripples into conversations/context/approval `pendingState`). OpenAI reasoning has no such limit (works with tools, multi-turn). When reasoning is on, Anthropic structured-output is best-effort (tool offered with `auto`, not forced).
- **Operator note:** set `reasoningEffort` only on agents whose model supports reasoning (OpenAI o-series/GPT-5-class; Anthropic thinking-capable Claude). A non-reasoning OpenAI model will reject `reasoning_effort`.
- **Deferred:** Anthropic reasoning+tools (thinking-block preservation); Gemini `thinkingConfig` (lands with the Gemini provider).

- [ ] **Step 2: Full suite green**
```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Report the total test count + confirm live tests green.

- [ ] **Step 3: Commit**
```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add docs/specs/2026-06-03-enterprise-decisions-gap-report.md && \
git commit -m "docs(ai): record reasoning-effort shipped + v1 Anthropic-no-tools limitation (reasoning)"
```

---

## Self-Review

**Spec coverage (vs locked decisions):**
- Effort-level field on AiAgent → Task 1. ✅
- Normalized `reasoning.effort` in the request, provider-agnostic → Task 1 (assembly) + Mock test. ✅
- OpenAI `reasoning_effort`, works with tools → Task 2 (unit + live). ✅
- Anthropic `thinking` + `max_tokens` bump, **no-tools-only** v1 → Task 3 (unit asserts thinking absent when tools present). ✅
- Anthropic thinking ⊗ forced structured-output → Task 3 (auto when reasoning on; forced unchanged otherwise). ✅
- Backward-compatible (unset → nothing sent) → Task 1 second test + Task 2/3 "no reasoning" asserts. ✅
- Decision note (not ADR) → Task 4. ✅

**Placeholder scan:** all code is concrete. The only external variable is the OpenAI reasoning **model id** in Task 2's live test (`o4-mini`) — explicitly flagged as adjustable against the live API because model availability is time-varying; the behavior under test (reasoning_effort accepted end-to-end) is fixed. Anthropic reuses the in-repo `claude-sonnet-4-6`.

**Type/name consistency:**
- Request key `reasoning` (Map with `effort`) — written in `AgentRunner.continueAgent`, read as `request.reasoning?.effort` in both adapters. ✅
- Effort values `low|medium|high` consistent across the field description, AgentRunner guard, and both adapters. ✅
- `effortToBudget` low/medium/high = 1024/8192/24576 — single definition in AnthropicProvider; asserted in Task 3 unit tests + recorded in Task 4. ✅
- `MockProvider.LAST_REQUEST` (volatile static Map) — used by Task 1 tests, confirmed against MockProvider source. ✅

**Notes for the implementer:**
- `continueAgent` is shared by `run()` and `resume()`, so reasoning is re-derived from the agent on resume too (correct — static config, never serialized).
- `summarizeOverflow` builds its own request (no `reasoning` key) — summary calls intentionally don't use thinking. Leave as-is.
- Reasoning tokens are reported inside the providers' existing usage counts (OpenAI `completion_tokens`, Anthropic `output_tokens`), so `tokensOut`/cost accounting already captures them — no change needed; the OpenAI live test asserts `tokensOut > 0`.
- `decodeResponse` ignores Anthropic `thinking` content blocks (only `text`/`tool_use` are handled) — the final answer is still the `text` block(s); confirmed safe.
