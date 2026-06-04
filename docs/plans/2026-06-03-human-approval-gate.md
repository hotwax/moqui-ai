# Human Approval Gate Implementation Plan (reconciled)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When an agent proposes a tool call marked `requiresApproval`, the run **suspends** instead of executing — it persists its loop state + a pending-approval record and returns — and **resumes** only after a human approves or rejects via a service.

**Architecture:** The loop body of `AgentRunner.run()` is extracted into a private `continueAgent(...)` that both `run` (fresh) and `resume` (after a decision) call — single-sourced. On a turn proposing any `requiresApproval` tool, `continueAgent` records one `AiToolApproval` per gated call, serializes the full loop state to `AiAgentRun.pendingState` (JSON), sets `AI_RUN_SUSPENDED`, and returns. `approve#`/`reject#ToolCall` record decisions; when the last pending one for a run is decided, `resume()` rehydrates the state, executes the turn (approved → dispatch; rejected → a denial result fed back to the model), and continues the loop. Whole-turn granularity. Default behavior (no gated tools) is unchanged.

**Tech Stack:** Groovy 3 / JDK 11, Moqui (HotWax fork), Spock + JUnit Platform Suite, H2 dev DB.

---

## Implements
GitHub issue **#11** (Phase 4: Human approval gate). **Supersedes** the stale `docs/plans/2026-06-02-phase4-human-approval.md` (written against the original loop; this is reconciled to the current loop with failover candidates, context assembly/compaction, the `remember` tool, and the richer result Map).

## Locked decisions (2026-06-03)
- **Reject → feed a denial result back; the agent continues** (not abort).
- **Whole-turn suspend granularity** — if any call in a turn is gated, suspend the whole turn; nothing in it runs until every gated call is decided, then all execute together.
- **Serialize loop state** into `pendingState` (works for stateless and conversation-backed runs uniformly).
- `requiresApproval` already flows through the tool catalog (`DefinitionLoader` parses `requires-approval="true"`) — no plumbing.
- `AiAgentRun.pendingState` added **directly** to the entity (we own it; not `extend-entity`).
- Statuses via `StatusItem`/`StatusFlowTransition` (install data), per the practices guide.

## Conventions (same as prior work)
- Tests: Spock `*Tests.groovy` in `src/test/groovy/`, registered in `MoquiSuite.groovy`. New class `AiApprovalTests` MUST be added to the suite.
- Run suite from moqui root `/Users/anilpatel/maarg-sd/moqui`:
  ```bash
  source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
  ```
  Live tests are key-gated; both accounts funded — run for real, don't skip.
- New entity/fields/statuses just need the boot the test triggers (Moqui auto-adds columns; tests load `data/AiStatusData.xml`).
- Commit **inside the component repo** (`runtime/component/moqui-ai`, branch `feature/ai-agent-framework`).

## Resume-state contract (the crux — get this right)
`pendingState` is JSON of the loop's mutable state at suspend:
`{ messages, replayCount, stepSeq, candIdx, summaryText, summaryWatermark, result, turnToolCalls }`
- `messages` — the working list (includes the just-recorded assistant tool-call turn). JSON-round-trips (maps/lists/strings).
- `replayCount` — the windowing split point (prior-turn prefix vs current turn).
- `stepSeq`, `candIdx` (sticky failover index) — ints.
- `summaryText`/`summaryWatermark` — compaction state.
- `result` — the run-result Map (tokensIn/Out, iterations, providerRunId, structuredResult, servedProviderName, servedByModelId, estimatedCost, assistantMessage, conversationId, agentRunId).
- `turnToolCalls` — the gated turn's full tool-call list (executed on resume).
Static config (maxIter, ctxOn, candidates, toolSchemas, responseSchema) is **reloaded from the agent** in both `run` and `resume`, never serialized.

---

## File Structure

| File | Change |
|---|---|
| `entity/AiApprovalEntities.xml` | New — `AiToolApproval` |
| `entity/AiEntities.xml` | `AiAgentRun` gains `pendingState` (text-very-long) |
| `data/AiStatusData.xml` | Add `AI_RUN_SUSPENDED` + `AiApprovalStatus` items + flow transitions |
| `src/main/groovy/org/moqui/ai/AgentRunner.groovy` | Extract `continueAgent`; approval gate (suspend); `resume()` |
| `service/ai/ApprovalServices.xml` | New — `approve#`/`reject#`/`decide#ToolCall`, `get#PendingApproval` |
| `src/test/groovy/AiApprovalTests.groovy` | New — suspend/approve/reject/resume flow |
| `src/test/groovy/MoquiSuite.groovy` | Register `AiApprovalTests` |

---

## Task 1: Approval entity + `pendingState` field + statuses

**Files:**
- Create: `entity/AiApprovalEntities.xml`
- Modify: `entity/AiEntities.xml`, `data/AiStatusData.xml`
- Test: `src/test/groovy/AiEntitiesTests.groovy`

- [ ] **Step 1: Write a failing entity + status round-trip test**

In `src/test/groovy/AiEntitiesTests.groovy`, add:
```groovy
    def "AiToolApproval + AiAgentRun.pendingState + AI_RUN_SUSPENDED status exist"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        ec.entity.makeValue("moqui.ai.AiAgentRun").setAll([agentRunId: "RUNAPPR1", agentName: "A",
            statusId: "AI_RUN_SUSPENDED", pendingState: '{"messages":[]}', fromDate: ec.user.nowTimestamp]).create()
        ec.entity.makeValue("moqui.ai.AiToolApproval").setAll([approvalId: "APPR1", agentRunId: "RUNAPPR1",
            toolCallId: "c1", toolName: "x", arguments: "{}", statusId: "AI_APPR_PENDING",
            requestedDate: ec.user.nowTimestamp]).create()
        when:
        def run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", "RUNAPPR1").one()
        def appr = ec.entity.find("moqui.ai.AiToolApproval").condition("approvalId", "APPR1").one()
        def st = ec.entity.find("moqui.basic.StatusItem").condition("statusId", "AI_RUN_SUSPENDED").one()
        then:
        run.pendingState == '{"messages":[]}'
        appr.statusId == "AI_APPR_PENDING"
        st != null
        cleanup:
        ec.entity.find("moqui.ai.AiToolApproval").condition("approvalId", "APPR1").deleteAll()
        ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", "RUNAPPR1").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```

- [ ] **Step 2: Run to confirm it fails**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — `AiToolApproval`/`pendingState`/`AI_RUN_SUSPENDED` not defined.

- [ ] **Step 3: Create the approval entity**

`entity/AiApprovalEntities.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<entities xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/entity-definition-3.xsd">

    <!-- One pending decision per approval-required tool call in a suspended turn. -->
    <entity entity-name="AiToolApproval" package="moqui.ai">
        <field name="approvalId" type="id" is-pk="true"/>
        <field name="agentRunId" type="id"/>
        <field name="stepSeqId" type="id"/>
        <field name="toolCallId" type="id"/>
        <field name="toolName" type="text-medium"/>
        <field name="serviceName" type="text-medium"/>
        <field name="arguments" type="text-very-long"><description>JSON of the proposed call args</description></field>
        <field name="statusId" type="id"><description>StatusItem statusTypeId=AiApprovalStatus: AI_APPR_PENDING|AI_APPR_APPROVED|AI_APPR_REJECTED</description></field>
        <field name="requestedByUserId" type="id"/>
        <field name="requestedDate" type="date-time"/>
        <field name="decidedByUserId" type="id"/>
        <field name="decidedDate" type="date-time"/>
        <field name="decisionNote" type="text-long"/>
        <!-- one-nofk: append-only audit; must not block the run/agent lifecycle (mirrors AiAgentRun) -->
        <relationship type="one-nofk" related="moqui.ai.AiAgentRun" short-alias="run"/>
        <relationship type="one" related="moqui.basic.StatusItem" short-alias="status"/>
    </entity>
</entities>
```

- [ ] **Step 4: Add `pendingState` to `AiAgentRun`**

In `entity/AiEntities.xml`, inside `<entity entity-name="AiAgentRun" ...>`, add after `errorText`:
```xml
        <field name="pendingState" type="text-very-long"><description>Phase 4: JSON loop state when AI_RUN_SUSPENDED (messages, replayCount, stepSeq, candIdx, summary, result, turnToolCalls)</description></field>
```

- [ ] **Step 5: Add statuses + transitions**

In `data/AiStatusData.xml`, READ it to match the existing format (it already defines `AiAgentRunStatus` items + the `AiAgentRunFlow`). Add the suspended run status, the approval status type/items, and flow transitions (use `StatusFlowTransition`, never `StatusValidChange`):
```xml
    <moqui.basic.StatusItem statusId="AI_RUN_SUSPENDED" statusTypeId="AiAgentRunStatus" sequenceNum="60" description="Suspended (awaiting approval)"/>
    <moqui.basic.StatusFlowTransition statusFlowId="AiAgentRunFlow" statusId="AI_RUN_RUNNING"   toStatusId="AI_RUN_SUSPENDED" transitionSequence="50" transitionName="Suspend"/>
    <moqui.basic.StatusFlowTransition statusFlowId="AiAgentRunFlow" statusId="AI_RUN_SUSPENDED" toStatusId="AI_RUN_RUNNING"   transitionSequence="51" transitionName="Resume"/>

    <moqui.basic.StatusType statusTypeId="AiApprovalStatus" description="AI Tool Approval Status"/>
    <moqui.basic.StatusItem statusId="AI_APPR_PENDING"  statusTypeId="AiApprovalStatus" sequenceNum="1" description="Pending"/>
    <moqui.basic.StatusItem statusId="AI_APPR_APPROVED" statusTypeId="AiApprovalStatus" sequenceNum="2" description="Approved"/>
    <moqui.basic.StatusItem statusId="AI_APPR_REJECTED" statusTypeId="AiApprovalStatus" sequenceNum="3" description="Rejected"/>
    <moqui.basic.StatusFlow statusFlowId="AiApprovalFlow" statusTypeId="AiApprovalStatus" description="Approval decision"/>
    <moqui.basic.StatusFlowTransition statusFlowId="AiApprovalFlow" statusId="AI_APPR_PENDING" toStatusId="AI_APPR_APPROVED" transitionSequence="1" transitionName="Approve"/>
    <moqui.basic.StatusFlowTransition statusFlowId="AiApprovalFlow" statusId="AI_APPR_PENDING" toStatusId="AI_APPR_REJECTED" transitionSequence="2" transitionName="Reject"/>
```
> Match the existing element style/namespacing in `AiStatusData.xml`. If the run-status type id or flow id differ from `AiAgentRunStatus`/`AiAgentRunFlow`, use the file's actual ids. `sequenceNum`/`transitionSequence` values just need to not collide with existing ones.

- [ ] **Step 6: Run — passes**; then commit

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS.
```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add entity/AiApprovalEntities.xml entity/AiEntities.xml data/AiStatusData.xml src/test/groovy/AiEntitiesTests.groovy && \
git commit -m "feat(ai): AiToolApproval entity + AiAgentRun.pendingState + suspended/approval statuses (#11)"
```

---

## Task 2: Extract `continueAgent` + the suspend gate

**Files:**
- Modify: `src/main/groovy/org/moqui/ai/AgentRunner.groovy`
- Test: `src/test/groovy/AiApprovalTests.groovy` (new), `src/test/groovy/MoquiSuite.groovy`

- [ ] **Step 1: Write a failing suspend test + register the new test class**

Create `src/test/groovy/AiApprovalTests.groovy` (mirror the integration scaffolding of `AgentRunnerTests`/`AiContextTests` — `@Shared ec`, the `ai` AiToolFactory handle, `internalLoginUser`, `setupSpec`/`cleanupSpec`; READ a sibling for the exact idiom). The agent grants a tool whose catalog entry is `requires-approval`. The test catalog tool `get#Echo` is currently NOT gated, so register a gated one for the test via the catalog. Simplest: add a gated test tool file OR mark via the catalog API. Use this approach — add a second test tools file and have the test agent grant it:

First create `ai/approval-test.tools.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<tools>
    <tool service="moqui.ai.test.TestServices.get#Echo" description="echo (approval-gated)" requires-approval="true"/>
</tools>
```
> NOTE: the catalog is keyed by serviceName; `get#Echo` is already registered (ungated) by `ai/test.tools.xml`. Two entries for the same serviceName collide. To avoid that, instead add a NEW gated test service. Add to `service/moqui/ai/test/TestServices.xml` a second echo verb `get#GatedEcho` (copy `get#Echo`'s in/out params + body), and reference THAT in `ai/approval-test.tools.xml` (`service="moqui.ai.test.TestServices.get#GatedEcho"`). READ `service/moqui/ai/test/TestServices.xml` + `ai/test.tools.xml` to copy the exact shapes.

Then the test:
```groovy
    def "a requiresApproval tool suspends the run before executing"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
        org.moqui.ai.provider.MockProvider.reset()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "ApprAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgentTool").setAll([agentName: "ApprAgent",
            toolName: "moqui.ai.test.TestServices.get#GatedEcho"]).createOrUpdate()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "c1", name: "moqui.ai.test.TestServices.get#GatedEcho", arguments: [text: "do it"]]], tokensIn: 1L, tokensOut: 1L])
        MockProvider.enqueue([assistantText: "done after approval", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        when:
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent").parameters([agentName: "ApprAgent", userMessage: "go"]).call()
        then:
        out.statusId == "AI_RUN_SUSPENDED"
        out.awaitingApproval == true
        (out.approvalIds as List).size() == 1
        // nothing executed yet: no successful AiToolCall for this run
        ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId).list().isEmpty()
        // pending approval recorded + pendingState persisted
        ec.entity.find("moqui.ai.AiToolApproval").condition("agentRunId", out.agentRunId).condition("statusId", "AI_APPR_PENDING").list().size() == 1
        ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one().pendingState != null
        cleanup:
        ec.entity.find("moqui.ai.AiToolApproval").condition("agentRunId", out.agentRunId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "ApprAgent").deleteAll()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentName", "ApprAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```
Add `MockProvider` import + `AiApprovalTests.class` to `MoquiSuite`. Also add `run#Agent` out-parameters `awaitingApproval` (Boolean) + `approvalIds` (List) in `service/ai/AgentServices.xml` and map them from the runner result (so the suspend result surfaces; they're absent/null on normal completion).

- [ ] **Step 2: Run to confirm it fails**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — no suspend behavior (the gated tool dispatches normally; status COMPLETED).

- [ ] **Step 3: Extract `continueAgent` + add the gate**

In `AgentRunner.groovy`, refactor: move the `int stepSeq = 0; int candIdx = 0; try { for (...) { ... } ... }` block out of `run()` into a new private method. `run()` keeps all setup through building `messages`/`replayCount`/`result`, then calls `continueAgent`. The loop body is unchanged EXCEPT the tool-dispatch section gains the approval gate.

Add the new constant after `REMEMBER_TOOL`:
```groovy
    private static final String GATE = "requiresApproval"   // marker only; tool flag is td.requiresApproval
```
(Optional — you can read `ai.getTool(name)?.requiresApproval` directly; no constant needed. Skip if you prefer.)

Replace the end of `run()` (from `int stepSeq = 0` through the closing of the try/catch) with a call:
```groovy
        return continueAgent(agent, runId, conversationId, candidates, toolSchemas, responseSchema,
            [messages: messages, replayCount: replayCount, stepSeq: 0, candIdx: 0,
             summaryText: summaryText, summaryWatermark: summaryWatermark, result: result])
```
Add the `continueAgent` method (the former loop, parameterized; re-derives config from `agent`; the dispatch section gains the gate). Place it right after `run()`:
```groovy
    /** Runs the agentic loop from the given state until COMPLETED/TRUNCATED/ABORTED/FAILED or
     *  SUSPENDED (a turn proposed a requiresApproval tool). Shared by run() (fresh) and resume(). */
    private Map continueAgent(EntityValue agent, String runId, String conversationId, List<Map> candidates,
            List<Map> toolSchemas, Map responseSchema, Map st) {
        int maxIter = (agent.maxIterations ?: 8) as int
        long maxTokens = (agent.maxTokens ?: 0L) as long
        int maxToolCalls = (agent.maxToolCallsPerTurn ?: 20) as int
        boolean ctxSummarize = (agent.contextStrategy == "summarize")
        boolean ctxOn = (agent.contextStrategy == "window") || ctxSummarize
        int ctxMsgs = (agent.contextWindowMessages ?: 20) as int
        int ctxChars = (agent.contextWindowChars ?: 48000) as int
        Map primary = candidates[0]

        List<Map> messages = st.messages as List<Map>
        int replayCount = st.replayCount as int
        int stepSeq = st.stepSeq as int
        int candIdx = st.candIdx as int
        String summaryText = st.summaryText as String
        String summaryWatermark = st.summaryWatermark as String
        Map result = st.result as Map
        try {
            for (int i = result.iterations as int; i < maxIter; i++) {
                result.iterations = i + 1
                String sysCtx = agent.systemPrompt as String
                List<Map> sendMessages = messages
                if (ctxOn) {
                    int rc = Math.min(replayCount, messages.size())
                    Map asm = ContextAssembler.windowHistory(messages.subList(0, rc),
                        messages.subList(rc, messages.size()), ctxMsgs, ctxChars)
                    sendMessages = asm.messages as List<Map>
                    List<Map> droppedMsgs = (asm.droppedMessages ?: []) as List<Map>
                    String dropThru = droppedMsgs ? (droppedMsgs.last().messageSeqId as String) : null
                    if (ctxSummarize && droppedMsgs && (summaryWatermark == null || dropThru > summaryWatermark)) {
                        String rolled = summarizeOverflow(primary, summaryText, droppedMsgs, result)
                        if (rolled != null) {
                            summaryText = rolled; summaryWatermark = dropThru
                            persist("update#moqui.ai.AiConversation", [conversationId: conversationId,
                                summaryText: summaryText, summaryThruMessageSeqId: summaryWatermark])
                        }
                    }
                    sysCtx = ContextAssembler.withFacts(ContextAssembler.withSummary(sysCtx, summaryText), loadFacts(conversationId))
                    if ((asm.dropped as int) > 0) {
                        stepSeq++
                        persist("create#moqui.ai.AiAgentRunStep", [agentRunId: runId, stepSeqId: stepSeq as String,
                            stepType: ctxSummarize ? "compaction" : "context_trim", finishReason: "dropped:${asm.dropped}" as String])
                    }
                }
                Map call = callWithFailover(candidates, candIdx,
                        [systemContext: sysCtx, messages: sendMessages, tools: toolSchemas, responseSchema: responseSchema], runId)
                candIdx = call.idx as int
                result.servedProviderName = call.providerName; result.servedByModelId = call.modelName
                for (Map fa in (call.failedAttempts as List<Map>)) {
                    stepSeq++
                    persist("create#moqui.ai.AiAgentRunStep", [agentRunId: runId, stepSeqId: stepSeq as String,
                        stepType: "llm_call_failed", finishReason: "provider_error:${fa.providerName}:${fa.modelName}" as String])
                }
                Map resp = call.resp as Map
                long inTok = (resp.tokensIn ?: 0L) as long, outTok = (resp.tokensOut ?: 0L) as long
                result.tokensIn += inTok; result.tokensOut += outTok
                if (resp.providerRunId) result.providerRunId = resp.providerRunId
                if (resp.structuredResult != null) result.structuredResult = resp.structuredResult
                stepSeq++
                persist("create#moqui.ai.AiAgentRunStep", [agentRunId: runId, stepSeqId: stepSeq as String,
                    stepType: "llm_call", tokensIn: inTok, tokensOut: outTok, finishReason: resp.finishReason])

                if (maxTokens > 0 && ((result.tokensIn as long) + (result.tokensOut as long)) > maxTokens)
                    return finish(result, runId, conversationId, "AI_RUN_ABORTED", "Per-run token ceiling exceeded")

                List toolCalls = (resp.toolCalls ?: []) as List
                if (!toolCalls) {
                    result.assistantMessage = resp.assistantText ?:
                        (result.structuredResult != null ? JsonOutput.toJson(result.structuredResult) : "")
                    if (conversationId) persistConversationMessage(conversationId, runId, [role: "assistant", content: result.assistantMessage])
                    return finish(result, runId, conversationId, "AI_RUN_COMPLETED", null)
                }
                if (toolCalls.size() > maxToolCalls)
                    return finish(result, runId, conversationId, "AI_RUN_ABORTED", "Tool-calls-per-turn ceiling exceeded")

                Map assistantTurn = [role: "assistant", toolCalls: toolCalls]
                messages.add(assistantTurn)
                if (conversationId) persistConversationMessage(conversationId, runId, assistantTurn)

                // ----- approval gate: if any call this turn needs approval, SUSPEND the whole turn -----
                List<Map> needApproval = (toolCalls as List<Map>).findAll { ai.getTool(it.name as String)?.requiresApproval }
                if (needApproval) {
                    List<String> approvalIds = []
                    for (Map tc in needApproval) {
                        String approvalId = ec.entity.sequencedIdPrimary("moqui.ai.AiToolApproval", null, null)
                        approvalIds.add(approvalId)
                        Map td = ai.getTool(tc.name as String)
                        persist("create#moqui.ai.AiToolApproval", [approvalId: approvalId, agentRunId: runId,
                            stepSeqId: stepSeq as String, toolCallId: tc.id, toolName: tc.name, serviceName: td?.serviceName,
                            arguments: JsonOutput.toJson(tc.arguments ?: [:]), statusId: "AI_APPR_PENDING",
                            requestedByUserId: ec.user.userId, requestedDate: ec.user.nowTimestamp])
                    }
                    persist("update#moqui.ai.AiAgentRun", [agentRunId: runId, statusId: "AI_RUN_SUSPENDED",
                        pendingState: JsonOutput.toJson([messages: messages, replayCount: replayCount, stepSeq: stepSeq,
                            candIdx: candIdx, summaryText: summaryText, summaryWatermark: summaryWatermark,
                            result: result, turnToolCalls: toolCalls])])
                    result.statusId = "AI_RUN_SUSPENDED"; result.awaitingApproval = true; result.approvalIds = approvalIds
                    return result
                }

                for (Map tc in toolCalls) {
                    String resultJson = (ctxOn && tc.name == REMEMBER_TOOL) ?
                        rememberFact(runId, stepSeq, conversationId, tc) : dispatchTool(runId, stepSeq, tc)
                    Map toolMsg = [role: "tool", toolCallId: tc.id, content: resultJson]
                    messages.add(toolMsg)
                    if (conversationId) persistConversationMessage(conversationId, runId, toolMsg)
                }
            }
            return finish(result, runId, conversationId, "AI_RUN_TRUNCATED", null)
        } catch (Throwable t) {
            logger.error("Agent run ${runId} failed", t)
            return finish(result, runId, conversationId, "AI_RUN_FAILED", t.message)
        }
    }
```

- [ ] **Step 4: Run — suspend test passes, all prior green**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS. Suspend test green; every existing test (no gated tools) still green — `continueAgent` is the same loop, just relocated. The new `run#Agent` out-params are null on normal completion.

- [ ] **Step 5: Commit**

```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add src/main/groovy/org/moqui/ai/AgentRunner.groovy service/ai/AgentServices.xml \
        service/moqui/ai/test/TestServices.xml ai/approval-test.tools.xml \
        src/test/groovy/AiApprovalTests.groovy src/test/groovy/MoquiSuite.groovy && \
git commit -m "feat(ai): extract continueAgent + suspend run on requiresApproval tool (#11)"
```

---

## Task 3: `resume()` after a decision

**Files:**
- Modify: `src/main/groovy/org/moqui/ai/AgentRunner.groovy`
- Test: `src/test/groovy/AiApprovalTests.groovy`

- [ ] **Step 1: Write a failing resume test (call resume directly)**

In `AiApprovalTests.groovy`, add a test that suspends, marks the approval APPROVED directly, then calls `resume` and expects completion + the gated tool executed:
```groovy
    def "resume after approval executes the gated call and completes"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
        org.moqui.ai.provider.MockProvider.reset()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "ApprAgent2", providerName: "mock",
            modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgentTool").setAll([agentName: "ApprAgent2",
            toolName: "moqui.ai.test.TestServices.get#GatedEcho"]).createOrUpdate()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "c1", name: "moqui.ai.test.TestServices.get#GatedEcho", arguments: [text: "hi"]]], tokensIn: 1L, tokensOut: 1L])
        MockProvider.enqueue([assistantText: "done after approval", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent").parameters([agentName: "ApprAgent2", userMessage: "go"]).call()
        // mark the approval APPROVED (the service layer is Task 4; here we set it directly)
        ec.entity.find("moqui.ai.AiToolApproval").condition("agentRunId", out.agentRunId).updateAll([statusId: "AI_APPR_APPROVED", decidedByUserId: "AiTestUser"])
        when:
        Map r = new org.moqui.ai.AgentRunner(ec, ai).resume(out.agentRunId as String)
        EntityValue run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()
        then:
        r.statusId == "AI_RUN_COMPLETED"
        run.assistantMessage == "done after approval"
        run.pendingState == null
        // the gated tool actually executed (a successful AiToolCall for it)
        ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId).condition("toolCallId", "c1").one().success == "Y"
        cleanup:
        ec.entity.find("moqui.ai.AiToolApproval").condition("agentRunId", out.agentRunId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "ApprAgent2").deleteAll()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentName", "ApprAgent2").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```

- [ ] **Step 2: Run to confirm it fails**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — `resume` doesn't exist.

- [ ] **Step 3: Implement `resume`**

Add to `AgentRunner` (public, after `continueAgent`):
```groovy
    /** Resume a suspended run once its approvals are decided: execute the gated turn per each
     *  decision (approved/non-gated → dispatch; rejected → a denial result the model can react to),
     *  then continue the loop. Returns the run result. No-op (returns current status) if not suspended. */
    Map resume(String agentRunId) {
        EntityValue run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", agentRunId).one()
        if (run == null) throw new IllegalArgumentException("Unknown run: ${agentRunId}")
        if (run.statusId != "AI_RUN_SUSPENDED") return [agentRunId: agentRunId, statusId: run.statusId]

        EntityValue agent = ec.entity.find("moqui.ai.AiAgent").condition("agentName", run.agentName).useCache(true).one()
        String conversationId = run.conversationId
        boolean ctxOn = (agent.contextStrategy == "window") || (agent.contextStrategy == "summarize")
        List<Map> candidates = loadModelCandidates(run.agentName as String, agent)
        List<Map> toolSchemas = loadToolSchemas(run.agentName as String)
        if (ctxOn && conversationId) toolSchemas = toolSchemas + [[name: REMEMBER_TOOL,
            description: "Record a durable, confirmed value (e.g. a confirmed order total, address, or decision) so it is never lost from context. Call this the moment you confirm a value that must persist across the conversation.",
            parameters: [type: "object", required: ["factKey", "factValue"], properties: [
                factKey: [type: "string", description: "short stable identifier, e.g. order_total"],
                factValue: [type: "string", description: "the confirmed value"]]]]]
        Map responseSchema = agent.responseSchema ?
            new groovy.json.JsonSlurper().parseText(agent.responseSchema as String) as Map : null

        Map st = new groovy.json.JsonSlurper().parseText(run.pendingState as String) as Map
        List<Map> messages = st.messages as List<Map>
        int stepSeq = st.stepSeq as int
        List<Map> turnToolCalls = st.turnToolCalls as List<Map>

        Map<String, EntityValue> approvals = [:]
        for (EntityValue a in ec.entity.find("moqui.ai.AiToolApproval").condition("agentRunId", agentRunId).list())
            approvals.put(a.toolCallId as String, a)

        // execute the suspended turn per decision, appending tool-result messages
        for (Map tc in turnToolCalls) {
            EntityValue appr = approvals.get(tc.id as String)
            String resultJson
            if (appr != null && appr.statusId == "AI_APPR_REJECTED") {
                resultJson = JsonOutput.toJson([error: "Denied by user${appr.decisionNote ? ': ' + appr.decisionNote : ''}"])
                persist("create#moqui.ai.AiToolCall", [agentRunId: agentRunId, stepSeqId: stepSeq as String,
                    toolCallId: tc.id, toolName: tc.name, serviceName: ai.getTool(tc.name as String)?.serviceName,
                    arguments: JsonOutput.toJson(tc.arguments ?: [:]), result: resultJson, success: "N",
                    errorText: "rejected", durationMs: 0])
            } else {
                resultJson = (ctxOn && tc.name == REMEMBER_TOOL) ?
                    rememberFact(agentRunId, stepSeq, conversationId, tc) : dispatchTool(agentRunId, stepSeq, tc)
            }
            Map toolMsg = [role: "tool", toolCallId: tc.id, content: resultJson]
            messages.add(toolMsg)
            if (conversationId) persistConversationMessage(conversationId, agentRunId, toolMsg)
        }

        // rehydrate the result Map (keys come back from JSON; ints/longs need care)
        Map result = st.result as Map
        result.tokensIn = (result.tokensIn ?: 0L) as long
        result.tokensOut = (result.tokensOut ?: 0L) as long
        result.iterations = (result.iterations ?: 0) as int
        result.estimatedCost = 0G   // recomputed in finish()
        persist("update#moqui.ai.AiAgentRun", [agentRunId: agentRunId, statusId: "AI_RUN_RUNNING", pendingState: null])
        return continueAgent(agent, agentRunId, conversationId, candidates, toolSchemas, responseSchema,
            [messages: messages, replayCount: st.replayCount as int, stepSeq: stepSeq, candIdx: st.candIdx as int,
             summaryText: st.summaryText as String, summaryWatermark: st.summaryWatermark as String, result: result])
    }
```
> The gated turn's tool results are appended to `messages` here; `continueAgent` then starts its loop at `result.iterations` — the next `provider.chat` responds to those tool results. The gated turn's assistant message is already in `messages` (recorded before suspend) and is not re-added.

- [ ] **Step 4: Run — resume test passes**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS — the gated call executes on resume; the run completes with the post-approval answer; `pendingState` cleared.

- [ ] **Step 5: Commit**

```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add src/main/groovy/org/moqui/ai/AgentRunner.groovy src/test/groovy/AiApprovalTests.groovy && \
git commit -m "feat(ai): resume() — execute decided turn (approve/reject) then continue the loop (#11)"
```

---

## Task 4: Approve / reject / decide / list services

**Files:**
- Create: `service/ai/ApprovalServices.xml`
- Test: `src/test/groovy/AiApprovalTests.groovy`

- [ ] **Step 1: Write failing service tests (approve→complete; reject→denial→complete)**

In `AiApprovalTests.groovy`, add two tests that drive the full flow through the SERVICE (not calling resume directly). Reuse the gated agent setup (factor a helper or repeat). Approve case asserts `dec.runStatusId == "AI_RUN_COMPLETED"` and the gated tool ran (`success == "Y"`). Reject case asserts completion and a rejected `AiToolCall` (`success == "N"`, result mentions "Denied"). (Model the two tests on the suspend test's setup; the second enqueued response is the model's post-decision answer, e.g. "ok, skipped that".)

- [ ] **Step 2: Run to confirm it fails**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — approval services don't exist.

- [ ] **Step 3: Create the services**

`service/ai/ApprovalServices.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<services xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/service-definition-3.xsd">

    <service verb="approve" noun="ToolCall" authenticate="true">
        <in-parameters><parameter name="approvalId" required="true"/><parameter name="decisionNote"/></in-parameters>
        <out-parameters><parameter name="agentRunId"/><parameter name="runStatusId"/></out-parameters>
        <actions><script>
            def r = ec.service.sync().name("ai.ApprovalServices.decide#ToolCall")
                .parameters([approvalId: approvalId, statusId: "AI_APPR_APPROVED", decisionNote: decisionNote]).call()
            agentRunId = r.agentRunId; runStatusId = r.runStatusId
        </script></actions>
    </service>

    <service verb="reject" noun="ToolCall" authenticate="true">
        <in-parameters><parameter name="approvalId" required="true"/><parameter name="decisionNote"/></in-parameters>
        <out-parameters><parameter name="agentRunId"/><parameter name="runStatusId"/></out-parameters>
        <actions><script>
            def r = ec.service.sync().name("ai.ApprovalServices.decide#ToolCall")
                .parameters([approvalId: approvalId, statusId: "AI_APPR_REJECTED", decisionNote: decisionNote]).call()
            agentRunId = r.agentRunId; runStatusId = r.runStatusId
        </script></actions>
    </service>

    <!-- shared: record one decision; resume the run when no pending approvals remain for it -->
    <service verb="decide" noun="ToolCall" authenticate="true">
        <in-parameters>
            <parameter name="approvalId" required="true"/>
            <parameter name="statusId" required="true"/>
            <parameter name="decisionNote"/>
        </in-parameters>
        <out-parameters><parameter name="agentRunId"/><parameter name="runStatusId"/></out-parameters>
        <actions>
            <entity-find-one entity-name="moqui.ai.AiToolApproval" value-field="appr"/>
            <if condition="appr == null"><return error="true" message="Unknown approvalId ${approvalId}"/></if>
            <if condition="appr.statusId != 'AI_APPR_PENDING'"><return error="true" message="Approval already decided"/></if>
            <service-call name="update#moqui.ai.AiToolApproval" in-map="[approvalId: approvalId, statusId: statusId,
                decisionNote: decisionNote, decidedByUserId: ec.user.userId, decidedDate: ec.user.nowTimestamp]"/>
            <set field="agentRunId" from="appr.agentRunId"/>
            <entity-find entity-name="moqui.ai.AiToolApproval" list="stillPending">
                <econdition field-name="agentRunId" from="agentRunId"/>
                <econdition field-name="statusId" value="AI_APPR_PENDING"/>
            </entity-find>
            <if condition="stillPending">
                <set field="runStatusId" value="AI_RUN_SUSPENDED"/>
                <else>
                    <script>
                        def aiTool = ec.factory.getTool("AI", org.moqui.ai.AiToolFactory.class)
                        def r = new org.moqui.ai.AgentRunner(ec, aiTool).resume(agentRunId)
                        runStatusId = r.statusId
                    </script>
                </else>
            </if>
        </actions>
    </service>

    <service verb="get" noun="PendingApproval" authenticate="true">
        <in-parameters><parameter name="agentRunId"/></in-parameters>
        <out-parameters><parameter name="approvalList" type="List"/></out-parameters>
        <actions>
            <entity-find entity-name="moqui.ai.AiToolApproval" list="approvalList">
                <econdition field-name="statusId" value="AI_APPR_PENDING"/>
                <econdition field-name="agentRunId" ignore-if-empty="true"/>
                <order-by field-name="requestedDate"/>
            </entity-find>
        </actions>
    </service>
</services>
```

- [ ] **Step 4: Run — service tests pass**; commit

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS — approve resumes to completion (gated tool ran); reject resumes to completion (denial fed back, model wrapped up).
```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add service/ai/ApprovalServices.xml src/test/groovy/AiApprovalTests.groovy && \
git commit -m "feat(ai): approve/reject/decide/get#PendingApproval services + resume trigger (#11)"
```

---

## Task 5: Multi-tool-turn e2e + decision-record doc

**Files:**
- Modify: `src/test/groovy/AiApprovalTests.groovy`
- Modify: `docs/specs/2026-06-03-enterprise-decisions-gap-report.md`

- [ ] **Step 1: Write a mixed-turn e2e test**

A turn proposing BOTH a non-gated tool and a gated tool suspends the WHOLE turn (nothing runs), then approving runs both. Enqueue an iter-1 response with two toolCalls (one `get#Echo` ungated, one `get#GatedEcho` gated) + an iter-2 stop. Assert: suspends with exactly ONE pending approval (for the gated call), the ungated tool did NOT run while suspended (no AiToolCall yet), then after approving, BOTH tools ran and the run completed. (Reuse the gated-agent setup; grant both tools.)

- [ ] **Step 2: Run — full suite green**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS. Report the total test count + confirm live tests green.

- [ ] **Step 3: Record shipped in the gap report**

In `docs/specs/2026-06-03-enterprise-decisions-gap-report.md`, add a row/note: **Human approval gate (Phase 4) shipped** — `requiresApproval` tools suspend the run (`AI_RUN_SUSPENDED` + `pendingState` + `AiToolApproval`); `approve#`/`reject#ToolCall` decide; the last decision resumes the run (approved → dispatch, rejected → denial fed back); whole-turn granularity. (This is a roadmap phase, not one of the 11 enterprise decisions — add it under a brief "Roadmap phases shipped" note or alongside the cost/context entries.) Note deferred: per-call granularity, approval expiry, approver permissions, the approvals UI (Phase 5).

- [ ] **Step 4: Commit**

```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add src/test/groovy/AiApprovalTests.groovy docs/specs/2026-06-03-enterprise-decisions-gap-report.md && \
git commit -m "test(ai): mixed-turn approval e2e; record human approval gate shipped (#11)"
```

---

## Engineering review refinements (locked 2026-06-03)

`/plan-eng-review` accepted the design at full scope and locked these changes — apply them on top of the task code above:

1. **No dangling tool_call on suspend (Issue 2, P1).** Do NOT persist the assistant tool-call turn to the conversation at the suspend gate. In `continueAgent`, add the assistant turn to the in-memory `messages` (so it's in `pendingState`), but move the `persistConversationMessage(conversationId, runId, assistantTurn)` to AFTER the approval gate — so a *non-gated* turn persists it inline as today, and a *gated/suspended* turn does NOT (it lives only in `pendingState`). In `resume()`, BEFORE executing the gated turn's tools, persist the assistant turn to the conversation (`[role:"assistant", toolCalls: turnToolCalls]`), then dispatch + persist the tool-result messages. Net: the conversation only ever holds complete `tool_call → tool_result` pairs, so a new `run#Agent` on a suspended/abandoned conversation never replays a dangling tool_call (which OpenAI/Anthropic reject).

2. **Error-propagating suspend writes (Issue 3, P1).** The suspend state is load-bearing for resume, so do NOT use the error-swallowing `persist()` for it. Add a `persistRequired(serviceName, params)` helper that calls the service WITHOUT the swallow-and-warn (let it throw). Use `persistRequired` for the `AiToolApproval` creates and the `update#AiAgentRun` that sets `AI_RUN_SUSPENDED` + `pendingState`. A failure then throws → the loop's existing `catch (Throwable)` → `finish(... "AI_RUN_FAILED", ...)` — a clean, surfaced failure instead of a zombie `SUSPENDED` run with null `pendingState`. (Keep the guarded `persist()` for observability writes: steps, tool-call logs.)

3. **DRY the remember-tool schema.** The `remember` tool-schema Map is built in both `run()` and `resume()`. Extract a private helper, e.g. `private List<Map> withRememberTool(List<Map> toolSchemas, boolean ctxOn, String conversationId)` (or a `Map rememberToolSchema()`), and call it from both. One definition.

4. **A1 regression test (add to Task 5).** Suspend a conversation-backed run (gated tool), then — WITHOUT deciding — start a SECOND `run#Agent` on the same `conversationId` with the MockProvider scripted to a plain stop. Assert the second run completes `AI_RUN_COMPLETED` (no dangling-tool_call error), proving the conversation has no malformed turn. This is the regression guard for refinement 1.

5. **Iteration-budget edge (documented, no code).** If the gated turn is the last allowed iteration (`iterations == maxIterations` at suspend), `resume()` executes the tools and `continueAgent`'s `for` loop immediately exits → `finish(AI_RUN_TRUNCATED)`. The tools ran but the model gets no turn to respond. This is correct (out of iteration budget); note it in the run's behavior, not a bug.

**Verified OK (no change needed):** resume-state serialization covers every loop mutable (messages, replayCount, stepSeq, candIdx, summary watermark, result fields, turnToolCalls); iteration counter is continuous across suspend/resume; `remember` never gates (not in catalog) and resumes via `rememberFact`; multiple gated calls all gate via `decide`'s still-pending check; `pendingState` size is a documented v1 limitation; crash-mid-resume and agent-edited-between are documented limitations.

## Self-Review

**Spec coverage** (vs. issue #11 + locked decisions):
- Suspend on `requiresApproval` (whole turn, nothing executes) → Task 2 gate + suspend test. ✅
- `AiToolApproval` + `pendingState` + statuses → Task 1. ✅
- `resume()` executes the turn (approved dispatch / rejected denial-fed-back) + continues → Task 3. ✅
- approve/reject/decide/get#PendingApproval services; last decision resumes → Task 4. ✅
- Whole-turn granularity (mixed turn: nothing runs until decided, then all) → Task 5 e2e. ✅
- Reject feeds denial back, agent continues (not abort) → Task 3 resume (rejected → error result, loop continues) + Task 4 reject test. ✅
- State serialization complete (messages, replayCount, stepSeq, candIdx, summary, result, turnToolCalls) → Task 2 pendingState + Task 3 rehydrate. ✅
- Deferred (per-call, expiry, permissions, UI) → not built; noted. ✅

**Placeholder scan:** entities/services/statuses + the full `continueAgent`/`resume` are concrete code. Two tests (Task 4 service tests, Task 5 e2e) are described against the established setup rather than fully spelled out — acceptable because they reuse the Task 2/3 setup verbatim with different MockProvider scripting + assertions stated; the implementer mirrors the shown tests. Run steps have exact commands.

**Type/name consistency:**
- `continueAgent(EntityValue agent, String runId, String conversationId, List<Map> candidates, List<Map> toolSchemas, Map responseSchema, Map st)` — called by `run()` (Task 2) and `resume()` (Task 3) with the same `st` shape. ✅
- `st` keys (messages, replayCount, stepSeq, candIdx, summaryText, summaryWatermark, result) match between the pendingState JSON (Task 2 suspend), the rehydrate (Task 3), and continueAgent's reads. ✅
- New statuses `AI_RUN_SUSPENDED`, `AI_APPR_PENDING/APPROVED/REJECTED` used consistently across entity, gate, resume, services. ✅
- `run#Agent` out-params `awaitingApproval`/`approvalIds` added (Task 2) and asserted (suspend test). ✅
- The dispatch on resume mirrors the loop (remember special-case + dispatchTool) — same `stepSeq`. ✅
- `requiresApproval` read via `ai.getTool(name)?.requiresApproval` (already in the catalog). ✅

**Risk notes for the eng-review:** (1) `continueAgent` is a large extraction — every existing test must stay green (it's the same loop relocated); the suite is the guard. (2) `pendingState` can be large for long conversations (serializes full `messages`) — acceptable for v1; note for a durability pass. (3) resume re-derives config from the agent, so an agent edited between suspend and resume uses the new config — acceptable, arguably desirable. (4) crash mid-resume before `pendingState` is cleared could re-run not-yet-recorded calls — documented limitation. (5) **symmetric concurrent-decide race** — two simultaneous `decide#` calls on the *last two* pending approvals of one multi-gated-call turn could each observe the other still-pending and neither resume, stranding the run at `AI_RUN_SUSPENDED`; low-probability and not cheaply fixable without a tx around the LLM calls (which the design forbids) — a future operator "force-resume" / sweeper for SUSPENDED runs with zero pending approvals closes it.

**This plan goes through `/plan-eng-review` before execution** (architectural weight: mid-run pause/resume + state serialization).

---

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 1 | CLEAR (PLAN) | scope accepted full; 2 P1 architecture issues found + fixed in-plan; 3 refinements baked |

- **Scope:** accepted at full scope (pause/resume is irreducibly multi-part).
- **P1 fixes locked:** (Issue 2) defer persisting the assistant turn to resume — no dangling `tool_call` in conversation history; (Issue 3) error-propagating suspend writes — no zombie `SUSPENDED` run on a failed `pendingState`/approval write.
- **Refinements baked:** DRY the remember-tool schema; add an A1 regression test (new run on a suspended conversation replays cleanly); documented the last-iteration-at-gate → `TRUNCATED` edge.
- **Verified OK:** resume-state completeness, iteration continuity, remember+gated coexistence, multiple gated calls.
- **UNRESOLVED:** none.
- **VERDICT:** ENG CLEARED — ready to implement (subagent-driven).
