# Context-Window Management — Phase 1 (Fidelity Floor) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bound the message list sent on every provider call (windowing) while guaranteeing confirmed business values survive via agent-written pinned facts — the fidelity floor of ADR 0001.

**Architecture:** A focused `ContextAssembler` builds the per-call view: inject pinned facts as a `## Known facts` block appended to `systemContext`, and window the **replayed prior-turn history** by message-count + a char-estimate guard (tool-pair-safe). The **current turn is never trimmed** (so a tool_call/tool_result pair is never split). A built-in `remember` tool lets the agent persist a keyed fact to `AiConversationFact` (conversation-scoped; the run's `conversationId` is injected server-side, never exposed to the model). All gated by `AiAgent.contextStrategy` (default `off` = today's behavior, byte-for-byte).

**Tech Stack:** Groovy 3 / JDK 11, Moqui (HotWax fork), Spock + JUnit Platform Suite, H2 dev DB.

---

## Implements
GitHub issue **#21** · ADR **0001** (`docs/decisions/0001-context-window-management.md`), Phase 1 only.

## Locked decisions (from ADR eng-review)
- **Scope = Phase 1 only:** window + `remember` tool + fact store + inject + log. (Compaction = Phase 2, tool-result clearing = Phase 3, retrieval = Phase 6 — NOT here.)
- **Trim budget:** message-count window + char-estimate guard (`chars/4 ≈ tokens`). No tokenizer.
- **Fact injection:** append a `## Known facts` block to `systemContext`.
- **Tool-pair safety:** never orphan a `tool_call`/`tool_result`; never trim the current run's in-progress messages — Phase 1 windows only the replayed prior-turn prefix, so the current turn is whole by construction.
- **`remember`:** requires a `conversationId` (stateless run → not offered / no-op); facts are **keyed, store-or-update** (new value supersedes).
- **Degrade, don't block:** a fact-load failure proceeds without injection (logged); nothing errors the run.

## Conventions (confirmed; same as prior work)
- Tests: Spock `*Tests.groovy` in `src/test/groovy/`, registered in `MoquiSuite.groovy` `@SelectClasses`. New class `AiContextTests` MUST be added to the suite.
- Run suite from moqui root `/Users/anilpatel/maarg-sd/moqui`:
  ```bash
  source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
  ```
  Live tests are key-gated; both accounts funded — they run for real, don't skip.
- New entity / nullable fields just need the boot the test triggers (Moqui auto-creates tables/columns).
- Commit **inside the component repo** (`runtime/component/moqui-ai`, branch `feature/ai-agent-framework`) with component-relative paths.
- Maps only for LLM request/response data.

## Canonical shapes (new)
- **Fact Map:** `[factKey, factValue]`.
- **`ContextAssembler.windowHistory(...)` returns** `[messages: List<Map>, dropped: int]`.
- **Built-in tool name:** the constant string `"remember"` (matches `^[a-zA-Z0-9_-]+$`, no sanitization needed).

---

## File Structure

| File | Change |
|---|---|
| `entity/AiEntities.xml` | `AiAgent` gains `contextStrategy`, `contextWindowMessages`, `contextWindowChars` |
| `entity/AiConversationEntities.xml` | Add `AiConversationFact` (conversation-scoped keyed facts) |
| `src/main/groovy/org/moqui/ai/ContextAssembler.groovy` | New — `withFacts` (inject) + `windowHistory` (window, tool-pair-safe) |
| `service/ai/FactServices.xml` | New — `remember#Fact` (find-or-create keyed fact) |
| `src/main/groovy/org/moqui/ai/AgentRunner.groovy` | Parse context config; offer + dispatch `remember`; assemble windowed view + inject facts; log drops |
| `src/main/groovy/org/moqui/ai/provider/MockProvider.groovy` | Capture `LAST_REQUEST` (test hook for asserting what was sent) |
| `src/test/groovy/AiContextTests.groovy` | New — assembler unit + fact-recording + e2e fidelity tests |
| `src/test/groovy/MoquiSuite.groovy` | Register `AiContextTests` |

---

## Task 1: `AiConversationFact` entity + `AiAgent` context config

**Files:**
- Modify: `entity/AiConversationEntities.xml`
- Modify: `entity/AiEntities.xml`
- Test: `src/test/groovy/AiEntitiesTests.groovy`

- [ ] **Step 1: Write a failing entity round-trip test**

In `src/test/groovy/AiEntitiesTests.groovy`, add (match the file's `disableAuthz`/create/read pattern):

```groovy
    def "AiConversationFact stores a conversation-scoped keyed fact"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeValue("moqui.ai.AiConversationFact").setAll([conversationId: "CONVMEM1", factKey: "order_total",
            factValue: "\$4,812.50", agentRunId: "RUN1", createdDate: ec.user.nowTimestamp]).create()
        when:
        def f = ec.entity.find("moqui.ai.AiConversationFact")
            .condition("conversationId", "CONVMEM1").condition("factKey", "order_total").one()
        then:
        f.factValue == "\$4,812.50"
        f.agentRunId == "RUN1"
        cleanup:
        ec.entity.find("moqui.ai.AiConversationFact").condition("conversationId", "CONVMEM1").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```

- [ ] **Step 2: Run to confirm it fails**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
(from moqui root). Expected: FAIL — entity `moqui.ai.AiConversationFact` not defined.

- [ ] **Step 3: Add the `AiConversationFact` entity to `AiConversationEntities.xml`**

In `entity/AiConversationEntities.xml`, inside the existing `<entities>` root (alongside `AiConversation` / `AiConversationMessage` — it's conversation-scoped, so it belongs here), add:
```xml
    <!-- Pinned facts (ADR 0001 fidelity guarantee): durable confirmed business values an agent
         records via the `remember` tool. Conversation-scoped, keyed, store-or-update (a new value
         supersedes the old). Injected into every call's systemContext; never compressed/dropped. -->
    <entity entity-name="AiConversationFact" package="moqui.ai">
        <field name="conversationId" type="id" is-pk="true"/>
        <field name="factKey" type="text-medium" is-pk="true"/>
        <field name="factValue" type="text-very-long"/>
        <field name="agentRunId" type="id"><description>The run that last recorded this fact</description></field>
        <field name="createdDate" type="date-time"/>
        <!-- Moqui auto-adds lastUpdatedStamp = when the fact was last set (supersession time) -->
    </entity>
```

- [ ] **Step 4: Add the `AiAgent` context-config fields**

In `entity/AiEntities.xml`, inside `<entity entity-name="AiAgent" ...>`, add after the `responseSchema` field:

```xml
        <field name="contextStrategy" type="text-short"><description>off (default) | window. window = bound the replayed history + offer the remember tool + inject pinned facts (ADR 0001 Phase 1).</description></field>
        <field name="contextWindowMessages" type="number-integer"><description>When contextStrategy=window: max replayed prior-turn messages to keep (current turn always kept). Default 20 when unset.</description></field>
        <field name="contextWindowChars" type="number-integer"><description>When contextStrategy=window: char-estimate guard (~chars/4 tokens) for the assembled view. Default 48000 when unset.</description></field>
```

- [ ] **Step 5: Run — passes**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add entity/AiConversationEntities.xml entity/AiEntities.xml src/test/groovy/AiEntitiesTests.groovy && \
git commit -m "feat(ai): AiConversationFact fact entity + AiAgent context-window config (#21)"
```

---

## Task 2: `ContextAssembler` (pure unit — inject + window, tool-pair-safe)

**Files:**
- Create: `src/main/groovy/org/moqui/ai/ContextAssembler.groovy`
- Test: `src/test/groovy/AiContextTests.groovy` (new)
- Modify: `src/test/groovy/MoquiSuite.groovy`

- [ ] **Step 1: Create the test class with failing unit tests + register it**

Create `src/test/groovy/AiContextTests.groovy`:
```groovy
import spock.lang.*
import org.moqui.ai.ContextAssembler

class AiContextTests extends Specification {

    def "withFacts appends a Known facts block to the system prompt"() {
        when:
        String s = ContextAssembler.withFacts("Be helpful.",
            [[factKey: "order_total", factValue: "\$4,812.50"], [factKey: "ship_to", factValue: "123 Main St"]])
        then:
        s.contains("Be helpful.")
        s.contains("## Known facts")
        s.contains("order_total: \$4,812.50")
        s.contains("ship_to: 123 Main St")
    }

    def "withFacts is a no-op when there are no facts"() {
        expect:
        ContextAssembler.withFacts("Be helpful.", []) == "Be helpful."
        ContextAssembler.withFacts("Be helpful.", null) == "Be helpful."
    }

    def "windowHistory keeps the last N replayed messages plus the whole current turn"() {
        given:
        List replayed = (1..6).collect { [role: "user", content: "old ${it}"] }
        List current = [[role: "user", content: "now"]]
        when:
        Map r = ContextAssembler.windowHistory(replayed, current, 2, 1000000)
        then:
        r.dropped == 4
        r.messages.size() == 3                       // 2 kept replayed + 1 current
        r.messages[0].content == "old 5"
        r.messages[1].content == "old 6"
        r.messages[2].content == "now"               // current turn always last, never dropped
    }

    def "windowHistory never orphans a tool result at the kept-window boundary"() {
        given:
        // replayed ends with an assistant tool-call + its tool result; keeping "last 1" would
        // start the window on the orphan tool result — it must be dropped instead.
        List replayed = [
            [role: "user", content: "q"],
            [role: "assistant", toolCalls: [[id: "c1", name: "x", arguments: [:]]]],
            [role: "tool", toolCallId: "c1", content: "{}"]]
        List current = [[role: "user", content: "now"]]
        when:
        Map r = ContextAssembler.windowHistory(replayed, current, 1, 1000000)
        then:
        // last-1 would be the tool result (orphan) -> dropped; kept replayed is empty
        r.messages.every { it.role != "tool" || it.toolCallId == null || r.messages.any { a -> a.toolCalls?.any { tc -> tc.id == it.toolCallId } } }
        !r.messages.any { it.role == "tool" }        // the orphan tool result was dropped
        r.messages.last().content == "now"
    }

    def "windowHistory char guard drops more from the front when over the cap"() {
        given:
        List replayed = (1..5).collect { [role: "user", content: ("x" * 100)] }   // ~100 chars each
        List current = [[role: "user", content: "now"]]
        when:
        Map r = ContextAssembler.windowHistory(replayed, current, 5, 120)          // cap ~120 chars
        then:
        // keeps as few replayed as needed to fit; current always kept
        (r.messages.sum { (it.content ?: "").length() } as int) <= 120 + 3
        r.messages.last().content == "now"
        r.dropped >= 4
    }
}
```

Register it: in `src/test/groovy/MoquiSuite.groovy`, append `AiContextTests.class` to the `@SelectClasses` list (match the file's current last entry + bracket).

- [ ] **Step 2: Run to confirm it fails**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — `org.moqui.ai.ContextAssembler` does not exist.

- [ ] **Step 3: Implement `ContextAssembler`**

`src/main/groovy/org/moqui/ai/ContextAssembler.groovy`:
```groovy
package org.moqui.ai

/** Builds the per-call context view (ADR 0001 Phase 1). Pure: operates on Lists/Strings, no ec.
 *  - withFacts: inject pinned facts as a "## Known facts" block appended to the system prompt.
 *  - windowHistory: bound the REPLAYED prior-turn history (message-count + char-estimate guard),
 *    tool-pair-safe; the current turn is passed through whole (never trimmed). */
class ContextAssembler {

    /** Append pinned facts to the system prompt. No-op when there are no facts. */
    static String withFacts(String systemPrompt, List<Map> facts) {
        if (!facts) return systemPrompt
        StringBuilder sb = new StringBuilder(systemPrompt ?: "")
        sb.append("\n\n## Known facts (always honor these confirmed values)\n")
        for (Map f in facts) sb.append("- ").append(f.factKey).append(": ").append(f.factValue).append("\n")
        return sb.toString()
    }

    /** Keep the last `maxMessages` of `replayed` (then char-guard trims more from the front),
     *  tool-pair-safe, and append the whole `current` turn. Returns [messages, dropped]. */
    static Map windowHistory(List<Map> replayed, List<Map> current, int maxMessages, int maxChars) {
        List<Map> src = replayed ?: []
        List<Map> cur = current ?: []
        int total = src.size()
        // 1) message-count window: keep the last maxMessages of the replayed prefix
        int start = Math.max(0, total - Math.max(0, maxMessages))
        List<Map> kept = new ArrayList<>(src.subList(start, total))
        // 2) tool-pair safety: never start the kept window on an orphaned tool result
        while (!kept.isEmpty() && kept[0].role == "tool") kept.remove(0)
        // 3) char-estimate guard: drop more from the front (tool-pair-safe) until under the cap.
        //    The current turn is always counted but never dropped (Phase 1 does not compact it).
        int curChars = charLen(cur)
        while (!kept.isEmpty() && (charLen(kept) + curChars) > maxChars) {
            kept.remove(0)
            while (!kept.isEmpty() && kept[0].role == "tool") kept.remove(0)
        }
        List<Map> out = new ArrayList<>(kept); out.addAll(cur)
        return [messages: out, dropped: total - kept.size()]
    }

    private static int charLen(List<Map> msgs) {
        int n = 0
        for (Map m in msgs) {
            n += ((m.content ?: "") as String).length()
            if (m.toolCalls) n += groovy.json.JsonOutput.toJson(m.toolCalls).length()
        }
        return n
    }
}
```

- [ ] **Step 4: Run — assembler unit tests pass**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS (the 5 assembler tests; rest of suite green).

- [ ] **Step 5: Commit**

```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add src/main/groovy/org/moqui/ai/ContextAssembler.groovy \
        src/test/groovy/AiContextTests.groovy src/test/groovy/MoquiSuite.groovy && \
git commit -m "feat(ai): ContextAssembler — fact injection + tool-pair-safe windowing (#21)"
```

---

## Task 3: Wire windowing + fact injection into `AgentRunner` (gated by contextStrategy)

**Files:**
- Modify: `src/main/groovy/org/moqui/ai/AgentRunner.groovy`
- Modify: `src/main/groovy/org/moqui/ai/provider/MockProvider.groovy`
- Test: `src/test/groovy/AiContextTests.groovy`

- [ ] **Step 1: Add a `LAST_REQUEST` capture to MockProvider (test hook)**

In `src/main/groovy/org/moqui/ai/provider/MockProvider.groovy`, capture the request so tests can assert what was sent. Add a static field and set it at the top of `chat`:
```groovy
    static volatile Map LAST_REQUEST = null
```
and as the FIRST line inside `chat(Map request)`:
```groovy
        LAST_REQUEST = request
```
(keep the existing `__error` throw + poll logic after it).

- [ ] **Step 2: Write a failing windowing integration test**

In `AiContextTests.groovy`, add (uses `@Shared ec` + MockProvider; mirror the EC-lifecycle of the other integration test classes — if they call `ec.destroy()` in cleanupSpec, match that; first read a sibling like `AgentRunnerTests.groovy`/`AiCostTests.groovy` to copy the exact `setupSpec`/login pattern). The test creates a conversation with prior turns, runs a windowed agent, and asserts the provider received a trimmed list with the system facts block absent (no facts yet) but history bounded:

```groovy
    def "windowed agent sends only the last N replayed messages to the provider"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.LAST_REQUEST = null
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "WinAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "be terse", maxIterations: 3, statusId: "AI_AGENT_ACTIVE",
            contextStrategy: "window", contextWindowMessages: 2, contextWindowChars: 1000000]).createOrUpdate()
        String convId = ec.entity.sequencedIdPrimary("moqui.ai.AiConversation", null, null)
        ec.entity.makeValue("moqui.ai.AiConversation").setAll([conversationId: convId, agentName: "WinAgent",
            userId: "AiTestUser", fromDate: ec.user.nowTimestamp, statusId: "AI_CONV_ACTIVE"]).createOrUpdate()
        // seed 5 prior messages
        (1..5).each { i ->
            EntityValue m = ec.entity.makeValue("moqui.ai.AiConversationMessage")
            m.set("conversationId", convId); m.setSequencedIdSecondary()
            m.setAll([role: "user", content: "old ${i}", fromDate: ec.user.nowTimestamp]); m.create()
        }
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "ok", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        when:
        new org.moqui.ai.AgentRunner(ec, ai).run("WinAgent", "newest", convId)
        List sent = org.moqui.ai.provider.MockProvider.LAST_REQUEST.messages as List
        then:
        // 2 windowed replayed + this turn's user message = 3
        sent.size() == 3
        sent.last().content == "newest"
        cleanup:
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "WinAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "context management OFF replays the full history unchanged (no regression)"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.LAST_REQUEST = null
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "NoCtxAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "x", maxIterations: 3, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        String convId = ec.entity.sequencedIdPrimary("moqui.ai.AiConversation", null, null)
        ec.entity.makeValue("moqui.ai.AiConversation").setAll([conversationId: convId, agentName: "NoCtxAgent",
            userId: "AiTestUser", fromDate: ec.user.nowTimestamp, statusId: "AI_CONV_ACTIVE"]).createOrUpdate()
        (1..5).each { i ->
            EntityValue m = ec.entity.makeValue("moqui.ai.AiConversationMessage")
            m.set("conversationId", convId); m.setSequencedIdSecondary()
            m.setAll([role: "user", content: "old ${i}", fromDate: ec.user.nowTimestamp]); m.create()
        }
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "ok", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        when:
        new org.moqui.ai.AgentRunner(ec, ai).run("NoCtxAgent", "newest", convId)
        List sent = org.moqui.ai.provider.MockProvider.LAST_REQUEST.messages as List
        then:
        sent.size() == 6                              // 5 replayed + this turn, unchanged
        org.moqui.ai.provider.MockProvider.LAST_REQUEST.systemContext == "x"   // no facts block
        cleanup:
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "NoCtxAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```

> Add `import org.moqui.entity.EntityValue` to `AiContextTests` if not present. The class also needs the `ai` (AiToolFactory) handle + login like the other integration tests — copy the exact `setupSpec`/`@Shared`/`internalLoginUser` idiom from `AgentRunnerTests.groovy`.

- [ ] **Step 3: Run to confirm the windowing test fails**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — windowed agent still sends all 6 (no windowing wired yet). The no-regression test should already PASS (default off).

- [ ] **Step 4: Wire context config + assembler into `AgentRunner`**

In `AgentRunner.groovy`:

(a) After the `maxToolCalls` line (currently line 36), add context-config parsing:
```groovy
        boolean ctxOn = (agent.contextStrategy == "window")
        int ctxMsgs = (agent.contextWindowMessages ?: 20) as int
        int ctxChars = (agent.contextWindowChars ?: 48000) as int
```

(b) Capture the replay boundary — change the history-replay block (currently lines 55-57) to record how many messages are replayed prior-turn history:
```groovy
        List<Map> messages = conversationId ? loadConversationMessages(conversationId) : []
        int replayCount = messages.size()
        messages.add([role: "user", content: userMessage])
        if (conversationId) persistConversationMessage(conversationId, runId, [role: "user", content: userMessage])
```

(c) Inside the loop, BEFORE the `callWithFailover` call (currently lines 65-66), build the per-call view. Replace the `Map call = callWithFailover(...)` line's `[systemContext: agent.systemPrompt, messages: messages, ...]` argument with an assembled view:
```groovy
                String sysCtx = agent.systemPrompt as String
                List<Map> sendMessages = messages
                if (ctxOn) {
                    int rc = Math.min(replayCount, messages.size())
                    Map asm = ContextAssembler.windowHistory(messages.subList(0, rc),
                        messages.subList(rc, messages.size()), ctxMsgs, ctxChars)
                    sendMessages = asm.messages as List<Map>
                    sysCtx = ContextAssembler.withFacts(sysCtx, loadFacts(conversationId))
                    if ((asm.dropped as int) > 0) {
                        stepSeq++
                        persist("create#moqui.ai.AiAgentRunStep", [agentRunId: runId, stepSeqId: stepSeq as String,
                            stepType: "context_trim", finishReason: "dropped:${asm.dropped}" as String])
                    }
                }
                Map call = callWithFailover(candidates, candIdx,
                        [systemContext: sysCtx, messages: sendMessages, tools: toolSchemas, responseSchema: responseSchema], runId)
```
(This replaces the existing two-line `Map call = callWithFailover(candidates, candIdx, [systemContext: agent.systemPrompt, messages: messages, tools: toolSchemas, responseSchema: responseSchema], runId)`.)

(d) Add a `loadFacts` helper next to `loadModelCandidates`:
```groovy
    /** Pinned facts for a conversation (ADR 0001 fidelity store), as [factKey, factValue] Maps.
     *  Guarded: a load failure returns [] so the run proceeds without injection (logged). */
    private List<Map> loadFacts(String conversationId) {
        if (!conversationId) return []
        try {
            List<Map> facts = []
            for (EntityValue f in ec.entity.find("moqui.ai.AiConversationFact")
                    .condition("conversationId", conversationId).orderBy("factKey").list())
                facts.add([factKey: f.factKey, factValue: f.factValue])
            return facts
        } catch (Throwable t) { logger.warn("Fact load failed (continuing without facts): ${t.message}"); return [] }
    }
```

(e) Add the import at the top (after the existing imports): the class is in the same package, so no import needed for `ContextAssembler`. Confirm `stepType` "context_trim" is acceptable (the field is free `text-short`; update the `AiAgentRunStep.stepType` description in `entity/AiEntities.xml` to `llm_call | tool_call | llm_call_failed | context_trim`).

- [ ] **Step 5: Run — windowing + no-regression pass**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS. Windowed agent sends 3; off-agent sends 6 unchanged; live tests green (their agents have no contextStrategy → off → unchanged).

- [ ] **Step 6: Commit**

```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add src/main/groovy/org/moqui/ai/AgentRunner.groovy \
        src/main/groovy/org/moqui/ai/provider/MockProvider.groovy \
        entity/AiEntities.xml \
        src/test/groovy/AiContextTests.groovy && \
git commit -m "feat(ai): window replayed history + inject facts in AgentRunner (gated by contextStrategy) (#21)"
```

---

## Task 4: `remember` tool — service + AgentRunner offer/dispatch (server-injected conversationId)

**Files:**
- Create: `service/ai/FactServices.xml`
- Modify: `src/main/groovy/org/moqui/ai/AgentRunner.groovy`
- Test: `src/test/groovy/AiContextTests.groovy`

- [ ] **Step 1: Create the `remember#Fact` service**

`service/ai/FactServices.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<services xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/service-definition-3.xsd">

    <!-- Record/supersede a conversation-scoped pinned fact (ADR 0001). Keyed by (conversationId,
         factKey): existing key updates in place (preserving createdDate); new key creates. -->
    <service verb="remember" noun="Fact" authenticate="true">
        <in-parameters>
            <parameter name="conversationId" required="true"/>
            <parameter name="factKey" required="true"/>
            <parameter name="factValue" required="true"/>
            <parameter name="agentRunId"/>
        </in-parameters>
        <out-parameters><parameter name="factKey"/></out-parameters>
        <actions>
            <entity-find-one entity-name="moqui.ai.AiConversationFact" value-field="existing"/>
            <if condition="existing != null">
                <then>
                    <set field="existing.factValue" from="factValue"/>
                    <set field="existing.agentRunId" from="agentRunId"/>
                    <entity-update value-field="existing"/>
                </then>
                <else>
                    <service-call name="create#moqui.ai.AiConversationFact" in-map="[conversationId: conversationId,
                        factKey: factKey, factValue: factValue, agentRunId: agentRunId, createdDate: ec.user.nowTimestamp]"/>
                </else>
            </if>
        </actions>
    </service>
</services>
```
> `entity-find-one` uses the in-parameters `conversationId` + `factKey` as the PK automatically (both are PK fields present in context).

- [ ] **Step 2: Write a failing remember test**

In `AiContextTests.groovy`, add a test where MockProvider scripts the agent calling `remember`, then a stop turn; assert the fact persisted:

```groovy
    def "agent records a fact via the remember tool"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        org.moqui.ai.provider.MockProvider.reset()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "MemAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "x", maxIterations: 4, statusId: "AI_AGENT_ACTIVE",
            contextStrategy: "window", contextWindowMessages: 20, contextWindowChars: 1000000]).createOrUpdate()
        String convId = ec.entity.sequencedIdPrimary("moqui.ai.AiConversation", null, null)
        ec.entity.makeValue("moqui.ai.AiConversation").setAll([conversationId: convId, agentName: "MemAgent",
            userId: "AiTestUser", fromDate: ec.user.nowTimestamp, statusId: "AI_CONV_ACTIVE"]).createOrUpdate()
        // turn 1: model calls remember; turn 2: model stops
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "r1", name: "remember", arguments: [factKey: "order_total", factValue: "\$4,812.50"]]],
            tokensIn: 1L, tokensOut: 1L])
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "noted", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        when:
        Map out = new org.moqui.ai.AgentRunner(ec, ai).run("MemAgent", "the total is \$4,812.50", convId)
        EntityValue fact = ec.entity.find("moqui.ai.AiConversationFact")
            .condition("conversationId", convId).condition("factKey", "order_total").one()
        then:
        out.statusId == "AI_RUN_COMPLETED"
        fact != null
        fact.factValue == "\$4,812.50"
        fact.agentRunId == out.agentRunId
        cleanup:
        ec.entity.find("moqui.ai.AiConversationFact").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "MemAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```

- [ ] **Step 3: Run to confirm it fails**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — `remember` is not offered/dispatched, so no fact is written (the tool-call would be treated as an unknown catalog tool and error back).

- [ ] **Step 4: Offer + dispatch the `remember` built-in tool in `AgentRunner`**

In `AgentRunner.groovy`:

(a) Add a constant at class top (after the `logger` field):
```groovy
    private static final String REMEMBER_TOOL = "remember"
```

(b) After `List<Map> toolSchemas = loadToolSchemas(agentName)` (currently line 43), offer the built-in remember tool when context is on AND there's a conversation to persist to:
```groovy
        if (ctxOn && conversationId) toolSchemas = toolSchemas + [[name: REMEMBER_TOOL,
            description: "Record a durable, confirmed value (e.g. a confirmed order total, address, or decision) so it is never lost from context. Call this the moment you confirm a value that must persist across the conversation.",
            parameters: [type: "object", required: ["factKey", "factValue"], properties: [
                factKey: [type: "string", description: "short stable identifier, e.g. order_total"],
                factValue: [type: "string", description: "the confirmed value"]]]]]
```
> Note `ctxOn`/`conversationId` are in scope here (parsed in Task 3 Step 4a; conversationId is the method param). This line must come AFTER the `ctxOn` parse — if needed, move the `ctxOn`/`ctxMsgs`/`ctxChars` parse block (Task 3 Step 4a) to just above the `loadToolSchemas` line so both are available.

(c) In the tool-dispatch loop (currently lines 103-108), special-case the remember tool BEFORE the generic `dispatchTool`. Replace the `for (Map tc in toolCalls) { ... }` body with:
```groovy
                for (Map tc in toolCalls) {
                    String resultJson
                    if (tc.name == REMEMBER_TOOL) {
                        resultJson = rememberFact(runId, conversationId, (tc.arguments ?: [:]) as Map)
                    } else {
                        resultJson = dispatchTool(runId, stepSeq, tc)
                    }
                    Map toolMsg = [role: "tool", toolCallId: tc.id, content: resultJson]
                    messages.add(toolMsg)
                    if (conversationId) persistConversationMessage(conversationId, runId, toolMsg)
                }
```

(d) Add the `rememberFact` helper (next to `loadFacts`): it injects the run's `conversationId` + `agentRunId` server-side (the model only supplied factKey/factValue) and calls the service:
```groovy
    /** Handle the built-in `remember` tool: persist a keyed fact with the run's conversationId
     *  injected server-side (never model-supplied). Returns a JSON confirmation for the model. */
    private String rememberFact(String runId, String conversationId, Map args) {
        if (!conversationId) return JsonOutput.toJson([error: "no conversation to remember into"])
        try {
            ec.message.clearErrors()
            ec.service.sync().name("ai.FactServices.remember#Fact").parameters([
                conversationId: conversationId, agentRunId: runId,
                factKey: args.factKey, factValue: args.factValue]).call()
            return JsonOutput.toJson([remembered: args.factKey])
        } catch (Throwable t) {
            logger.warn("remember failed (continuing): ${t.message}")
            return JsonOutput.toJson([error: t.message])
        }
    }
```

- [ ] **Step 5: Run — remember test passes**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS. The fact persists with the run's conversationId + agentRunId; live tests green (no contextStrategy → remember not offered → unchanged).

- [ ] **Step 6: Commit**

```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add service/ai/FactServices.xml src/main/groovy/org/moqui/ai/AgentRunner.groovy \
        src/test/groovy/AiContextTests.groovy && \
git commit -m "feat(ai): remember tool — server-injected conversationId, keyed fact store (#21)"
```

---

## Task 5: End-to-end fidelity guarantee + decision-record note

**Files:**
- Modify: `src/test/groovy/AiContextTests.groovy`
- Modify: `docs/specs/2026-06-03-enterprise-decisions-gap-report.md`

- [ ] **Step 1: Write the fidelity-guarantee test (the whole point)**

A fact remembered early must reach a later call even after its original message is windowed out. In `AiContextTests.groovy`:

```groovy
    def "a remembered fact survives window eviction and reaches a later call"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        org.moqui.ai.provider.MockProvider.reset()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "FidAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "base", maxIterations: 4, statusId: "AI_AGENT_ACTIVE",
            contextStrategy: "window", contextWindowMessages: 1, contextWindowChars: 1000000]).createOrUpdate()
        String convId = ec.entity.sequencedIdPrimary("moqui.ai.AiConversation", null, null)
        ec.entity.makeValue("moqui.ai.AiConversation").setAll([conversationId: convId, agentName: "FidAgent",
            userId: "AiTestUser", fromDate: ec.user.nowTimestamp, statusId: "AI_CONV_ACTIVE"]).createOrUpdate()
        // Turn 1: agent remembers the total, then stops.
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "r1", name: "remember", arguments: [factKey: "order_total", factValue: "\$4,812.50"]]],
            tokensIn: 1L, tokensOut: 1L])
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "noted", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        new org.moqui.ai.AgentRunner(ec, ai).run("FidAgent", "the order total is \$4,812.50", convId)
        when:
        // Turn 2: many turns later the early message is windowed out (window=1), but the FACT must persist.
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.LAST_REQUEST = null
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "the total was \$4,812.50", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        new org.moqui.ai.AgentRunner(ec, ai).run("FidAgent", "what was the order total?", convId)
        String sysSent = org.moqui.ai.provider.MockProvider.LAST_REQUEST.systemContext as String
        then:
        sysSent.contains("## Known facts")
        sysSent.contains("order_total: \$4,812.50")     // survived eviction, injected every call
        cleanup:
        ec.entity.find("moqui.ai.AiConversationFact").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "FidAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```

- [ ] **Step 2: Run — full suite green**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS — the fidelity guarantee holds (fact in `systemContext` after eviction), plus all prior tests + live tests.

- [ ] **Step 3: Record Phase 1 shipped in the gap report**

In `docs/specs/2026-06-03-enterprise-decisions-gap-report.md`, update Decision 2's row/section: Phase 1 of context-window management shipped — agent-defined pinned facts (`remember` tool → `AiConversationFact`) injected to `systemContext`, message-count + char-guard windowing of replayed history (tool-pair-safe), drops logged; gated by `AiAgent.contextStrategy` (default off). Note compaction (Phase 2), tool-result clearing (Phase 3), and retrieval (Phase 6) remain deferred. Update the Tally line. Surgical edits.

- [ ] **Step 4: Commit**

```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add src/test/groovy/AiContextTests.groovy docs/specs/2026-06-03-enterprise-decisions-gap-report.md && \
git commit -m "test(ai): fidelity guarantee — remembered fact survives eviction; record Phase 1 shipped (#21)"
```

---

## Self-Review

**Spec coverage** (vs. issue #21 + ADR Phase 1):
- `AiConversationFact` keyed conversation-scoped facts → Task 1. ✅
- `AiAgent` context config (strategy + window/char) → Task 1. ✅
- `ContextAssembler` inject + tool-pair-safe windowing → Task 2 (pure) + Task 3 (wired). ✅
- `remember` tool, server-injected conversationId, store-or-update → Task 4. ✅
- Drop logging (`context_trim` step) → Task 3. ✅
- Fidelity guarantee (survives eviction) → Task 5. ✅
- No-regression (off = unchanged) → Task 3. ✅
- Degrade-don't-block (loadFacts guarded; stateless remember no-op) → Task 3 (loadFacts), Task 4 (rememberFact guard). ✅
- Deferred (compaction/clearing/retrieval/tokenizer) → not built; noted. ✅

**Placeholder scan:** every code step has complete code; every run step has command + expected outcome. No TBD. ✅

**Type/name consistency:**
- `ContextAssembler.withFacts(String, List<Map>) → String` and `windowHistory(List, List, int, int) → [messages, dropped]` — defined Task 2, called Task 3 Step 4c. ✅
- `loadFacts(String) → List<Map>` of `[factKey, factValue]` — defined Task 3 Step 4d, consumed by `withFacts`. ✅
- `rememberFact(String runId, String conversationId, Map args) → String` — defined Task 4 Step 4d, called Task 4 Step 4c. ✅
- Built-in tool name constant `REMEMBER_TOOL = "remember"` — Task 4 Step 4a; matched in dispatch (4c) and offered schema (4b). ✅
- Config: `ctxOn`/`ctxMsgs`/`ctxChars` parsed once (Task 3 Step 4a, moved above `loadToolSchemas` per Task 4 Step 4b note). ✅
- `MockProvider.LAST_REQUEST` — added Task 3 Step 1, asserted in Tasks 3 + 5. ✅
- `stepType` values extended with `context_trim` (entity description updated Task 3 Step 4e). ✅
- `AiConversationFact` PK `(conversationId, factKey)`; `remember#Fact` find-or-create by that PK; `loadFacts` reads by `conversationId`. ✅

**Ordering / independent deployability:** Task 1 (data model) → Task 2 (pure assembler) → Task 3 (windowing wired; no-regression proven) → Task 4 (remember write side) → Task 5 (fidelity e2e + docs). Each ends green and committed. Windowing (Task 3) is safe before `remember` exists because `loadFacts` returns `[]` until facts are written.
