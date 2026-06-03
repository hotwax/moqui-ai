# Enterprise API Decisions — Gap Report & Decision Record

- **Date:** 2026-06-03
- **Component:** `moqui-ai` (branch `feature/ai-agent-framework`)
- **Status:** **Authoritative decision record.** Supersedes the open questions in
  [2026-06-03-responses-api-migration-audit.md](2026-06-03-responses-api-migration-audit.md).
- **Source:** the "Moqui AI Agent Framework — Product Decisions" brief (v1.0), audited against the
  live code (`AgentRunner.groovy`, `AiEntities.xml`, the provider layer) and then narrowed to a v1
  scope in discussion with the product owner (2026-06-03).
- **Constraint:** decision record only. **No implementation until this is approved.**

---

## Design philosophy (unchanged)
> "We own what matters to enterprise. We defer nothing that touches data, state, security, or
> auditability." A single Moqui-native LLM interface across OpenAI/Anthropic/Google where switching
> provider is a one-field change. We own state; the system is single-business (no multi-tenancy).

---

## The v1 release scope — the punchline

After auditing all 11 decisions and narrowing in discussion, **v1 = the framework that already
exists + one net-new feature (structured output).** Everything else is already done, deferred, or
handled by convention.

| Decision | v1 disposition |
|---|---|
| 1 own state · 3 no built-in tools · 8 tool-arg validation | ✅ **already implemented** |
| **5 structured output (agent-defined, locked)** | ✅ **SHIPPED** — agent-defined `responseSchema` → normalized `structuredResult`; verified live on OpenAI + Anthropic |
| 6 reasoning — OpenAI | ✅ **closed for v1** — works today via `modelName` (o-series), no framework change; normalized flag deferred |
| **7 multi-provider fallback chain** | ✅ **SHIPPED** — `AiAgentModel` priority chain + sticky failover on provider-call failures; run records served provider/model; failed attempts logged as `llm_call_failed` steps |
| **cost awareness — stamping + query** | ✅ **SHIPPED** — `estimatedCost` stamped per run from an effective-dated `AiModelPrice` (priced off the *served* model, post-fallback); `get#AiSpend` aggregates by agent / user / time window; `store#AiModelPrice` upserts prices. `maxCost` *enforcement* still ⏳ **deferred** (later policy task) — field intentionally unused |
| 2 context management · 9 tool-result caps · 11 masking hooks | ⏳ **deferred** (post-v1) |
| 4 streaming · 10 tenantId / multi-tenancy | 🚫 **not building** |

---

## Gap report — current code vs. the 11 decisions

| # | Decision | Verdict | Evidence / gap | v1 action |
|---|---|---|---|---|
| **1** | Session/conversation state — **we own it** | **ALIGNED** | History loaded from `AiConversationMessage` and replayed (`AgentRunner.loadConversationMessages`), never from provider. Chat Completions is stateless — no `previous_response_id`, no `store`. | none |
| **2** | Context-window management | **MISSING** | No `contextStrategy`/`contextWindowMessages`/`contextWindowTokens` on `AiAgent`. `loadConversationMessages` replays **all** prior messages; `maxTokens` only *aborts* the run, doesn't truncate/summarize. | **defer** |
| **3** | Provider built-in tools — not supported | **ALIGNED** | All tools resolve via the file catalog → a Moqui service (`dispatchTool` → `ec.service.sync()`). No provider-side execution path exists. | none |
| **4** | Streaming — optional, off by default | **MISSING** | `provider.chat()` is sync-only; no `stream` flag, no SSE. "Off by default" satisfied trivially. | **not building** |
| **5** | Structured output — normalized, schema-driven | **SHIPPED** | Agent-defined `AiAgent.responseSchema` → normalized `structuredResult` out-param. OpenAI via `response_format` json_schema (strict); Anthropic via forced synthetic `structured_output` tool on the closing turn. Verified live on both providers. | **done (v1)** — see Decision Record below |
| **6** | Reasoning / thinking | **MISSING (flag)** | No `reasoning`/`reasoningBudgetTokens` on `AiAgent`; no thinking storage. **But** OpenAI reasoning needs zero framework work — it's model selection (`modelName` = o-series). | **defer flag**; document OpenAI-via-`modelName` |
| **7** | Multi-provider fallback chain | **SHIPPED** | `AiAgentModel` priority chain + sticky failover on provider-call failures; run records served provider/model; failed attempts logged as `llm_call_failed` steps. `servedByModelId` + `providerName` exposed as `run#Agent` out-params. | **done (v1)** |
| **8** | Tool argument validation | **ALIGNED** | Moqui validates service in-params; `dispatchTool` returns a **structured JSON error** to the model (not an exception) on invalid args, on tool error, and on unknown tool ("Tool not in catalog"). All logged to `AiToolCall`. | none |
| **9** | Tool-result size — cap + overflow | **MISSING** | `dispatchTool` serializes the full result with no cap. No `maxResultTokens`/`overflowStrategy` on `AiTool`. | **defer** — design-around via convention (below) |
| **10** | Metadata/correlation in our entities; none to provider | **DIVERGES → narrowed** | Provider side: no metadata sent (ALIGNED). Entity side: have `agentRunId`, `agentName`, `userId`, `fromDate`/`thruDate`, `providerName`, `modelName`, `tokensIn/Out`, `estimatedCost`, `conversationId`. **`tenantId` removed from scope** (single-business). `servedByModelId` + `providerRunId` correlation fields **shipped** — populated on `AiAgentRun` and exposed as `run#Agent` out-params. **Cost stamping + query shipped:** `estimatedCost` is now stamped per run from an effective-dated `AiModelPrice` (priced off the *served* model, post-fallback); `get#AiSpend` aggregates spend by agent / user / time window; `store#AiModelPrice` upserts prices. `maxCost` *enforcement* remains **deferred** (later policy task) — the field is intentionally still unused. | none for v1 |
| **11** | PII/masking — hook-based | **MISSING** | No `preRequestHook`/`postResponseHook`; no hook points in `AgentRunner`. | **defer** — tools own AI-safe output (below) |

**Tally:** 3 ALIGNED (1, 3, 8) · 1 narrowed (10) · shipped 2 (5, 7) + cost stamping/query (under 10) ·
defer 3 (2, 9, 11) + `maxCost` enforcement · not building 2 (4, 10-tenantId) · closed-for-v1 1 (6).

---

## Decision Record (the eleven answers, locked 2026-06-03)

1. **Responses API — declined for v1.** Decisions 1 (own state) and 3 (no built-in tools) remove
   the only two reasons Responses exists (server-side state, hosted tools). Stripped of those it is
   an OpenAI-only wire format doing what stateless Chat Completions already does — and Chat
   Completions is also the shape Anthropic/Google speak, preserving "switch provider = one field."
   **Stay on Chat Completions.** Revisit only if OpenAI ships a needed capability exclusively on
   Responses. → migration-audit doc closed as *evaluated, declined*.
2. **Context management — deferred.** Aware of the limit; not building truncate/summarize yet.
3. **Built-in tools — confirmed never (v1).**
4. **Streaming — not building.** Not planned at this time.
5. **Structured output — SHIPPED in v1.** Locked at the **agent definition** (see below). Agent-defined
   `AiAgent.responseSchema` normalizes to a `structuredResult` out-param; OpenAI uses `response_format`
   json_schema (strict), Anthropic uses a forced synthetic `structured_output` tool on the closing turn.
   Verified live on both providers.
6. **Reasoning — closed for v1; free for OpenAI today.** OpenAI reasoning works today via `modelName`
   (o-series) with **no framework change** — Decision 6 is closed for v1 (the normalized flag is deferred).
   Value is real only for *planner-type*
   agents (multi-step/multi-constraint flows: fulfillment-source selection, SLA root-cause,
   turning vague asks into correct tool sequences). It's overhead for the bulk of OMS traffic
   (lookups, status, extraction). OpenAI reasoning already works via `modelName`; the normalized
   `reasoning`/`reasoningBudgetTokens` flag (which only adds Anthropic/Google parity) is deferred
   to a future reasoning slice.
7. **Fallback chain — SHIPPED in v1.** `AiAgentModel` priority chain + sticky failover on
   provider-call failures; run records served provider/model; failed attempts logged as
   `llm_call_failed` steps.
8. **Tool-arg validation — confirmed, Moqui handles it.** Already aligned.
9. **Tool-result size — deferred; design-around now.** Convention: author `get#` tools to return
   LLM-sized output (paginate, summarize, top-N) — never raw row dumps. To be added to the
   practices guide.
10. **No tenantId / multi-tenancy.** One business owns the instance; Moqui user/authz scopes data.
    `servedByModelId` + `providerRunId` correlation fields **shipped** — populated on `AiAgentRun` and
    exposed as `run#Agent` out-params (alongside `providerName`; the broader fallback-chain work shipped under #7).
11. **Masking — deferred.** The tool/service owns AI-safe output; `get#` services intended for AI
    use are designed accordingly. No hook machinery in v1.

---

## Structured output — locked design shape (v1 build item)

The agent definition owns the contract; the caller invokes and receives whatever the agent
guarantees. (Product owner, 2026-06-03: "the person defining the agent will lock that; the user
works with what it gets from the available agent.")

- **`AiAgent.responseSchema`** — JSON Schema stored as text (CLOB), parsed to a Map at run time.
  `null` ⇒ free-text behavior as today. Fully backward-compatible; existing agents untouched.
- **`run#Agent` output** — when `responseSchema` is set, return a typed `structuredResult` Map
  (ready to write into a Moqui entity); when `null`, return the current `assistantMessage` text.
  One agent → one predictable contract; the caller does not choose.
- **Per-provider translation in the adapter** from that one stored schema — OpenAI `json_schema`,
  Google `responseSchema`, Anthropic tool-trick — caller never sees the mechanism. Schema lives in
  our entity (good for audit), not scattered across call sites.
- **Known gotcha (adapter concern, not a blocker):** structured output applies to the agent's
  **final** answer, not intermediate tool-calling turns. OpenAI/Google coexist with function tools
  fine. **Anthropic's** structured-output mechanism *is* a forced tool call, which competes with
  the agent's business tools — so the adapter must allow business tools across the loop, then force
  the structured-output tool on the closing turn.
- **v1 caveat — `responseSchema` + tool grants on the same agent:** validated only on **Anthropic**
  (business tools run across the loop, then the forced `structured_output` tool fires on the closing
  turn). On **OpenAI** the strict `response_format` is sent on every turn and can suppress function
  tool calls, so v1 targets **single-turn** structured output (no tool grants) for OpenAI.

- **Bug found & fixed via the live tool loop:** the funded Anthropic live tool-loop test surfaced a
  tool-name sanitization bug (Anthropic rejects tool names that don't match its allowed character set).
  Fixed by lifting the sanitization into `AbstractLlmProvider` so all providers share one normalization
  path. Confirmed by the now-passing `live: full agent loop calls a tool via Anthropic` test.

**Next step (after approval):** a `writing-plans` plan for the structured-output feature only —
`AiAgent.responseSchema` field, the three adapter translations, the `structuredResult` output path,
and the Anthropic closing-turn handling — TDD per the established test harness. No other v1 code.
