# Reference: The AI Ops Console (Screens)

The `moqui-ai` component ships a single operator console, **AI Ops**, mounted as a
Moqui screen under the standard `apps` application. It is the human-facing surface
for running agents, building new agents, reviewing run traces, deciding tool-call
approvals, watching spend, browsing conversations, and curating the builder glossary.

This document is derived from and verified against the screen XML:
`screen/AiOps.xml`, every file under `screen/AiOps/`, and the shared include
`screen/includes/AiRunTrace.xml`. The code is canonical.

---

## Mount

| Property | Value |
|---|---|
| Screen root | `screen/AiOps.xml` |
| URL path | `/apps/AiOps` (mounted under the `apps` application root) |
| Default menu title | **AI Ops** |
| Menu icon | `fa fa-robot` (icon type) |
| Authentication | `require-authentication="true"` |
| Default subscreen | `Playground` (`<subscreens default-item="Playground">`) |
| Layout | `<subscreens-active/>` — the active subscreen renders in the body |

### v1 security posture

Every screen in the console — the root and all subscreens — sets only
`require-authentication="true"`, i.e. **any logged-in user** may reach it. The root
screen carries an explicit comment that the dedicated `AI_OPERATOR` group plus
`ArtifactAuthz` gating is **deferred to a follow-up so the demo isn't gated**.

Note one stale reference in the code: `Playground.xml` and `Composer.xml` comments
mention "the AiOps screen's `inheritAuthz`" / "the AiOps ALL_USERS grant". In this
framework version authz does not cascade via an `inheritAuthz` column; access in v1
rests on `require-authentication` plus the `ArtifactGroupMember` grants documented in
`explanation/security-model.md`. The comments describe intent, not a working
inheritance mechanism. (Full authz model: see `explanation/security-model.md`.)

### Subscreen menu order

The `<subscreens>` block declares **8 menu items** in this order:

| `menu-index` | Name | Menu title | Location |
|---|---|---|---|
| 1 | `Playground` | Playground | `screen/AiOps/Playground.xml` |
| 2 | `Composer` | Composer | `screen/AiOps/Composer.xml` |
| 3 | `Agents` | Agents | `screen/AiOps/Agents.xml` |
| 4 | `Runs` | Runs | `screen/AiOps/Runs.xml` |
| 5 | `Approvals` | Approvals | `screen/AiOps/Approvals.xml` |
| 6 | `Cost` | Cost | `screen/AiOps/Cost.xml` |
| 7 | `Conversations` | Conversations | `screen/AiOps/Conversations.xml` |
| 8 | `Glossary` | Glossary | `screen/AiOps/Glossary.xml` |

A **ninth screen file**, `screen/AiOps/RunDetail.xml`, lives in the same directory but
is **not** a menu item — it sets `default-menu-include="false"` and is reached only as
an auto-discovered sibling drill-in (linked from **Runs** and **Approvals**). It is
documented below alongside **Runs**.

The shared include `screen/includes/AiRunTrace.xml` is not a subscreen at all — it is a
reusable widget fragment pulled into Playground, Composer (preview), and RunDetail.

---

## 1. Playground

**Purpose:** A minimal "ask the assistant" surface. In this NotNaked checkout it is
**hardwired to the NotNaked OMS Assistant** — it runs the seeded `nn-oms-assistant`
agent through the NotNaked service `notnaked.OmsAiServices.run#OmsAssistant`, *not* the
generic `ai.AgentServices.run#Agent`. The UI labels it "NotNaked OMS Assistant" and
prompts the operator to "Ask about your orders." This screen is therefore
NotNaked-specific, not a framework-generic playground.

**Parameters:** `userMessage`, `agentRunId` (set after a run, to show the result + trace).

**Transitions:**

| Transition | Backing service | Notes |
|---|---|---|
| `run` | `notnaked.OmsAiServices.run#OmsAssistant` (in `userMessage`, out → `context`) | On success, re-displays `.` carrying `agentRunId` |

**Behavior / widgets:**
- A single-form prompt (`RunForm`) with a 3-row text area defaulting to
  "Summarize the current orders" and a **Run** submit.
- After a run, if `agentRunId` is set the screen loads the `moqui.ai.AiAgentRun` and
  renders a **Result** section: an answer box headed with the run summary
  (`${run.statusId} · ${run.servedByModelId} · ${run.tokensIn}/${run.tokensOut} tok · $${run.estimatedCost}`)
  and the assistant message, followed by the shared **`AiRunTrace`** include for the
  step/tool-call trace.

**Backing services:** `notnaked.OmsAiServices.run#OmsAssistant`; reads
`moqui.ai.AiAgentRun`.

---

## 2. Composer

**Purpose:** Build a new agent by conversing with the **Composer Assistant**. Three
stacked regions — chat, a live draft panel, and a preview pane. All work routes
through the tested `ai.ComposerServices` plus `create#Conversation` / `run#Agent`.

**Parameters:** `conversationId` (the build session), `draftAgentId` (the draft being
shaped), `userMessage`, `agentRunId` (last composer run, for the chat trace),
`testMessage`, `previewRunId`.

**Transitions:**

| Transition | Backing service(s) | Notes |
|---|---|---|
| `send` | `ai.AgentServices.create#Conversation` (only if no `conversationId`, `agentName: 'composer-assistant'`), then `ai.AgentServices.run#Agent` (`agentName: 'composer-assistant'`, with `userMessage` + `conversationId`) | Carries `conversationId`, `agentRunId`, `draftAgentId` forward |
| `preview` | `ai.ComposerServices.preview#Agent` (in `agentId: draftAgentId`, `testMessage`) | Returns `agentRunId`, surfaced forward as `previewRunId` |
| `discard` | `ai.ComposerServices.discard#Draft` (in `agentId: draftAgentId`) | Returns to `.` keeping only `conversationId` |

**Behavior / widgets:**
- **Region 1 — Chat:** loads `AiConversationMessage` for the session
  (ordered by `messageSeqId`), renders them as a read-only list (role + content),
  and offers the `SendForm` (hidden `conversationId`/`draftAgentId`, a message text
  area, **Send**).
- **Region 2 — Live draft panel** (shown when a `draft` exists): if no `draftAgentId`
  was passed it derives the latest draft as the most recent `AiAgent` with
  `statusId = AI_AGENT_DRAFT` (ordered `-agentId`); it then loads that `AiAgent` and
  its `AiAgentTool` grants. The panel shows the draft name + status, system prompt,
  `providerName / modelName`, the granted-tool list (tool id + `requiresApprovalOverride`),
  and a **Discard draft** button (confirmed).
- **Region 3 — Preview pane** (shown when a `draft` exists): a `PreviewForm` test
  input that calls `preview`. The UI states mutating actions are **HELD** (shown, not
  executed) while read-only tools run on real data. When a `previewRunId` is present it
  loads the preview `AiAgentRun` and any held `AiToolApproval` rows
  (`statusId = AI_APPR_PENDING` for that run), rendering the preview answer, the list of
  held would-be tool calls (tool name + arguments), and the shared **`AiRunTrace`**
  include for the preview run.

**Backing services:** `ai.AgentServices.create#Conversation`,
`ai.AgentServices.run#Agent`, `ai.ComposerServices.preview#Agent`,
`ai.ComposerServices.discard#Draft`; reads `AiConversationMessage`, `AiAgent`,
`AiAgentTool`, `AiAgentRun`, `AiToolApproval`.

---

## 3. Agents

**Purpose:** Direct authoring/administration of agents and a read-only view of their
tool grants. Distinct from the conversational Composer — this is a plain CRUD form.

**Transitions:**

| Transition | Backing service | Notes |
|---|---|---|
| `saveAgent` | `ai.AgentServices.store#AiAgent` | In-map: `agentId`, `agentName`, `providerName`, `modelName`, `maxIterations`, `statusId`, `systemPrompt` |

**Behavior / widgets:**
- **Installed agents:** lists all `AiAgent` (ordered by `agentName`) showing name,
  provider, model, status, and max iterations.
- **Tool grants:** lists all `AiAgentTool` (ordered `agentId,toolId`) showing the
  agent id and tool id — read-only.
- **Add / update an agent** (`AgentForm`): hidden `agentId` (present when editing),
  `agentName` text line, a provider drop-down (`openai`/`anthropic`), a `modelName`
  text line (defaulting to `gpt-4o-mini`), `maxIterations` (default `5`), a status
  drop-down offering only **Active** (`AI_AGENT_ACTIVE`) / **Disabled**
  (`AI_AGENT_DISABLED`), and a system-prompt text area. Submitting calls `saveAgent`.

**Backing services:** `ai.AgentServices.store#AiAgent`; reads `AiAgent`, `AiAgentTool`.

---

## 4. Runs (and RunDetail)

### Runs

**Purpose:** A searchable list of all agent runs (`AiAgentRun`). It is a leaf list
screen; clicking a run drills into **RunDetail**.

**Transitions:** None of its own — it is a search/list screen. Row links navigate to
the sibling `RunDetail` screen (`url="../RunDetail"`, passing `agentRunId`).

**Behavior / widgets:**
- Loads `AiAgentRun` via `<search-form-inputs default-order-by="-startedDate">` so the
  header fields drive filtering and ordering.
- `RunList` (header-dialog, paged) columns: **Run** (`agentRunId`, a drill-in link),
  **Agent** (`agentName`), **Status** (`statusId`, filterable via a drop-down of
  `StatusItem` where `statusTypeId = AiAgentRunStatus`, displayed by description),
  **Model** (`servedByModelId`), **Provider** (`providerName`), **Started**
  (`startedDate`, with a date-period filter), **Iters** (`iterations`), **In**/**Out**
  (`tokensIn`/`tokensOut`), and **Cost** (`estimatedCost`).

**Backing services:** none (entity reads only — `AiAgentRun`, `moqui.basic.StatusItem`).

### RunDetail

**Purpose:** The full record for one run. Not a menu item
(`default-menu-include="false"`) — an auto-discovered sibling under `AiOps/`, reached
via the drill-in from **Runs** and from **Approvals**.

**Parameters:** `agentRunId` (required).

**Transitions:** None — read-only. A toolbar **Back to Runs** link returns to `../Runs`.

**Behavior / widgets:**
- Loads the `AiAgentRun`. Header box shows status/iterations/tokens/cost, then a line
  with provider / `servedByModelId` / `providerRunId`, then started/ended
  (`startedDate`/`endedDate`).
- **Conversation** box: the user message and assistant message, plus an **Error**
  sub-section rendered only when `run.errorText` is set.
- The shared **`AiRunTrace`** include for the step + tool-call trace.

**Backing services:** none (entity read only — `AiAgentRun`).

---

## 5. Approvals

**Purpose:** The operator queue of pending tool-call approvals. Approving or rejecting
a row decides the held call and **resumes the suspended run** automatically.

**Transitions:**

| Transition | Backing service | Notes |
|---|---|---|
| `approve` | `ai.ApprovalServices.approve#ToolCall` (in `approvalId`, `decisionNote`) | Resume is automatic inside the service |
| `reject` | `ai.ApprovalServices.reject#ToolCall` (in `approvalId`, `decisionNote`) | |

**Behavior / widgets:**
- The pending list is **not** a raw entity find — it delegates to
  `ai.ApprovalServices.get#PendingApproval`, then aliases its `approvalList` to
  `pendingList`. The screen comment documents *why*: a Composer **preview** suspends on
  would-be mutating calls and writes `AI_APPR_PENDING` rows that must **not** surface in
  the operator queue, so the service filters out approvals whose run is a preview
  (`AiAgentRun.isPreview = Y`). Keeping the rule in the service keeps the exclusion in
  one tested place.
- Shows "No pending approvals." when the list is empty.
- `Pending` list columns: **Approval** (`approvalId`), **Run** (`agentRunId`, a link to
  `../RunDetail`), **Tool** (`toolName`), **Service** (`serviceName`), **Proposed args**
  (`arguments`), **Requested** (`requestedDate`), an editable **Note** (`decisionNote`),
  and per-row **Approve** (success) / **Reject** (danger) buttons.
- Each button is a `hidden-form-link` that POSTs the row's `approvalId` (explicit
  `<parameter>`) plus the sibling editable `decisionNote` (via
  `pass-through-parameters="true"`); both are confirmed actions.

**Backing services:** `ai.ApprovalServices.get#PendingApproval`,
`ai.ApprovalServices.approve#ToolCall`, `ai.ApprovalServices.reject#ToolCall`.

---

## 6. Cost

**Purpose:** Spend reporting over agent runs, with filters and grouping.

**Parameters:** `agentName`, `userId`, `fromDate`, `thruDate`, `groupBy` (default
`agent`).

**Transitions:** None — the **Filter** form submits to `.` (the same screen) to
re-run the report with new inputs.

**Behavior / widgets:**
- On load, calls `ai.CostServices.get#AiSpend` with `agentName`, `userId`, `fromDate`,
  `thruDate`, `groupBy`, into `spend`.
- **Filter** form: an agent drop-down (options from `AiAgent` by `agentName`), a
  `userId` text line, from/thru date pickers, a **Group By** drop-down
  (`agent` / `user` / `none`), and a **Show** submit.
- **Total spend** box: `spend.totalCost`, `spend.totalTokensIn`,
  `spend.totalTokensOut`, `spend.runCount`.
- **Breakdown** list (shown when `spend.rows` exist): per-group **Group Key** (`key`),
  **Cost** (`totalCost`), **Tokens In** (`totalTokensIn`), **Tokens Out**
  (`totalTokensOut`), **Runs** (`runCount`).

**Backing services:** `ai.CostServices.get#AiSpend`; reads `AiAgent` for the filter.

---

## 7. Conversations

**Purpose:** Browse multi-turn conversations, their rolling summary, pinned facts, and
full message history.

**Parameters:** `conversationId`.

**Transitions:** None — read-only. The list links to `.` with a `conversationId` to
open the detail section.

**Behavior / widgets:**
- Always lists the `AiConversationActivity` view-entity (ordered `-lastActivityDate`, a
  derived `MAX(AiConversationMessage.createdDate)`): **Conversation**
  (`conversationId`, a self-link), **Agent** (`agentName`), **Title**, **Status**
  (`statusId`, displayed via `StatusItem` description), **Last Activity**
  (`lastActivityDate`).
- When a `conversationId` is selected it loads that `AiConversation`, its
  `AiConversationMessage` rows (ordered `messageSeqId`), and its `AiConversationFact`
  rows (ordered `factKey`), and renders a **Detail** section:
  - Header box: agent / status / last activity.
  - **Rolling summary** box (only when `conv.summaryText` is set): the summary text plus
    "(summarized through message `${conv.summaryThruMessageSeqId}`)".
  - **Pinned facts** box (only when facts exist): a `factKey` / `factValue` list.
  - **Messages** box: a list of `messageSeqId` / `role` / `content` / `createdDate`.

**Backing services:** none (entity reads only — `AiConversation`,
`AiConversationMessage`, `AiConversationFact`, `moqui.basic.StatusItem`).

---

## 8. Glossary

**Purpose:** The **Builder Knowledgebase** curation console — review/approve/reject
suggested domain terms, view the approved glossary, add a curated term by hand, and run
the seed / promote maintenance actions. All actions route through `ai.GlossaryServices`.

**Transitions:**

| Transition | Backing service | Notes |
|---|---|---|
| `approve` | `ai.GlossaryServices.approve#DomainTerm` (in `termId`) | |
| `reject` | `ai.GlossaryServices.reject#DomainTerm` (in `termId`) | |
| `addTerm` | `ai.GlossaryServices.store#DomainTerm` (in `term`, `termKind`, `description`, `synonym`) | Curate a term by hand |
| `seed` | `ai.GlossaryServices.seed#DomainGlossary` (no params) | Re-seed from model + services |
| `promote` | `ai.GlossaryServices.promote#TermsFromSignals` (in `threshold: 3`) | Promote frequently-seen terms |

**Behavior / widgets:**
- **Suggested terms** (`AiDomainTerm` where `statusId = AI_TERM_SUGGESTED`, ordered
  `-usageCount,term`): columns **Term**, **Kind** (`termKind`), **Source**
  (`sourceType`), **Seen** (`usageCount`), **Description**, and per-row **Approve**
  (success) / **Reject** (danger) `hidden-form-link` buttons that POST the `termId`.
  Shows "No suggestions pending." when empty.
- **Approved glossary** (`AiDomainTerm` where `statusId = AI_TERM_APPROVED`, ordered
  `termKind,term`): **Term**, **Kind**, **Usage** (`usageCount`), **Description**.
- **Add a curated term / maintain the glossary:** an `AddTerm` form (term text line; a
  **Kind** drop-down with `AI_TERM_NOUN` / `AI_TERM_VERB`; an optional synonym; a
  description) submitting `addTerm`; a **Re-seed from model + services** button
  (`seed`); and a **Promote terms from signals** button (`promote`).

**Backing services:** `ai.GlossaryServices.approve#DomainTerm`,
`reject#DomainTerm`, `store#DomainTerm`, `seed#DomainGlossary`,
`promote#TermsFromSignals`; reads `AiDomainTerm`.

---

## Shared include: `AiRunTrace`

**Location:** `screen/includes/AiRunTrace.xml`. **Not a subscreen** — a reusable widget
fragment. It is `require-authentication="false"` (it renders inside already-authenticated
screens) and takes a required `agentRunId` parameter.

**Purpose:** Render the step-by-step trace for one agent run identically wherever it is
shown — used by **Playground**, the **Composer** preview pane, and **RunDetail**.

**Behavior / widgets:**
- Loads `AiAgentRunStep` (ordered `stepSeqId`) and `AiToolCall` (ordered
  `stepSeqId,toolCallId`) for the run.
- **Steps** list: `stepSeqId`, **Type** (`stepType`), **In**/**Out**
  (`tokensIn`/`tokensOut`), **Finish** (`finishReason`).
- **Tool Calls** list: **Tool** (`toolName`), **OK** (`success`), **Arguments**
  (`arguments`), **Result** (`result`), **ms** (`durationMs`).

**Backing services:** none (entity reads only — `AiAgentRunStep`, `AiToolCall`).

---

## Console-wide notes

- **Single mount, eight tabs, one hidden drill-in.** `AiOps.xml` mounts under `apps`
  with 8 menu subscreens; `RunDetail` is a ninth file reachable only by drill-in link.
- **Most screens are read-only over the AI entities.** Only Playground (run),
  Composer (send/preview/discard), Agents (saveAgent), Approvals (approve/reject), and
  Glossary (approve/reject/addTerm/seed/promote) invoke services; the rest are entity
  lists/detail views.
- **All service calls route through the tested service layer** (`ai.AgentServices`,
  `ai.ComposerServices`, `ai.ApprovalServices`, `ai.CostServices`,
  `ai.GlossaryServices`, and NotNaked's `notnaked.OmsAiServices` for the Playground) —
  the screens never write the AI entities directly. The approval queue and preview-run
  exclusion deliberately live in `get#PendingApproval` rather than in screen actions.
