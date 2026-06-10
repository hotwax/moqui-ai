# Decision Record — moqui-ai

> The "why" behind the as-built `moqui-ai` framework, distilled into a concise decision
> register. Each entry is **decision → rationale → status**, where status is one of
> **Shipped**, **Deferred**, or **Declined**, and every status has been verified against
> the code (not just the design docs). Where a doc and the code disagreed, the code wins —
> those cases are called out explicitly.

**Sources distilled here**

- `docs/specs/2026-06-03-enterprise-decisions-gap-report.md` — the authoritative
  v1-scope decision record (the 11 enterprise decisions + the shipped roadmap phases).
- `docs/decisions/0001-context-window-management.md` — ADR 0001 (the layered context strategy).
- `docs/specs/2026-06-05-agent-tool-registry-design.md` — the registry keystone (opaque ids, safety floor).
- `docs/specs/2026-06-05-composer-assistant-moqui-design.md` — the Composer (activation gate, preview).
- `docs/specs/2026-06-03-responses-api-migration-audit.md` — the Responses-API evaluation (closed/declined).

For the as-built field-level and service-level detail these decisions produced, see
`docs/reference/` and `docs/explanation/architecture.md`.

---

## Decision register

| # | Decision | Status |
|---|---|---|
| D1 | Opaque ids, not service-FQN keys, for tools and agents | **Shipped** |
| D2 | Safety = effect-derived-from-service + a non-overridable denylist floor | **Shipped** |
| D3 | Activating a composed agent requires human approval | **Shipped** |
| D4 | Context management — windowing + pinned facts + compaction | **Shipped (Phases 1–2)** |
| D5 | Context — tool-result clearing (layer 4) and semantic retrieval (Phase 6 RAG) | **Deferred** |
| D6 | Cost stamped per run off the *served* model | **Shipped** |
| D7 | `maxCost` enforcement (run aborts when projected cost exceeds the cap) | **Deferred** |
| D8 | Multi-provider sticky failover chain | **Shipped** |
| D9 | Reasoning / thinking via a normalized effort flag | **Shipped (with an Anthropic v1 limit)** |
| D10 | Migrate to the OpenAI Responses API | **Declined** |
| D11 | Shipped providers = OpenAI + Anthropic + Mock; Google/Gemini provider | **Shipped (OpenAI/Anthropic/Mock); Google Deferred** |
| D12 | `AiAgentKnowledge` entity (attached per-agent knowledge) | **Declined / never built** |
| D13 | Run audit (`AiAgentRun`/Step/`AiToolCall`) kept separate from the conversation transcript (`AiConversationMessage`/`Fact`) | **Shipped** |

---

## D1 — Opaque ids over service-FQN keys

**Decision.** A tool's identity is an opaque, stable `toolId`; an agent's is an opaque
`agentId`. The backing Moqui service FQN (`AiTool.serviceName`) and the human-friendly
names (`toolName` = `verb_noun`, `agentName`) are *mutable attributes*, not keys.

**Rationale.** When the tool's identity *was* the service FQN, three costs fell out of that
one choice: (1) a service FQN overflows the 40-char `id` PK column — the live truncation
bug that blocked the NotNaked demo; (2) the implementation path leaked to the model as its
tool name; (3) identity was welded to the service location, so nothing could be renamed and
tools could only be declared in XML, never authored at runtime. Opaque ids make tools and
agents *mutable data with stable identity* — the prerequisite for an agent that builds agents.
Renaming never breaks grants or run history.

**Status — Shipped.** Verified in `entity/AiEntities.xml`: `AiAgent.agentId` and
`AiTool.toolId` are `is-pk="true"`; `serviceName` is described in the entity as
"Backing Moqui service FQN — an attribute, NOT a key"; `agentName`/`toolName` carry
unique indexes (`AI_AGENT_NAME`, `AI_TOOL_NAME`) but are not PKs. Grants
(`AiAgentTool`) and the failover chain (`AiAgentModel`) are keyed by `agentId`.

**Reference.** `docs/specs/2026-06-05-agent-tool-registry-design.md` §3 (D1), §4.

---

## D2 — Safety: effect-from-service + denylist floor

**Decision.** A tool's `effect` (`AI_TOOL_READ_ONLY` | `AI_TOOL_MUTATING`) is *derived from
the backing service's verb*, not hand-set. Read-only tools default to `exposable=Y,
requiresApproval=N`; mutating tools default to `exposable=N` (a curator must bless them),
`requiresApproval=Y`. On top of that, a non-overridable **denylist** of service-name
patterns (`AiToolDenylist`) forces `exposable=N` for admin/security/internal services — a
hard floor that no override can lift.

**Rationale.** Exposing a service as a tool is the dangerous half of runtime authoring.
Deriving danger from the service (rather than trusting the author to label it) and flooring
it with a denylist means the Composer "cannot expose a dangerous service even if asked."

**Status — Shipped.** Verified in `service/ai/ToolServices.xml` (`store#AiTool`): the read
verbs `['get','find','list','view','search','check','calculate']` derive `AI_TOOL_READ_ONLY`,
everything else `AI_TOOL_MUTATING`; effect-based defaults set `exposable`/`requiresApproval`;
the denylist loop matches `serviceName` against each `AiToolDenylist.servicePattern` regex and
sets `exposable=N` (non-overridable). `AiToolDenylist` exists in `entity/AiToolEntities.xml`
with `servicePattern` as the PK.

**Reference.** `docs/specs/2026-06-05-agent-tool-registry-design.md` §3 (D4), §6.

---

## D3 — Activation requires human approval

**Decision.** Promoting a composed draft agent to live (`AI_AGENT_DRAFT` →
`AI_AGENT_ACTIVE`) requires a human in the loop. The commit itself is gated, in addition to
the per-tool approval gate on the agents being built.

**Rationale.** A business user assembling an agent should not be able to silently put it into
production. A person reviews the draft (and can preview it) before it goes live.

**Status — Shipped.** Verified in `service/ai/ComposerServices.xml` (`activate#Agent`): the
service comment states "HUMAN APPROVAL is enforced UPSTREAM: activate_agent is a
requiresApproval AiTool, so the Composer Assistant proposing it suspends its own run via the
existing gate; this service runs only after a human approves." The service is also directly
callable by an operator (the screen's Activate button) and re-checks that every granted tool
is still `exposable=Y` + `AI_TOOL_ACTIVE` before flipping the status. The approval-gate
machinery itself (suspend → `pendingState` → fail-closed `resume`) is the Phase-4 work
recorded as Shipped in the gap report.

**Reference.** `docs/specs/2026-06-05-composer-assistant-moqui-design.md` §7, §12.2;
gap report "Human approval gate (Phase 4) — SHIPPED."

---

## D4 — Context management: window + pinned facts + compaction (Phases 1–2)

**Decision.** Manage the assembled message list (both cross-turn replay and in-run
accumulation) with a layered hybrid, gated per agent by `AiAgent.contextStrategy`
(default `off` = full replay, no behavior change):

- **Phase 1 — window + pinned facts.** Message-count + char-guard windowing of replayed
  history (tool-pair-safe — never orphans a tool result); a framework-injected `remember`
  tool writes durable values to `AiConversationFact`, injected into the system context on
  every call so they survive window eviction; drops are logged as `context_trim` steps.
- **Phase 2 — compaction.** `contextStrategy=summarize` rolls overflow into a persisted
  `AiConversation.summaryText` (+ watermark) via the agent's own model, summarized once per
  run, injected as a `## Conversation summary` block; falls back to windowing on
  summarization failure; logged as `compaction` steps.

**Rationale.** The message list grows from two sources and both overflow the window and
degrade quality ("context rot"). The product owner ranked **fidelity** (never silently lose a
confirmed business value — an order total, a confirmed address) as the non-negotiable top
priority. Pinned facts are the fidelity guarantee that makes the lossy layers (windowing,
compaction) *safe*. ADR 0001 scored the hybrid (option D) highest (7.8 weighted) against
window-only, compaction-only, and fact-pinning-only.

**Status — Shipped (Phases 1–2).** Verified in `src/main/groovy/org/moqui/ai/ContextAssembler.groovy`
(`windowHistory` with message-count + char guard and tool-pair safety; `withSummary` injecting
the `## Conversation summary` block) and in `AgentRunner.groovy` (compaction summarization with
a windowing fallback on failure). `AiAgent.contextStrategy` is documented in the entity as
`off (default) | window`.

**Reference.** `docs/decisions/0001-context-window-management.md`; gap report Decision 2.

---

## D5 — Context layer 4 (tool-result clearing) + Phase 6 (semantic retrieval / RAG)

**Decision.** ADR 0001's layer 4 (drop/stub raw old tool-result messages from replay) and the
just-in-time semantic-retrieval layer (Phase 6 — embed history, retrieve relevant old turns on
demand) are *deferred*, not part of v1.

**Rationale.** Layer 4 is the cheap subset of the separate, still-staged tool-result-cap
decision (Decision 9). Phase 6 needs embeddings + a vector store; ADR 0001 explicitly chose to
"ship now without semantic retrieval" — every message is persisted (`AiConversationMessage`), so
nothing is destroyed and retrieval can be added later without a dependency.

**Status — Deferred.** Verified absent in code: `ContextAssembler.groovy` implements only
`windowHistory` + `withSummary` (compaction) — there is no tool-result-clearing path. A grep of
`AgentRunner.groovy` and `ContextAssembler.groovy` for `embed` / `vector` / `elastic` / `retriev`
returns nothing. (The Phase-6 RAG design survives only as a historical plan under
`docs/archive/plans/`, which still references the dropped `AiAgentKnowledge` entity — see D12.)

**Reference.** `docs/decisions/0001-context-window-management.md` (Considered Options D & E,
"Phased rollout," "Interactions" → Decision 9); gap report Decision 2 ("Tool-result clearing
(Phase 3), semantic retrieval (Phase 6) deferred").

---

## D6 — Cost stamped off the served model

**Decision.** `AiAgentRun.estimatedCost` is stamped per run from an effective-dated
`AiModelPrice`, priced off the **served** model — i.e. the provider/model that actually
answered after any failover, not the agent's first-choice model. `get#AiSpend` aggregates by
agent / user / time window; `store#AiModelPrice` upserts prices. A missing price yields a cost
of 0 (never blocks a run).

**Rationale.** With a failover chain (D8), the model that ran may not be the model configured
first. Costing off the served model is the only correct attribution. Pricing is effective-dated
so historical runs keep their then-current price.

**Status — Shipped.** Verified in `AgentRunner.groovy` (`estimateCost(...)` looks up
`AiModelPrice` by `providerName`/`modelName` with `conditionDate`, returns 0 when no price;
`finish()` calls it with `result.servedProviderName` / `result.servedByModelId`) and in
`CostCalc.groovy` (pure per-million-token math). `servedByModelId` + `providerName` are
`run#Agent` out-params.

**Reference.** Gap report cost-awareness row + Decision 10.

---

## D7 — `maxCost` enforcement

**Decision.** A per-agent `maxCost` cap that aborts (or refuses) a run when projected cost
exceeds it is *deferred* — a later policy task. The field is intentionally carried but unused.

**Rationale.** Cost *visibility* (stamping + query, D6) was the v1 need; *enforcement* is a
policy layer that can land later without reshaping the run.

**Status — Deferred (verified: field exists, never read).** `AiAgent.maxCost` exists in
`entity/AiEntities.xml` and is accepted as a pass-through `BigDecimal` parameter on
`store#AiAgent` (`service/ai/AgentServices.xml`), but a grep of `AgentRunner.groovy` and
`CostCalc.groovy` for `maxCost` returns **zero hits** — it is never read or enforced anywhere
in the run loop. (For contrast, the sibling limits `maxIterations`, `maxTokens`, and
`maxToolCallsPerTurn` *are* read and enforced in `AgentRunner.run()`.)

**Reference.** Gap report cost-awareness row ("`maxCost` *enforcement* still deferred… field
intentionally unused") + Decision 10.

---

## D8 — Multi-provider sticky failover chain

**Decision.** An agent can declare an ordered `AiAgentModel` (provider, model) chain by
priority. On a provider-call failure the runner falls through to the next candidate; once a
candidate succeeds it is **sticky** — subsequent iterations in the same run stay on the
working candidate rather than restarting from the top. The run records the served
provider/model; each failed attempt is logged as a failed `llm_call` step (`success=N`).

**Rationale.** Provider outages and per-model errors should degrade gracefully without
operator intervention, and switching provider should remain "a one-field change."

**Status — Shipped.** Verified in `AgentRunner.groovy`: `callWithFailover(...)` iterates
candidates from a start index, returns the first whose `provider.chat()` succeeds, and collects
failed attempts; the caller sets `candIdx = call.idx` with the comment "sticky: stay on the
working candidate," writes failed `llm_call` steps (`success=N`) for each failed attempt, and persists
`servedByModelId` / `servedProviderName` / `providerRunId` on the run.

**Reference.** Gap report Decision 7.

---

## D9 — Reasoning / thinking via a normalized effort flag

**Decision.** `AiAgent.reasoningEffort` (`none` (default/unset) | `low` | `medium` | `high`)
normalizes to a provider-agnostic `reasoning.effort` in the request, translated per provider:
OpenAI → `reasoning_effort` (reasoning-capable models only); Anthropic → extended
`thinking{budget_tokens}` (low/medium/high = 1024/8192/24576, with `max_tokens` bumped to
budget + 4096). Default unset ⇒ byte-for-byte unchanged behavior.

**Rationale.** Reasoning depth has real value for planner-type agents (multi-step,
multi-constraint flows) but is overhead for the bulk of OMS traffic (lookups, status,
extraction) — so it is an opt-in, per-agent flag rather than a global mode.

**Status — Shipped, with an Anthropic v1 limitation.** Verified in
`provider/AnthropicProvider.groovy`: thinking is applied **only when the request carries no
tools** — `if (request.reasoning?.effort && !request.tools) { ... body.thinking = ... }` — with
an in-code comment explaining that Anthropic requires preserving thinking blocks across
`tool_result` turns, which the v1 message shape does not carry (so reasoning + tools on
Anthropic, including the built-in `remember` tool, is deferred). OpenAI has no such limit. The
`AiAgent.reasoningEffort` entity field's own description records this v1 constraint.

**Reference.** Gap report "Reasoning (effort flag) — SHIPPED 2026-06-04" + Decision 6.

---

## D10 — OpenAI Responses API migration

**Decision.** Do **not** migrate to the OpenAI Responses API; stay on Chat Completions.

**Rationale.** Decision 1 (we always own conversation state) and Decision 3 (no provider
built-in tools) remove the only two reasons the Responses API exists — server-side state and
hosted tools. Stripped of those, Responses is an OpenAI-only wire format doing what stateless
Chat Completions already does — and Chat Completions is also the shape Anthropic (and a future
Google provider) speak, preserving "switch provider = one field." Revisit only if OpenAI ships
a needed capability exclusive to Responses.

**Status — Declined.** Verified absent in code: a grep of `src/` for
`responsesprovider` / `responses[._]api` / `previous_response_id` returns nothing; the OpenAI
provider speaks Chat Completions. The migration-audit doc
(`docs/specs/2026-06-03-responses-api-migration-audit.md`) is banner'd "CLOSED — evaluated,
declined for v1."

**Reference.** Gap report Decision 1; `docs/specs/2026-06-03-responses-api-migration-audit.md`.

---

## D11 — Shipped providers: OpenAI + Anthropic + Mock (no Google)

**Decision.** v1 ships three providers — OpenAI, Anthropic, and Mock (the test/offline
provider). A Google/Gemini provider is deferred.

**Rationale.** The design philosophy aspires to "a single Moqui-native LLM interface across
OpenAI/Anthropic/Google," but v1 scope landed OpenAI + Anthropic (both verified live) plus Mock
for tests; Google was not built.

**Status — Shipped (OpenAI/Anthropic/Mock); Google Deferred.** Verified on disk: the
`provider/` package contains exactly `AbstractLlmProvider`, `AnthropicProvider`, `MockProvider`,
`OpenAiProvider` — there is **no `GoogleProvider`**. `AiToolFactory` registers Mock
unconditionally and Anthropic/OpenAI only when their keys are configured; there is no Google
registration path. The string "google" survives only as an example registry-key value in a
`LlmProvider.groovy` docstring and as an aspiration in the gap report's design-philosophy /
Decision 6 ("Gemini `thinkingConfig` lands with the Gemini provider") — **not** as shipped code.
This is a case where the doc's framing ("across OpenAI/Anthropic/Google") runs ahead of the
code; the code ships two real providers plus Mock.

**Reference.** Gap report design-philosophy line + Decision 6 (Gemini deferred);
`docs/specs/2026-06-02-ai-agent-framework-design.md` §5 (Google/Gemini deferred).

---

## D12 — `AiAgentKnowledge` (attached per-agent knowledge)

**Decision.** The `AiAgentKnowledge` entity and the `<knowledge>` XML block from the original
Phase-1 plan were dropped. Domain context ships instead as pinned facts (the `remember` tool →
`AiConversationFact`, D4) plus the Builder Knowledgebase glossary (`AiDomainTerm`), not as
per-agent attached knowledge.

**Rationale.** Attached static knowledge plus its embedding/retrieval (the old Phase-1 → Phase-6
path) was superseded by the fact-pinning fidelity mechanism for run-time context and by the
glossary for naming/grounding. The RAG layer that would have indexed `AiAgentKnowledge` is itself
deferred (D5).

**Status — Declined / never built.** Verified absent in code: a grep for `AiAgentKnowledge`
across `entity/` and `src/` returns **zero hits** — no such entity is defined and no code
references it. It appears only in historical material under `docs/archive/plans/` (the dropped
Phase-1 / Phase-6 plans) and in the framework-design spec, which explicitly records that
"the `AiAgentKnowledge` entity and the `<knowledge>` XML block in the original plan were"
dropped and that domain context "ships as pinned facts + the glossary, not the dropped
`AiAgentKnowledge`."

**Reference.** `docs/specs/2026-06-02-ai-agent-framework-design.md` §8 and the §16/§411 scope
note; ADR 0001 (pinned facts as the chosen fidelity mechanism).

---

## D13 — Run audit separate from the conversation transcript

**Decision.** Keep two record sets for a turn: the **run audit** (`AiAgentRun` → `AiAgentRunStep`
→ `AiToolCall`) and the **conversation transcript + memory** (`AiConversationMessage`,
`AiConversationFact`), joined by `agentRunId`. Do **not** merge them into one — even though the run
header's `userMessage`/`assistantMessage` overlap the conversation's user/assistant messages.

**Rationale.** The overlap is deliberate denormalization, not redundancy to normalize away:
(1) a **stateless** run (`conversationId = null`) has no conversation, so the run header is the
*only* record of its messages; (2) the audit must be **self-contained and immutable** — `RunDetail`
renders a run without joining to conversation state, and stays correct even after the transcript is
windowed / compacted / deleted; (3) the two have **different lifecycles** — per-execution and frozen
vs. cumulative and replayed. Merging would couple the immutable audit to the lossy, replayed
transcript and break stateless runs. The cost of keeping them separate is a consistency obligation:
the run writes both, and nothing edits one without the other. (This decision is logged because the
overlap reads, at first glance, like duplicate data inviting a refactor — it isn't.)

**Status — Shipped.** Verified in `AgentRunner.groovy`: the run loop writes the audit
(`create#moqui.ai.AiAgentRunStep`, `AiToolCall`) for every run, and — only when `conversationId` is
set — also persists `AiConversationMessage` rows (user, assistant-with-toolCalls, tool result, final
assistant), each carrying `agentRunId`; `AiAgentRun.userMessage`/`assistantMessage` are written
regardless. A stateless run produces audit rows but no conversation rows.

**Reference.** `docs/explanation/architecture.md` → "Runs vs. conversations"; ADR 0001 (the
conversation / memory model).
