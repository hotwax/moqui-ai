# Context-Window Management — Phase 2 (Compaction) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a conversation's replayed history overflows the window, summarize the overflow into a rolling, persisted summary that carries forward — keeping the gist of old turns instead of dropping them — without ever losing a confirmed fact.

**Architecture:** Adds a `summarize` value to `AiAgent.contextStrategy`. The conversation carries a rolling summary (`AiConversation.summaryText` + `summaryThruMessageSeqId` watermark). Each run considers only the live tail (messages past the watermark); when that tail overflows the message/char window, `AgentRunner` summarizes the dropped overflow (existing summary + overflow) with the agent's own model, persists the new summary + advances the watermark, and injects the summary as a `## Conversation summary` block on `systemContext` (next to pinned facts). On summarization failure it falls back to Phase 1 windowing. Default `off`/`window` unchanged.

**Tech Stack:** Groovy 3 / JDK 11, Moqui (HotWax fork), Spock + JUnit Platform Suite, H2 dev DB.

---

## Implements
GitHub issue **#22** · ADR **0001** (`docs/decisions/0001-context-window-management.md`), Phase 2. Builds directly on Phase 1 (#21): `AiConversationFact`, `ContextAssembler`, `AgentRunner` windowing + fact injection, `MockProvider.LAST_REQUEST`.

## Locked decisions (2026-06-03)
- **Summarize with the agent's own model** (the primary candidate `[providerName, modelName]`). No new model config.
- **Rolling, persisted summary** on `AiConversation` (`summaryText` + `summaryThruMessageSeqId`). Summarize once, reuse + extend.
- **Live tail** each run = messages with `messageSeqId > watermark`; older turns are represented by `summaryText`.
- **Inject** summary as a `## Conversation summary (earlier turns)` block on `systemContext` (provider-agnostic, like facts).
- **Degrade, don't block:** summarization failure → fall back to Phase 1 windowing (drop, facts pinned), log, never error the run.
- Observability: log a `compaction` step; the summary call's tokens count toward the run.

## Conventions (same as prior work)
- Tests: Spock `*Tests.groovy` in `src/test/groovy/`, registered in `MoquiSuite.groovy`. New cases go in the existing `AiContextTests` (already registered).
- Run suite from moqui root `/Users/anilpatel/maarg-sd/moqui`:
  ```bash
  source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
  ```
  Live tests are key-gated; both accounts funded — run for real, don't skip.
- New nullable fields just need the boot the test triggers (Moqui auto-adds columns).
- Commit **inside the component repo** (`runtime/component/moqui-ai`, branch `feature/ai-agent-framework`) with component-relative paths.
- Naming: "Fact"/"summary" — never "Memory".

## Moqui sequenced-id ordering note
`messageSeqId` is a Moqui `setSequencedIdSecondary()` value — zero-padded and lexically sortable, so `orderBy("messageSeqId")` and an entity condition `messageSeqId greater-than <watermark>` order/filter correctly. The watermark is the `messageSeqId` of the newest message already folded into the summary.

---

## File Structure

| File | Change |
|---|---|
| `entity/AiConversationEntities.xml` | `AiConversation` gains `summaryText` + `summaryThruMessageSeqId` |
| `entity/AiEntities.xml` | `AiAgentRunStep.stepType` description gains `compaction` |
| `src/main/groovy/org/moqui/ai/ContextAssembler.groovy` | `withSummary` (inject summary block); `windowHistory` also returns the dropped messages |
| `src/main/groovy/org/moqui/ai/AgentRunner.groovy` | `summarize` strategy: watermark-filtered replay, summarize overflow with agent's model, persist rolling summary, inject summary, fall back on failure, log compaction |
| `src/test/groovy/AiContextTests.groovy` | assembler unit (withSummary, droppedMessages) + compaction integration + e2e |
| `docs/specs/2026-06-03-enterprise-decisions-gap-report.md` | record Phase 2 shipped |

---

## Task 1: `AiConversation` rolling-summary fields

**Files:**
- Modify: `entity/AiConversationEntities.xml`
- Test: `src/test/groovy/AiEntitiesTests.groovy`

- [ ] **Step 1: Write a failing entity round-trip test**

In `src/test/groovy/AiEntitiesTests.groovy`, add (match the file's pattern):
```groovy
    def "AiConversation stores a rolling summary + watermark"() {
        given:
        ec.artifactExecution.disableAuthz()
        String cid = "CONVSUM1"
        ec.entity.makeValue("moqui.ai.AiConversation").setAll([conversationId: cid, agentName: "A",
            fromDate: ec.user.nowTimestamp, statusId: "AI_CONV_ACTIVE",
            summaryText: "earlier: customer wants 3 units", summaryThruMessageSeqId: "00007"]).create()
        when:
        def c = ec.entity.find("moqui.ai.AiConversation").condition("conversationId", cid).one()
        then:
        c.summaryText == "earlier: customer wants 3 units"
        c.summaryThruMessageSeqId == "00007"
        cleanup:
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", cid).deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```

- [ ] **Step 2: Run to confirm it fails**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — fields `summaryText`/`summaryThruMessageSeqId` not defined.

- [ ] **Step 3: Add the fields**

In `entity/AiConversationEntities.xml`, inside `<entity entity-name="AiConversation" ...>`, add after the `lastActivityDate` field:
```xml
        <field name="summaryText" type="text-very-long"><description>ADR 0001 Phase 2: rolling summary of turns older than summaryThruMessageSeqId (compaction)</description></field>
        <field name="summaryThruMessageSeqId" type="id"><description>Watermark: messageSeqId of the newest message already folded into summaryText</description></field>
```

- [ ] **Step 4: Run — passes**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add entity/AiConversationEntities.xml src/test/groovy/AiEntitiesTests.groovy && \
git commit -m "feat(ai): AiConversation rolling-summary fields for compaction (#22)"
```

---

## Task 2: `ContextAssembler` — `withSummary` + `windowHistory` returns dropped messages

**Files:**
- Modify: `src/main/groovy/org/moqui/ai/ContextAssembler.groovy`
- Test: `src/test/groovy/AiContextTests.groovy`

- [ ] **Step 1: Write failing pure unit tests**

In `AiContextTests.groovy` (the pure-test section, no `ec`), add:
```groovy
    def "withSummary prepends a Conversation summary block when summary is present"() {
        when:
        String s = ContextAssembler.withSummary("Be helpful.", "Customer confirmed 3 units, net-30 terms.")
        then:
        s.contains("Be helpful.")
        s.contains("## Conversation summary (earlier turns)")
        s.contains("Customer confirmed 3 units, net-30 terms.")
    }

    def "withSummary is a no-op when there is no summary"() {
        expect:
        ContextAssembler.withSummary("Be helpful.", null) == "Be helpful."
        ContextAssembler.withSummary("Be helpful.", "") == "Be helpful."
    }

    def "windowHistory returns the dropped messages (oldest replayed not kept)"() {
        given:
        List replayed = (1..6).collect { [role: "user", content: "old ${it}"] }
        List current = [[role: "user", content: "now"]]
        when:
        Map r = ContextAssembler.windowHistory(replayed, current, 2, 1000000)
        then:
        r.dropped == 4
        (r.droppedMessages as List).collect { it.content } == ["old 1", "old 2", "old 3", "old 4"]
        (r.messages as List).collect { it.content } == ["old 5", "old 6", "now"]
    }
```

- [ ] **Step 2: Run to confirm it fails**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — `withSummary` undefined; `droppedMessages` key absent.

- [ ] **Step 3: Implement**

In `ContextAssembler.groovy`, add the `withSummary` method (next to `withFacts`):
```groovy
    /** Prepend a rolling-summary block to the system prompt. No-op when there is no summary. */
    static String withSummary(String systemPrompt, String summaryText) {
        if (!summaryText) return systemPrompt
        StringBuilder sb = new StringBuilder(systemPrompt ?: "")
        sb.append("\n\n## Conversation summary (earlier turns)\n").append(summaryText).append("\n")
        return sb.toString()
    }
```
And change `windowHistory` to also return the dropped messages. `kept` is always a contiguous suffix of `src`, so the dropped messages are the leading prefix `src[0 ..< (src.size - kept.size)]`. Replace the final `return` line:
```groovy
        return [messages: out, dropped: total - kept.size()]
```
with:
```groovy
        List<Map> droppedMessages = new ArrayList<>(src.subList(0, total - kept.size()))
        return [messages: out, dropped: total - kept.size(), droppedMessages: droppedMessages]
```

- [ ] **Step 4: Run — pure tests pass**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS (new pure tests; Phase 1 tests still green — `dropped` count unchanged, `droppedMessages` is additive).

- [ ] **Step 5: Commit**

```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add src/main/groovy/org/moqui/ai/ContextAssembler.groovy src/test/groovy/AiContextTests.groovy && \
git commit -m "feat(ai): ContextAssembler withSummary + windowHistory returns dropped messages (#22)"
```

---

## Task 3: `summarize` strategy in `AgentRunner` (rolling compaction)

**Files:**
- Modify: `src/main/groovy/org/moqui/ai/AgentRunner.groovy`
- Modify: `entity/AiEntities.xml`
- Test: `src/test/groovy/AiContextTests.groovy`

- [ ] **Step 1: Write failing compaction integration tests**

In `AiContextTests.groovy` (integration section, reuse the existing scaffolding), add. Note MockProvider returns enqueued responses in order; with `contextStrategy="summarize"` the summarization call happens during assembly BEFORE the main chat, so enqueue the summary response first, then the main response.

```groovy
    def "summarize strategy compacts overflow into a persisted rolling summary"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.LAST_REQUEST = null
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "SumAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "base", maxIterations: 3, statusId: "AI_AGENT_ACTIVE",
            contextStrategy: "summarize", contextWindowMessages: 2, contextWindowChars: 1000000]).createOrUpdate()
        String convId = ec.entity.sequencedIdPrimary("moqui.ai.AiConversation", null, null)
        ec.entity.makeValue("moqui.ai.AiConversation").setAll([conversationId: convId, agentName: "SumAgent",
            userId: "AiTestUser", fromDate: ec.user.nowTimestamp, statusId: "AI_CONV_ACTIVE"]).createOrUpdate()
        (1..5).each { i ->
            EntityValue m = ec.entity.makeValue("moqui.ai.AiConversationMessage")
            m.set("conversationId", convId); m.setSequencedIdSecondary()
            m.setAll([role: "user", content: "old ${i}", fromDate: ec.user.nowTimestamp]); m.create()
        }
        // 1st enqueued = the summarization call's response; 2nd = the main agent answer
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "SUMMARY: discussed old 1-3", finishReason: "stop", toolCalls: [], tokensIn: 5L, tokensOut: 3L])
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "ok", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        when:
        new org.moqui.ai.AgentRunner(ec, ai).run("SumAgent", "newest", convId)
        EntityValue conv = ec.entity.find("moqui.ai.AiConversation").condition("conversationId", convId).one()
        String sysSent = org.moqui.ai.provider.MockProvider.LAST_REQUEST.systemContext as String
        List sent = org.moqui.ai.provider.MockProvider.LAST_REQUEST.messages as List
        then:
        conv.summaryText == "SUMMARY: discussed old 1-3"          // overflow summarized + persisted
        conv.summaryThruMessageSeqId != null                       // watermark advanced
        sysSent.contains("## Conversation summary (earlier turns)")
        sysSent.contains("SUMMARY: discussed old 1-3")             // summary injected into the main call
        sent.collect { it.content } == ["old 4", "old 5", "newest"] // kept window + current turn
        cleanup:
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "SumAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "summarization failure falls back to windowing and the run still completes"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.LAST_REQUEST = null
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "SumFailAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "base", maxIterations: 3, statusId: "AI_AGENT_ACTIVE",
            contextStrategy: "summarize", contextWindowMessages: 2, contextWindowChars: 1000000]).createOrUpdate()
        String convId = ec.entity.sequencedIdPrimary("moqui.ai.AiConversation", null, null)
        ec.entity.makeValue("moqui.ai.AiConversation").setAll([conversationId: convId, agentName: "SumFailAgent",
            userId: "AiTestUser", fromDate: ec.user.nowTimestamp, statusId: "AI_CONV_ACTIVE"]).createOrUpdate()
        (1..5).each { i ->
            EntityValue m = ec.entity.makeValue("moqui.ai.AiConversationMessage")
            m.set("conversationId", convId); m.setSequencedIdSecondary()
            m.setAll([role: "user", content: "old ${i}", fromDate: ec.user.nowTimestamp]); m.create()
        }
        // 1st enqueued = the summarization call FAILS; 2nd = the main agent answer still succeeds
        org.moqui.ai.provider.MockProvider.enqueue([__error: "summary provider down"])
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "ok", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        when:
        Map out = new org.moqui.ai.AgentRunner(ec, ai).run("SumFailAgent", "newest", convId)
        EntityValue conv = ec.entity.find("moqui.ai.AiConversation").condition("conversationId", convId).one()
        List sent = org.moqui.ai.provider.MockProvider.LAST_REQUEST.messages as List
        then:
        out.statusId == "AI_RUN_COMPLETED"                         // run not blocked by summary failure
        conv.summaryText == null                                   // no summary persisted on failure
        sent.collect { it.content } == ["old 4", "old 5", "newest"] // fell back to windowing (dropped, not summarized)
        cleanup:
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "SumFailAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```

- [ ] **Step 2: Run to confirm they fail**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — `summarize` strategy not implemented (no summary persisted; messages not compacted as expected).

- [ ] **Step 3: Implement the `summarize` strategy in `AgentRunner`**

READ `AgentRunner.groovy` (current). Make these edits:

(a) Add a summarization-instruction constant after the `REMEMBER_TOOL` constant:
```groovy
    private static final String SUMMARY_INSTRUCTION =
        "You are compacting a conversation to save context. Update the running summary to incorporate " +
        "the new messages below. Be concise but PRESERVE decisions, commitments, identifiers, and any " +
        "values that may matter later. Output only the updated summary text."
```

(b) Recognize the new strategy. The Phase 1 parse is `boolean ctxOn = (agent.contextStrategy == "window")`. Replace it with:
```groovy
        boolean ctxSummarize = (agent.contextStrategy == "summarize")
        boolean ctxOn = (agent.contextStrategy == "window") || ctxSummarize
```
(So `ctxOn` still gates windowing + the remember tool + fact injection for both `window` and `summarize`; `ctxSummarize` additionally enables compaction.)

(c) Carry `messageSeqId` on replayed messages + support a watermark filter. Replace `loadConversationMessages(String conversationId)` so it carries the seq id and accepts an optional `afterSeqId`:
```groovy
    /** Load persisted conversation messages, in order, as message Maps (incl. messageSeqId).
     *  When afterSeqId is set, only messages with a greater messageSeqId are returned (the live
     *  tail past a compaction watermark). */
    private List<Map> loadConversationMessages(String conversationId, String afterSeqId = null) {
        List<Map> out = []
        def finder = ec.entity.find("moqui.ai.AiConversationMessage")
            .condition("conversationId", conversationId).orderBy("messageSeqId")
        if (afterSeqId) finder.condition("messageSeqId", "greater", afterSeqId)
        for (EntityValue m in finder.list()) {
            Map msg = [role: m.role, content: m.content, messageSeqId: m.messageSeqId]
            if (m.toolCallId) msg.toolCallId = m.toolCallId
            if (m.toolCalls) msg.toolCalls = new groovy.json.JsonSlurper().parseText(m.toolCalls as String)
            out.add(msg)
        }
        return out
    }
```
(The extra `messageSeqId` key is harmless to the providers — encoders only read role/content/toolCalls/toolCallId.)

(d) Load the rolling summary + use the watermark when summarizing. The run already loads `EntityValue agent`. Add, near where `conversationId` messages are first loaded (the history-replay block), a summary load and a watermark-aware replay. Replace:
```groovy
        List<Map> messages = conversationId ? loadConversationMessages(conversationId) : []
        int replayCount = messages.size()
```
with:
```groovy
        EntityValue conv = conversationId ? ec.entity.find("moqui.ai.AiConversation")
            .condition("conversationId", conversationId).one() : null
        String summaryText = conv?.summaryText
        String summaryWatermark = conv?.summaryThruMessageSeqId
        // For the summarize strategy, replay only the live tail past the watermark (older turns are
        // represented by summaryText). For off/window, replay the full history (Phase 1 behavior).
        List<Map> messages = conversationId
            ? loadConversationMessages(conversationId, ctxSummarize ? summaryWatermark : null) : []
        int replayCount = messages.size()
```

(e) In the per-call assembly block (inside the loop, the `if (ctxOn) { ... }` added in Phase 1), when `ctxSummarize` and the window dropped messages, summarize them into the rolling summary BEFORE building `sysCtx`. Replace the Phase 1 assembly block:
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
```
with:
```groovy
                String sysCtx = agent.systemPrompt as String
                List<Map> sendMessages = messages
                if (ctxOn) {
                    int rc = Math.min(replayCount, messages.size())
                    Map asm = ContextAssembler.windowHistory(messages.subList(0, rc),
                        messages.subList(rc, messages.size()), ctxMsgs, ctxChars)
                    sendMessages = asm.messages as List<Map>
                    List<Map> droppedMsgs = (asm.droppedMessages ?: []) as List<Map>
                    if (ctxSummarize && droppedMsgs) {
                        String rolled = summarizeOverflow(primary, summaryText, droppedMsgs, runId, result)
                        if (rolled != null) {                    // success: persist + advance watermark
                            summaryText = rolled
                            summaryWatermark = droppedMsgs.last().messageSeqId as String
                            persist("update#moqui.ai.AiConversation", [conversationId: conversationId,
                                summaryText: summaryText, summaryThruMessageSeqId: summaryWatermark])
                        }                                        // failure: rolled==null -> fall through to plain windowing
                    }
                    sysCtx = ContextAssembler.withFacts(
                        ContextAssembler.withSummary(sysCtx, summaryText), loadFacts(conversationId))
                    if ((asm.dropped as int) > 0) {
                        stepSeq++
                        persist("create#moqui.ai.AiAgentRunStep", [agentRunId: runId, stepSeqId: stepSeq as String,
                            stepType: ctxSummarize ? "compaction" : "context_trim",
                            finishReason: "dropped:${asm.dropped}" as String])
                    }
                }
```

(f) Add the `summarizeOverflow` helper (next to `loadFacts`). It calls the agent's primary model with the summary instruction; returns the new summary text, or `null` on failure (caller falls back). It also adds the summary call's tokens to the run total.
```groovy
    /** Roll the overflow into the conversation summary using the agent's own (primary) model.
     *  Returns the new summary text, or null on failure (caller falls back to plain windowing). */
    private String summarizeOverflow(Map primary, String existingSummary, List<Map> overflow, String runId, Map result) {
        try {
            StringBuilder sb = new StringBuilder()
            if (existingSummary) sb.append("Existing summary:\n").append(existingSummary).append("\n\n")
            sb.append("New messages to fold in:\n")
            for (Map m in overflow) sb.append("[").append(m.role).append("] ").append(m.content ?: "").append("\n")
            LlmProvider p = ai.getProvider(primary.providerName as String)
            Map resp = p.chat([model: primary.modelName, systemContext: SUMMARY_INSTRUCTION,
                messages: [[role: "user", content: sb.toString()]]])
            String text = resp.assistantText as String
            if (!text) return null
            result.tokensIn += (resp.tokensIn ?: 0L) as long       // compaction is part of the run's cost
            result.tokensOut += (resp.tokensOut ?: 0L) as long
            return text
        } catch (Throwable t) {
            logger.warn("Compaction summarization failed (falling back to windowing): ${t.message}")
            return null
        }
    }
```

(g) Update the `AiAgentRunStep.stepType` description in `entity/AiEntities.xml` to add `compaction`: `llm_call | tool_call | llm_call_failed | context_trim | compaction`.

> Note: the summarization `provider.chat` is a separate call from the main loop's `callWithFailover`; it does NOT fail over (it uses the primary candidate only) and any error returns null → fall back. This keeps compaction simple and never blocks the run.

- [ ] **Step 4: Run — compaction + fallback tests pass**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS. Summarize agent persists the summary + advances the watermark + injects it; the failure test falls back to windowing with the run completing; Phase 1 `window`/`off` tests + live tests unchanged.

- [ ] **Step 5: Commit**

```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add src/main/groovy/org/moqui/ai/AgentRunner.groovy entity/AiEntities.xml \
        src/test/groovy/AiContextTests.groovy && \
git commit -m "feat(ai): summarize strategy — rolling compaction with agent model, fall back on failure (#22)"
```

---

## Task 4: End-to-end (summary carries forward + is reused) + doc note

**Files:**
- Modify: `src/test/groovy/AiContextTests.groovy`
- Modify: `docs/specs/2026-06-03-enterprise-decisions-gap-report.md`

- [ ] **Step 1: Write the carry-forward + reuse e2e test**

The summary persisted in one turn must be injected on a later turn, and must NOT be regenerated when there's no new overflow. In `AiContextTests.groovy`:
```groovy
    def "a persisted summary carries forward and is reused without re-summarizing"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        org.moqui.ai.provider.MockProvider.reset()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "CarryAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "base", maxIterations: 3, statusId: "AI_AGENT_ACTIVE",
            contextStrategy: "summarize", contextWindowMessages: 2, contextWindowChars: 1000000]).createOrUpdate()
        String convId = ec.entity.sequencedIdPrimary("moqui.ai.AiConversation", null, null)
        ec.entity.makeValue("moqui.ai.AiConversation").setAll([conversationId: convId, agentName: "CarryAgent",
            userId: "AiTestUser", fromDate: ec.user.nowTimestamp, statusId: "AI_CONV_ACTIVE",
            summaryText: "earlier: customer confirmed 3 units", summaryThruMessageSeqId: "00003"]).createOrUpdate()
        // only 1 live message past the watermark -> below window(2) -> NO overflow -> NO summarization call
        EntityValue m = ec.entity.makeValue("moqui.ai.AiConversationMessage")
        m.set("conversationId", convId); m.setSequencedIdSecondary()
        m.setAll([role: "user", content: "live one", fromDate: ec.user.nowTimestamp]); m.create()
        // only the main answer is enqueued; if the code wrongly re-summarized, it would consume this as the summary
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.LAST_REQUEST = null
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "answer", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        when:
        Map out = new org.moqui.ai.AgentRunner(ec, ai).run("CarryAgent", "and now?", convId)
        EntityValue conv = ec.entity.find("moqui.ai.AiConversation").condition("conversationId", convId).one()
        String sysSent = org.moqui.ai.provider.MockProvider.LAST_REQUEST.systemContext as String
        then:
        out.statusId == "AI_RUN_COMPLETED"
        sysSent.contains("earlier: customer confirmed 3 units")    // existing summary injected (carried forward)
        conv.summaryText == "earlier: customer confirmed 3 units"  // unchanged — not regenerated (no overflow)
        conv.summaryThruMessageSeqId == "00003"                    // watermark unchanged
        cleanup:
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "CarryAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```

- [ ] **Step 2: Run — full suite green**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS — carry-forward/reuse holds (no extra summarization call consumed), plus all prior + live tests. Report the total test count.

- [ ] **Step 3: Record Phase 2 shipped in the gap report**

In `docs/specs/2026-06-03-enterprise-decisions-gap-report.md`, READ it, then update Decision 2's row/section + punchline + Tally with surgical edits: context-window **Phase 2 (compaction)** shipped — `contextStrategy=summarize` rolls overflow into a persisted `AiConversation.summaryText` (+ watermark) using the agent's own model, injected as a `## Conversation summary` block; falls back to windowing on summarization failure; logged as `compaction` steps. Tool-result clearing (Phase 3) and semantic retrieval (Phase 6) remain deferred.

- [ ] **Step 4: Commit**

```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add src/test/groovy/AiContextTests.groovy docs/specs/2026-06-03-enterprise-decisions-gap-report.md && \
git commit -m "test(ai): compaction carry-forward + reuse e2e; record Phase 2 shipped (#22)"
```

---

## Self-Review

**Spec coverage** (vs. issue #22 + ADR Phase 2):
- `contextStrategy=summarize` → Task 3 (b). ✅
- Summarize with agent's own model → `summarizeOverflow` uses `primary` (Task 3 f). ✅
- Rolling persisted summary (summaryText + watermark) → entity (Task 1) + persist on success (Task 3 e). ✅
- Live tail past watermark → watermark-filtered `loadConversationMessages` (Task 3 c, d). ✅
- Inject as `## Conversation summary` block on systemContext → `withSummary` (Task 2) + assembly (Task 3 e). ✅
- Degrade-don't-block on summarization failure → `summarizeOverflow` returns null → fall through to windowing; failure test (Task 3 Step 1). ✅
- Reuse without re-summarizing when no overflow → carry-forward test (Task 4). ✅
- Observability (`compaction` step; summary tokens counted) → Task 3 (e, f, g). ✅
- `off`/`window` unaffected → `ctxSummarize` only adds behavior; window path unchanged; Phase 1 tests stay green. ✅
- Deferred (tool-result clearing, retrieval) → not built; noted. ✅

**Placeholder scan:** every code step has complete code; run steps have exact command + expected outcome. No TBD. ✅

**Type/name consistency:**
- `windowHistory` now returns `[messages, dropped(int), droppedMessages(List<Map>)]` — Phase 1 callers still read `dropped`; Task 3 reads `droppedMessages`. ✅
- `withSummary(String, String) → String` (Task 2), called in Task 3 (e) wrapping `withFacts`. ✅
- `summarizeOverflow(Map primary, String existingSummary, List<Map> overflow, String runId, Map result) → String|null` — defined Task 3 (f), called Task 3 (e). ✅
- `loadConversationMessages(String, String afterSeqId=null)` — overload-compatible with the Phase 1 single-arg call (off/window pass null). ✅
- `ctxSummarize`/`ctxOn` — parsed Task 3 (b); `ctxOn` still governs the remember-tool offer + windowing (so `summarize` agents also get facts + the remember tool, correct). ✅
- Entity fields `summaryText`/`summaryThruMessageSeqId` (Task 1) — written in Task 3 (e), read in Task 3 (d). `messageSeqId` carried in message maps (Task 3 c) — used for the watermark in Task 3 (e). ✅
- `stepType` "compaction" — entity description (Task 3 g), written (Task 3 e). ✅

**Ordering / independent deployability:** Task 1 (entity) → Task 2 (pure assembler additions) → Task 3 (the strategy) → Task 4 (e2e + docs). Each ends green and committed. Task 2's `droppedMessages` is additive (Phase 1 unaffected); Task 3's watermark-replay only triggers for `summarize`.
