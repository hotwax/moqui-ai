# Phase 2: Conversation Continuity — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a caller carry a multi-turn conversation by passing a `conversationId` to
`ai.AgentServices.run#Agent`. The framework persists each turn's messages to the conversation and
replays prior turns on the next call — the caller passes an ID, never the history.

**Architecture:** Adds two entities (`AiConversation`, `AiConversationMessage`) and extends `AiAgentRun`
with `conversationId` via `<extend-entity>` (never edit the Phase 1 entity in place). `AgentRunner.run`
gains an optional `conversationId`: when present it seeds the message list from persisted conversation
messages and appends the new turn's messages back. No `conversationId` → Phase 1 stateless behavior,
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
and conversation summarization/truncation for long histories (a Phase 2.x follow-up — see "NOT in this phase").

---

## File Structure (added/changed)

```
runtime/component/moqui-ai/
├── entity/AiConversationEntities.xml                     ← AiConversation, AiConversationMessage + extend AiAgentRun (Task 1)
├── data/AiConversationStatusData.xml                     ← AiConversationStatus StatusItems/Flow (Task 1)
├── src/main/groovy/org/moqui/ai/AgentRunner.groovy ← MODIFY: conversationId history replay + persist (Task 2)
├── service/ai/AgentServices.xml                    ← MODIFY: run#Agent gains conversationId; add create#Conversation (Task 3)
└── src/test/groovy/
    ├── MoquiSuite.groovy                           ← MODIFY: add AiConversationTests
    └── AiConversationTests.groovy                        ← Task 4 (multi-turn replay)
```

---

## Task 1: Conversation entities + status data

**Files:**
- Create: `runtime/component/moqui-ai/entity/AiConversationEntities.xml`
- Create: `runtime/component/moqui-ai/data/AiConversationStatusData.xml`
- Test: covered by `AiConversationTests` in Task 4 (no standalone entity test — keep the suite lean)

- [ ] **Step 1: Define the conversation entities + extend AiAgentRun**

`runtime/component/moqui-ai/entity/AiConversationEntities.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<entities xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/entity-definition-3.xsd">

    <entity entity-name="AiConversation" package="moqui.ai">
        <field name="conversationId" type="id" is-pk="true"/>
        <field name="agentName" type="id"/>
        <field name="userId" type="id"/>
        <field name="title" type="text-medium"/>
        <field name="fromDate" type="date-time"/>
        <field name="lastActivityDate" type="date-time"/>
        <field name="statusId" type="id"><description>StatusItem statusTypeId=AiConversationStatus: AI_CONV_ACTIVE | AI_CONV_CLOSED</description></field>
        <relationship type="one" related="moqui.ai.AiAgent" short-alias="agent"/>
        <relationship type="one" related="moqui.basic.StatusItem" short-alias="status"/>
    </entity>

    <!-- One persisted message per turn-part, in order, replayed on the next call. -->
    <entity entity-name="AiConversationMessage" package="moqui.ai">
        <field name="conversationId" type="id" is-pk="true"/>
        <field name="messageSeqId" type="id" is-pk="true"/>
        <field name="role" type="text-short"><description>system | user | assistant | tool</description></field>
        <field name="content" type="text-very-long"/>
        <field name="toolCalls" type="text-very-long"><description>JSON of List&lt;Map&gt; when an assistant turn requested tools</description></field>
        <field name="toolCallId" type="text-medium"><description>set when role = tool</description></field>
        <field name="agentRunId" type="id"><description>which run produced this message</description></field>
        <field name="fromDate" type="date-time"/>
        <relationship type="one" related="moqui.ai.AiConversation" short-alias="conversation"/>
    </entity>

    <!-- Link each run to its conversation WITHOUT editing the Phase 1 entity (practices guide §1.7). -->
    <extend-entity entity-name="AiAgentRun" package="moqui.ai">
        <field name="conversationId" type="id"/>
        <relationship type="one" related="moqui.ai.AiConversation" short-alias="conversation"/>
    </extend-entity>
</entities>
```

- [ ] **Step 2: Define conversation status data (install)**

`runtime/component/moqui-ai/data/AiConversationStatusData.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<entity-facade-xml type="install">
    <moqui.basic.StatusType statusTypeId="AiConversationStatus" description="AI Conversation Status"/>
    <moqui.basic.StatusItem statusId="AI_CONV_ACTIVE" statusTypeId="AiConversationStatus" sequenceNum="1" description="Active"/>
    <moqui.basic.StatusItem statusId="AI_CONV_CLOSED" statusTypeId="AiConversationStatus" sequenceNum="2" description="Closed"/>
    <moqui.basic.StatusFlow statusFlowId="AiConversationFlow" statusTypeId="AiConversationStatus" description="AI conversation lifecycle"/>
    <moqui.basic.StatusFlowTransition statusFlowId="AiConversationFlow" statusId="AI_CONV_ACTIVE" toStatusId="AI_CONV_CLOSED" transitionSequence="1" transitionName="Close"/>
    <moqui.basic.StatusFlowTransition statusFlowId="AiConversationFlow" statusId="AI_CONV_CLOSED" toStatusId="AI_CONV_ACTIVE" transitionSequence="2" transitionName="Reopen"/>
</entity-facade-xml>
```

- [ ] **Step 3: Commit**

```bash
git add runtime/component/moqui-ai/entity/AiConversationEntities.xml \
        runtime/component/moqui-ai/data/AiConversationStatusData.xml
git commit -m "feat(moqui-ai): conversation entities + extend AiAgentRun with conversationId"
```

---

## Task 2: Conversation history replay + persistence in AgentRunner

`AgentRunner.run` gains an optional `conversationId`. The change is additive: the Phase 1 single-turn
path is preserved when `conversationId` is null.

**Files:**
- Modify: `runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/AgentRunner.groovy`
- Test: `AiConversationTests` (Task 4)

- [ ] **Step 1: Add an overloaded run(agentName, userMessage, conversationId)**

In `AgentRunner.groovy`, keep the existing `Map run(String agentName, String userMessage)` as a
thin delegate and add the conversation-aware method. Replace the method signature block:
```groovy
    /** Phase 1 entry — stateless single turn. */
    Map run(String agentName, String userMessage) { return run(agentName, userMessage, null) }

    /** @param conversationId optional; when set, prior conversation messages are replayed and this turn's
     *  messages are persisted back to the conversation.
     *  @return runResult Map (see Phase 1 "Canonical Map shapes") + conversationId. */
    Map run(String agentName, String userMessage, String conversationId) {
        EntityValue agent = ec.entity.find("moqui.ai.AiAgent")
            .condition("agentName", agentName).useCache(true).one()
        if (agent == null) throw new IllegalArgumentException("Unknown agent: ${agentName}")

        int maxIter = (agent.maxIterations ?: 8) as int
        long maxTokens = (agent.maxTokens ?: 0L) as long
        int maxToolCalls = (agent.maxToolCallsPerTurn ?: 20) as int
        LlmProvider provider = ai.getProvider(agent.providerName as String)
        List<Map> toolSchemas = loadToolSchemas(agentName)

        String runId = ec.entity.sequencedIdPrimary("moqui.ai.AiAgentRun", null, null)
        Map result = [agentRunId: runId, conversationId: conversationId, assistantMessage: null,
                      tokensIn: 0L, tokensOut: 0L, iterations: 0, truncated: false, statusId: "AI_RUN_RUNNING"]
        persist("create#moqui.ai.AiAgentRun", [agentRunId: runId, agentName: agentName, conversationId: conversationId,
            userId: ec.user.userId, fromDate: ec.user.nowTimestamp, statusId: "AI_RUN_RUNNING",
            providerName: agent.providerName, modelName: agent.modelName, userMessage: userMessage])

        // ---- history replay: prior conversation messages, then this turn's user message ----
        List<Map> messages = conversationId ? loadConversationMessages(conversationId) : []
        messages.add([role: "user", content: userMessage])
        if (conversationId) persistConversationMessage(conversationId, runId, [role: "user", content: userMessage])

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
                    return finishConversation(result, runId, conversationId, "AI_RUN_ABORTED", "Per-run token ceiling exceeded")

                List toolCalls = (resp.toolCalls ?: []) as List
                if (!toolCalls) {
                    result.assistantMessage = resp.assistantText ?: ""
                    if (conversationId) persistConversationMessage(conversationId, runId, [role: "assistant", content: result.assistantMessage])
                    return finishConversation(result, runId, conversationId, "AI_RUN_COMPLETED", null)
                }
                if (toolCalls.size() > maxToolCalls)
                    return finishConversation(result, runId, conversationId, "AI_RUN_ABORTED", "Tool-calls-per-turn ceiling exceeded")

                Map assistantMsg = [role: "assistant", toolCalls: toolCalls]
                messages.add(assistantMsg)
                if (conversationId) persistConversationMessage(conversationId, runId, assistantMsg)
                for (Map tc in toolCalls) {
                    String resultJson = dispatchTool(runId, stepSeq, tc)
                    Map toolMsg = [role: "tool", toolCallId: tc.id, content: resultJson]
                    messages.add(toolMsg)
                    if (conversationId) persistConversationMessage(conversationId, runId, toolMsg)
                }
            }
            return finishConversation(result, runId, conversationId, "AI_RUN_TRUNCATED", null)
        } catch (Throwable t) {
            logger.error("Agent run ${runId} failed", t)
            return finishConversation(result, runId, conversationId, "AI_RUN_FAILED", t.message)
        }
    }
```
(The body is the Phase 1 loop with conversation persistence interleaved. If you prefer the smaller
diff, factor the shared loop into a private method taking a `Closure onMessage` that is a no-op
when `conversationId == null`; either way the loop logic stays single-sourced — DRY.)

- [ ] **Step 2: Add the conversation helper methods**

Append to `AgentRunner`:
```groovy
    /** Load persisted conversation messages, in order, as a List of message Maps. */
    private List<Map> loadConversationMessages(String conversationId) {
        List<Map> out = []
        for (EntityValue m in ec.entity.find("moqui.ai.AiConversationMessage")
                .condition("conversationId", conversationId).orderBy("messageSeqId").list()) {
            Map msg = [role: m.role, content: m.content]
            if (m.toolCallId) msg.toolCallId = m.toolCallId
            if (m.toolCalls) msg.toolCalls = new groovy.json.JsonSlurper().parseText(m.toolCalls as String)
            out.add(msg)
        }
        return out
    }

    /** Persist one message to the conversation (own tx; guarded — never aborts the run). */
    private void persistConversationMessage(String conversationId, String runId, Map msg) {
        String seqId = ec.entity.sequencedIdSecondary()   // see note in Step 3
        persist("create#moqui.ai.AiConversationMessage", [conversationId: conversationId, messageSeqId: seqId,
            role: msg.role, content: msg.content, toolCallId: msg.toolCallId,
            toolCalls: msg.toolCalls != null ? groovy.json.JsonOutput.toJson(msg.toolCalls) : null,
            agentRunId: runId, fromDate: ec.user.nowTimestamp])
    }

    /** finish() + conversation lastActivityDate bump. */
    private Map finishConversation(Map result, String runId, String conversationId, String statusId, String errorText) {
        Map r = finish(result, runId, statusId, errorText)   // Phase 1 finish(): persists run update
        if (conversationId) persist("update#moqui.ai.AiConversation", [conversationId: conversationId, lastActivityDate: ec.user.nowTimestamp])
        return r
    }
```

- [ ] **Step 3: Resolve the message seq id approach**

`AiConversationMessage.messageSeqId` is a detail seqId scoped to the conversation (practices guide §1.1:
detail PKs use `setSequencedIdSecondary()` per master). Confirm the exact Moqui call on first
compile — likely `ec.entity.makeValue("moqui.ai.AiConversationMessage").setSequencedIdSecondary()` on
the value before create, rather than a bare `ec.entity.sequencedIdSecondary()`. Adjust
`persistConversationMessage` to use the verified form (create the value, `setSequencedIdSecondary()`,
set fields, `.create()`), keeping the create in its own tx via the guarded `persist` pattern.

- [ ] **Step 4: Commit**

```bash
git add runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/AgentRunner.groovy
git commit -m "feat(moqui-ai): conversation history replay + per-turn message persistence"
```

---

## Task 3: run#Agent conversationId param + create#Conversation service

**Files:**
- Modify: `runtime/component/moqui-ai/service/ai/AgentServices.xml`

- [ ] **Step 1: Add conversationId to run#Agent and a create#Conversation service**

Edit `service/ai/AgentServices.xml`. Add `conversationId` to `run#Agent` in/out, pass it to the runner,
and add `create#Conversation`:
```xml
    <!-- add to run#Agent <in-parameters>: -->
    <parameter name="conversationId"><description>Optional. When set, prior turns are replayed and this turn is persisted.</description></parameter>
    <!-- add to run#Agent <out-parameters>: -->
    <parameter name="conversationId"/>
    <!-- in run#Agent <actions>, change the runner call to pass conversationId: -->
    <!--   def r = runner.run(agentName, userMessage, conversationId)  ; conversationId = r.conversationId -->

    <service verb="create" noun="Conversation" authenticate="true">
        <in-parameters>
            <parameter name="agentName" required="true"/>
            <parameter name="title"/>
        </in-parameters>
        <out-parameters><parameter name="conversationId"/></out-parameters>
        <actions>
            <set field="conversationId" from="ec.entity.sequencedIdPrimary('moqui.ai.AiConversation', null, null)"/>
            <service-call name="create#moqui.ai.AiConversation" in-map="[conversationId: conversationId, agentName: agentName,
                title: title, userId: ec.user.userId, fromDate: ec.user.nowTimestamp, statusId: 'AI_CONV_ACTIVE']"/>
        </actions>
    </service>
```
The full updated `run#Agent` actions script:
```xml
        <actions>
            <script><![CDATA[
                def ai = ec.factory.getTool("AI", org.moqui.ai.AiToolFactory.class)
                def r = new org.moqui.ai.AgentRunner(ec, ai).run(agentName, userMessage, conversationId)
                assistantMessage = r.assistantMessage; agentRunId = r.agentRunId; conversationId = r.conversationId
                tokensIn = r.tokensIn; tokensOut = r.tokensOut; iterations = r.iterations
                truncated = r.truncated; statusId = r.statusId
            ]]></script>
        </actions>
```

- [ ] **Step 2: Commit**

```bash
git add runtime/component/moqui-ai/service/ai/AgentServices.xml
git commit -m "feat(moqui-ai): run#Agent conversationId param + create#Conversation service"
```

---

## Task 4: Multi-turn replay test

**Files:**
- Create: `runtime/component/moqui-ai/src/test/groovy/AiConversationTests.groovy`
- Modify: `runtime/component/moqui-ai/src/test/groovy/MoquiSuite.groovy` (add `AiConversationTests.class` to `@SelectClasses`)

- [ ] **Step 1: Add AiConversationTests to the suite, then write the failing test**

`runtime/component/moqui-ai/src/test/groovy/AiConversationTests.groovy`:
```groovy
import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.provider.MockProvider

class AiConversationTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "ConversationAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "remember context", maxIterations: 5,
            statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
    }
    def cleanupSpec() {
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "ConversationAgent").deleteAll()
        ec.destroy()
    }
    def cleanup() { MockProvider.reset() }

    def "second turn replays the first turn's messages"() {
        given: "a conversation"
        Map t = ec.service.sync().name("ai.AgentServices.create#Conversation")
            .parameters([agentName: "ConversationAgent", title: "t1"]).call()
        String conversationId = t.conversationId

        when: "turn 1"
        MockProvider.enqueue([assistantText: "hi there", finishReason: "stop", tokensOut: 2L])
        ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "ConversationAgent", userMessage: "hello", conversationId: conversationId]).call()

        and: "turn 2"
        MockProvider.enqueue([assistantText: "yes, you said hello", finishReason: "stop", tokensOut: 4L])
        Map out2 = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "ConversationAgent", userMessage: "what did I say?", conversationId: conversationId]).call()

        then: "conversation holds both turns' messages (user+assistant x2 = 4), and turn 2 replayed turn 1"
        out2.assistantMessage == "yes, you said hello"
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", conversationId).count() == 4L
        // the second run's request must have included the first turn's messages (replay)
        ec.entity.find("moqui.ai.AiAgentRun").condition("conversationId", conversationId).count() == 2L

        cleanup:
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", conversationId).deleteAll()
        ec.entity.find("moqui.ai.AiAgentRun").condition("conversationId", conversationId).deleteAll()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", conversationId).deleteAll()
    }

    def "no conversationId behaves as a stateless single turn (Phase 1)"() {
        given: MockProvider.enqueue([assistantText: "stateless", finishReason: "stop"])
        when: Map out = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "ConversationAgent", userMessage: "hi"]).call()
        then:
        out.assistantMessage == "stateless"
        out.conversationId == null
    }
}
```

- [ ] **Step 2: Run the suite to verify the new test fails, then implement Tasks 1–3, then green**

Run: `./gradlew :runtime:component:moqui-ai:test`
Expected: FAIL until Tasks 1–3 are implemented; then PASS.

- [ ] **Step 3: Commit**

```bash
git add runtime/component/moqui-ai/src/test/groovy/AiConversationTests.groovy \
        runtime/component/moqui-ai/src/test/groovy/MoquiSuite.groovy
git commit -m "test(moqui-ai): multi-turn conversation replay + stateless fallback"
```

---

## Phase Done — Definition of Done
- A caller can `create#Conversation`, then call `run#Agent` repeatedly with the `conversationId`; each turn
  replays prior messages and persists its own.
- No `conversationId` → Phase 1 stateless behavior, unchanged.
- `AiAgentRun.conversationId` links runs to their conversation (added via `extend-entity`, Phase 1 entity untouched).
- Conversation statuses are `StatusItem` records; the suite is green.

## NOT in this phase
- **Long-history management** (token-budget-aware truncation or rolling summarization of old
  turns before replay). Replaying an unbounded history will eventually exceed context/cost —
  flag as the next conversation follow-up; for now full replay is correct and simple.
- Conversation listing/search UI (Phase 5), per-conversation cost rollup (Phase 3).
- Concurrent-write protection on a single conversation (two simultaneous runs on one conversationId) — note
  as a known limitation; revisit if the UI enables it.
