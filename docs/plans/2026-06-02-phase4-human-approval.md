# Phase 4: Human Approval Gate — Implementation Plan

> **Status: SUPERSEDED** by docs/plans/2026-06-03-human-approval-gate.md (the version that shipped).

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When an agent wants to call a tool marked `requiresApproval`, the run **suspends**
instead of executing: it persists its state and a pending-approval record, returns control to
the caller, and **resumes** only after a human approves or rejects via a service call.

**Architecture:** Adds `AiToolApproval` (the pending decision) and a `pendingState` field on
`AiAgentRun` (the serialized messages needed to resume) via `<extend-entity>`. The Phase 1 loop
body is extracted into a private `continueAgent(...)` that both `run` (fresh) and `resume`
(after a decision) call — single-sourced, DRY. New statuses: `AI_RUN_SUSPENDED` and an
`AiApprovalStatus` flow. All data Map-based.

**Tech Stack:** same as Phase 1.

**Depends on:** Phase 1 — `AgentRunner` loop, `AiAgentRun`, `AiTool.requiresApproval` (already in
the Phase 1 catalog/entity), `dispatchTool`, `MoquiSuite`. **Inherits Phase 1's unverified API
assumptions; reconcile against the proven build.**

**Conventions (binding):** UDM Domain Object Practices Guide — Maps not data classes; status via
`StatusItem`/`StatusFlow`/`StatusFlowTransition` (`install` data); `extend-entity` to add fields;
domain verbs (`approve#`, `reject#`) per §3.4; `*Tests.groovy` + `MoquiSuite`; no Java/Moqui name
conflicts.

---

## Design decision — suspend/resume granularity (documented, not asked)

When a single LLM turn requests several tool calls and one or more require approval, we suspend
at **turn granularity** and resume once **all** of that turn's approvals are decided:

- Hitting a turn with ≥1 `requiresApproval` tool → create one `AiToolApproval` per
  approval-required call, persist `pendingState` (the message list + the turn's full tool-call
  list + step seq), set the run to `AI_RUN_SUSPENDED`, return `[awaitingApproval: true,
  approvalIds: [...]]`. Nothing in that turn executes yet.
- `approve#ToolCall` / `reject#ToolCall` records one decision. If pending approvals remain for the
  run, it stays suspended. When the **last** one is decided, the run resumes: each call in the
  turn executes (approved/auto) or yields an error result (rejected), then the loop continues —
  which may suspend again at a later turn.

Why turn-granularity (vs. one-approval-at-a-time): it matches how the model proposed the calls
(as a batch), gives the operator the whole proposed action set at once, and keeps `pendingState`
a single clean snapshot. Alternative (per-call suspend) is noted in "NOT in this phase".

---

## File Structure (added/changed)

```
runtime/component/moqui-ai/
├── entity/AiApprovalEntities.xml                   ← AiToolApproval + extend AiAgentRun.pendingState (Task 1)
├── data/AiApprovalStatusData.xml                   ← AiApprovalStatus + AI_RUN_SUSPENDED transitions (Task 1)
├── src/main/groovy/org/moqui/ai/AgentRunner.groovy ← MODIFY: extract continueAgent; suspend; resume (Task 2)
├── service/ai/ApprovalServices.xml                 ← approve#ToolCall, reject#ToolCall, get#PendingApproval (Task 3)
└── src/test/groovy/
    ├── MoquiSuite.groovy                           ← MODIFY: add AiApprovalTests
    └── AiApprovalTests.groovy                      ← Task 4
```

---

## Task 1: Approval entity + statuses

**Files:**
- Create: `runtime/component/moqui-ai/entity/AiApprovalEntities.xml`
- Create: `runtime/component/moqui-ai/data/AiApprovalStatusData.xml`

- [ ] **Step 1: Define the approval entity + extend AiAgentRun**

`runtime/component/moqui-ai/entity/AiApprovalEntities.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<entities xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/entity-definition-3.xsd">

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
        <relationship type="one" related="moqui.ai.AiAgentRun" short-alias="run"/>
        <relationship type="one" related="moqui.basic.StatusItem" short-alias="status"/>
    </entity>

    <!-- pendingState: the serialized run state needed to resume after a decision. -->
    <extend-entity entity-name="AiAgentRun" package="moqui.ai">
        <field name="pendingState" type="text-very-long"><description>JSON: {messages, stepSeq, turnToolCalls} when AI_RUN_SUSPENDED</description></field>
    </extend-entity>
</entities>
```

- [ ] **Step 2: Define approval statuses + the new run transition (install data)**

`runtime/component/moqui-ai/data/AiApprovalStatusData.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<entity-facade-xml type="install">
    <moqui.basic.StatusType statusTypeId="AiApprovalStatus" description="AI Tool Approval Status"/>
    <moqui.basic.StatusItem statusId="AI_APPR_PENDING"  statusTypeId="AiApprovalStatus" sequenceNum="1" description="Pending"/>
    <moqui.basic.StatusItem statusId="AI_APPR_APPROVED" statusTypeId="AiApprovalStatus" sequenceNum="2" description="Approved"/>
    <moqui.basic.StatusItem statusId="AI_APPR_REJECTED" statusTypeId="AiApprovalStatus" sequenceNum="3" description="Rejected"/>
    <moqui.basic.StatusFlow statusFlowId="AiApprovalFlow" statusTypeId="AiApprovalStatus" description="Approval decision"/>
    <moqui.basic.StatusFlowTransition statusFlowId="AiApprovalFlow" statusId="AI_APPR_PENDING" toStatusId="AI_APPR_APPROVED" transitionSequence="1" transitionName="Approve"/>
    <moqui.basic.StatusFlowTransition statusFlowId="AiApprovalFlow" statusId="AI_APPR_PENDING" toStatusId="AI_APPR_REJECTED" transitionSequence="2" transitionName="Reject"/>

    <!-- new run status + transitions to/from suspended (statusTypeId from Phase 1) -->
    <moqui.basic.StatusItem statusId="AI_RUN_SUSPENDED" statusTypeId="AiAgentRunStatus" sequenceNum="6" description="Suspended (awaiting approval)"/>
    <moqui.basic.StatusFlowTransition statusFlowId="AiAgentRunFlow" statusId="AI_RUN_RUNNING"   toStatusId="AI_RUN_SUSPENDED" transitionSequence="5" transitionName="Suspend"/>
    <moqui.basic.StatusFlowTransition statusFlowId="AiAgentRunFlow" statusId="AI_RUN_SUSPENDED" toStatusId="AI_RUN_RUNNING"   transitionSequence="6" transitionName="Resume"/>
    <moqui.basic.StatusFlowTransition statusFlowId="AiAgentRunFlow" statusId="AI_RUN_SUSPENDED" toStatusId="AI_RUN_COMPLETED" transitionSequence="7" transitionName="ResumeComplete"/>
</entity-facade-xml>
```

- [ ] **Step 3: Commit**

```bash
git add runtime/component/moqui-ai/entity/AiApprovalEntities.xml \
        runtime/component/moqui-ai/data/AiApprovalStatusData.xml
git commit -m "feat(moqui-ai): tool approval entity + suspended status"
```

---

## Task 2: Suspend/resume in AgentRunner

**Files:**
- Modify: `runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/AgentRunner.groovy`

- [ ] **Step 1: Extract the loop body into continueAgent(...)**

Refactor `run` so the for-loop lives in a private `Map continueAgent(...)` that takes the runId,
agent, provider, toolSchemas, the current `messages`, and the starting `stepSeq`. `run` does
setup then calls it; `resume` (Step 3) also calls it. The tool-call section gains the approval
check. Replace the loop section with:
```groovy
    /** Runs the loop from the given state until done, truncated, aborted, failed, or SUSPENDED. */
    private Map continueAgent(String runId, EntityValue agent, LlmProvider provider,
                             List<Map> toolSchemas, List<Map> messages, int stepSeq, Map result) {
        int maxIter = (agent.maxIterations ?: 8) as int
        long maxTokens = (agent.maxTokens ?: 0L) as long
        int maxToolCalls = (agent.maxToolCallsPerTurn ?: 20) as int
        try {
            for (int i = result.iterations as int; i < maxIter; i++) {
                result.iterations = i + 1
                Map resp = provider.chat([model: agent.modelName, systemContext: agent.systemPrompt,
                        messages: messages, tools: toolSchemas])
                long inTok = (resp.tokensIn ?: 0L) as long, outTok = (resp.tokensOut ?: 0L) as long
                result.tokensIn += inTok; result.tokensOut += outTok
                stepSeq++
                persist("create#moqui.ai.AiAgentRunStep", [agentRunId: runId, stepSeqId: stepSeq as String,
                    stepType: "llm_call", tokensIn: inTok, tokensOut: outTok, finishReason: resp.finishReason])

                if (maxTokens > 0 && ((result.tokensIn as long) + (result.tokensOut as long)) > maxTokens)
                    return finish(result, runId, agent.providerName as String, agent.modelName as String, "AI_RUN_ABORTED", "Per-run token ceiling exceeded")

                List<Map> toolCalls = (resp.toolCalls ?: []) as List<Map>
                if (!toolCalls) {
                    result.assistantMessage = resp.assistantText ?: ""
                    return finish(result, runId, agent.providerName as String, agent.modelName as String, "AI_RUN_COMPLETED", null)
                }
                if (toolCalls.size() > maxToolCalls)
                    return finish(result, runId, agent.providerName as String, agent.modelName as String, "AI_RUN_ABORTED", "Tool-calls-per-turn ceiling exceeded")

                messages.add([role: "assistant", toolCalls: toolCalls])

                // ----- approval gate: if any call in this turn needs approval, SUSPEND the whole turn -----
                List<Map> needApproval = toolCalls.findAll { ai.getTool(it.name as String)?.requiresApproval }
                if (needApproval) {
                    List<String> approvalIds = []
                    for (Map tc in needApproval) {
                        String approvalId = ec.entity.sequencedIdPrimary("moqui.ai.AiToolApproval", null, null)
                        approvalIds.add(approvalId)
                        Map td = ai.getTool(tc.name as String)
                        persist("create#moqui.ai.AiToolApproval", [approvalId: approvalId, agentRunId: runId,
                            stepSeqId: stepSeq as String, toolCallId: tc.id, toolName: tc.name,
                            serviceName: td?.serviceName, arguments: groovy.json.JsonOutput.toJson(tc.arguments ?: [:]),
                            statusId: "AI_APPR_PENDING", requestedByUserId: ec.user.userId,
                            requestedDate: ec.user.nowTimestamp])
                    }
                    persist("update#moqui.ai.AiAgentRun", [agentRunId: runId, statusId: "AI_RUN_SUSPENDED",
                        pendingState: groovy.json.JsonOutput.toJson([messages: messages, stepSeq: stepSeq, turnToolCalls: toolCalls])])
                    result.statusId = "AI_RUN_SUSPENDED"; result.awaitingApproval = true; result.approvalIds = approvalIds
                    return result
                }

                // no approvals needed: dispatch all calls this turn, then continue
                for (Map tc in toolCalls) {
                    String resultJson = dispatchTool(runId, stepSeq, tc)
                    messages.add([role: "tool", toolCallId: tc.id, content: resultJson])
                }
            }
            return finish(result, runId, agent.providerName as String, agent.modelName as String, "AI_RUN_TRUNCATED", null)
        } catch (Throwable t) {
            logger.error("Agent run ${runId} failed", t)
            return finish(result, runId, agent.providerName as String, agent.modelName as String, "AI_RUN_FAILED", t.message)
        }
    }
```
And `run(agentName, userMessage, conversationId=null)` becomes setup + `continueAgent`:
```groovy
        // ...after building messages and result (iterations=0)...
        return continueAgent(runId, agent, provider, toolSchemas, messages, 0, result)
```
(`finish` signature was widened in Phase 3 to take providerName/modelName; keep that.)

- [ ] **Step 2: Add the result key + status note**

`continueAgent` adds `awaitingApproval`/`approvalIds` to the runResult Map on suspend. Document
this in the runResult shape (the Map gains two optional keys; absent on normal completion).

- [ ] **Step 3: Add resume(...)**

Append to `AgentRunner`:
```groovy
    /** Resume a suspended run once ALL its approvals are decided. Executes each call in the
     *  suspended turn per its decision (approved/auto = run it; rejected = error result), then
     *  continues the loop. Called by approve#/reject#ToolCall when no pending approvals remain. */
    Map resume(String agentRunId) {
        EntityValue run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", agentRunId).one()
        if (run == null) throw new IllegalArgumentException("Unknown run: ${agentRunId}")
        if (run.statusId != "AI_RUN_SUSPENDED") return [agentRunId: agentRunId, statusId: run.statusId]

        EntityValue agent = ec.entity.find("moqui.ai.AiAgent").condition("agentName", run.agentName).useCache(true).one()
        LlmProvider provider = ai.getProvider(agent.providerName as String)
        List<Map> toolSchemas = loadToolSchemas(run.agentName as String)

        Map state = new groovy.json.JsonSlurper().parseText(run.pendingState as String) as Map
        List<Map> messages = state.messages as List<Map>
        int stepSeq = state.stepSeq as int
        List<Map> turnToolCalls = state.turnToolCalls as List<Map>

        // index decisions by toolCallId
        Map<String, EntityValue> approvals = [:]
        for (EntityValue a in ec.entity.find("moqui.ai.AiToolApproval").condition("agentRunId", agentRunId).list())
            approvals.put(a.toolCallId as String, a)

        // execute the suspended turn per decision, appending tool-result messages
        for (Map tc in turnToolCalls) {
            EntityValue appr = approvals.get(tc.id as String)
            String resultJson
            if (appr != null && appr.statusId == "AI_APPR_REJECTED") {
                resultJson = groovy.json.JsonOutput.toJson([error: "Rejected by ${appr.decidedByUserId}: ${appr.decisionNote ?: 'no reason given'}"])
                persist("create#moqui.ai.AiToolCall", [agentRunId: agentRunId, stepSeqId: stepSeq as String,
                    toolCallId: tc.id, toolName: tc.name, serviceName: ai.getTool(tc.name as String)?.serviceName,
                    arguments: groovy.json.JsonOutput.toJson(tc.arguments ?: [:]), result: resultJson,
                    success: "N", errorText: "rejected", durationMs: 0])
            } else {
                resultJson = dispatchTool(agentRunId, stepSeq, tc)   // approved or auto
            }
            messages.add([role: "tool", toolCallId: tc.id, content: resultJson])
        }

        // reset run to RUNNING, clear pendingState, continue the loop
        Map result = [agentRunId: agentRunId, conversationId: run.conversationId, assistantMessage: null,
                      tokensIn: (run.tokensIn ?: 0L) as long, tokensOut: (run.tokensOut ?: 0L) as long,
                      iterations: (run.iterations ?: 0) as int, truncated: false, statusId: "AI_RUN_RUNNING"]
        persist("update#moqui.ai.AiAgentRun", [agentRunId: agentRunId, statusId: "AI_RUN_RUNNING", pendingState: null])
        return continueAgent(agentRunId, agent, provider, toolSchemas, messages, stepSeq, result)
    }
```

- [ ] **Step 4: Commit**

```bash
git add runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/AgentRunner.groovy
git commit -m "feat(moqui-ai): suspend on requiresApproval, resume after decision"
```

---

## Task 3: Approve / reject / list services

**Files:**
- Create: `runtime/component/moqui-ai/service/ai/ApprovalServices.xml`

- [ ] **Step 1: Define the decision + listing services**

`runtime/component/moqui-ai/service/ai/ApprovalServices.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<services xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/service-definition-3.xsd">

    <!-- decide one approval; if it was the last pending one for the run, resume the run. -->
    <service verb="approve" noun="ToolCall" authenticate="true">
        <in-parameters>
            <parameter name="approvalId" required="true"/>
            <parameter name="decisionNote"/>
        </in-parameters>
        <out-parameters><parameter name="agentRunId"/><parameter name="runStatusId"/></out-parameters>
        <actions><script>ec.service.sync().name("ai.ApprovalServices.decide#ToolCall")
            .parameters([approvalId: approvalId, statusId: "AI_APPR_APPROVED", decisionNote: decisionNote]).call()
            agentRunId = ec.context.agentRunId; runStatusId = ec.context.runStatusId</script></actions>
    </service>

    <service verb="reject" noun="ToolCall" authenticate="true">
        <in-parameters>
            <parameter name="approvalId" required="true"/>
            <parameter name="decisionNote"/>
        </in-parameters>
        <out-parameters><parameter name="agentRunId"/><parameter name="runStatusId"/></out-parameters>
        <actions><script>ec.service.sync().name("ai.ApprovalServices.decide#ToolCall")
            .parameters([approvalId: approvalId, statusId: "AI_APPR_REJECTED", decisionNote: decisionNote]).call()
            agentRunId = ec.context.agentRunId; runStatusId = ec.context.runStatusId</script></actions>
    </service>

    <!-- shared decision logic -->
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
            <!-- resume only when no pending approvals remain for this run -->
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

- [ ] **Step 2: Commit**

```bash
git add runtime/component/moqui-ai/service/ai/ApprovalServices.xml
git commit -m "feat(moqui-ai): approve/reject/get PendingApproval services + resume trigger"
```

---

## Task 4: Approval flow tests

**Files:**
- Create: `runtime/component/moqui-ai/src/test/groovy/AiApprovalTests.groovy`
- Modify: `runtime/component/moqui-ai/src/test/groovy/MoquiSuite.groovy` (add `AiApprovalTests.class`)

- [ ] **Step 1: Write the suspend → approve → resume and reject tests**

`runtime/component/moqui-ai/src/test/groovy/AiApprovalTests.groovy`:
```groovy
import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.provider.MockProvider

class AiApprovalTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() {
        ec = Moqui.getExecutionContext()
        // a tool that requires approval (catalog entry created directly for the test)
        ec.factory.getTool("AI", org.moqui.ai.AiToolFactory.class).loadToolsFromText(
            '<tools><tool service="moqui.ai.test.TestServices.get#Echo" description="echo" requires-approval="true"/></tools>')
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "ApprAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgentTool")
            .setAll([agentName: "ApprAgent", toolName: "moqui.ai.test.TestServices.get#Echo"]).createOrUpdate()
    }
    def cleanupSpec() {
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentName", "ApprAgent").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "ApprAgent").deleteAll()
        ec.destroy()
    }
    def cleanup() { MockProvider.reset() }

    def "approval-required tool suspends the run, then approving resumes to completion"() {
        given:
        MockProvider.enqueue([toolCalls: [[id: "c1", name: "moqui.ai.test.TestServices.get#Echo",
            arguments: [text: "do it"]]], finishReason: "tool_use"])
        MockProvider.enqueue([assistantText: "done after approval", finishReason: "stop"])
        when: "run suspends"
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "ApprAgent", userMessage: "go"]).call()
        then:
        out.statusId == "AI_RUN_SUSPENDED"
        out.awaitingApproval == true
        out.approvalIds.size() == 1

        when: "approve resumes"
        Map dec = ec.service.sync().name("ai.ApprovalServices.approve#ToolCall")
            .parameters([approvalId: out.approvalIds[0], decisionNote: "ok"]).call()
        then:
        dec.runStatusId == "AI_RUN_COMPLETED"
        ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one().assistantMessage == "done after approval"
        ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId).condition("success", "Y").count() >= 1L

        cleanup:
        ec.entity.find("moqui.ai.AiToolApproval").condition("agentRunId", out.agentRunId).deleteAll()
    }

    def "rejecting feeds an error result back and the run still completes"() {
        given:
        MockProvider.enqueue([toolCalls: [[id: "c1", name: "moqui.ai.test.TestServices.get#Echo",
            arguments: [text: "do it"]]], finishReason: "tool_use"])
        MockProvider.enqueue([assistantText: "ok, skipped that", finishReason: "stop"])
        when:
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "ApprAgent", userMessage: "go"]).call()
        Map dec = ec.service.sync().name("ai.ApprovalServices.reject#ToolCall")
            .parameters([approvalId: out.approvalIds[0], decisionNote: "no"]).call()
        then:
        dec.runStatusId == "AI_RUN_COMPLETED"
        def call = ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId).list()[0]
        call.success == "N"
        call.result.toLowerCase().contains("reject")

        cleanup:
        ec.entity.find("moqui.ai.AiToolApproval").condition("agentRunId", out.agentRunId).deleteAll()
    }
}
```

- [ ] **Step 2: Run the suite; iterate to green; commit**

Run: `./gradlew :runtime:component:moqui-ai:test`
```bash
git add runtime/component/moqui-ai/src/test/groovy/AiApprovalTests.groovy \
        runtime/component/moqui-ai/src/test/groovy/MoquiSuite.groovy
git commit -m "test(moqui-ai): approval suspend/approve/reject/resume flow"
```

---

## Phase Done — Definition of Done
- A turn proposing a `requiresApproval` tool suspends the run (`AI_RUN_SUSPENDED`), persists
  `pendingState`, and returns `awaitingApproval` + `approvalIds` — nothing executes yet.
- `approve#ToolCall` / `reject#ToolCall` record the decision; when the last pending approval for a
  run is decided, the run resumes: approved/auto calls execute, rejected calls yield an error
  result the model can react to; the loop continues to completion (or suspends again).
- `get#PendingApproval` lists what's waiting. Suite green.

## NOT in this phase
- **Per-call (vs per-turn) approval** — we suspend/resume at turn granularity; finer control deferred.
- **Approval expiry / timeout** (auto-reject a stale pending approval) — note as a follow-up.
- **Approval routing / permissions** (who may approve which tools) — for now any authenticated user;
  tie to Moqui `UserPermission` later (the `StatusFlowTransition.userPermissionId` field is the hook).
- **Approvals UI** (the queue screen) — Phase 5.
- **Crash-safety nuance:** resume relies on persisted `pendingState`; a process crash mid-resume
  re-runs the turn's not-yet-recorded calls. Acceptable for now; document for the durability pass.
