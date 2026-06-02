# Phase 2: Conversation Threads — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a caller carry a multi-turn conversation by passing a `threadId` to
`ai.AgentServices.run#Agent`. The framework persists each turn's messages to the thread and
replays prior turns on the next call — the caller passes an ID, never the history.

**Architecture:** Adds two entities (`AiThread`, `AiThreadMessage`) and extends `AiAgentRun`
with `threadId` via `<extend-entity>` (never edit the Phase 1 entity in place). `AgentRunner.run`
gains an optional `threadId`: when present it seeds the message list from persisted thread
messages and appends the new turn's messages back. No `threadId` → Phase 1 stateless behavior,
unchanged. All data stays Map-based.

**Tech Stack:** same as Phase 1 (Groovy 3, Moqui hotwax/main JDK 11, Spock 2.1, `groovy.json`).

**Depends on:** Phase 1 (slice 1) committed and green — `AgentRunner`, `AiAgentRun`,
`ai.AgentServices.run#Agent`, `MoquiSuite`. **This plan inherits Phase 1's unverified API
assumptions; reconcile against the proven Phase 1 build before relying on the exact code.**

**Conventions (binding):** the UDM Domain Object Practices Guide
(`/Users/anilpatel/maarg-sd/docs/udm-domain-object-practices.md`). Specifically: **Maps not
data classes**; status via `StatusItem`/`StatusFlow`/`StatusFlowTransition` (`install` data);
tests are `*Tests.groovy` aggregated by `MoquiSuite` (append each new `*Tests` to
`@SelectClasses`); `extend-entity` to add fields to existing entities; services return
`Map<String,Object>`; `text-indicator` / `id` / `date-time` field types.

**Out of this phase:** cost aggregation (Phase 3), approval (Phase 4), UI (Phase 5), RAG (Phase 6),
and thread summarization/truncation for long histories (a Phase 2.x follow-up — see "NOT in this phase").

---

## File Structure (added/changed)

```
runtime/component/moqui-ai/
├── entity/AiThreadEntities.xml                     ← AiThread, AiThreadMessage + extend AiAgentRun (Task 1)
├── data/AiThreadStatusData.xml                     ← AiThreadStatus StatusItems/Flow (Task 1)
├── src/main/groovy/org/moqui/ai/AgentRunner.groovy ← MODIFY: threadId history replay + persist (Task 2)
├── service/ai/AgentServices.xml                    ← MODIFY: run#Agent gains threadId; add create#Thread (Task 3)
└── src/test/groovy/
    ├── MoquiSuite.groovy                           ← MODIFY: add AiThreadTests
    └── AiThreadTests.groovy                        ← Task 4 (multi-turn replay)
```

---

## Task 1: Thread entities + status data

**Files:**
- Create: `runtime/component/moqui-ai/entity/AiThreadEntities.xml`
- Create: `runtime/component/moqui-ai/data/AiThreadStatusData.xml`
- Test: covered by `AiThreadTests` in Task 4 (no standalone entity test — keep the suite lean)

- [ ] **Step 1: Define the thread entities + extend AiAgentRun**

`runtime/component/moqui-ai/entity/AiThreadEntities.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<entities xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/entity-definition-3.xsd">

    <entity entity-name="AiThread" package="moqui.ai">
        <field name="threadId" type="id" is-pk="true"/>
        <field name="agentName" type="id"/>
        <field name="userId" type="id"/>
        <field name="title" type="text-medium"/>
        <field name="fromDate" type="date-time"/>
        <field name="lastActivityDate" type="date-time"/>
        <field name="statusId" type="id"><description>StatusItem statusTypeId=AiThreadStatus: AI_THREAD_ACTIVE | AI_THREAD_CLOSED</description></field>
        <relationship type="one" related="moqui.ai.AiAgent" short-alias="agent"/>
        <relationship type="one" related="moqui.basic.StatusItem" short-alias="status"/>
    </entity>

    <!-- One persisted message per turn-part, in order, replayed on the next call. -->
    <entity entity-name="AiThreadMessage" package="moqui.ai">
        <field name="threadId" type="id" is-pk="true"/>
        <field name="messageSeqId" type="id" is-pk="true"/>
        <field name="role" type="text-short"><description>system | user | assistant | tool</description></field>
        <field name="content" type="text-very-long"/>
        <field name="toolCalls" type="text-very-long"><description>JSON of List&lt;Map&gt; when an assistant turn requested tools</description></field>
        <field name="toolCallId" type="text-medium"><description>set when role = tool</description></field>
        <field name="agentRunId" type="id"><description>which run produced this message</description></field>
        <field name="fromDate" type="date-time"/>
        <relationship type="one" related="moqui.ai.AiThread" short-alias="thread"/>
    </entity>

    <!-- Link each run to its thread WITHOUT editing the Phase 1 entity (practices guide §1.7). -->
    <extend-entity entity-name="AiAgentRun" package="moqui.ai">
        <field name="threadId" type="id"/>
        <relationship type="one" related="moqui.ai.AiThread" short-alias="thread"/>
    </extend-entity>
</entities>
```

- [ ] **Step 2: Define thread status data (install)**

`runtime/component/moqui-ai/data/AiThreadStatusData.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<entity-facade-xml type="install">
    <moqui.basic.StatusType statusTypeId="AiThreadStatus" description="AI Conversation Thread Status"/>
    <moqui.basic.StatusItem statusId="AI_THREAD_ACTIVE" statusTypeId="AiThreadStatus" sequenceNum="1" description="Active"/>
    <moqui.basic.StatusItem statusId="AI_THREAD_CLOSED" statusTypeId="AiThreadStatus" sequenceNum="2" description="Closed"/>
    <moqui.basic.StatusFlow statusFlowId="AiThreadFlow" statusTypeId="AiThreadStatus" description="AI thread lifecycle"/>
    <moqui.basic.StatusFlowTransition statusFlowId="AiThreadFlow" statusId="AI_THREAD_ACTIVE" toStatusId="AI_THREAD_CLOSED" transitionSequence="1" transitionName="Close"/>
    <moqui.basic.StatusFlowTransition statusFlowId="AiThreadFlow" statusId="AI_THREAD_CLOSED" toStatusId="AI_THREAD_ACTIVE" transitionSequence="2" transitionName="Reopen"/>
</entity-facade-xml>
```

- [ ] **Step 3: Commit**

```bash
git add runtime/component/moqui-ai/entity/AiThreadEntities.xml \
        runtime/component/moqui-ai/data/AiThreadStatusData.xml
git commit -m "feat(moqui-ai): thread entities + extend AiAgentRun with threadId"
```

---

## Task 2: Thread history replay + persistence in AgentRunner

`AgentRunner.run` gains an optional `threadId`. The change is additive: the Phase 1 single-turn
path is preserved when `threadId` is null.

**Files:**
- Modify: `runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/AgentRunner.groovy`
- Test: `AiThreadTests` (Task 4)

- [ ] **Step 1: Add an overloaded run(agentName, userMessage, threadId)**

In `AgentRunner.groovy`, keep the existing `Map run(String agentName, String userMessage)` as a
thin delegate and add the thread-aware method. Replace the method signature block:
```groovy
    /** Phase 1 entry — stateless single turn. */
    Map run(String agentName, String userMessage) { return run(agentName, userMessage, null) }

    /** @param threadId optional; when set, prior thread messages are replayed and this turn's
     *  messages are persisted back to the thread.
     *  @return runResult Map (see Phase 1 "Canonical Map shapes") + threadId. */
    Map run(String agentName, String userMessage, String threadId) {
        EntityValue agent = ec.entity.find("moqui.ai.AiAgent")
            .condition("agentName", agentName).useCache(true).one()
        if (agent == null) throw new IllegalArgumentException("Unknown agent: ${agentName}")

        int maxIter = (agent.maxIterations ?: 8) as int
        long maxTokens = (agent.maxTokens ?: 0L) as long
        int maxToolCalls = (agent.maxToolCallsPerTurn ?: 20) as int
        LlmProvider provider = ai.getProvider(agent.providerName as String)
        List<Map> toolSchemas = loadToolSchemas(agentName)

        String runId = ec.entity.sequencedIdPrimary("moqui.ai.AiAgentRun", null, null)
        Map result = [agentRunId: runId, threadId: threadId, assistantMessage: null,
                      tokensIn: 0L, tokensOut: 0L, iterations: 0, truncated: false, statusId: "AI_RUN_RUNNING"]
        persist("create#moqui.ai.AiAgentRun", [agentRunId: runId, agentName: agentName, threadId: threadId,
            userId: ec.user.userId, fromDate: ec.user.nowTimestamp, statusId: "AI_RUN_RUNNING",
            providerName: agent.providerName, modelName: agent.modelName, userMessage: userMessage])

        // ---- history replay: prior thread messages, then this turn's user message ----
        List<Map> messages = threadId ? loadThreadMessages(threadId) : []
        messages.add([role: "user", content: userMessage])
        if (threadId) persistThreadMessage(threadId, runId, [role: "user", content: userMessage])

        int stepSeq = 0
        try {
            for (int i = 0; i < maxIter; i++) {
                result.iterations = i + 1
                Map resp = provider.chat([model: agent.modelName, systemContext: agent.systemPrompt,
                        messages: messages, tools: toolSchemas])
                long inTok = (resp.tokensIn ?: 0L) as long, outTok = (resp.tokensOut ?: 0L) as long
                result.tokensIn += inTok; result.tokensOut += outTok
                stepSeq++
                persist("create#moqui.ai.AiAgentRunStep", [agentRunId: runId, stepSeqId: stepSeq as String,
                    stepType: "llm_call", tokensIn: inTok, tokensOut: outTok, finishReason: resp.finishReason])

                if (maxTokens > 0 && ((result.tokensIn as long) + (result.tokensOut as long)) > maxTokens)
                    return finishThread(result, runId, threadId, "AI_RUN_ABORTED", "Per-run token ceiling exceeded")

                List toolCalls = (resp.toolCalls ?: []) as List
                if (!toolCalls) {
                    result.assistantMessage = resp.assistantText ?: ""
                    if (threadId) persistThreadMessage(threadId, runId, [role: "assistant", content: result.assistantMessage])
                    return finishThread(result, runId, threadId, "AI_RUN_COMPLETED", null)
                }
                if (toolCalls.size() > maxToolCalls)
                    return finishThread(result, runId, threadId, "AI_RUN_ABORTED", "Tool-calls-per-turn ceiling exceeded")

                Map assistantMsg = [role: "assistant", toolCalls: toolCalls]
                messages.add(assistantMsg)
                if (threadId) persistThreadMessage(threadId, runId, assistantMsg)
                for (Map tc in toolCalls) {
                    String resultJson = dispatchTool(runId, stepSeq, tc)
                    Map toolMsg = [role: "tool", toolCallId: tc.id, content: resultJson]
                    messages.add(toolMsg)
                    if (threadId) persistThreadMessage(threadId, runId, toolMsg)
                }
            }
            return finishThread(result, runId, threadId, "AI_RUN_TRUNCATED", null)
        } catch (Throwable t) {
            logger.error("Agent run ${runId} failed", t)
            return finishThread(result, runId, threadId, "AI_RUN_FAILED", t.message)
        }
    }
```
(The body is the Phase 1 loop with thread persistence interleaved. If you prefer the smaller
diff, factor the shared loop into a private method taking a `Closure onMessage` that is a no-op
when `threadId == null`; either way the loop logic stays single-sourced — DRY.)

- [ ] **Step 2: Add the thread helper methods**

Append to `AgentRunner`:
```groovy
    /** Load persisted thread messages, in order, as a List of message Maps. */
    private List<Map> loadThreadMessages(String threadId) {
        List<Map> out = []
        for (EntityValue m in ec.entity.find("moqui.ai.AiThreadMessage")
                .condition("threadId", threadId).orderBy("messageSeqId").list()) {
            Map msg = [role: m.role, content: m.content]
            if (m.toolCallId) msg.toolCallId = m.toolCallId
            if (m.toolCalls) msg.toolCalls = new groovy.json.JsonSlurper().parseText(m.toolCalls as String)
            out.add(msg)
        }
        return out
    }

    /** Persist one message to the thread (own tx; guarded — never aborts the run). */
    private void persistThreadMessage(String threadId, String runId, Map msg) {
        String seqId = ec.entity.sequencedIdSecondary()   // see note in Step 3
        persist("create#moqui.ai.AiThreadMessage", [threadId: threadId, messageSeqId: seqId,
            role: msg.role, content: msg.content, toolCallId: msg.toolCallId,
            toolCalls: msg.toolCalls != null ? groovy.json.JsonOutput.toJson(msg.toolCalls) : null,
            agentRunId: runId, fromDate: ec.user.nowTimestamp])
    }

    /** finish() + thread lastActivityDate bump. */
    private Map finishThread(Map result, String runId, String threadId, String statusId, String errorText) {
        Map r = finish(result, runId, statusId, errorText)   // Phase 1 finish(): persists run update
        if (threadId) persist("update#moqui.ai.AiThread", [threadId: threadId, lastActivityDate: ec.user.nowTimestamp])
        return r
    }
```

- [ ] **Step 3: Resolve the message seq id approach**

`AiThreadMessage.messageSeqId` is a detail seqId scoped to the thread (practices guide §1.1:
detail PKs use `setSequencedIdSecondary()` per master). Confirm the exact Moqui call on first
compile — likely `ec.entity.makeValue("moqui.ai.AiThreadMessage").setSequencedIdSecondary()` on
the value before create, rather than a bare `ec.entity.sequencedIdSecondary()`. Adjust
`persistThreadMessage` to use the verified form (create the value, `setSequencedIdSecondary()`,
set fields, `.create()`), keeping the create in its own tx via the guarded `persist` pattern.

- [ ] **Step 4: Commit**

```bash
git add runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/AgentRunner.groovy
git commit -m "feat(moqui-ai): thread history replay + per-turn message persistence"
```

---

## Task 3: run#Agent threadId param + create#Thread service

**Files:**
- Modify: `runtime/component/moqui-ai/service/ai/AgentServices.xml`

- [ ] **Step 1: Add threadId to run#Agent and a create#Thread service**

Edit `service/ai/AgentServices.xml`. Add `threadId` to `run#Agent` in/out, pass it to the runner,
and add `create#Thread`:
```xml
    <!-- add to run#Agent <in-parameters>: -->
    <parameter name="threadId"><description>Optional. When set, prior turns are replayed and this turn is persisted.</description></parameter>
    <!-- add to run#Agent <out-parameters>: -->
    <parameter name="threadId"/>
    <!-- in run#Agent <actions>, change the runner call to pass threadId: -->
    <!--   def r = runner.run(agentName, userMessage, threadId)  ; threadId = r.threadId -->

    <service verb="create" noun="Thread" authenticate="true">
        <in-parameters>
            <parameter name="agentName" required="true"/>
            <parameter name="title"/>
        </in-parameters>
        <out-parameters><parameter name="threadId"/></out-parameters>
        <actions>
            <set field="threadId" from="ec.entity.sequencedIdPrimary('moqui.ai.AiThread', null, null)"/>
            <service-call name="create#moqui.ai.AiThread" in-map="[threadId: threadId, agentName: agentName,
                title: title, userId: ec.user.userId, fromDate: ec.user.nowTimestamp, statusId: 'AI_THREAD_ACTIVE']"/>
        </actions>
    </service>
```
The full updated `run#Agent` actions script:
```xml
        <actions>
            <script><![CDATA[
                def ai = ec.factory.getTool("AI", org.moqui.ai.AiToolFactory.class)
                def r = new org.moqui.ai.AgentRunner(ec, ai).run(agentName, userMessage, threadId)
                assistantMessage = r.assistantMessage; agentRunId = r.agentRunId; threadId = r.threadId
                tokensIn = r.tokensIn; tokensOut = r.tokensOut; iterations = r.iterations
                truncated = r.truncated; statusId = r.statusId
            ]]></script>
        </actions>
```

- [ ] **Step 2: Commit**

```bash
git add runtime/component/moqui-ai/service/ai/AgentServices.xml
git commit -m "feat(moqui-ai): run#Agent threadId param + create#Thread service"
```

---

## Task 4: Multi-turn replay test

**Files:**
- Create: `runtime/component/moqui-ai/src/test/groovy/AiThreadTests.groovy`
- Modify: `runtime/component/moqui-ai/src/test/groovy/MoquiSuite.groovy` (add `AiThreadTests.class` to `@SelectClasses`)

- [ ] **Step 1: Add AiThreadTests to the suite, then write the failing test**

`runtime/component/moqui-ai/src/test/groovy/AiThreadTests.groovy`:
```groovy
import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.provider.MockProvider

class AiThreadTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "ThreadAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "remember context", maxIterations: 5,
            statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
    }
    def cleanupSpec() {
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "ThreadAgent").deleteAll()
        ec.destroy()
    }
    def cleanup() { MockProvider.reset() }

    def "second turn replays the first turn's messages"() {
        given: "a thread"
        Map t = ec.service.sync().name("ai.AgentServices.create#Thread")
            .parameters([agentName: "ThreadAgent", title: "t1"]).call()
        String threadId = t.threadId

        when: "turn 1"
        MockProvider.enqueue([assistantText: "hi there", finishReason: "stop", tokensOut: 2L])
        ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "ThreadAgent", userMessage: "hello", threadId: threadId]).call()

        and: "turn 2"
        MockProvider.enqueue([assistantText: "yes, you said hello", finishReason: "stop", tokensOut: 4L])
        Map out2 = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "ThreadAgent", userMessage: "what did I say?", threadId: threadId]).call()

        then: "thread holds both turns' messages (user+assistant x2 = 4), and turn 2 replayed turn 1"
        out2.assistantMessage == "yes, you said hello"
        ec.entity.find("moqui.ai.AiThreadMessage").condition("threadId", threadId).count() == 4L
        // the second run's request must have included the first turn's messages (replay)
        ec.entity.find("moqui.ai.AiAgentRun").condition("threadId", threadId).count() == 2L

        cleanup:
        ec.entity.find("moqui.ai.AiThreadMessage").condition("threadId", threadId).deleteAll()
        ec.entity.find("moqui.ai.AiAgentRun").condition("threadId", threadId).deleteAll()
        ec.entity.find("moqui.ai.AiThread").condition("threadId", threadId).deleteAll()
    }

    def "no threadId behaves as a stateless single turn (Phase 1)"() {
        given: MockProvider.enqueue([assistantText: "stateless", finishReason: "stop"])
        when: Map out = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "ThreadAgent", userMessage: "hi"]).call()
        then:
        out.assistantMessage == "stateless"
        out.threadId == null
    }
}
```

- [ ] **Step 2: Run the suite to verify the new test fails, then implement Tasks 1–3, then green**

Run: `./gradlew :runtime:component:moqui-ai:test`
Expected: FAIL until Tasks 1–3 are implemented; then PASS.

- [ ] **Step 3: Commit**

```bash
git add runtime/component/moqui-ai/src/test/groovy/AiThreadTests.groovy \
        runtime/component/moqui-ai/src/test/groovy/MoquiSuite.groovy
git commit -m "test(moqui-ai): multi-turn thread replay + stateless fallback"
```

---

## Phase Done — Definition of Done
- A caller can `create#Thread`, then call `run#Agent` repeatedly with the `threadId`; each turn
  replays prior messages and persists its own.
- No `threadId` → Phase 1 stateless behavior, unchanged.
- `AiAgentRun.threadId` links runs to their thread (added via `extend-entity`, Phase 1 entity untouched).
- Thread statuses are `StatusItem` records; the suite is green.

## NOT in this phase
- **Long-history management** (token-budget-aware truncation or rolling summarization of old
  turns before replay). Replaying an unbounded history will eventually exceed context/cost —
  flag as the next thread follow-up; for now full replay is correct and simple.
- Thread listing/search UI (Phase 5), per-thread cost rollup (Phase 3).
- Concurrent-write protection on a single thread (two simultaneous runs on one threadId) — note
  as a known limitation; revisit if the UI enables it.
