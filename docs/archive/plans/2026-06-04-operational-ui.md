# Operational UI (Phase 5) — Reconciled Execution Plan

> Supersedes `2026-06-02-phase5-operational-ui.md` (reorders to playground-first, reconciles against
> context/cost/approval/reasoning shipped since, and verifies live in NotNaked). The 2026-06-02 plan
> still holds the full screen XML for Runs/Approvals/Cost/Conversations — reference it for those; this
> plan adds the Playground, the deltas, and the build/verify workflow.

**Goal:** An AI Ops console (Moqui XML screens) so operators run agents and see what happened —
without a DB client or the raw Service Run form.

**Architecture:** Plain Moqui XML screens (`<screen>`/`<form-list>`/`<form-single>`/`<transition>`)
in the `moqui-ai` component, mounted under the `apps` webapp. Read the `Ai*` entities; call the
shipped services (`run#Agent`, `approve#/reject#ToolCall`, `get#AiSpend`). Presentation only.

**Tech stack:** Moqui screen XML + webroot renderer. No new deps, no jar rebuild (screens are
runtime resources; Moqui hot-reloads them in dev).

**Build/verify workflow (important):**
- Author screens in the **notnaked clone**: `/Users/anilpatel/maarg-sd/notnaked/runtime/component/moqui-ai/screen/...` (this is where it runs).
- Verify **live** against the running NotNaked instance (`localhost:8080`) via the `/browse` skill (headless) or by asking the user to look.
- New screen *files* mounted into a new tree need one restart; edits to existing screens hot-reload (refresh).
- Commit + push from the notnaked clone to `feature/ai-agent-framework` (screens are framework code; dev-env checkout pulls later).

**Status of dependencies (all shipped + live in NotNaked):** `AiAgent`(+`reasoningEffort`), `AiAgentRun`/`Step`/`ToolCall`,
`AiConversation`(+`summaryText`)/`Message`/`Fact`, `AiToolApproval` + `approve#/reject#ToolCall`, `get#AiSpend`, `run#Agent`.

---

## Task 1: Mount the AiOps tree under `apps` + root menu (require-authentication)

**Files:** `screen/AiOps.xml` (root) + however this deployment mounts a component screen under `apps`.

- [ ] **Step 1 — research the mount.** Examine how an existing classic-Moqui screen tree mounts under `/apps` in NotNaked (the `system` app is the model; also check `oms`/`oms-bi`). Find the mechanism: a `<subscreens-item>` added to the webroot `apps` root screen, an auto-mount convention, or a `MoquiConf`/`screen-extend`. Replicate it for `AiOps`. Confirm the resulting URL (likely `/apps/AiOps` or `/vapps/AiOps`).
- [ ] **Step 2 — root screen** `screen/AiOps.xml`: `default-menu-title="AI Ops"`, `require-authentication="true"`, `<subscreens default-item="Playground">` with items: Playground, Runs, Approvals, Cost, Conversations; `<widgets><subscreens-active/></widgets>`. (Use the 2026-06-02 plan's AiOps.xml as the base; change `default-item` to `Playground` and add the Playground item.)
- [ ] **Step 3 — v1 security:** `require-authentication="true"` only (any logged-in user; NotNaked admin sees it). DEFER the `AI_OPERATOR` group + `ArtifactAuthz` (the 2026-06-02 Task 1 Step 2) to a follow-up so the demo isn't gated. Note this in the doc.
- [ ] **Step 4 — verify live:** restart NotNaked (it loads the new screen tree), log in, open the AiOps URL → confirm the menu renders with the five tabs (empty subscreens OK at this point). Commit.

---

## Task 2: Playground (NEW — the priority screen)

**File:** `screen/AiOps/Playground.xml`

Run an agent and see the result + trace, in one screen. Replaces the Service Run form.

- [ ] **Step 1 — screen.** `screen/AiOps/Playground.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd"
        default-menu-title="Playground" require-authentication="true">
    <parameter name="agentName"/>
    <parameter name="userMessage"/>
    <parameter name="agentRunId"/>            <!-- set after a run, to show the result -->

    <transition name="run">
        <service-call name="ai.AgentServices.run#Agent" out-map="context"
            in-map="[agentName: agentName, userMessage: userMessage]"/>
        <default-response url=".">
            <parameter name="agentRunId"/><parameter name="agentName"/>
        </default-response>
    </transition>

    <actions>
        <entity-find entity-name="moqui.ai.AiAgent" list="agentList" cache="true"><order-by field-name="agentName"/></entity-find>
        <if condition="agentRunId"><entity-find-one entity-name="moqui.ai.AiAgentRun" value-field="run"/></if>
    </actions>

    <widgets>
        <form-single name="RunForm" transition="run">
            <field name="agentName"><default-field title="Agent">
                <drop-down><entity-options text="${agentName} (${providerName}:${modelName})" key="${agentName}">
                    <entity-find entity-name="moqui.ai.AiAgent"><order-by field-name="agentName"/></entity-find>
                </entity-options></drop-down></default-field></field>
            <field name="userMessage"><default-field title="Message"><text-area rows="3" cols="80"/></default-field></field>
            <field name="submitField"><default-field title=""><submit text="Run"/></default-field></field>
        </form-single>

        <section name="Result" condition="run">
            <widgets>
                <container-box><box-header title="Answer — ${run.statusId} · ${run.servedByModelId} · ${run.tokensIn}/${run.tokensOut} tok · $${run.estimatedCost}"/>
                    <box-body><label text="${run.assistantMessage}" type="p"/></box-body></container-box>
                <include-screen location="component://moqui-ai/screen/includes/AiRunTrace.xml"><parameter name="agentRunId" from="run.agentRunId"/></include-screen>
            </widgets>
        </section>
    </widgets>
</screen>
```
(`AiRunTrace.xml` is built in Task 3; build Task 3's trace include first or stub the `<section>` until it exists.)

- [ ] **Step 2 — verify live in NotNaked:** open Playground, pick `nn-oms-assistant`, send "Summarize the current orders" → confirm a real GPT answer renders + the trace shows the `get#OrderSummaryList` tool call + tokens/cost. (This needs the keys-restart done. This screen IS the demo.) Use `/browse` to drive + screenshot, or hand to the user. Commit.

Note: `run#Agent` is `transaction="ignore"`; invoked from a screen transition the LLM call may run inside the transition's tx — acceptable for v1 (a gpt-4o-mini call is well under tx timeout). Flag for a later async/polling pass.

---

## Task 3: Runs monitor + shared `AiRunTrace` include
Build per **2026-06-02 plan Task 2** (Runs.xml, RunDetail.xml, includes/AiRunTrace.xml) — the XML there is current. Verify field names against the entities (`AiAgentRun`: agentName, statusId, fromDate, iterations, tokensIn, tokensOut, estimatedCost, servedByModelId, providerName; `AiAgentRunStep`: stepSeqId, stepType, tokensIn, tokensOut, finishReason; `AiToolCall`: toolName, success, arguments, result, durationMs). Verify live, commit.

## Task 4: Approvals queue
Build per **2026-06-02 plan Task 3** (Approvals.xml). The approve/reject transitions call the shipped `ai.ApprovalServices.approve#/reject#ToolCall` (resume is automatic). The one detail to verify against a real form-list: per-row submit wiring for Approve (and a `decisionNote` text field). Verify live (suspend → see in queue → approve → run resumes), commit.

## Task 5: Cost review
Build per **2026-06-02 plan Task 4** (Cost.xml over `get#AiSpend`). Verify the out-map shape (`totalCost`, `totalTokensIn`, `totalTokensOut`, `runCount`, `rows`) against the service. Verify live, commit.

## Task 6: Conversations browser (+ facts + summary)
Build per **2026-06-02 plan Task 5** (Conversations.xml: list → messages), PLUS deltas:
- When a conversation is selected, also show its **rolling summary** (`AiConversation.summaryText`) in a box, and its **pinned facts** (`moqui.ai.AiConversationFact` — factKey, factValue) in a small form-list.
Verify live, commit.

---

## Verification (live, not MoquiSuite)
Screens aren't unit-tested. For each: sync to the notnaked clone (already there if authored in place), restart-or-hot-reload, then drive `/browse` against `localhost:8080` (logged in) to confirm it renders + works; screenshot for the record. The Playground end-to-end (real agent → answer + trace) is the acceptance demo.

## NOT in this phase
Charts/timeseries; real-time auto-refresh; agent/tool authoring screens; the `AI_OPERATOR` group gating (deferred to a follow-up — v1 is `require-authentication` only); async/polling for long runs.

## Definition of done
Open AiOps in NotNaked → Playground runs `nn-oms-assistant` on the 5 orders and shows the answer + trace; Runs lists/drills; Approvals actions a gated call (run resumes); Cost shows totals; Conversations shows messages + facts + summary. Screens committed + pushed on `feature/ai-agent-framework`.
