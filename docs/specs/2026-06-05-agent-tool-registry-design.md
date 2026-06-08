# Agent & Tool Registry — Design Spec

> **Reconciled 2026-06-08.** The canonical as-built state is in ../reference/ and ../explanation/. Where this spec and the code disagree, the code wins.

> **Agent-Builder Keystone.** The foundational layer that makes tools and agents *mutable data
> with stable identity*, so they can be authored at runtime. The builder agent and its domain
> knowledgebase are separate specs that sit on top of this one.

- **Date:** 2026-06-05
- **Status:** Shipped (reconciled 2026-06-08)
- **Component:** `moqui-ai` (https://github.com/hotwax/moqui-ai, branch `feature/ai-agent-framework`)
- **Platform:** HotWax fork of Moqui, **JDK 11**
- **Supersedes for tools/agents:** the in-memory catalog built from `ai/*.tools.xml` by `DefinitionLoader`

---

## 1. Purpose

Today a tool's identity *is* the backing Moqui service's fully-qualified name. `DefinitionLoader`
sets `toolName == serviceName` (e.g. `notnaked.OmsAiServices.get#OrderSummaryList`) and uses it as
the `AiTool` / `AiAgentTool` primary key. Three costs fall out of that one decision:

1. **Width** — a service FQN overflows the `id` (40-char) PK column (the live truncation bug that
   blocked the NotNaked demo).
2. **Leak** — the implementation path is what the model sees as its tool name (after a lossy
   `sanitizeName` round-trip in the provider).
3. **Rigidity** — identity is welded to the service location, so nothing can be renamed, and tools
   can only be declared in code (XML), never authored at runtime.

This spec replaces that with a registry where **tools and agents are mutable records with a stable
opaque identity and editable, human-friendly names** — the prerequisite for an *agent that builds
agents*. It also folds in the naming model and the safety model for runtime authoring.

## 2. Scope

**In scope (the keystone):**
- Stable-identity + editable-naming model for `AiTool` and `AiAgent`.
- DB as the source of truth, seeded via the standard entity data loader.
- The safety model for exposing a service as a tool (roles, derived danger, denylist).
- The authoring services (`store#AiTool` / `store#AiAgent`) and their validation.
- The consequent changes to the run-time loop and the existing code/tests/demo.

**Out of scope (separate specs that sit on this):**
- The **builder agent** — the meta-agent UI/flow that *proposes* names, descriptions, and tool
  selections and calls the authoring services.
- The **builder knowledgebase / domain glossary** — learned, OMS-grounded vocabulary that gets
  better over time (ties to the knowledge-retrieval backlog).
- Multi-tenant scoping of the catalog.

## 3. Decisions

- **D1 — Stable opaque identity, editable names (Option B).** `toolId` and `agentId` are opaque,
  stable PKs that never change. `verb`/`noun`/`toolName`/`description` (tools) and
  `agentName`/`description` (agents) are mutable attributes. Renaming never breaks grants or
  history. The service FQN becomes a plain attribute (`serviceName`), not a key.
- **D2 — Two roles.** A **Capability Curator** (e.g. OMS administrator / power user) turns services
  into tools — the gated half. An **Agent Composer** (business user) assembles, names, and prompts
  agents from the curated catalog and can never expose a raw service.
- **D3 — DB is the source of truth; seeded as standard data.** Tools and agents are ordinary entity
  records declared in `data/*.xml` and loaded by the entity data loader (`seed` for
  platform/framework, `ext`/`ext-seed` for client/demo). Runtime edits are DB writes. This replaces
  the bespoke `ai/*.tools.xml` scan.
- **D4 — Safety: derive + floor.** `effect` (READ_ONLY | MUTATING) is derived from the service;
  read-only is auto-exposable, mutating is Curator-only and defaults to `requiresApproval=Y`. A
  non-overridable **denylist** of service-name patterns forces `exposable=N` for admin/security/
  internal services.
- **D5 — Naming: guidance, not enum.** `toolName` = `verb_noun`, derived and unique; the *quality*
  of names comes later from the builder + knowledgebase, not from a fixed vocabulary here.

## 4. Data model

All entities `package="moqui.ai"`. Changes only; unlisted fields are unchanged.

### 4.1 `AiTool` (the capability catalog — restructured)

| Field | Type | Role |
|---|---|---|
| `toolId` | `id` (PK) | opaque, stable identity (sequenced for user-created; explicit stable id in seed data) |
| `toolName` | `text-short`, unique index | derived `verb_noun` — the LLM-facing name |
| `verb` | `text-short` | editable (`list`, `cancel`) |
| `noun` | `text-short` | editable (`orders`, `return`) |
| `description` | `text-long` | LLM-facing "what / when to use" |
| `serviceName` | `text-medium` | backing Moqui service (the FQN) — **attribute, not a key** |
| `effect` | `id` | `AI_TOOL_READ_ONLY` \| `AI_TOOL_MUTATING` (derived) |
| `exposable` | `text-indicator` | may agents be granted this at all |
| `requiresApproval` | `text-indicator` | run-time human gate (existing) |
| `sourceComponent` | `text-medium` | provenance (which component seeded it; null if user-authored) |
| `createdByUserId` | `id` | provenance for user-authored tools |
| `statusId` | `id` | `AI_TOOL_ACTIVE` \| `AI_TOOL_DISABLED` |

Removed: `toolName` as PK. Note: the JSON schema is **not** stored — generated on demand (§6).

### 4.2 `AiAgent` (restructured)

| Field | Type | Role |
|---|---|---|
| `agentId` | `id` (PK) | opaque, stable identity |
| `agentName` | `text-short`, unique index | editable, human-friendly label |
| `description` | `text-long` | what the agent is for (authored with guidance; seeds the prompt — see defaults below) |
| `systemPrompt`, `providerName`, `modelName`, `responseSchema`, `contextStrategy`, `contextWindowMessages`, `contextWindowChars`, `reasoningEffort`, `maxIterations`, `maxTokens`, `maxCost`, `maxToolCallsPerTurn`, `statusId` | — | same fields/types as before; only `agentName`-as-PK is removed. **Several are defaulted on create** — see below. |

> **As-built correction (was "unchanged").** This spec originally said these fields were "unchanged."
> The shipped `ai.AgentServices.store#AiAgent` **defaults them on create** so a freshly drafted agent is
> runnable without hand-editing entities (the user/Composer describes *what* it does and the system picks
> the model, so `preview#Agent`/`activate#Agent` work out of the box):
> - `providerName` ← `ai_default_provider` (system property) → fallback `openai`
> - `modelName` ← `ai_default_model` (system property) → fallback `gpt-4o-mini`
> - `maxIterations` ← `5`
> - `systemPrompt` ← `description`
> - `statusId` ← `AI_AGENT_DRAFT`
>
> These are **create-only** (guarded by "no `agentId`") and use `?:` — they never override an explicit
> value and never run on update.

### 4.3 Grant + config + observability (re-keyed to ids)

- **`AiAgentTool`** (grant): PK `(agentId, toolId)` — was `(agentName, toolName)`. New optional
  `requiresApprovalOverride` (`text-indicator`) lets one agent be stricter than the tool default.
- **`AiAgentModel`**: PK `(agentId, priority)` — was `(agentName, priority)`.
- **`AiAgentRun`**: `agentName` field → `agentId`; add `agentName` as a **denormalized snapshot**
  (the label at run time, so history reads correctly after a rename).
- **`AiConversation`**: `agentName` field → `agentId`.
- **`AiToolCall`**: add `toolId` + `serviceName`; keep `toolName` as the display snapshot.

### 4.4 `AiToolDenylist` (new)

| Field | Type | Role |
|---|---|---|
| `servicePattern` | `text-medium` (PK) | regex of service names that may **never** become tools |
| `reason` | `text-medium` | why (shown to the Curator) |

Seeded with admin/security/internal patterns. Checked by `store#AiTool`; non-overridable.

### 4.5 Statuses / enums (seed data)

- `StatusItem` (statusTypeId `AiToolStatus`): `AI_TOOL_ACTIVE`, `AI_TOOL_DISABLED`.
- `StatusItem` (statusTypeId `AiAgentStatus`): `AI_AGENT_DRAFT`, `AI_AGENT_ACTIVE`, `AI_AGENT_DISABLED`.
  (`AI_AGENT_DRAFT` supports the Composer Assistant's draft lifecycle; drafts are never listed or run as active.)
- Enumeration (or `StatusItem`) for `effect`: `AI_TOOL_READ_ONLY`, `AI_TOOL_MUTATING`.
  (Enumeration is the right archetype per the UDM guide — a fixed, code-referenced type, not a
  lifecycle. Confirm against the guide at implementation.)

## 5. Naming & derivation rules

- `toolName = (verb + "_" + noun)`, lowercased, snake_case. Recomputed whenever `verb`/`noun`
  change. Wire-safe by construction (`^[a-z0-9_]+$`).
- `toolName` is **unique** across active tools (entity unique index). A collision makes
  `store#AiTool` fail with a clear message; the Curator (or, later, the builder) picks another
  verb/noun. `agentName` is unique the same way.
- Because names are wire-safe, the provider `sanitizeName` / `remapToolNames` round-trip collapses
  to a thin defensive no-op (kept as a safety net, not a load-bearing translation).

## 6. Validation, safety & schema — one gate

`store#AiTool` and `store#AiAgent` are the single authoring path. `store#AiTool`:

1. **Validate** the `serviceName` resolves to a real service (fail-loud, reusing the existing
   `ToolSchemaBuilder` resolution). Unknown service → error.
2. **Derive `effect`** from the service verb: read verbs (`get`, `find`, `list`, `view`, `search`,
   `check`, `calculate`) → `READ_ONLY`; everything else → `MUTATING`. *(v1 heuristic; deeper
   static "does it write entities?" analysis is a future refinement. Curator may override within
   authz.)*
3. **Apply the denylist** — if `serviceName` matches any `AiToolDenylist.servicePattern`, force
   `exposable=N` and refuse any override. This is the hard floor.
4. **Defaults** — read-only → `exposable=Y`, `requiresApproval=N`; mutating → `exposable=N` (Curator
   must bless), `requiresApproval=Y`.
5. **Derive + uniqueness-check `toolName`** (§5).

**Schema, on demand.** When the run loop assembles `request.tools`, the JSON schema for each granted
tool is generated from the *live* service definition via `ToolSchemaBuilder` — never stored, never
stale.

**Authz.** Creating/updating tools (`store#AiTool`) is gated to the Curator role; assembling agents
(`store#AiAgent` + grants) to the Composer role. Standard Moqui `ArtifactAuthz`. The Composer can
only grant tools where `exposable=Y` and `statusId=AI_TOOL_ACTIVE`.

**Naming-signal seam (as-built).** Both `store#AiTool` and `store#AiAgent` accept two pass-through
inputs — `suggestedName` (what the Composer proposed) and `intentText` (the user's described intent) —
that are inert to authoring. After the entity write, each calls
`ai.GlossaryServices.capture#NamingSignal` (with `signalType = AI_SIG_TOOL_NAME` / `AI_SIG_AGENT_NAME`,
`chosenName`, `suggestedName`, `intentText`) so an override becomes a learnable signal for the Builder
Knowledgebase. This is the framework's single semantic-capture point. Each store service sets
`ec.context.signalCaptured = true` **before** the write so the defensive `AiGlossaryEcas` EECA floor on
`AiTool`/`AiAgent` skips that row — the rich in-service capture is preferred over the EECA floor. The
signal write is non-fatal to authoring.

**`grant_capability` is an explicit wrapper, not entity-auto.** The Composer's `grant_capability`
backing service is the **explicit** `ai.AgentServices.store#AiAgentTool`, *not* the entity-auto
`store#moqui.ai.AiAgentTool`. It must be explicit so it can itself be exposed as a tool (entity-auto
services have no `ServiceDefinition` and cannot be introspected by `ToolSchemaBuilder`). It enforces the
safety floor — it loads the tool and **refuses the grant unless `exposable=Y` and
`statusId=AI_TOOL_ACTIVE`** (a draft can never reference a service the Curator hasn't blessed) — and only
then delegates to the entity-auto `store#moqui.ai.AiAgentTool` for the actual idempotent write.

## 7. Data flow

1. **Seed / ext (load time).** Platform tools ship as `seed` `AiTool` data; client/demo tools +
   agents + grants ship as `ext`/`ext-seed` in the owning component's `data/` dir. `gradlew load`
   loads them. (NotNaked: `get#OrderSummaryList` tool + `nn-oms-assistant` agent + grant become
   `ext-seed` data — replacing the `ensure#OmsAssistant` on-demand hack.)
2. **Runtime authoring.** Curator → `store#AiTool` (§6). Composer → `store#AiAgent` + grant rows.
   These services are exactly what the future builder agent will call as its own tools.
3. **Catalog reads.** `AiToolFactory` serves tool defs by querying `AiTool` (active + exposable for
   grant-eligibility) and building schemas on demand — instead of parsing an XML catalog at boot.
4. **Run time.** `AgentRunner` loads the agent (by `agentId`, resolvable by `agentName`) + its
   grants → `AiTool` rows → builds `request.tools` (`toolName` + `description` + on-demand schema) →
   dispatches via `serviceName`.

## 8. Impact on existing code (no migration — pre-release)

Nothing is released, so this is a code+test change, not a data migration. Touch-points:

- **Entities:** `AiEntities.xml` (`AiTool`, `AiAgent`, `AiAgentTool`, `AiAgentModel`, `AiAgentRun`),
  `AiConversationEntities.xml` (`AiConversation.agentId`), `AiToolCall`; new `AiToolDenylist`.
- **Loader/factory:** remove `DefinitionLoader`'s `ai/*.tools.xml` scan; `AiToolFactory` reads the DB
  + uses `ToolSchemaBuilder` on demand. `ToolSchemaBuilder` itself is retained.
- **Run loop:** `AgentRunner` keys by `agentId`/`toolId`; `AbstractLlmProvider` sanitize becomes a
  no-op safety net.
- **Services:** `run#Agent` accepts `agentName` or `agentId`; add `store#AiTool` / `store#AiAgent`;
  re-key any approval/cost lookups that referenced names.
- **NotNaked demo:** delete `ensure#OmsAssistant` + the on-demand creation; add `ext-seed` data.
  The `get#OrderSummaryList` service is unchanged.
- **Tests:** update all suites that use `agentName`/`toolName` as keys to the id-based model; add
  unit tests for §5/§6; replace the XML-catalog loader tests with DB-seeded catalog tests.
- **Dev DB:** drop/recreate the affected tables (empty) so columns match the new model.

## 9. Testing

- **Unit:** `toolName` derivation + uniqueness; `effect` derivation per verb; denylist floor;
  `store#AiTool`/`store#AiAgent` validation + defaults; on-demand schema generation.
- **Integration:** seed a tool + agent + grant via the data loader → run the agent end-to-end →
  assert dispatch by `serviceName` and a clean `verb_noun` wire name (no FQN, no sanitize needed).
- **Acceptance (live):** NotNaked — `gradlew load` makes `nn-oms-assistant` present with no on-demand
  hack; the Playground runs it; the Agents screen lists it. Rename the agent → grants + run history
  intact.

## 10. What this enables (the north star)

With tools/agents as mutable, safely-authored data, the **builder agent** (next spec) can author
them through the same `store#` services a human uses, and the **domain knowledgebase** (its own spec)
can feed it OMS-grounded naming that improves over time. This keystone deliberately stops at the
data + safety foundation so those layers land on something that won't shift.

## 11. Open questions

- **Id generation:** Moqui sequenced id for user-created `toolId`/`agentId`; explicit stable ids in
  seed data. (Recommended; confirm at planning.)
- **`effect` fidelity:** verb-heuristic for v1 vs. static entity-write analysis later.
- **Multi-tenant catalog scoping:** deferred to its own spec; note where `ownerPartyId`/tenant would
  attach if needed.
