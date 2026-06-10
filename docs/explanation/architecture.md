# moqui-ai — Architecture

> How the moqui-ai agent framework works and **why** it is built this way. This is the
> conceptual companion to the field-level catalogs in [`../reference/`](../reference/) — for
> exact entity fields, service params, and screen transitions, read those. Here we explain the
> moving parts: the agentic loop, the provider layer, multi-provider failover, context
> management, the human approval gate and preview mode, the registry model, the Composer, and
> the Builder Knowledgebase.
>
> The **code is canonical**. Every behavioral claim below was verified against the shipped
> source — principally `src/main/groovy/org/moqui/ai/AgentRunner.groovy` and the provider
> adapters under `src/main/groovy/org/moqui/ai/provider/`.

## Design stance: Moqui-native, Map-based

moqui-ai is not a bolt-on SDK. It is built out of ordinary Moqui idioms:

- **Tools are services.** A tool is just a Moqui service the LLM is allowed to call. Dispatch is
  `ec.service.sync().name(serviceName).parameters(args).call()` — so Moqui's authorization,
  transactions, and parameter validation apply for free.
- **Agents are entities + a service.** An agent is a row (`AiAgent`); running one is the
  `ai.AgentServices.run#Agent` service. There is no separate agent runtime.
- **Everything flows as `Map`s.** The loop and the providers exchange plain `Map`/`List`
  structures — the same stance as Moqui's `ElasticFacade`. A new provider implements one method
  (`Map chat(Map request)`); it never sees a Moqui-specific type.
- **No core-facade, no Spring, no annotations.** The framework registers a single
  `ToolFactory` (`AiToolFactory`) and is otherwise reachable through `service.xml`,
  `ec.service`, and `ec.entity`.

The one stateful holder is `AiToolFactory`, registered as `ec.factory.getTool("AI", AiToolFactory.class)`.
It owns the **provider registry** and an in-memory **tool catalog** built from `AiTool` rows. It
deliberately does **not** hold agents or conversations — those are read from entities on every
run, because the registry mutates at runtime (the Composer drafts and activates agents) and seed
data can be loaded out-of-band.

---

## The agentic loop

`AgentRunner` is the provider-agnostic loop. The entry service `run#Agent` takes the stable opaque
`agentId` only and calls `AgentRunner.run(...)`; a human `agentName` is resolved to an id upstream
(at the conversation-entry layer — `create#Conversation` / `run#Conversation`), never on the executor.
The loop holds **no enclosing transaction**: LLM calls happen outside any tx, each tool call runs
in its own tx (the `ec.service.sync()` default), and each observability write runs in its own
short tx — guarded so a failed audit write only logs a warning and never aborts the run. That is
why `run#Agent`, `preview#Agent`, and the approval `decide#`/`approve#`/`reject#` services are all
declared `transaction="ignore"`.

```
run#Agent(agentId, userMessage, conversationId?)
        │
        ▼
  load AiAgent (useCache(false))           ← fresh read; see "Why no cache" below
  load model candidates (AiAgentModel)     ← the failover chain
  load granted tool schemas (+ remember)   ← from AiAgentTool grants
        │
        ▼
  ┌──────────────── loop, i = 0 .. maxIterations ────────────────┐
  │  assemble context (system prompt + facts + summary + window) │
  │  callWithFailover → provider.chat(request)  ── HTTP, no tx ── │
  │  record AiAgentRunStep(llm_call) + token counts              │
  │                                                              │
  │  no tool calls?  ── yes ─▶ COMPLETED (return assistantMessage)│
  │       │ no                                                   │
  │  any call needs approval?  ── yes ─▶ SUSPEND (persist state) │
  │       │ no                                                   │
  │  dispatch each tool call → ec.service.sync()                 │
  │  record AiToolCall per call; append tool results to messages │
  └──────────────────────────────────────────────────────────────┘
        │ ran out of iterations
        ▼
     TRUNCATED
```

### What one iteration does

1. **Assemble the request.** The system prompt, the message history, the granted tool schemas,
   the optional `responseSchema`, and the optional `reasoning` setting are packed into a request
   `Map`. The context view is **re-assembled every iteration on purpose** — a tool may call
   `remember` mid-run, so a later iteration must see the new fact and re-window the grown history.
2. **Call the model with failover** (see below). Token counts (`tokensIn`/`tokensOut`) accumulate;
   the served provider/model and the provider's own response id (`providerRunId`) are recorded.
3. **Branch on the response.**
   - **No tool calls** → the run is done. The assistant text (or the JSON of a structured result)
     becomes `assistantMessage`; status `AI_RUN_COMPLETED`.
   - **Tool calls present** → if any of them needs approval, the whole turn **suspends** (below).
     Otherwise each call is dispatched and its result appended as a `tool` message for the next
     iteration.

### Ceilings and terminal states

The loop is bounded several ways, each mapping to a terminal `AiAgentRun.statusId`:

| Bound | Source field | Default | Terminal status |
|---|---|---|---|
| Iterations | `maxIterations` | 8 | `AI_RUN_TRUNCATED` (ran out) |
| Tokens (in+out) | `maxTokens` | 0 = off | `AI_RUN_ABORTED` |
| Tool calls in one turn | `maxToolCallsPerTurn` | 20 | `AI_RUN_ABORTED` |
| An unhandled throwable | — | — | `AI_RUN_FAILED` (logged) |

> **`maxCost` is stored but not enforced.** `AiAgent.maxCost` exists and `store#AiAgent` accepts
> it, but `AgentRunner` never reads it — cost is *recorded*, not a runtime ceiling. Only
> `maxTokens` and `maxToolCallsPerTurn` abort a run. (See [decisions](decisions.md).)

### Per-step audit trail

Every run is a recoverable, auditable story. `AiAgentRun` is the header; under it,
`AiAgentRunStep` rows record what happened, in order, with `stepType` one of:

- `llm_call` — a provider call (with its token counts and finish reason). Its `success` field is the
  outcome: `Y` for the call that answered, `N` for a candidate that errored and was skipped during
  failover. (Tool detail is captured on `AiToolCall`, not as a step.)
- `context_trim` — the windower dropped messages (strategy `window`)
- `compaction` — the windower dropped messages and they were folded into a summary (strategy `summarize`)

Each dispatched tool call also writes an `AiToolCall` row (arguments, result JSON, success flag,
error text, duration). A rejected call during resume writes an `AiToolCall` with `success="N"`
and a "Denied by user" result so the model can react to the denial. This is the data the **Runs /
RunDetail** screens render.

### Cost is stamped off the *served* model

When a run finalizes (`finish()`), `estimateCost()` looks up the effective `AiModelPrice` for the
**provider/model that actually served the run** — not the configured primary — and computes
`estimatedCost` from the token counts (`CostCalc`, prices per million tokens). If no price row is
configured the cost is `0` and the run is never blocked. This is what makes the **Cost** screen
and `get#AiSpend` correct after a failover: spend is priced against what was really used.

### Why no cache on the agent read

`AgentRunner` reads `AiAgent`, `AiAgentModel`, and `AiAgentTool` grants with `useCache(false)`.
The registry mutates at runtime (the Composer drafts/activates agents and grants tools) and seed
data may be loaded by a separate `gradlew load` process that cannot invalidate *this* JVM's cache.
A cached by-PK miss would otherwise survive as a stale "Unknown agent" even after the row exists,
and a stale grant list would hide a just-granted capability. The read runs once per run, right
before multi-second provider calls, so a fresh read costs nothing meaningful.

---

## Providers and structured output

The only contract a provider implements is `LlmProvider`:

```
String getName()          // registry key: "mock" | "anthropic" | "openai"
Map    chat(Map request)  // request Map in, response Map out — makes the HTTP call
```

- **Request Map** carries: `model`, `systemContext`, `messages`, `tools`, optional
  `responseSchema` (a JSON-Schema `Map`), and optional `reasoning` (`[effort: low|medium|high]`).
- **Response Map** carries: `assistantText`, `toolCalls`, `tokensIn`, `tokensOut`,
  `finishReason`, `providerRunId` (the provider's own response id), and `structuredResult` (the
  parsed answer when a `responseSchema` was set).

`AbstractLlmProvider` is the shared HTTP transport: it POSTs an encoded body via Moqui's
`RestClient`, applies provider auth headers, and **fails loudly on any non-2xx** (otherwise an
error body would parse as an empty completion and the run would silently "complete" with no
answer). Concrete adapters implement only the wire format — `encodeRequest(Map → String)` and
`decodeResponse(String → Map)`.

### Three shipped providers

| Provider | Class | Endpoint | Notes |
|---|---|---|---|
| OpenAI | `OpenAiProvider` | Chat Completions (`/chat/completions`) | function tool-calls; `response_format: json_schema` for structured output |
| Anthropic | `AnthropicProvider` | Messages (`/v1/messages`) | `tool_use`/`tool_result` content blocks; a synthetic `structured_output` tool for structured output |
| Mock | `MockProvider` | — | deterministic, scriptable; always registered, no config — used by tests |

There is **no Google/Gemini provider** (the interface comment lists "google" as a *possible* key,
but no implementation ships). Providers register at boot, but only when their API key is
configured, and a provider-init failure is isolated so it never breaks startup.

### Tool schemas

`ToolSchemaBuilder` generates a JSON-Schema object for a service's in-parameters by introspecting
its live `ServiceDefinition` — mapping Moqui types to JSON types, skipping Moqui's implicit
auth/system parameters. Schemas are **generated on demand, never stored**, so a tool's schema can
never drift from the service it points at. Each provider's `encodeRequest` then renders these
into its own tool/function wire shape.

### Structured output

When an agent sets a `responseSchema`, the framework asks the provider for a structured answer and
returns it as `structuredResult`:

- **OpenAI** sends `response_format: {type: json_schema, strict: true}` and parses the assistant
  text back as JSON.
- **Anthropic** appends a synthetic `structured_output` tool carrying the schema; when no business
  tools are granted it forces that tool for a one-shot answer. On decode, `applyStructured`
  extracts the synthetic call's arguments into `structuredResult` and **removes only the synthetic
  call** — any co-emitted business tool calls are preserved so the loop still dispatches them.

> v1 caveat (OpenAI): `response_format` is sent on every turn, which biases the model to emit
> schema-conforming JSON immediately and can suppress function tool calls. v1 structured-output
> agents are single-turn (no tool grants), so this is fine; an agent needing both tools and a
> schema would need this gated to the final turn.

### Reasoning effort

`AiAgent.reasoningEffort` (`low`/`medium`/`high`) becomes a provider-specific knob:

- **OpenAI** maps it straight to `reasoning_effort` (for reasoning-capable models only).
- **Anthropic** maps it to an extended-thinking budget (`1024`/`8192`/`24576` tokens). In v1 this
  is enabled **only when the agent has no tools** — Anthropic requires preserving thinking blocks
  across `tool_result` turns, which the v1 message shape does not carry. Because the built-in
  `remember` tool is added whenever context management is on with a conversation, a context-managed
  conversational agent likewise suppresses thinking — intentional. The synthetic
  `structured_output` tool is terminal (no round-trip), so thinking is safe alongside it.

---

## Multi-provider failover

An agent can declare an ordered chain of provider/model candidates as `AiAgentModel` rows
(`agentId, priority` PK; lower priority tried first). If no chain is defined, the loop falls back
to the agent's own `providerName`/`modelName` — so a single-model agent just works.

`callWithFailover` tries candidates from a starting index in order; the first whose
`provider.chat()` succeeds wins. The key property is **stickiness**: the index of the winning
candidate (`candIdx`) is carried forward, so once a fallback succeeds the loop **stays on that
working candidate** for the remaining iterations rather than re-probing the broken primary every
turn.

```
candidates: [0] openai:gpt-4o   [1] anthropic:claude-3-5-sonnet   [2] openai:gpt-4o-mini
iteration 1: try 0 → fails → try 1 → ok      candIdx ⇒ 1   (step: llm_call success=N for 0)
iteration 2: start at 1 → ok                 candIdx ⇒ 1   (no re-probe of 0)
```

Each skipped candidate writes a failed `llm_call` `AiAgentRunStep` (`success=N`, with the failing
provider:model) for observability. The served provider/model is persisted on the run
(`servedByModelId`, and `providerName` is updated to the served provider), so history and cost
reflect what actually answered. If *every* remaining candidate fails, the loop throws and the run
finalizes as `AI_RUN_FAILED`.

> Compaction summarization does **not** follow the sticky candidate — it always uses the chain's
> primary model and is best-effort (see below).

---

## Runs vs. conversations

*Two records of one turn — and why that isn't duplicate data.*

A **run** (`AiAgentRun`) is one execution: one `run#Agent` call, from the user's message to the
final answer, including every internal step and tool call. A **conversation** (`AiConversation`)
is a thread of *many* runs over time. The bridge is two foreign keys: `AiAgentRun.conversationId`
points a run at its conversation, and every `AiConversationMessage` / `AiConversationFact` carries
the `agentRunId` of the run that wrote it.

The subtle part: a conversational run keeps **two parallel records of the same turn**, for two
different consumers.

- **Run side** — `AiAgentRun → AiAgentRunStep → AiToolCall`. The **audit of one execution**: the
  steps, the dispatched tool calls, tokens, cost. Written for *every* run, including a stateless
  one (`conversationId = null`). Immutable.
- **Conversation side** — `AiConversationMessage` (+ `AiConversationFact`). The **durable
  transcript and memory** that lives *across* runs and is *replayed* into the next run's context.
  Written *only* when there is a conversation. Lossy by design (windowed / compacted).

A single run that calls one tool and answers writes, in conversation `CONV_77`, four messages —
all stamped `agentRunId = AGRUN_1001`:

| conversationId | messageSeqId | role | content / toolCalls |
|---|---|---|---|
| CONV_77 | 0001 | user | "What's the refund total for order 12345?" |
| CONV_77 | 0002 | assistant | *toolCalls:* `[get_order]` |
| CONV_77 | 0003 | tool | `{"total":48.12}` |
| CONV_77 | 0004 | assistant | "Order 12345's refund total is $48.12." |

…and the user/assistant text **also** lives on the run header (`AiAgentRun.userMessage` /
`assistantMessage`). That overlap is **deliberate denormalization**, not an accident:

1. **Stateless runs have no conversation** — for `conversationId = null`, the run header is the
   *only* record of the message.
2. **The audit must be self-contained and immutable** — `RunDetail` renders a run without joining
   to conversation state, and stays correct even after the transcript is windowed / compacted /
   deleted.
3. **Different lifecycles** — run-side is per-execution and frozen; conversation-side is cumulative
   and replayed.

The one rule this imposes: **the run writes both, and nothing edits one without the other.**

> **Why not merge them?** Folding the run audit into the conversation transcript was considered and
> rejected — it would break stateless runs and couple the immutable audit to the lossy, replayed
> transcript. Recorded as **D13** in [decisions.md](decisions.md).

---

## Context management

Context management is configured per agent via `contextStrategy`:

- `off` — replay nothing extra; send the turn as-is.
- `window` — bound the replayed history by message count and char estimate.
- `summarize` — windowing **plus** rolling compaction of what falls out of the window.

This implements ADR 0001 (a layered hybrid: window + compaction + pinned facts), whose
non-negotiable driver is **fidelity — never silently lose a confirmed business value.** The lossy
layers (windowing, compaction) are only made *safe* by the pinned-fact layer, which lives outside
the compressible transcript.

### Windowing (`ContextAssembler`)

`ContextAssembler` is pure (no `ec`). `windowHistory` keeps the last *N* messages of the replayed
prior-turn history, then trims more from the front under a char-estimate cap (`chars/4 ≈ tokens`,
no tokenizer dependency). It is **tool-pair-safe** — it never starts the kept window on an orphaned
`tool` result, so an assistant `tool_call` is never separated from its `tool_result`. The **current
turn is always passed through whole** and never trimmed. The method returns the kept messages plus
the dropped set, so the loop can record what was dropped and (under `summarize`) fold it into a
summary.

### Compaction (the `summarize` strategy)

When messages fall out of the window under `summarize`, `summarizeOverflow` asks the **primary
model** to fold the dropped prefix into a rolling `AiConversation.summaryText`, which is then
injected into the system prompt on subsequent calls (a `## Conversation summary` block). Three
properties matter:

- **Best-effort.** A summarization failure logs a warning and falls back to plain windowing — the
  run continues. Its tokens fold silently into `estimatedCost`/spend.
- **Once-per-run watermark.** `AiConversation.summaryThruMessageSeqId` advances only on a
  successful summarization. Because the replayed message list is fixed for the run, later
  iterations see the same dropped prefix; the guard `dropThru > summaryWatermark` (message seq ids
  are lexically sortable Moqui sequenced ids) ensures an identical overflow is **not re-folded**
  every iteration.
- **Auditable.** Every drop writes a step (`compaction` under `summarize`, `context_trim` under
  `window`) with a `dropped:N` finish reason — never silent.

### Pinned facts (the fidelity guarantee)

`AiConversationFact` is a conversation-scoped, keyed store of confirmed values that is **injected
every call and never summarized or dropped** (`ContextAssembler.withFacts` renders a `## Known
facts` block onto the system prompt). A failed fact load degrades gracefully — the run proceeds
without injection (logged), it does not error.

### The server-injected `remember` tool

When context management is on **and** there is a conversation, the loop appends a built-in
`remember` tool to the agent's tool schemas (`withRememberTool`). The model calls it the moment it
confirms a durable value (an order total, a confirmed address). Critically:

- The `conversationId` is **injected server-side**, never model-supplied — the model can only pass
  `factKey`/`factValue`.
- A `remember` call writes an `AiToolCall` audit row like any other tool, and is **approval-gateable**
  and **survives suspend/resume** (it is handled in the same dispatch path).
- Facts are **store-or-update by `(conversationId, factKey)`** — a new confirmed value supersedes
  the old.

This is the mechanism that lets the lossy window/compaction layers stay safe: anything the model
*confirms* is pinned outside the transcript and re-injected verbatim every turn.

---

## The human approval gate and preview mode

Some tools must not run without a human's say-so. A tool is gated when its `AiTool.requiresApproval`
is set, or per-grant via `AiAgentTool.requiresApprovalOverride` (an agent can be **stricter** than
the tool default, never looser).

### Suspend → decide → resume

When a turn proposes **any** gated tool call, the loop suspends the **entire turn** rather than
running the non-gated calls first:

1. For each gated call, write an `AiToolApproval` row (`AI_APPR_PENDING`) capturing the tool, the
   service, and the JSON arguments.
2. Serialize the loop's mutable state (`messages`, `replayCount`, `stepSeq`, `candIdx`, summary,
   `result`, and the turn's tool calls) into `AiAgentRun.pendingState`, set status
   `AI_RUN_SUSPENDED`, and return `awaitingApproval=true` with the `approvalIds`. The assistant
   tool-call turn is **withheld** from the persisted conversation at this point, so the conversation
   never holds an orphan `tool_call` without its results.
3. An operator approves or rejects each via `approve#`/`reject#ToolCall`. The shared `decide#ToolCall`
   records the decision and, **only when no `AI_APPR_PENDING` rows remain for the run**, calls
   `AgentRunner.resume()`.
4. `resume()` persists the withheld assistant turn, executes each call per its decision (approved /
   non-gated → dispatch; rejected → a "Denied by user" result the model can react to), then
   re-enters the same loop from the saved state.

Because static config (max iterations, candidates, tool schemas, response schema) is **re-derived**
from the agent on resume and never serialized, only mutable loop state crosses the suspend boundary.

### The fail-closed `anyUndecided` guard

`resume()` is **fail-closed**. Before executing the turn it checks: is any *gated* call in the turn
still undecided (its approval row PENDING or missing)? If so, it leaves the run `AI_RUN_SUSPENDED`
untouched and returns. A gated tool can **never** execute without an explicit decision. The
production caller only resumes once the last approval is decided; this guard makes a misuse or a
double-fired `decide` a **safe no-op**, not a consume-and-deny. (Verified in `AgentRunner.resume()`.)

```
turn proposes [ get_order (read), refund_order (gated) ]
   ▼ SUSPEND whole turn; pendingState saved; AI_RUN_SUSPENDED
operator rejects refund_order
   ▼ decide#ToolCall → no pending left → resume()
resume: anyUndecided? no
   get_order → dispatched;  refund_order → "Denied by user" result
   loop continues → model sees the denial → COMPLETED
```

### Preview mode (the Composer's sandbox)

Preview lets a **draft** agent be run on real data with nothing irreversible executed.
`AgentRunner.runPreview()` sets an internal `forceApprovalOnMutating` flag and runs the agent
statelessly (no conversation). The approval gate then treats **every `AI_TOOL_MUTATING` tool as if
it required approval** — so read-only tools run for real, but any mutating call **suspends** instead
of executing. The run is stamped `AiAgentRun.isPreview="Y"`.

Preview runs are deliberately throwaway, which has two consequences enforced in code:

- **They never pollute the operator queue.** `get#PendingApproval` excludes approvals belonging to
  `isPreview="Y"` runs (a null-safe `not-in` on preview run ids) — a preview suspends mutating calls
  only to *show* them, so those PENDING rows are not real decisions.
- **They are deleted on discard.** `discard#Draft` deletes a draft's preview runs along with their
  steps, tool calls, and held approvals.

This is the safety story behind "try this agent before activating it": the operator sees exactly
which writes it *would* make, on real read data, without any write happening.

---

## The registry model

The registry is the keystone the rest of the framework hangs on. Three ideas define it.

**1. Opaque ids, `verb_noun` wire names.** Agents and tools have stable opaque PKs (`agentId`,
`toolId`), not service-FQN keys. The LLM-facing wire name of a tool is its `toolName`, a
snake-case `verb_noun` (e.g. `get_order`) derived at authoring time and uniqueness-checked. Opaque
ids mean a tool can be renamed or re-pointed without breaking grants; `verb_noun` names are
wire-safe by construction (so `AbstractLlmProvider.sanitizeName` is a defensive no-op, not a
load-bearing FQN translator).

**2. DB-backed catalog, lazily loaded.** `AiTool` rows are the source of truth.
`DefinitionLoader.loadCatalog` reads only **ACTIVE + exposable** tools and builds an in-memory
catalog (keyed by `toolId`, indexed by `toolName`), generating each tool's schema on demand from
the live service. The catalog is lazy-loaded on first use (no `ExecutionContext` exists at
`ToolFactory.init`) and rebuilt via `refreshCatalog()` — which `store#AiTool` calls inline so a
freshly authored tool is immediately grant-eligible. A seeded tool whose service was removed is
logged and skipped rather than breaking the whole catalog at boot.

**3. The safety floor: `exposable` + denylist.** A service does not become callable by an agent
just by existing. `store#AiTool` is the single authoring gate, and it enforces a floor:

- **Effect is derived from the verb.** Read verbs (`get`, `find`, `list`, `view`, `search`,
  `check`, `calculate`) → `AI_TOOL_READ_ONLY`; everything else → `AI_TOOL_MUTATING` (a Curator can
  override). Read-only defaults to `exposable=Y`, `requiresApproval=N`; mutating defaults to
  `exposable=N`, `requiresApproval=Y`.
- **The denylist is non-overridable.** Any `AiToolDenylist` service pattern that matches forces
  `exposable=N`, regardless of what was requested.

Only **exposable + active** tools can be granted to an agent (`store#AiAgentTool` re-checks this),
and the catalog only loads exposable + active tools — so a draft can never reference a service the
Curator has not blessed. At run time the loop loads only the agent's `AiAgentTool` grants and
builds their schemas; an agent thus sees a small, explicitly-granted toolset, not the whole
catalog.

---

## The Composer — the framework pointed at itself

The Composer is an agent that **builds agents.** Its insight: the framework's own authoring
operations are themselves Moqui services, so they can be registered as tools and granted to a
special `composer-assistant` agent. The Composer is therefore not special-cased code — it is the
ordinary agentic loop whose granted tools happen to be authoring meta-services
(`ai.ComposerServices.*`, `ai.AgentServices.store#AiAgentTool`, etc., declared as catalog rows in
`data/AiComposerData.xml`).

Its meta-tools (full params in [`../reference/services.md`](../reference/services.md)):

- **`find#Capability`** — searches the grant-eligible catalog. It **tokenizes** the query and
  includes a tool if **any** term hits its `toolName`/`verb`/`noun`/`description`, ranked by hit
  count (a whole-phrase `contains` missed obvious matches like "summarize recent orders" against a
  description containing "summarize" and "orders" non-contiguously). Only exposable + active tools.
- **`describe#Capability`** — one tool's purpose plus its on-demand input schema.
- **`list#DomainTerm`** — the business vocabulary to ground naming in (backed by the Knowledgebase
  below; catalog nouns as a floor).
- **`propose#Naming`** — a best-guess agent name/description: a KB-grounded heuristic floor (snap a
  noun to its canonical glossary term) with optional LLM refinement (`gpt-4o-mini` /
  `claude-3-5-haiku-latest`), guarded so it never fails the build.
- **`set#Guardrail`** — set per-grant approval strictness (`AiAgentTool.requiresApprovalOverride`).
- **`request#Capability`** — record a capability gap for the Curator instead of fabricating a tool.
- **`preview#Agent`** → `runPreview` (the sandbox above).
- **`activate#Agent`** — commit a draft to active.
- **`discard#Draft`** — delete a draft and its preview runs/held approvals.

### draft → preview → approve-to-activate

A new agent is authored as a **draft**. `store#AiAgent` makes a freshly drafted agent **runnable
without hand-editing**: on create it defaults `providerName`/`modelName` from MoquiConf
(`ai_default_provider`/`ai_default_model`, falling back to `openai`/`gpt-4o-mini`), `maxIterations`
to 5, and `systemPrompt` to the description, and defaults the status to `AI_AGENT_DRAFT`. (These
defaults are **create-only** — an explicit value on update is never overridden.) The draft is
exercised via `preview#Agent`, then promoted via `activate#Agent`, which re-checks that every
granted tool is still exposable + active.

Crucially, **activation requires human approval.** `activate_agent` is itself a `requiresApproval`
tool. So when the Composer proposes activating an agent, *its own run* suspends on the approval gate
— the same suspend/decide/resume machinery — and the activation service runs only after a human
approves. (`activate#Agent` is also callable directly by an operator via the Agents screen's
Activate button.) Grants likewise go through an **explicit** `store#AiAgentTool` wrapper (not
entity-auto) that enforces the exposable+active floor — so a draft can never be granted a service
the Curator has not blessed.

---

## The Builder Knowledgebase

The Knowledgebase grounds agent/tool **naming** in each deployment's real vocabulary, so a
generated name speaks the operator's dialect (e.g. "rma" resolves to "return"). It is a curated,
OMS-grounded glossary (`AiDomainTerm`, typed `AI_TERM_NOUN`/`AI_TERM_VERB`) with dialect synonyms
(`AiTermSynonym`) and a learning log (`AiNamingSignal`). v1 is **lexical + suggest-only**: nothing
auto-enters the approved glossary.

**Seeding.** `seed#DomainGlossary` builds the starting glossary from the live model — domain nouns
for OMS entities that actually exist in this deployment, curated UDM concepts, and the verbs of
known services — all `AI_TSRC_SEEDED` + `AI_TERM_APPROVED`. It is idempotent and re-runnable. An
install-time **cron `ServiceJob`** (`data/AiGlossaryJobData.xml`) auto-seeds it.

**Grounding.** `find#DomainTerm` does lexical retrieval over APPROVED terms (+ approved synonyms),
ranked by match strength × usage, with a conservative near-match for dialect inflections. The
Composer's `propose#Naming` snaps a raw verb/noun guess to the nearest canonical glossary term.

**The learn loop: capture → promote → approve.** This is a **two-part** capture mechanism, not a
single hook (the shipped reconciliation of the original design):

1. **Rich in-service capture (preferred).** `store#AiTool` and `store#AiAgent` accept
   `suggestedName`/`intentText` and call `capture#NamingSignal` with that context. They set
   `ec.context.signalCaptured=true` **before** the entity write so the EECA floor skips this write.
2. **Defensive EECA floor.** `AiGlossaryEcas.eecas.xml` declares EECAs on `AiTool` and `AiAgent`
   (`on-create`/`on-update`) that capture a bare naming signal for **any** write not already
   captured in-service and not a seed. This backstops a direct `EntityValue.store()` or a future
   builder path that bypasses the `store#` services. It is auto-scanned by Moqui (any
   `entity/*.eecas.xml` loads automatically) — **no MoquiConf registration is needed**.

Captured signals are scanned by `promote#TermsFromSignals`: chosen-name tokens that recur at or
above a threshold and are not already glossary terms are **proposed** (`AI_TSRC_LEARNED` +
`AI_TERM_SUGGESTED`) for a Curator. A Curator then **approves** or **rejects** via the Glossary
screen (`approve#`/`reject#DomainTerm`, `store#DomainTerm`). Only approved terms feed grounding —
closing the loop without anything auto-entering the vocabulary.

```
author a tool/agent ──▶ capture#NamingSignal (in-service, rich)
                          └─ EECA floor backstops bare writes
recurring tokens ──▶ promote#TermsFromSignals ──▶ SUGGESTED term
Curator ──▶ approve#DomainTerm ──▶ APPROVED ──▶ feeds find#DomainTerm / propose#Naming
```

---

## See also

- [`../reference/entities.md`](../reference/entities.md) — entity fields, PKs, status/enum values.
- [`../reference/services.md`](../reference/services.md) — service params and behavior.
- [`../reference/screens.md`](../reference/screens.md) — the AI Ops console.
- [`../reference/configuration.md`](../reference/configuration.md) — config, secrets, deploy hygiene.
- [`security-model.md`](security-model.md) — authz/membership and agent-runs-as-user.
- [`decisions.md`](decisions.md) — the decision register (what was deferred and why).
- [`../decisions/0001-context-window-management.md`](../decisions/0001-context-window-management.md) — the context ADR.
