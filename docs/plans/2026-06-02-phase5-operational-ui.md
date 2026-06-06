# Phase 5: Operational UI — Implementation Plan

> **Status: SUPERSEDED** by docs/plans/2026-06-04-operational-ui.md (the version that shipped).

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give non-developer operators screens to do their job without a DB client: watch agent
runs, work the approvals queue, review spend, and browse conversations.

**Architecture:** Plain Moqui XML screens (`<screen>`/`<form-list>`/`<transition>`), patterned on
`runtime/base-component/tools/screen/Tools/StatusFlows.xml`. They read the entities from Phases
1–4 and call existing services (`approve#ToolCall`, `get#AiSpend`). No new business logic — this
phase is presentation only. Screens reuse the run-trace include from the dev console where possible.

**Tech Stack:** Moqui screen XML + the standard webroot (Vue/Quasar) renderer; no new deps.

**Depends on:** Phase 1 (`AiAgentRun`/`Step`/`ToolCall`, dev-console run-trace), Phase 2
(`AiConversation`/`Message`), Phase 3 (`get#AiSpend`), Phase 4 (`AiToolApproval`,
`approve#/reject#ToolCall`). **Inherits earlier phases' unverified assumptions.**

**Conventions (binding):** UDM Domain Object Practices Guide; **screen security** via
`require-authentication` + `ArtifactGroup`/`ArtifactAuthz` (see the `moqui-security` skill and
guide); form-list pattern from the example screen above; no Java/Moqui name conflicts. **Verify
the exact screen/form XML elements against an existing webroot screen on first run** — screen XML
is the least-exercised surface in this plan set.

---

## Design decisions (documented with recommended defaults — change if you disagree)

1. **Mount point.** Recommended: a single subscreen tree `screen/AiOps.subscreens` mounted under
   the standard `apps` webapp (e.g. reachable at `/apps/aiOps`), with four subscreens (Runs,
   Approvals, Cost, Conversations). Alternative: fold into an existing admin app. Default: own
   `AiOps` tree — clean separation, easy to secure as a unit.
2. **Operator security.** Recommended: `require-authentication="true"` on the `AiOps` root, plus an
   `ArtifactGroup` covering the `AiOps` screens and an `ArtifactAuthz` granting an `AI_OPERATOR`
   user group. Approve/reject transitions additionally require that group. Default: gate the whole
   tree behind `AI_OPERATOR`; seed the group + an authz record as `install` data.
3. **Dashboards/charts.** Recommended: **tabular + aggregate counts** for v1 (form-lists + the
   `get#AiSpend` totals). Rich charts (timeseries spend, run-volume graphs) are deferred — Moqui's
   webroot can host Vue/Quasar charts later, but they're polish, not function. Default: tables now.
4. **Dev console vs operator UI.** The dev console (Agents/Tools/Playground/Runs, deferred from
   Phase 1 slice-1) is developer-facing; this is operator-facing. They **share the run-trace
   screen-include** (`AiRunTrace`) so a run renders identically in both. If the dev console isn't
   built yet when this phase starts, build `AiRunTrace` here and let the dev console reuse it.

---

## File Structure (added)

```
runtime/component/moqui-ai/
├── screen/AiOps.xml                                ← root subscreen menu (Task 1)
├── screen/AiOps/Runs.xml                           ← run monitor + drill-in (Task 2)
├── screen/AiOps/RunDetail.xml                      ← single run + AiRunTrace include (Task 2)
├── screen/includes/AiRunTrace.xml                  ← shared trace (steps + tool calls) (Task 2)
├── screen/AiOps/Approvals.xml                      ← pending queue + approve/reject (Task 3)
├── screen/AiOps/Cost.xml                           ← spend review (Task 4)
├── screen/AiOps/Conversations.xml                  ← conversation browser (Task 5)
└── data/AiSecurityData.xml                         ← AI_OPERATOR group + ArtifactAuthz (Task 1)
```

(No Spock test for screens — screens are verified via the `/browse` QA skill or manual smoke,
not the `MoquiSuite`. See "Verification" at the end.)

---

## Task 1: Subscreen root + operator security

**Files:**
- Create: `runtime/component/moqui-ai/screen/AiOps.xml`
- Create: `runtime/component/moqui-ai/data/AiSecurityData.xml`

- [ ] **Step 1: Root screen with menu + subscreens, authenticated**

`runtime/component/moqui-ai/screen/AiOps.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd"
        default-menu-title="AI Ops" default-menu-include="true" require-authentication="true">
    <subscreens default-item="Runs">
        <subscreens-item name="Runs" menu-title="Runs" location="component://moqui-ai/screen/AiOps/Runs.xml"/>
        <subscreens-item name="Approvals" menu-title="Approvals" location="component://moqui-ai/screen/AiOps/Approvals.xml"/>
        <subscreens-item name="Cost" menu-title="Cost" location="component://moqui-ai/screen/AiOps/Cost.xml"/>
        <subscreens-item name="Conversations" menu-title="Conversations" location="component://moqui-ai/screen/AiOps/Conversations.xml"/>
    </subscreens>
    <widgets><subscreens-active/></widgets>
</screen>
```
(Mounting it under `/apps`: add an `<subscreens-item>` pointing to this screen in the webroot
`apps` screen, or rely on auto-mount conventions — confirm how this deployment registers app
subscreens.)

- [ ] **Step 2: Operator group + authorization (install data)**

`runtime/component/moqui-ai/data/AiSecurityData.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<entity-facade-xml type="install">
    <moqui.security.UserGroup userGroupId="AI_OPERATOR" description="AI Operations"/>
    <!-- authorize the AiOps screen subtree for the operator group -->
    <moqui.security.ArtifactGroup artifactGroupId="AI_OPS_SCREENS" description="AI Ops screens"/>
    <moqui.security.ArtifactGroupMember artifactGroupId="AI_OPS_SCREENS"
        artifactName="component://moqui-ai/screen/AiOps.xml" artifactTypeEnumId="AtScreen" nameIsPattern="Y"/>
    <moqui.security.ArtifactAuthz artifactAuthzId="AI_OPS_VIEW" userGroupId="AI_OPERATOR"
        artifactGroupId="AI_OPS_SCREENS" authzTypeEnumId="AuthzAllow" authzActionEnumId="AuthzAll"/>
</entity-facade-xml>
```
Confirm the exact `moqui.security` entity/field names and enum IDs (`AtScreen`, `AuthzAllow`,
`AuthzAll`) against the live security model / the `moqui-security` skill — these are standard but
deployment-seeded. Approve/reject are further gated because the Approvals transitions call
services that run as the operator (Moqui authz applies).

- [ ] **Step 3: Commit**

```bash
git add runtime/component/moqui-ai/screen/AiOps.xml runtime/component/moqui-ai/data/AiSecurityData.xml
git commit -m "feat(moqui-ai): AiOps subscreen root + operator security"
```

---

## Task 2: Run monitor + shared run-trace include

**Files:**
- Create: `runtime/component/moqui-ai/screen/AiOps/Runs.xml`
- Create: `runtime/component/moqui-ai/screen/AiOps/RunDetail.xml`
- Create: `runtime/component/moqui-ai/screen/includes/AiRunTrace.xml`

- [ ] **Step 1: Run list, filterable, drill to detail**

`runtime/component/moqui-ai/screen/AiOps/Runs.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd"
        default-menu-title="Runs" require-authentication="true">
    <subscreens default-item="RunList">
        <subscreens-item name="RunList" menu-title="List" location="component://moqui-ai/screen/AiOps/Runs.xml#thisIsList"/>
    </subscreens>
    <actions>
        <entity-find entity-name="moqui.ai.AiAgentRun" list="runList">
            <search-form-inputs default-order-by="-fromDate"/>
        </entity-find>
    </actions>
    <widgets>
        <form-list name="RunList" list="runList" skip-form="false" multi="false">
            <field name="agentRunId"><default-field title="Run">
                <link url="RunDetail" text="${agentRunId}" parameter-map="[agentRunId:agentRunId]"/></default-field></field>
            <field name="agentName"><default-field><display/></default-field></field>
            <field name="statusId"><default-field title="Status"><display-entity entity-name="moqui.basic.StatusItem" text="${description}"/></default-field></field>
            <field name="fromDate"><default-field><display/></default-field></field>
            <field name="iterations"><default-field><display/></default-field></field>
            <field name="tokensIn"><default-field><display/></default-field></field>
            <field name="tokensOut"><default-field><display/></default-field></field>
            <field name="estimatedCost"><default-field title="Cost"><display/></default-field></field>
            <!-- search filters rendered by search-form-inputs -->
            <field name="statusId" title="Status"><header-field show-order-by="true">
                <drop-down allow-empty="true"><entity-options key="${statusId}" text="${description}">
                    <entity-find entity-name="moqui.basic.StatusItem"><econdition field-name="statusTypeId" value="AiAgentRunStatus"/></entity-find>
                </entity-options></drop-down></header-field></field>
            <field name="agentName"><header-field show-order-by="true"><text-find/></header-field></field>
        </form-list>
    </widgets>
</screen>
```

- [ ] **Step 2: Run detail = summary + shared trace include**

`runtime/component/moqui-ai/screen/AiOps/RunDetail.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd"
        default-menu-title="Run Detail" require-authentication="true">
    <parameter name="agentRunId" required="true"/>
    <actions><entity-find-one entity-name="moqui.ai.AiAgentRun" value-field="run"/></actions>
    <widgets>
        <container-box><box-header title="Run ${run.agentRunId} — ${run.agentName}"/><box-body>
            <render-mode><text type="html"><![CDATA[Status: ${run.statusId} · iterations: ${run.iterations} · cost: ${run.estimatedCost}]]></text></render-mode>
        </box-body></container-box>
        <include-screen location="component://moqui-ai/screen/includes/AiRunTrace.xml"/>
    </widgets>
</screen>
```

- [ ] **Step 3: The shared run-trace include (steps + tool calls)**

`runtime/component/moqui-ai/screen/includes/AiRunTrace.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd"
        require-authentication="false">
    <parameter name="agentRunId" required="true"/>
    <actions>
        <entity-find entity-name="moqui.ai.AiAgentRunStep" list="stepList"><econdition field-name="agentRunId"/><order-by field-name="stepSeqId"/></entity-find>
        <entity-find entity-name="moqui.ai.AiToolCall" list="callList"><econdition field-name="agentRunId"/><order-by field-name="stepSeqId,toolCallId"/></entity-find>
    </actions>
    <widgets>
        <form-list name="Steps" list="stepList" skip-form="true">
            <field name="stepSeqId"><default-field><display/></default-field></field>
            <field name="stepType"><default-field><display/></default-field></field>
            <field name="tokensIn"><default-field><display/></default-field></field>
            <field name="tokensOut"><default-field><display/></default-field></field>
            <field name="finishReason"><default-field><display/></default-field></field>
        </form-list>
        <form-list name="ToolCalls" list="callList" skip-form="true">
            <field name="toolName"><default-field><display/></default-field></field>
            <field name="success"><default-field><display/></default-field></field>
            <field name="arguments"><default-field><display/></default-field></field>
            <field name="result"><default-field><display/></default-field></field>
            <field name="durationMs"><default-field title="ms"><display/></default-field></field>
        </form-list>
    </widgets>
</screen>
```
This `AiRunTrace` is the shared component the dev-console Playground/Runs reuse (decision #4).

- [ ] **Step 4: Commit**

```bash
git add runtime/component/moqui-ai/screen/AiOps/Runs.xml runtime/component/moqui-ai/screen/AiOps/RunDetail.xml \
        runtime/component/moqui-ai/screen/includes/AiRunTrace.xml
git commit -m "feat(moqui-ai): run monitor + shared run-trace include"
```

---

## Task 3: Approvals queue (the one interactive screen)

**Files:**
- Create: `runtime/component/moqui-ai/screen/AiOps/Approvals.xml`

- [ ] **Step 1: Pending list with approve/reject transitions**

`runtime/component/moqui-ai/screen/AiOps/Approvals.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd"
        default-menu-title="Approvals" require-authentication="true">
    <transition name="approve"><service-call name="ai.ApprovalServices.approve#ToolCall"
            in-map="[approvalId:approvalId, decisionNote:decisionNote]"/><default-response url="."/></transition>
    <transition name="reject"><service-call name="ai.ApprovalServices.reject#ToolCall"
            in-map="[approvalId:approvalId, decisionNote:decisionNote]"/><default-response url="."/></transition>
    <actions>
        <entity-find entity-name="moqui.ai.AiToolApproval" list="pendingList">
            <econdition field-name="statusId" value="AI_APPR_PENDING"/>
            <order-by field-name="requestedDate"/>
        </entity-find>
    </actions>
    <widgets>
        <form-list name="Pending" list="pendingList" skip-form="false">
            <field name="approvalId"><default-field><hidden/></default-field></field>
            <field name="toolName"><default-field title="Tool"><display/></default-field></field>
            <field name="arguments"><default-field title="Proposed args"><display/></default-field></field>
            <field name="requestedDate"><default-field title="Requested"><display/></default-field></field>
            <field name="decisionNote"><default-field title="Note"><text-line size="30"/></default-field></field>
            <field name="approveAction"><default-field title="">
                <submit text="Approve"><parameter name="approvalId" from="approvalId"/></submit></default-field>
                <!-- form transition target = approve -->
            </field>
            <field name="rejectAction"><default-field title="">
                <link url="reject" text="Reject" parameter-map="[approvalId:approvalId, decisionNote:decisionNote]" btn-type="danger"/></default-field></field>
        </form-list>
    </widgets>
</screen>
```
Note: wire the row "Approve" submit to the `approve` transition (set the form's transition or use
per-row `<link url="approve" ...>` like Reject). Confirm the exact `form-list` row-action wiring
(submit vs link, passing `approvalId`/`decisionNote`) against the example screen — the *behavior*
(call approve#/reject#ToolCall with the row's approvalId) is the contract; the widget syntax is
the detail to verify. Resuming the run is automatic (Phase 4 service does it).

- [ ] **Step 2: Commit**

```bash
git add runtime/component/moqui-ai/screen/AiOps/Approvals.xml
git commit -m "feat(moqui-ai): approvals queue screen (approve/reject)"
```

---

## Task 4: Cost review

**Files:**
- Create: `runtime/component/moqui-ai/screen/AiOps/Cost.xml`

- [ ] **Step 1: Filter form → get#AiSpend → totals + rows**

`runtime/component/moqui-ai/screen/AiOps/Cost.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd"
        default-menu-title="Cost" require-authentication="true">
    <parameter name="agentName"/><parameter name="userId"/>
    <parameter name="fromDate"/><parameter name="thruDate"/><parameter name="groupBy"><default-value>agent</default-value></parameter>
    <actions>
        <service-call name="ai.CostServices.get#AiSpend" out-map="spend"
            in-map="[agentName:agentName, userId:userId, fromDate:fromDate, thruDate:thruDate, groupBy:groupBy]"/>
    </actions>
    <widgets>
        <form-single name="Filter" transition=".">
            <field name="agentName"><default-field><text-line/></default-field></field>
            <field name="fromDate"><default-field><date-time type="date"/></default-field></field>
            <field name="thruDate"><default-field><date-time type="date"/></default-field></field>
            <field name="groupBy"><default-field><drop-down><option key="agent"/><option key="user"/><option key="none"/></drop-down></default-field></field>
            <field name="submitField"><default-field title=""><submit text="Show"/></default-field></field>
        </form-single>
        <container-box><box-header title="Total"/><box-body>
            <label text="Cost ${spend.totalCost} · in ${spend.totalTokensIn} · out ${spend.totalTokensOut} · runs ${spend.runCount}"/>
        </box-body></container-box>
        <form-list name="Breakdown" list="spend.rows" skip-form="true">
            <field name="agentName"><default-field><display/></default-field></field>
            <field name="userId"><default-field><display/></default-field></field>
            <field name="totalCost"><default-field title="Cost"><display/></default-field></field>
            <field name="runCount"><default-field title="Runs"><display/></default-field></field>
        </form-list>
    </widgets>
</screen>
```

- [ ] **Step 2: Commit**

```bash
git add runtime/component/moqui-ai/screen/AiOps/Cost.xml
git commit -m "feat(moqui-ai): cost review screen over get#AiSpend"
```

---

## Task 5: Conversation browser

**Files:**
- Create: `runtime/component/moqui-ai/screen/AiOps/Conversations.xml`

- [ ] **Step 1: Conversation list → messages**

`runtime/component/moqui-ai/screen/AiOps/Conversations.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd"
        default-menu-title="Conversations" require-authentication="true">
    <parameter name="conversationId"/>
    <actions>
        <entity-find entity-name="moqui.ai.AiConversation" list="convList"><order-by field-name="-lastActivityDate"/></entity-find>
        <if condition="conversationId">
            <entity-find entity-name="moqui.ai.AiConversationMessage" list="msgList">
                <econdition field-name="conversationId"/><order-by field-name="messageSeqId"/></entity-find>
        </if>
    </actions>
    <widgets>
        <form-list name="Conversations" list="convList" skip-form="true">
            <field name="conversationId"><default-field><link url="." text="${conversationId}" parameter-map="[conversationId:conversationId]"/></default-field></field>
            <field name="agentName"><default-field><display/></default-field></field>
            <field name="title"><default-field><display/></default-field></field>
            <field name="lastActivityDate"><default-field><display/></default-field></field>
        </form-list>
        <section name="Messages" condition="conversationId"><widgets>
            <form-list name="Messages" list="msgList" skip-form="true">
                <field name="role"><default-field><display/></default-field></field>
                <field name="content"><default-field><display/></default-field></field>
                <field name="fromDate"><default-field title="At"><display/></default-field></field>
            </form-list>
        </widgets></section>
    </widgets>
</screen>
```

- [ ] **Step 2: Commit**

```bash
git add runtime/component/moqui-ai/screen/AiOps/Conversations.xml
git commit -m "feat(moqui-ai): conversation browser screen"
```

---

## Verification (screens aren't unit-tested)

Screens are verified by loading the app, not by `MoquiSuite`:
- Start the app (`./gradlew load` then run; **only when explicitly asked** per project rules).
- Use the `/browse` QA skill (or manual) to: open `/apps/aiOps`, confirm the four tabs render;
  create a run that needs approval, see it in **Approvals**, approve it, confirm the run resumes
  to completed in **Runs**; check **Cost** totals; open a conversation in **Conversations**.
- Confirm an operator without `AI_OPERATOR` is denied (security check).

## Phase Done — Definition of Done
- Operators can monitor runs (with trace), action the approvals queue, review spend, and browse
  conversations — no DB client.
- Screens gated behind `AI_OPERATOR`; approve/reject call the Phase 4 services (run auto-resumes).
- `AiRunTrace` is a shared include the dev console reuses.

## NOT in this phase
- **Charts / timeseries dashboards** (decision #3 deferred) — tabular only for v1.
- **Real-time updates** (auto-refresh / websockets) — operator refreshes manually.
- **Agent/tool authoring screens** — that's the developer console (Phase 1 follow-on), not operator UI.
- **Bulk approve/reject** and approval routing by tool — follow-ups.
- **Per-row Approve widget wiring** is the one screen-XML detail flagged to verify against a real
  form-list with row submit actions.
