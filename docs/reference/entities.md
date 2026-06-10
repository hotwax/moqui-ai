# Entity Reference — moqui-ai

> **Source of truth:** the entity definitions in `entity/*.xml` and the status/enum
> seed data in `data/*.xml`. This document is derived from, and verified against,
> the as-built code. Where this doc and any older spec/plan disagree, **the code
> wins** — historical specs and plans use the pre-refactor `agentName`/`toolName`
> primary keys; the shipped model uses opaque `agentId`/`toolId` PKs.

## Overview

The moqui-ai data model is split across eight entity files, all in package
`moqui.ai`, plus the EECA file that backstops naming-signal capture:

| File | Entities |
|---|---|
| `entity/AiEntities.xml` | `AiAgent`, `AiTool`, `AiAgentTool`, `AiAgentModel`, `AiAgentRun`, `AiAgentRunStep`, `AiToolCall` |
| `entity/AiToolEntities.xml` | `AiToolDenylist` |
| `entity/AiComposerEntities.xml` | `AiCapabilityRequest` |
| `entity/AiConversationEntities.xml` | `AiConversation`, `AiConversationMessage`, `AiConversationFact` |
| `entity/AiApprovalEntities.xml` | `AiToolCallRequest` |
| `entity/AiPriceEntities.xml` | `AiModelPrice` |
| `entity/AiGlossaryEntities.xml` | `AiDomainTerm`, `AiTermSynonym`, `AiNamingSignal` |
| `entity/AiGlossaryEcas.eecas.xml` | EECAs `AiToolNamingSignal`, `AiAgentNamingSignal` (no entities) |

Seventeen entities in total.

### Status & enumeration model

All lifecycle state is modeled with the framework's `moqui.basic.StatusItem` /
`moqui.basic.Enumeration` types. Status transitions are declared with
**`moqui.basic.StatusFlowTransition`** (in `data/AiStatusData.xml`,
`data/AiConversationStatusData.xml`, `data/AiGlossaryData.xml`) — this codebase
does **not** use `StatusValidChange`.

The convention throughout: a **`StatusItem`** (with a `StatusType` + a `StatusFlow`
of `StatusFlowTransition` rows) is used for fields with a real lifecycle that can
change over time; a fixed, code-referenced classification with no lifecycle is an
**`Enumeration`** (with an `EnumerationType`). For example, `AiTool.statusId` is a
`StatusItem` (Active/Disabled toggles), while `AiTool.effectEnumId` is an
`Enumeration` (read-only vs. mutating is intrinsic, never transitions).

| Status / enum type | Type kind | Values | Backed entity field |
|---|---|---|---|
| `AiAgentStatus` | StatusItem | `AI_AGENT_DRAFT`, `AI_AGENT_ACTIVE`, `AI_AGENT_DISABLED` | `AiAgent.statusId` |
| `AiToolStatus` | StatusItem | `AI_TOOL_ACTIVE`, `AI_TOOL_DISABLED` | `AiTool.statusId` |
| `AiToolEffect` | Enumeration | `AI_TOOL_READ_ONLY`, `AI_TOOL_MUTATING` | `AiTool.effectEnumId` |
| `AiAgentRunStatus` | StatusItem | `AI_RUN_RUNNING`, `AI_RUN_COMPLETED`, `AI_RUN_FAILED`, `AI_RUN_TRUNCATED`, `AI_RUN_ABORTED`, `AI_RUN_SUSPENDED` | `AiAgentRun.statusId` |
| `AiToolCallRequestStatus` | StatusItem | `AI_TCREQ_PENDING`, `AI_TCREQ_APPROVED`, `AI_TCREQ_REJECTED` | `AiToolCallRequest.statusId` |
| `AiConversationStatus` | StatusItem | `AI_CONV_ACTIVE`, `AI_CONV_CLOSED` | `AiConversation.statusId` |
| `AiCapReqStatus` | StatusItem | `AI_CAPREQ_OPEN`, `AI_CAPREQ_DONE`, `AI_CAPREQ_DISMISSED` | `AiCapabilityRequest.statusId` |
| `AiDomainTermStatus` | StatusItem | `AI_TERM_SUGGESTED`, `AI_TERM_APPROVED`, `AI_TERM_REJECTED` | `AiDomainTerm.statusId`, `AiTermSynonym.statusId` |
| `AiTermKind` | Enumeration | `AI_TERM_NOUN`, `AI_TERM_VERB` | `AiDomainTerm.termKind` |
| `AiTermSource` | Enumeration | `AI_TSRC_SEEDED`, `AI_TSRC_LEARNED`, `AI_TSRC_CURATED` | `AiDomainTerm.sourceType`, `AiTermSynonym.sourceType` |
| `AiSignalType` | Enumeration | `AI_SIG_TOOL_NAME`, `AI_SIG_AGENT_NAME` | `AiNamingSignal.signalType` |

> **Note on `AI_AGENT_DRAFT` / `AI_RUN_SUSPENDED`:** the status seed
> (`data/AiStatusData.xml`) defines `AI_AGENT_DRAFT` with `sequenceNum="0"` and
> `AI_RUN_SUSPENDED` with `sequenceNum="60"`; both are full members of their status
> type. The agent flow allows `DRAFT → ACTIVE`, `DRAFT → DISABLED`, and
> `ACTIVE ↔ DISABLED`. The run flow allows `RUNNING → {COMPLETED, FAILED, TRUNCATED,
> ABORTED}` and `RUNNING ↔ SUSPENDED` (the human-approval gate).

---

## Definitions

### AiAgent

`entity/AiEntities.xml`. A declared agent: a provider/model + system prompt + the
behavioral knobs that govern its run loop.

- **PK:** `agentId` (`id`) — opaque, stable identity (sequenced for user-created
  agents; an explicit stable id in seed data). The opaque PK means renaming an
  agent never breaks its grants or run history.
- **Unique index:** `AI_AGENT_NAME` on `agentName`.

| Field | Type | Purpose |
|---|---|---|
| `agentId` | `id` (PK) | Opaque stable identity. |
| `agentName` | `text-short` | Editable, human-friendly label. **Unique** across agents. |
| `description` | `text-long` | What the agent is for; may seed the prompt on create. |
| `providerName` | `text-short` | LLM provider (e.g. `openai`, `anthropic`, `mock`). |
| `modelName` | `text-medium` | Configured primary model. |
| `systemPrompt` | `text-very-long` | System prompt for the agent. |
| `responseSchema` | `text-very-long` | Optional JSON Schema (as text). When set, `run#Agent` returns a typed `structuredResult` Map; adapters translate it to the provider's native structured-output mechanism. |
| `contextStrategy` | `text-short` | Conversation-context handling. The code (`AgentRunner.groovy`) recognizes three values: **`off`** (default), **`window`** (bound replayed history + offer the `remember` tool + inject pinned facts — ADR 0001 Phase 1), and **`summarize`** (window behavior plus rolling compaction of overflow — ADR 0001 Phase 2). *(The field's own description comment lists only `off | window`; the code is authoritative and treats `summarize` as a valid third value.)* |
| `contextWindowMessages` | `number-integer` | When strategy is `window`/`summarize`: max replayed prior-turn messages to keep (current turn always kept). Defaults to 20 when unset. |
| `contextWindowChars` | `number-integer` | When strategy is `window`/`summarize`: char-estimate guard (~chars/4 tokens) for the assembled view. Defaults to 48000 when unset. |
| `reasoningEffort` | `text-short` | `none` (default/unset) `| low | medium | high`. Provider-agnostic reasoning depth. OpenAI → `reasoning_effort` (reasoning-capable models only). Anthropic → extended-thinking budget; **v1 applies it only to agents WITHOUT tool grants** (reasoning + tools on Anthropic is deferred). No effect on models that don't support reasoning. |
| `maxIterations` | `number-integer` | Agentic-loop cap (defaults to 5 on create). |
| `maxTokens` | `number-integer` | Per-call max output tokens. |
| `maxCost` | `number-decimal` | Cost ceiling. **Stored but NOT enforced** — the field (and its pass-through service param) exist, but no code reads it as a guard. |
| `maxToolCallsPerTurn` | `number-integer` | Cap on tool calls dispatched within a single turn. |
| `statusId` | `id` | → `AiAgentStatus`: `AI_AGENT_DRAFT | AI_AGENT_ACTIVE | AI_AGENT_DISABLED`. |

- **Relationships:** `status` → `moqui.basic.StatusItem`.

---

### AiTool

`entity/AiEntities.xml`. A Moqui service exposed to agents as a callable tool. The
**opaque `toolId` PK** is the registry keystone — renaming never breaks grants or
history.

- **PK:** `toolId` (`id`) — opaque, stable identity (sequenced for user-created;
  explicit stable id in seed data).
- **Unique index:** `AI_TOOL_NAME` on `toolName`.

| Field | Type | Purpose |
|---|---|---|
| `toolId` | `id` (PK) | Opaque stable identity. |
| `toolName` | `text-short` | Derived `verb_noun`, snake_case, wire-safe (`^[a-z0-9_]+$`). The LLM-facing name. **Unique** across tools. |
| `verb` | `text-short` | Editable verb (e.g. `list`, `cancel`). |
| `noun` | `text-short` | Editable noun (e.g. `orders`, `return`). |
| `description` | `text-long` | LLM-facing "what / when to use". |
| `serviceName` | `text-medium` | Backing Moqui service FQN — **an attribute, NOT a key** (no FK to a service entity). |
| `effectEnumId` | `id` | → `AiToolEffect`: `AI_TOOL_READ_ONLY | AI_TOOL_MUTATING`. Derived from the service verb. |
| `exposable` | `text-indicator` | `Y/N` — may agents be granted this tool at all? A denylist match forces `N`. |
| `requiresApproval` | `text-indicator` | `Y/N` — run-time human gate. A proposed call to an approval-required tool suspends the run (see `AiToolCallRequest`). |
| `sourceComponent` | `text-medium` | Provenance: which component seeded it; null if user-authored. |
| `createdByUserId` | `id` | Provenance for user-authored tools. |
| `statusId` | `id` | → `AiToolStatus`: `AI_TOOL_ACTIVE | AI_TOOL_DISABLED`. |

- **Relationships:** `effect` → `moqui.basic.Enumeration` (title `AiToolEffect`,
  key-map `effectEnumId` → `enumId`); `status` → `moqui.basic.StatusItem`.

---

### AiAgentTool

`entity/AiEntities.xml`. The grant join: which tools an agent may use. Created only
through the explicit, exposable-gated `store#AiAgentTool` service wrapper.

- **PK:** `agentId` + `toolId` (composite).

| Field | Type | Purpose |
|---|---|---|
| `agentId` | `id` (PK) | The agent being granted. |
| `toolId` | `id` (PK) | The granted tool. |
| `requiresApprovalOverride` | `text-indicator` | Optional `Y/N`. Lets one agent be **stricter** than the tool's default `requiresApproval` — never looser; the tool default is a floor. |

- **Relationships:** `agent` → `moqui.ai.AiAgent`; `tool` → `moqui.ai.AiTool`
  (**`one-nofk`** — the tool may be a seeded/user row, so the grant stays resilient
  to catalog edits).

---

### AiAgentModel

`entity/AiEntities.xml`. The **multi-provider failover chain**: ordered
provider/model candidates for one agent. Sticky failover advances to the next
priority on a provider-call failure.

- **PK:** `agentId` + `priority` (composite).

| Field | Type | Purpose |
|---|---|---|
| `agentId` | `id` (PK) | The owning agent. |
| `priority` | `number-integer` (PK) | Lower is tried first (`0,1,2,…`). Sticky failover advances on provider-call failure. |
| `providerName` | `text-short` | Candidate provider. |
| `modelName` | `text-medium` | Candidate model. |

- **Relationships:** `agent` → `moqui.ai.AiAgent` (**`one-nofk`** — config child;
  keeps agent delete / test-cleanup unencumbered, mirroring `AiAgentRun`).

---

### AiAgentRun

`entity/AiEntities.xml`. The top-level **observability** record — one row per agent
invocation. Append-only audit.

- **PK:** `agentRunId` (`id`).

| Field | Type | Purpose |
|---|---|---|
| `agentRunId` | `id` (PK) | Run identity. |
| `agentId` | `id` | The agent that produced this run (FK by id). |
| `agentName` | `text-short` | **Denormalized snapshot** of the agent's label *at run time*, so history reads correctly after a rename. |
| `userId` | `id` | The signed-in user the run executed as. |
| `startedDate` | `date-time` | Run start. |
| `endedDate` | `date-time` | Run end; null while RUNNING/SUSPENDED. Duration = `endedDate − startedDate` (derived, not stored). |
| `statusId` | `id` | → `AiAgentRunStatus`: `AI_RUN_RUNNING | AI_RUN_COMPLETED | AI_RUN_FAILED | AI_RUN_TRUNCATED | AI_RUN_ABORTED | AI_RUN_SUSPENDED`. |
| `providerName` | `text-short` | Configured provider for the run. |
| `modelName` | `text-medium` | Configured primary model. |
| `servedByModelId` | `text-medium` | The model that **actually** served the run; differs from the configured primary when fallback occurred. |
| `providerRunId` | `text-medium` | The provider's own response id (OpenAI id / Anthropic message id), for support correlation. |
| `userMessage` | `text-very-long` | The user's input message. |
| `assistantMessage` | `text-very-long` | The assistant's final message. |
| `iterations` | `number-integer` | Loop iterations executed. |
| `tokensIn` | `number-integer` | Total input tokens. |
| `tokensOut` | `number-integer` | Total output tokens. |
| `estimatedCost` | `number-decimal` | Estimated cost, priced off the **served** model. |
| `errorText` | `text-very-long` | Error detail when failed. |
| `pendingState` | `text-very-long` | JSON loop state when `AI_RUN_SUSPENDED` (messages, replayCount, stepSeq, candIdx, summary, result, turnToolCalls) — the snapshot `resume()` reloads. |
| `conversationId` | `id` | The conversation this run belongs to (optional). |
| `isPreview` | `text-indicator` | `Y` when this run is a **sandbox preview** (`ComposerServices.preview#Agent` / `AgentRunner.runPreview`): mutating tools are force-gated so the run SUSPENDS to show would-be calls without executing them. Throwaway — its suspended row + pending approvals are **excluded** from the operator queue (`get#PendingToolCallRequest`, `Approvals.xml`) and deleted by `discard#Draft`. Null/absent = a normal run. |

- **Relationships:** `agent` → `moqui.ai.AiAgent` (**`one-nofk`** — append-only audit,
  must not block the agent lifecycle); `conversation` → `moqui.ai.AiConversation`
  (`one-nofk`); `status` → `moqui.basic.StatusItem`.

> **`userMessage`/`assistantMessage` overlap `AiConversationMessage` rows by design — not duplicate
> data.** The run header is the immutable, self-contained audit (and the *only* record for a
> stateless run with no conversation); the conversation messages are the lossy, replayed transcript,
> joined back by `agentRunId`. See
> [explanation/architecture.md → Runs vs. conversations](../explanation/architecture.md#runs-vs-conversations).

---

### AiAgentRunStep

`entity/AiEntities.xml`. One step within a run — each LLM round-trip, tool batch,
context operation, or failed provider call.

- **PK:** `agentRunId` + `stepSeqId` (composite).

| Field | Type | Purpose |
|---|---|---|
| `agentRunId` | `id` (PK) | Owning run. |
| `stepSeqId` | `id` (PK) | Step sequence within the run. |
| `stepType` | `text-short` | The kind of step: `llm_call | context_trim | compaction`. (Outcome lives in `success`; a failed llm call is `stepType=llm_call`, `success=N`.) |
| `tokensIn` | `number-integer` | Step input tokens. |
| `tokensOut` | `number-integer` | Step output tokens. |
| `success` | `text-indicator` | `Y/N` outcome of an `llm_call` step (a failed failover attempt = `N`). Null/Y for non-call steps. |
| `finishReason` | `text-short` | Provider finish reason for the step. |

- **Relationships:** `run` → `moqui.ai.AiAgentRun`.

---

### AiToolCall

`entity/AiEntities.xml`. One executed tool call inside a step — the dispatch audit.

- **PK:** `agentRunId` + `stepSeqId` + `toolCallId` (composite).

| Field | Type | Purpose |
|---|---|---|
| `agentRunId` | `id` (PK) | Owning run. |
| `stepSeqId` | `id` (PK) | Owning step. |
| `toolCallId` | `id` (PK) | Tool-call identity within the step. |
| `toolId` | `id` | The tool that was called (id). `toolName`/`serviceName` are display/dispatch **snapshots**. |
| `toolName` | `text-medium` | Snapshot of the tool's wire name at call time. |
| `serviceName` | `text-medium` | Snapshot of the dispatched service FQN. |
| `arguments` | `text-very-long` | JSON of the call arguments. |
| `result` | `text-very-long` | JSON of the call result. |
| `success` | `text-indicator` | `Y/N`. |
| `errorText` | `text-very-long` | Error detail on failure. |
| `durationMs` | `number-integer` | Call duration in milliseconds. |

- **Relationships:** none declared (PK ties it to the run/step).

---

### AiToolDenylist

`entity/AiToolEntities.xml`. The **non-overridable safety floor**: service-name
regex patterns that may never become tools. Checked by `store#AiTool`; a match
forces `exposable=N` and refuses any override.

- **PK:** `servicePattern` (`text-medium`).

| Field | Type | Purpose |
|---|---|---|
| `servicePattern` | `text-medium` (PK) | Regex of service names that may never be exposed as a tool. |
| `reason` | `text-medium` | Why — shown to the Curator. |

Seeded patterns (`data/AiStatusData.xml`):

| `servicePattern` | `reason` |
|---|---|
| `.*\.delete#.*` | Deletes must not be AI-exposable |
| `org\.moqui\.impl\..*` | Framework-internal services |
| `.*[Pp]assword.*` | Credential/security services |
| `.*UserAccount.*` | User/account administration |
| `.*ArtifactAuthz.*` | Authorization administration |

---

### AiCapabilityRequest

`entity/AiComposerEntities.xml`. A gap the Composer Assistant found but cannot fill
(only the Curator may create tools). The Curator works this queue from the **Capability
Requests** console — dismiss, fulfill (link a tool), or provision (create a tool inline).

- **PK:** `capabilityRequestId` (`id`).

| Field | Type | Purpose |
|---|---|---|
| `capabilityRequestId` | `id` (PK) | Request identity. |
| `intent` | `text-long` | What the user wanted, in their words. |
| `suggestedVerb` | `text-short` | Proposed verb. |
| `suggestedNoun` | `text-short` | Proposed noun. |
| `notes` | `text-long` | Free-text notes. |
| `requestedByUserId` | `id` | Who raised it. |
| `agentRunId` | `id` | The compose run that surfaced the gap (provenance). |
| `conversationId` | `id` | Originating conversation. |
| `requestedDate` | `date-time` | When raised. |
| `statusId` | `id` | → `AiCapReqStatus`: `AI_CAPREQ_OPEN | AI_CAPREQ_DONE | AI_CAPREQ_DISMISSED`. |
| `resolvedByUserId` | `id` | Curator who fulfilled/dismissed it. |
| `resolvedDate` | `date-time` | When resolved. |
| `resolutionNote` | `text-long` | Curator's resolution note. |
| `fulfilledToolId` | `id` | The tool created/linked when fulfilled (provenance). |

- **Relationships:** `status` → `moqui.basic.StatusItem`; `run` →
  `moqui.ai.AiAgentRun` (`one-nofk`); `fulfilledTool` → `moqui.ai.AiTool`
  (`one-nofk`, `fulfilledToolId` → `toolId`).

---

### AiConversation

`entity/AiConversationEntities.xml`. A multi-turn conversation with an agent.
Carries the **rolling-summary** (compaction) state.

- **PK:** `conversationId` (`id`).

| Field | Type | Purpose |
|---|---|---|
| `conversationId` | `id` (PK) | Conversation identity. |
| `agentId` | `id` | The agent this conversation belongs to (by id). |
| `userId` | `id` | The owning user. |
| `title` | `text-medium` | Display title. |
| `createdDate` | `date-time` | When the conversation was created. |
| `summaryText` | `text-very-long` | ADR 0001 Phase 2: rolling summary of turns older than `summaryThruMessageSeqId` (compaction). |
| `summaryThruMessageSeqId` | `id` | Watermark: `messageSeqId` of the newest message already folded into `summaryText`. |
| `statusId` | `id` | → `AiConversationStatus`: `AI_CONV_ACTIVE | AI_CONV_CLOSED`. |

- **Relationships:** `agent` → `moqui.ai.AiAgent` (`one-nofk`); `status` →
  `moqui.basic.StatusItem`.

> **Last activity is derived, not stored.** The former `lastActivityDate` column was dropped
> (*derive, don't denormalize*): the `AiConversationActivity` view-entity exposes
> `lastActivityDate = MAX(AiConversationMessage.createdDate)`, which the Conversations screen sorts on.

---

### AiConversationMessage

`entity/AiConversationEntities.xml`. One persisted message per turn-part, in order,
replayed on the next call.

- **PK:** `conversationId` + `messageSeqId` (composite).

| Field | Type | Purpose |
|---|---|---|
| `conversationId` | `id` (PK) | Owning conversation. |
| `messageSeqId` | `id` (PK) | Message order within the conversation. |
| `role` | `text-short` | `system | user | assistant | tool`. |
| `content` | `text-very-long` | Message content. |
| `toolCalls` | `text-very-long` | JSON of `List<Map>` when an assistant turn requested tools. |
| `toolCallId` | `text-medium` | Set when `role = tool`. |
| `agentRunId` | `id` | Which run produced this message. |
| `createdDate` | `date-time` | When this message-part was recorded. |

- **Relationships:** `conversation` → `moqui.ai.AiConversation`.

> **Overlaps `AiAgentRun.userMessage`/`assistantMessage` by design — not duplicate data.** These
> rows are the durable, replayed transcript (joined to their run by `agentRunId`); the run header is
> the immutable per-execution audit. See
> [explanation/architecture.md → Runs vs. conversations](../explanation/architecture.md#runs-vs-conversations).

---

### AiConversationFact

`entity/AiConversationEntities.xml`. **Pinned facts** (ADR 0001 fidelity guarantee):
durable confirmed business values an agent records via the server-injected
`remember` tool. Conversation-scoped, keyed, store-or-update (a new value supersedes
the old). Injected into every call's system context; never compressed or dropped.

- **PK:** `conversationId` + `factKey` (composite).

| Field | Type | Purpose |
|---|---|---|
| `conversationId` | `id` (PK) | Owning conversation. |
| `factKey` | `text-medium` (PK) | The fact's key. |
| `factValue` | `text-very-long` | The fact's value. |
| `agentRunId` | `id` | The run that last recorded this fact. |
| `createdDate` | `date-time` | When first created. |

> Moqui auto-adds `lastUpdatedStamp` = when the fact was last set (supersession time).

---

### AiToolCallRequest

`entity/AiApprovalEntities.xml`. One pending decision per approval-required tool call
in a suspended turn. The human-approval gate's queue.

- **PK:** `toolCallRequestId` (`id`).

| Field | Type | Purpose |
|---|---|---|
| `toolCallRequestId` | `id` (PK) | Approval identity. |
| `agentRunId` | `id` | The suspended run. |
| `stepSeqId` | `id` | The step the call belongs to. |
| `toolCallId` | `id` | The proposed call's id. |
| `toolName` | `text-medium` | The tool's wire name. |
| `serviceName` | `text-medium` | The backing service. |
| `arguments` | `text-very-long` | JSON of the proposed call args. |
| `statusId` | `id` | → `AiToolCallRequestStatus`: `AI_TCREQ_PENDING | AI_TCREQ_APPROVED | AI_TCREQ_REJECTED`. |
| `requestedByUserId` | `id` | Who triggered the run. |
| `requestedDate` | `date-time` | When the approval was raised. |
| `decidedByUserId` | `id` | Who decided. |
| `decidedDate` | `date-time` | When decided. |
| `decisionNote` | `text-long` | Optional decision note. |

- **Relationships:** `run` → `moqui.ai.AiAgentRun` (`one-nofk` — append-only audit,
  must not block the run/agent lifecycle); `status` → `moqui.basic.StatusItem`.

---

### AiModelPrice

`entity/AiPriceEntities.xml`. Effective-dated price per (provider, model). A model's
price changes over time; old runs keep the price current at run time.

- **PK:** `providerName` + `modelName` + `fromDate` (composite).

| Field | Type | Purpose |
|---|---|---|
| `providerName` | `text-short` (PK) | Provider. |
| `modelName` | `text-medium` (PK) | Model. |
| `fromDate` | `date-time` (PK) | Effective-from date. |
| `thruDate` | `date-time` | Effective-thru date (null = current). |
| `inputPricePerMillion` | `currency-precise` | Input price **per 1,000,000 tokens**. |
| `outputPricePerMillion` | `currency-precise` | Output price **per 1,000,000 tokens**. |
| `currencyUomId` | `id` | Currency UOM. |

- **Relationships:** none declared.

---

### AiDomainTerm

`entity/AiGlossaryEntities.xml`. The **glossary**: curated domain nouns + capability
verbs, typed, provenanced, and status-gated. Used to ground tool/agent naming.

- **PK:** `termId` (`id`).
- **Unique index:** `AI_TERM_UNIQUE` on `term` + `termKind` + `ownerScope`.

| Field | Type | Purpose |
|---|---|---|
| `termId` | `id` (PK) | Term identity. |
| `term` | `text-short` | Canonical term (`order`, `cancel`); unique per `termKind` + `ownerScope`. |
| `termKind` | `id` | → `AiTermKind`: `AI_TERM_NOUN | AI_TERM_VERB`. |
| `description` | `text-long` | Term description. |
| `sourceType` | `id` | → `AiTermSource`: `AI_TSRC_SEEDED | AI_TSRC_LEARNED | AI_TSRC_CURATED`. |
| `statusId` | `id` | → `AiDomainTermStatus`: `AI_TERM_SUGGESTED | AI_TERM_APPROVED | AI_TERM_REJECTED`. |
| `usageCount` | `number-integer` | Chosen-in-authoring reinforcement count. |
| `ownerScope` | `id` | Reserved for tenant/deployment scope; null = global in v1. |

- **Relationships:** `status` → `moqui.basic.StatusItem`; `kindEnum` →
  `moqui.basic.Enumeration` (`one-nofk`, title `AiTermKind`, key-map `termKind` →
  `enumId`).

> **Term status flow** (`data/AiGlossaryData.xml`): `SUGGESTED → APPROVED`,
> `SUGGESTED → REJECTED`, and `APPROVED → REJECTED` ("Retire").

---

### AiTermSynonym

`entity/AiGlossaryEntities.xml`. The **dialect**: aliases that map to a canonical
term (e.g. `rma` → `return`).

- **PK:** `termId` + `synonym` (composite).

| Field | Type | Purpose |
|---|---|---|
| `termId` | `id` (PK) | The canonical term this aliases. |
| `synonym` | `text-short` (PK) | The alias. |
| `sourceType` | `id` | Provenance (same `AiTermSource` enum domain as `AiDomainTerm`). |
| `statusId` | `id` | Lifecycle status (`AiDomainTermStatus` domain). |

- **Relationships:** `domainTerm` → `moqui.ai.AiDomainTerm`; `status` →
  `moqui.basic.StatusItem`.

---

### AiNamingSignal

`entity/AiGlossaryEntities.xml`. The **learning log**: what the Composer proposed vs.
what the human kept, per authoring event. Feeds the promote-terms-from-signals loop.

- **PK:** `signalId` (`id`).

| Field | Type | Purpose |
|---|---|---|
| `signalId` | `id` (PK) | Signal identity. |
| `signalType` | `id` | → `AiSignalType`: `AI_SIG_TOOL_NAME | AI_SIG_AGENT_NAME`. |
| `intentText` | `text-long` | The user's described intent / the backing service. |
| `suggestedName` | `text-medium` | What the Composer proposed (null if human-authored directly). |
| `chosenName` | `text-medium` | What the human kept. |
| `wasOverridden` | `text-indicator` | `Y` if `chosen != suggested`. |
| `userId` | `id` | Who authored. |
| `createdDate` | `date-time` | When the signal was captured. |

- **Relationships:** none declared.

---

## Glossary auto-capture (EECA floor)

`entity/AiGlossaryEcas.eecas.xml` declares two entity-ECAs that backstop
naming-signal capture for **any** `AiTool`/`AiAgent` write not already captured
in-service and not a seed. The rich in-service hook (`store#AiTool` /
`store#AiAgent` calling `capture#NamingSignal` with `suggestedName`/`intentText`)
is preferred; these EECAs catch direct writes (e.g. a raw `EntityValue.store()` or a
future builder path that bypasses the store services). No `MoquiConf.xml`
registration is needed — Moqui auto-scans `entity/*.eecas.xml`.

| EECA id | Entity | Triggers | Action |
|---|---|---|---|
| `AiToolNamingSignal` | `moqui.ai.AiTool` | on-create, on-update | calls `ai.GlossaryServices.capture#NamingSignal` with `signalType: AI_SIG_TOOL_NAME`, `chosenName: toolName` |
| `AiAgentNamingSignal` | `moqui.ai.AiAgent` | on-create, on-update | calls `ai.GlossaryServices.capture#NamingSignal` with `signalType: AI_SIG_AGENT_NAME`, `chosenName: agentName` |

Both fire only when the guard condition holds:
`ec.context.signalGuard != true && ec.context.signalCaptured != true` — so a write
that already captured a signal in-service (which sets `signalCaptured`), or one that
explicitly suppresses capture (which sets `signalGuard`, e.g. seed loads), does not
double-record.

---

## Conventions recap

- **Opaque PKs.** `AiAgent` and `AiTool` use opaque `agentId`/`toolId`; the
  human-facing `agentName`/`toolName` are unique editable labels. Renames never break
  grants (`AiAgentTool`), failover config (`AiAgentModel`), or audit history
  (`AiAgentRun`/`AiToolCall`). Older specs and the archived plans use the pre-refactor
  `agentName`/`toolName` PKs — those are historical; the code above is canonical.
- **Run-time snapshots.** Audit entities denormalize the label/name/service at the
  moment of execution (`AiAgentRun.agentName`, `AiToolCall.toolName`/`serviceName`) so
  history reads correctly after later renames.
- **`one-nofk` for audit/config children.** Audit (`AiAgentRun`, `AiToolCallRequest`) and
  config-child (`AiAgentModel`) relationships, and the grant→tool link, use `one-nofk`
  so they never block the agent/conversation/run lifecycle or resist catalog edits.
- **Status vs. enumeration.** Lifecycle fields are `StatusItem` with
  `StatusFlowTransition` flows; fixed, code-referenced classifications are
  `Enumeration`. `StatusValidChange` is not used anywhere in this component.
