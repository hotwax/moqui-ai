# OpenAI Responses API — Audit, Gap Analysis & Migration Plan

- **Date:** 2026-06-03
- **Component:** `moqui-ai` (branch `feature/ai-agent-framework`)
- **Status:** ❌ **CLOSED — evaluated, declined for v1 (2026-06-03).** Superseded by
  [2026-06-03-enterprise-decisions-gap-report.md](2026-06-03-enterprise-decisions-gap-report.md).
  The enterprise decision record resolves this doc's open questions: Decision 1 (we always own
  state, `store:false`, provider state never used) and Decision 3 (no provider built-in tools)
  remove the only two reasons to adopt the Responses API. Stripped of server-side state and hosted
  tools, Responses is an OpenAI-only wire format doing what stateless Chat Completions already does
  — and Chat Completions also fits Anthropic/Google, preserving "switch provider = one field."
  **We stay on Chat Completions.** Revisit only if OpenAI ships a capability we need that is
  exclusive to Responses. The audit (§1) and the openai-agents→Moqui mapping (§3) remain useful
  reference; the migration plan (§4) is shelved.
- **Status (original):** Audit + plan for review. **No implementation until approved.**
- **Scope note:** My knowledge cutoff is Jan 2026; the Responses API and `openai-agents` SDK
  evolve. Wire-level specifics below (field/event/tool names) are flagged **[verify vs current
  OpenAI docs]** where exactness matters. Architecture and Moqui mapping are version-independent.

---

## ⚠️ Read this first — the decision the brief understates

The brief frames this as "migrate the OpenAI integration from Chat Completions to the Responses
API." But the audit shows the current integration is **not** a naive, stateless Chat Completions
call. It is a **provider-agnostic agent framework** that *already implements* — in Moqui idioms —
the agentic loop, tool registry/dispatch, conversation persistence, and run observability that the
Responses API and the `openai-agents` SDK exist to provide.

So the real question is **not** "how do we become agentic" (we are). It is:

> **Should the OpenAI provider switch from the portable Chat Completions wire format to OpenAI's
> proprietary, server-stateful Responses API — diverging it from the uniform multi-provider
> contract — in exchange for server-side state, built-in tools (web_search/file_search/
> code_interpreter), and reasoning items?**

`Responses` is **OpenAI-only**. Anthropic and Google have no equivalent; our framework deliberately
keeps state and the loop on our side so every provider behaves uniformly. A blind full migration
would fork the OpenAI provider into a second paradigm and partially duplicate state management we
already own (`AiConversation`/`AiConversationMessage`).

**Recommendation (detailed in §4):** treat Responses as an **opt-in OpenAI capability tier**, not a
replacement. Keep the portable Chat Completions adapter as the default cross-provider path; add a
`ResponsesProvider` (or per-agent `apiMode = chat | responses`) that unlocks built-in tools +
server state for agents that explicitly want them. This preserves the abstraction and is
incrementally deployable. The plan below is structured around that, and calls out where a
full-swap would differ.

---

## 1. Audit findings — what exists today

All OpenAI/LLM code lives in the `moqui-ai` component. There is **one** HTTP integration point
(`AbstractLlmProvider`), with per-provider encode/decode adapters.

### 1.1 HTTP client layer

| File | Role |
|---|---|
| `src/main/groovy/org/moqui/ai/provider/AbstractLlmProvider.groovy` | Shared transport: `RestClient` POST, auth headers, **throws on non-2xx**, returns decoded Map |
| `src/main/groovy/org/moqui/ai/provider/OpenAiProvider.groovy` | OpenAI Chat Completions encode/decode |
| `src/main/groovy/org/moqui/ai/provider/AnthropicProvider.groovy` | Anthropic Messages encode/decode |
| `src/main/groovy/org/moqui/ai/provider/MockProvider.groovy` | Scripted, for tests |
| `src/main/groovy/org/moqui/ai/LlmProvider.groovy` | Interface: `String getName()` · `Map chat(Map request)` |

**OpenAI provider specifics (the subject of this migration):**
1. **Endpoint:** `POST {ai_openai_base_url}/chat/completions` (default base `https://api.openai.com/v1`). Auth: `Authorization: Bearer ${ai_openai_key}`.
2. **Request shape sent:** `{ model, messages:[{role, content} | {role:assistant, content:null, tool_calls:[{id,type:function,function:{name,arguments(JSON string)}}]} | {role:tool, tool_call_id, content}], tools:[{type:function, function:{name,description,parameters(JSON schema)}}] }`. System prompt is the first `{role:system}` message. Function names are **sanitized** to `^[a-zA-Z0-9_-]+$` and mapped back on decode (Moqui service names contain `.`/`#`).
3. **Response fields used:** `choices[0].message.content`, `choices[0].message.tool_calls[].{id,function.name,function.arguments}`, `choices[0].finish_reason`, `usage.prompt_tokens`, `usage.completion_tokens`.
4. **Conversation history:** **Managed manually.** The full `messages` array is rebuilt and re-sent on every call. There is no `previous_response_id` / server state. (Phase 2 added optional persistence: when a `conversationId` is passed, prior messages are loaded from `AiConversationMessage` and replayed; otherwise it's a single stateless turn.)
5. **Tool/function calling:** **Fully implemented.** The loop is owned by `AgentRunner` (a Groovy while-loop), not the model/server — see §1.3.

### 1.2 Entities (run state + definitions + observability)

`entity/AiEntities.xml` and `entity/AiConversationEntities.xml`:

| Entity | PK | Purpose |
|---|---|---|
| `AiAgent` | `agentName` | Agent config (provider, model, systemPrompt, max-iterations, ceilings, statusId) |
| `AiTool` | `toolName` | Tool catalog entry (serviceName, description, requiresApproval) — currently in-memory primary, entity reserved |
| `AiAgentTool` | (`agentName`,`toolName`) | Which tools an agent may use (`one-nofk` to AiTool) |
| `AiAgentRun` | `agentRunId` | **Run log:** agentName, userId, status (`AI_RUN_*`), provider, model, userMessage, assistantMessage, iterations, tokensIn/Out, estimatedCost, errorText, conversationId |
| `AiAgentRunStep` | (`agentRunId`,`stepSeqId`) | Per-iteration step (llm_call/tool_call, tokens, finishReason) |
| `AiToolCall` | (`agentRunId`,`stepSeqId`,`toolCallId`) | Each tool dispatch: args, result, success, durationMs |
| `AiConversation` | `conversationId` | Named multi-turn conversation (agent, user, status, lastActivityDate) |
| `AiConversationMessage` | (`conversationId`,`messageSeqId`) | Persisted message (role, content, toolCalls JSON, toolCallId) for replay |

Statuses use `StatusItem`/`StatusFlow`/`StatusFlowTransition` (install data). **Audit fields:** all
carry `fromDate` and Moqui's auto `lastUpdatedStamp`; **none declare `createdDate`** (note for the
new-entity constraint in §4).

### 1.3 Services & orchestration

- `service/ai/AgentServices.xml` → **`ai.AgentServices.run#Agent`** (`transaction="ignore"`, `authenticate="true"`): the entry point. Delegates to `AgentRunner`. Also `create#Conversation`.
- `service/moqui/ai/test/TestServices.xml` → `get#Echo` (test tool only).
- `AgentRunner.groovy` — **the agentic loop**, in pure Groovy:
  - loads agent + granted tool schemas (from the in-memory catalog), assembles system context;
  - `for i in 0..maxIterations`: `provider.chat(messages)` → if response has `toolCalls`, dispatch each via `ec.service.sync().name(serviceName).parameters(args)` (Moqui authz applies), append tool-result message, loop; else return the text;
  - enforces per-run ceilings (tokens / cost / tool-calls-per-turn);
  - persists `AiAgentRun` + `AiAgentRunStep` + `AiToolCall` (guarded — persistence never aborts a run); holds **no enclosing transaction** (each tool call / write is its own tx);
  - the loop terminator is **our `max-iterations` + "no tool calls" check**, driven off `finish_reason`/empty-toolCalls — *not* a server `status`.
- `AiToolFactory.groovy` — `ToolFactory` SPI singleton: provider registry (mock always; anthropic/openai key-gated) + the file-defined tool catalog.
- `ToolSchemaBuilder.groovy` — generates a tool's JSON schema from the referenced Moqui service's `in-parameters`.
- `DefinitionLoader.groovy` — scans each component's `ai/*.tools.xml`, validates (fail-loud), builds the catalog.

### 1.4 Configuration
`MoquiConf.xml` default-properties (resolved via Moqui's built-in env-var → property mechanism,
`is-secret` masked): `ai_openai_key`, `ai_openai_base_url`, `ai_anthropic_*`, `ai_timeout_seconds`.
Local dev secrets in gitignored `dev.env`.

**Audit conclusion:** We already have agentic loop + tool registry + tool dispatch + conversation
persistence + run observability + provider abstraction, all in Moqui idioms. What we do **not**
have: server-side conversation state, built-in hosted tools, reasoning items, native streaming,
agent **handoffs**, and **guardrails**.

---

## 2. Gap analysis — Chat Completions vs Responses API

What the Responses API (`POST /v1/responses`) changes relative to our current Chat Completions
path. **[verify field/event names vs current OpenAI docs]**

| Dimension | Today (Chat Completions, in `OpenAiProvider`) | Responses API | Delta / work |
|---|---|---|---|
| **State** | Client-owned; we replay full `messages` (or our `AiConversationMessage`) every call | Server-stored; continue via `previous_response_id` | New: store/return `responseId`; decide who owns truth (see §4 decision) |
| **Request body** | `messages[]` + `tools[type:function]` | `input` (string or items) + `tools` (functions **and** built-ins) + `previous_response_id` + `instructions` | New encode path |
| **Response model** | `choices[0].message` (+ `tool_calls`) | Typed `output[]` items: `message`/`output_text`, `function_call`, `function_call_output`, `reasoning` | New decode path; map output items → our normalized Map |
| **Loop driver** | our `max-iterations` + empty-toolCalls | `status`: `completed` \| `requires_action` \| `in_progress`; on `requires_action` submit `function_call_output`s | Loop adapts to status-driven; or keep our loop and treat `requires_action` like our toolCalls branch |
| **Built-in tools** | none (every tool is a Moqui service) | `web_search_preview`, `file_search`, `code_interpreter` — server-run, declared in `tools` | New capability: declare + surface results; no `ec.service.sync` for these |
| **Reasoning** | none | `reasoning` output items (reasoning models) | New: persist/trace reasoning if used |
| **Streaming** | none (single response) | SSE typed events (`response.output_text.delta`, `response.completed`, …) | New (optional); pairs with a future streaming surface |
| **Tokens/usage** | `usage.prompt_tokens`/`completion_tokens` | `usage.input_tokens`/`output_tokens` (+ reasoning tokens) | Field rename in decode |

**What does NOT change (already done, reused):** tool dispatch via `ec.service.sync()`, the tool
catalog + schema generation, run/step/toolcall observability, conversation entities, per-run
ceilings, the provider registry, secret handling. The Responses migration is **additive to the
OpenAI provider**, not a rewrite of the framework.

---

## 3. Architecture notes — `openai-agents` SDK capabilities → Moqui idioms

Studied as **reference architecture only** (it is Python/JS on the Responses API; we do not import
it). For each capability: how the SDK does it, and the Moqui-idiomatic equivalent (noting what we
already have).

**1. The agentic loop.** SDK `Runner.run` calls the model, inspects the response, dispatches any
tool calls, appends outputs, and re-invokes until the model returns a final output or a max-turns
limit — for Responses, it keys off `status == requires_action` and submits `function_call_output`s.
→ **Moqui:** this is exactly our `AgentRunner` Groovy loop. For Responses, adapt the terminator to
`status` and the continuation to `previous_response_id` (no message replay). Keep it a synchronous
Groovy method behind `run#Agent` with `transaction="ignore"`; LLM HTTP stays outside any tx. Long
runs (built-in tools, many turns) may later warrant a Moqui **async service** + poll, but the
synchronous loop is the right first step.

**2. Tool registry.** SDK registers Python functions as tools (`@function_tool`), auto-derives the
JSON schema from type hints, and serializes the return value back to the model. → **Moqui:** we
already do this with **`ai/*.tools.xml`** (declarative catalog) + **`ToolSchemaBuilder`** (schema
from a service's `in-parameters`) + dispatch via **`ec.service.sync()`** with the result
JSON-serialized back. For Responses we add **built-in tools** as catalog entries with a `kind`
(`service` vs `builtin`): `builtin` tools are declared in the `tools` array but are **not**
dispatched locally (the server runs them). A `tool-manifest` view over `AiTool`/`AiAgentTool`
already serves as the registry.

**3. Agent handoffs.** SDK `handoffs=[...]` lets an agent transfer the conversation to another
agent mid-run; the SDK exposes each handoff to the model as a synthetic tool, and the receiving
agent inherits the running context. → **Moqui (new):** model a handoff as a **special tool** whose
"dispatch" is `ec.service.sync().name("ai.AgentServices.run#Agent").parameters([agentName: target,
conversationId: …])` — i.e., service delegation with a **shared context map** (the conversation +
a handoff reason). Add an `AiAgentHandoff` entity (`fromAgent`, `toAgent`, allowed) so handoffs are
declared/auditable, and record a handoff step in the run log. The receiving agent gets context via
the shared `conversationId` (Phase 2 replay).

**4. Tracing.** SDK captures a trace per run: spans for each model call, tool call, and handoff,
with token usage and which agent handled what. → **Moqui:** we already have an **entity-backed run
log** — `AiAgentRun` (+ tokens/cost) → `AiAgentRunStep` → `AiToolCall`. Extend it for Responses:
add `responseId` (+ `previousResponseId`) to `AiAgentRun`/step, a `reasoning` step type, and an
`agentName` on steps so handoffs are attributable. This is queryable via standard entity-find —
no external tracing backend needed.

**5. Guardrails.** SDK runs input guardrails before the model call and output guardrails after,
each able to short-circuit/raise. → **Moqui (new):** a guardrail is a **Moqui service returning a
pass/fail + reason**. Register pre/post guardrail service names on the agent (e.g.
`AiAgentGuardrail` entity: `agentName`, `phase = input|output`, `serviceName`, `seq`). The
orchestrator calls them inline via `ec.service.sync()` around the model call; a failed input
guardrail aborts the run (`AI_RUN_BLOCKED`), a failed output guardrail rejects/retries. Inline in
the orchestrator (not SECA) so it can short-circuit deterministically.

---

## 4. Migration plan (no implementation yet)

### 4.0 Strategic decision to confirm first
Pick the posture (see the callout up top):
- **(A) Opt-in tier (recommended):** add a Responses path *alongside* Chat Completions; agents opt
  in via `AiAgent.apiMode = chat | responses`. Preserves multi-provider uniformity; unlocks
  built-in tools + server state where wanted.
- **(B) Full swap:** OpenAI provider always uses Responses. Simpler single OpenAI path, but forks
  it from the other providers and duplicates/*competes with* our conversation state.

Everything below assumes **(A)**; (B) differs only in that the Chat Completions adapter is retired
and `apiMode` disappears.

### 4.1 HTTP client layer changes
- Add **`ResponsesProvider`** (or a `responses` mode inside `OpenAiProvider`) extending
  `AbstractLlmProvider`: `endpointPath = /responses`; encode our normalized request → Responses
  `input`/`tools`/`previous_response_id`; decode `output[]` items → our normalized response Map
  (text + toolCalls), plus pass through `responseId`, `status`, reasoning, and `usage.input/output_tokens`.
- The normalized `LlmProvider` Map contract stays; add optional keys (`responseId`,
  `previousResponseId`, `status`) the loop can use when present (other providers ignore them).
- Reuse the existing non-2xx fail-loud guard and the function-name sanitizer.

### 4.2 New entities (run-state persistence)
Moqui is stateless per request, so Responses continuation needs persisted linkage:
- **Extend `AiAgentRun`:** `apiMode`, `responseId`, `previousResponseId` (so a follow-up turn
  continues server state). *(Edit the entity directly — it's ours, pre-release; no `extend-entity`.)*
- **`AiAgentHandoff`** (new): `(agentName, toAgentName)` allowed handoffs.
- **`AiAgentGuardrail`** (new): `(agentName, phase, seqId)` → `serviceName`.
- **Built-in tool support:** add `toolKind` (`service`|`builtin`) + `builtinType` to `AiTool`.
- **Audit-field constraint:** every NEW entity must declare `createdDate` (`date-time`) and rely on
  Moqui's auto `lastUpdatedStamp`. (Existing entities use `fromDate`; we'll add `createdDate` to
  new ones per the constraint; consider backfilling existing ones in a later cleanup ticket.)

### 4.3 New / changed services
- **Orchestrator:** `AgentRunner` already *is* the loop. Add a Responses branch: when
  `apiMode=responses`, continue via `previousResponseId` instead of replaying messages, and key
  termination off `status` (`requires_action` → dispatch tools → submit outputs; `completed` →
  done). Keep `run#Agent` as the single entry point.
- **Tool-dispatch service:** today dispatch is inline in `AgentRunner.dispatchTool`. Extract a
  thin `ai.AgentServices.dispatch#Tool` (toolName + args → result) so it's independently testable
  and reusable; built-in tools skip local dispatch.
- **Run-state service:** `create/update#AiAgentRun` (entity-auto) already covers it; add a small
  `store#AiAgentRunResponse` to persist `responseId` between turns.
- **Guardrail + handoff** hooks invoked inline by the orchestrator (per §3).

### 4.4 Existing services reusable as tools with minimal wrapping
Any Moqui service whose `in-parameters` form a clean JSON schema is already a tool via one line in
`ai/*.tools.xml` — **no wrapping**. Good first candidates are read-only `get#…` services. Services
with rich/typed object params may need a thin flat-parameter wrapper so the generated schema is
LLM-friendly. (This is unchanged by Responses.)

### 4.5 Sequenced tickets (each independently deployable, no big-bang)
1. **T1 — `ResponsesProvider` (read-only path):** encode/decode for a *no-tool* Responses call;
   unit tests (encode/decode) + guarded live test. Agents still default to `chat`. *Ships value:
   proves the wire format; zero behavior change for existing agents.*
2. **T2 — `apiMode` + run-state:** add `AiAgent.apiMode`, `AiAgentRun.responseId/previousResponseId`;
   `AgentRunner` Responses branch for **text-only** multi-turn via `previous_response_id`; live test.
3. **T3 — Function tools over Responses:** map our `function_call` / `function_call_output` cycle to
   the `status=requires_action` loop; reuse `dispatchTool`. End-to-end live test (tool call via
   Responses). *Parity with the current Chat Completions loop.*
4. **T4 — Built-in tools:** `toolKind=builtin` + declare `web_search_preview` (smallest); surface
   results in the run log; live test. *New capability, opt-in per agent.*
5. **T5 — Tracing extensions:** `responseId`/reasoning/agentName on steps; reasoning-item capture.
6. **T6 — Guardrails:** `AiAgentGuardrail` + pre/post hooks in the orchestrator; `AI_RUN_BLOCKED`.
7. **T7 — Handoffs:** `AiAgentHandoff` + handoff-as-tool delegation via `run#Agent` + shared
   conversation; run-log attribution.
8. **T8 (optional) — Streaming:** SSE decode + a streaming surface (screen/endpoint).

T1–T3 reach **functional parity** with today's OpenAI loop on the Responses API; T4–T8 are the net-new
agentic capabilities. Each ticket is its own branch/PR with green tests before the next.

---

## Open questions for review
1. **Posture (A) opt-in tier vs (B) full swap** — the single most important call.
2. Do we want Responses' **server-side state** as source of truth, or keep our `AiConversation`
   entities authoritative and use `previous_response_id` only as an optimization? (Affects T2.)
3. Which **built-in tools** matter first (web_search vs file_search vs code_interpreter)?
4. Are **handoffs** and **guardrails** in scope now, or deferred until the Responses core (T1–T4)
   lands? (They're net-new and orthogonal to the Chat→Responses migration.)
5. Anthropic/Google have no Responses equivalent — confirm they stay on the current chat-style
   adapters (reinforces posture A).
