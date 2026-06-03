# ADR 0001 — Context-Window Management for AI Agents

- **Status:** Accepted (eng-review locked 2026-06-03)
- **Date:** 2026-06-03
- **Deciders:** Product owner + framework team (moqui-ai)
- **Implements:** Decision 2 of the enterprise gap report (`docs/specs/2026-06-03-enterprise-decisions-gap-report.md`)
- **Format:** MADR (Markdown Any Decision Record). ADRs are immutable — supersede with a new ADR rather than editing the decision.

---

## Context and Problem Statement

The agentic loop sends the LLM a **message list** on every `provider.chat` call. That list grows from two sources:
- **Across turns** — we replay all prior `AiConversationMessage` rows so the model remembers the conversation; over many turns the replayed list balloons.
- **Within one run** — a single `run#Agent` can make many tool calls, appending an assistant tool-call message + a tool-result message per iteration; large/many tool results balloon the list inside one run.

Both feed the **same** message list. Today there is **no management**: `loadConversationMessages` replays *everything* unconditionally, and `maxTokens` only *aborts* a run rather than shaping the window. A long conversation or a tool-heavy run will eventually overflow the model's context window.

Two forces make this more than a "fit" problem:
1. **Context rot** (Anthropic, 2025; "lost in the middle", Liu et al. 2023): as tokens grow, recall *degrades*. Bloated context **lowers answer quality** — so trimming helps quality, not just cost.
2. **ERP fidelity:** earlier turns can hold **confirmed business values** (an order total, a confirmed address). Silently dropping or lossy-summarizing them corrupts a business fact. **This is unacceptable** and rules out naive truncation.

**Problem:** choose a context-management strategy that keeps us within the window and cheap, *without ever silently losing a confirmed business value*, provider-agnostic, and shippable without the deferred RAG/embeddings work.

## Decision Drivers (weighted)

The product owner ranked **fidelity as the non-negotiable top priority**; the rest are constraints to satisfy, not maximize. Weights used to score options:

| Driver | Weight | Meaning |
|---|---|---|
| **Fidelity** | **40%** | Never silently lose a confirmed business value. |
| **Quality** | 25% | Avoid context-rot degradation; keep context high-signal. |
| **Cost** | 20% | Fewer tokens resent per turn (stateless API; ties to the shipped cost feature). |
| **Simplicity** | 15% | Least code/maintenance; provider-agnostic; no embeddings (RAG deferred). |

**Scope (decided):** management applies to the **whole assembled message list on every call** — covering both cross-turn replay and in-run accumulation (one mechanism; both already flow through the same `messages` list in `AgentRunner`).

**Phasing (decided):** ship **now without semantic retrieval**. We persist every message (`AiConversationMessage`), so nothing is destroyed; on-demand retrieval of old turns is a future **Phase 6 (RAG)** enhancement, not a dependency.

## Considered Options

What the field does (grounded in Anthropic's "Effective context engineering for AI agents", Sep 2025, which names three techniques — compaction, structured note-taking, sub-agents — plus just-in-time retrieval; corroborated by MemGPT/Letta tiered memory and LangChain's summary-buffer memory):

- **A. Sliding window only** — keep the last N tokens/messages, drop the oldest. Simple, cheap. **Silently loses old facts** — the exact ERP failure.
- **B. Compaction only** — summarize older turns near the limit, continue with the summary (Claude Code's approach). Preserves gist; costs an LLM call. **Lossy by design** — Anthropic's own caveat: *"overly aggressive compaction can result in the loss of subtle but critical context."*
- **C. Fact-pinning only** — agent writes durable notes to a store outside the transcript (Anthropic memory tool / MemGPT). Preserves facts, **but does not bound the transcript** — the window still overflows.
- **D. Hybrid — window + compaction + pinned facts + tool-result clearing.** Recent turns verbatim; older turns summarized; confirmed values pinned in a never-compressed store injected every call; raw old tool results cleared ("the safest, lightest-touch form of compaction" per Anthropic); always log what was dropped. **What leaders actually run** (Anthropic layers all of these).
- **E. Just-in-time semantic retrieval** — embed history, retrieve relevant turns on demand. Powerful, but requires embeddings + vector store = **deferred Phase 6**. Out of scope now.

### Decision matrix (1–10, weighted)

| Option | Fidelity ×0.40 | Quality ×0.25 | Cost ×0.20 | Simplicity ×0.15 | **Weighted** |
|---|---|---|---|---|---|
| A. Window only | 1 | 6 | 9 | 10 | **5.2** |
| B. Compaction only | 4 | 7 | 6 | 6 | **5.4** |
| C. Fact-pinning only | 8 | 5 | 5 | 5 | **6.2** |
| **D. Hybrid** | **9** | **8** | **7** | 5 | **7.8** |

(Math: A = 0.4+1.5+1.8+1.5; B = 1.6+1.75+1.2+0.9; C = 3.2+1.25+1.0+0.75; D = 3.6+2.0+1.4+0.75.)

A is disqualified by the fidelity mandate regardless of score. B (Claude Code's lone approach) and C each fail a hard requirement (B loses detail; C doesn't bound the window). D is the only option that satisfies fidelity-as-non-negotiable while keeping quality and cost in line.

## Decision Outcome

**Chosen: Option D — a layered hybrid**, with **agent self-note fact pinning** as the fidelity guarantee. Applied to the assembled message list on every `provider.chat` call:

1. **Pinned facts (the fidelity guarantee).** The agent is granted a framework **`remember` tool**; when it confirms a durable business value it calls the tool, which writes a fact to a conversation-scoped store. Pinned facts are injected into context **every call and are never summarized or dropped**. (Anthropic note-taking / memory-tool pattern; the chosen capture mechanism.) This is what makes the lossy layers below *safe* — confirmed values live outside the compressible transcript.
2. **Recent window verbatim.** Keep the last N turns/tokens exact (token-based, using the per-call counts we already track).
3. **Compaction.** When the assembled context exceeds a threshold, summarize older transcript turns into a single `summary` message (recall-first prompt), continue with summary + recent window. Lossy — but acceptable *because* confirmed values are pinned in layer 1.
4. **Tool-result clearing.** Drop/stub raw old tool-result messages from replay — Anthropic's "safest, lightest-touch compaction." Gives in-run relief now without the deferred Decision 9 cap machinery.
5. **Log what was dropped.** Every compaction/clearing event is recorded (never silent), satisfying the auditability principle.

Configured per agent on `AiAgent` (`contextStrategy` + window/threshold sizes), provider-agnostic (operates on our normalized Map messages), no embeddings.

### Why this option

It is the only option that honors **fidelity as non-negotiable** (layer 1) while also *improving* quality (layers 2–4 fight context rot) and cutting cost (fewer tokens resent). It matches what the most authoritative current source (Anthropic) actually ships, it is provider-agnostic, and it needs no deferred infrastructure.

## Consequences

**Positive**
- Confirmed business values provably survive (pinned, never compressed) — the ERP guarantee.
- Better answer quality (smaller, high-signal context fights context rot) and lower per-turn cost.
- Nothing is destroyed (full history persisted) — Phase 6 retrieval can later pull back any old turn.
- Provider-agnostic; no new infra.

**Negative / accepted trade-offs**
- **Fact capture depends on the agent calling `remember`.** Mitigated by good system-prompt guidance + examples; a developer-declared deterministic floor can be added later if the agent proves unreliable (we explicitly chose the tool-only approach now for simplicity).
- **Compaction is lossy for conversational filler** (not for pinned facts). Accepted; the dropped detail is still persisted and retrievable in Phase 6.
- **Compaction costs an extra LLM call** when triggered. Mitigated by threshold-triggering (not every turn) and keeping a stable cacheable prefix (prompt caching).
- More moving parts than a sliding window (lowest simplicity score). Mitigated by phasing.

**Interactions**
- **Prompt caching:** keep the stable prefix (system + pinned facts + summary) stable to preserve cache hits; mutate only the recent tail.
- **Cost feature (shipped):** fewer resent tokens directly lowers `estimatedCost`.
- **Decision 9 (tool-result caps, staged):** layer 4 (tool-result clearing) is the cheap subset we can do now; the full per-tool cap is still that separate, staged decision.

## Phased rollout (decision-level; exact plan to follow via writing-plans)

To avoid a big-bang and to never ship the *unsafe* version first, the fidelity floor lands with the first increment:
1. **Window + pinned facts (`remember` tool + fact store) + log** — the fidelity-safe core (recent verbatim, older dropped, confirmed values preserved, drops logged).
2. **Compaction** — summarize dropped older turns for gist preservation (quality add-on).
3. **Tool-result clearing** — in-run relief.
(Retrieval = Phase 6, later.)

## Engineering review refinements (locked 2026-06-03)

`/plan-eng-review` refined the decision before implementation:
- **First plan = Phase 1 only** (window + `remember` tool + fact store + inject + log). Compaction (Phase 2) and tool-result clearing (Phase 3) are separate follow-on plans. (Complexity gate: the full build is >8 files / multiple services — phase it.)
- **Trim budget = message-count window + char-estimate guard** (`chars/4 ≈ tokens`). No tokenizer dependency (we have no pre-send token count; provider `usage` is post-hoc). Deterministic, provider-agnostic. Fidelity held by the fact store regardless of budget accuracy.
- **Fact injection = append to `systemContext`** as a `## Known facts` block. Provider-agnostic, highest-signal placement, and the system prefix only changes when a new fact is added (keeps prompt caching warm during stable stretches).
- **Tool-pair safety (correctness constraint):** windowing trims at message-group boundaries — it must never orphan an assistant `tool_call` from its `tool_result`, and must never trim the *current run's* in-progress messages (only prior-turn replayed history).
- **`remember` semantics:** requires a `conversationId` (stateless single-turn runs → logged no-op); facts are **keyed and store-or-update** (a new confirmed value supersedes the old).
- **Structure:** extract a focused `ContextAssembler` unit (inject + window) rather than growing `AgentRunner.run()`.
- **Degrade, don't block:** a failed fact-load proceeds without injection (logged); a stateless `remember` is a no-op — neither errors the run.

## Confirmation (how we validate the decision holds)

- A test proving a confirmed value pinned via `remember` survives a forced compaction/window-eviction (the fidelity guarantee).
- A test proving the assembled context stays under the configured limit for a long conversation and a tool-heavy run.
- A test proving every drop/compaction is logged.
- No regression for agents with management disabled (default off / generous limits).

## More Information (sources)

- Anthropic, *Effective context engineering for AI agents* (Sep 29, 2025) — compaction, structured note-taking/memory tool, tool-result clearing, "context rot," "smallest set of high-signal tokens." Primary, current, authoritative.
- Liu et al., *Lost in the Middle* (2023) — positional attention degradation.
- MemGPT / Letta — tiered "virtual context" memory (main context vs external store, self-paging). The fact-pinning lineage.
- LangChain conversation memory types — `summary-buffer` (recent verbatim + rolling summary) is option D's layers 2–3.
- Anthropic prompt caching docs — stable-prefix interaction.

*(Wire-level specifics of vendor features evolve; the architecture and our decision are version-independent.)*
