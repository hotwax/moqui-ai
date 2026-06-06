# moqui-ai

A Moqui-native AI agent framework, packaged as a self-contained component. Declare
agents and tools as ordinary Moqui data and services, then run an agent with a single
service call — no core-framework changes, no `ec.ai` facade, no Spring, no annotations.

Agents are LLM loops that call your existing Moqui services as tools. The framework
handles the provider wire formats, the agentic loop, persistence, observability, cost,
human-approval gating, context management, and an operational console.

---

## What it is

- **Agents** (`AiAgent`) — a system prompt + model choice + a set of granted tools, run
  as a Moqui service. Identified by an opaque `agentId`; `agentName` is the editable label.
- **Tools** (`AiTool`) — a thin, gated wrapper around one Moqui service. The agentic loop
  exposes granted tools to the model and dispatches the model's tool calls back through
  `ec.service.sync()`, so existing Moqui authz applies to every call.
- **Observability** — every run, step, tool call, and approval is persisted, viewable in
  the AiOps console.

---

## Architecture

The component registers a single `ToolFactory` via Moqui's tool SPI. There is **no core
facade and no `ec.ai`** — agents are reached through ordinary services, and the factory
(holding the provider registry and the tool catalog) is fetched with `ec.factory.getTool`.

`org.moqui.ai.AiToolFactory implements ToolFactory`, `getName() == "AI"`, registered in
this component's `MoquiConf.xml`:

```xml
<tools>
    <tool-factory class="org.moqui.ai.AiToolFactory" init-priority="30" disabled="false"/>
</tools>
```

What the factory does at boot:

- Always registers the `mock` provider (no config needed — used by tests).
- Registers `anthropic` and/or `openai` **only when their API key is configured**, reading
  config from System properties / environment (no `ExecutionContext` exists yet at
  `ToolFactory.init`). Provider init failures are isolated — a bad key never breaks boot.
- Builds the tool catalog lazily from `AiTool` rows on first access (the database is the
  source of truth); `refreshCatalog()` rebuilds it after authoring changes.

The agentic loop lives in `org.moqui.ai.AgentRunner`. It is provider-agnostic and Map-based,
holds **no enclosing transaction** (LLM calls run outside any tx; each tool call and each
observability write manages its own tx), and enforces per-run ceilings
(`maxIterations`, `maxTokens`, `maxToolCallsPerTurn`).

A new provider implements one interface, `org.moqui.ai.LlmProvider` (`Map chat(Map request)`).

---

## Quick start

### 1. Configure a provider

Set an API key (and optionally override the defaults) via `MoquiConf.xml` `default-property`
entries or environment variables. The keys are:

| Property                 | Default                       | Notes                          |
|--------------------------|-------------------------------|--------------------------------|
| `ai_anthropic_key`       | _(empty)_                     | secret; enables Anthropic when set |
| `ai_anthropic_base_url`  | `https://api.anthropic.com`   |                                |
| `ai_anthropic_version`   | `2023-06-01`                  | Anthropic API version header   |
| `ai_openai_key`          | _(empty)_                     | secret; enables OpenAI when set |
| `ai_openai_base_url`     | `https://api.openai.com/v1`   | point at any OpenAI-compatible endpoint to reuse the `openai` provider |
| `ai_timeout_seconds`     | `60`                          | HTTP timeout for provider calls |

```bash
export ai_anthropic_key=sk-ant-...
# or
export ai_openai_key=sk-...
```

The **provider and model are chosen per agent** (`AiAgent.providerName` / `modelName`, plus
an optional `AiAgentModel` failover chain) — not in config.

### 2. Run an agent

```groovy
def r = ec.service.sync().name("ai.AgentServices.run#Agent").parameters([
    agentName:      "order-helper",
    userMessage:    "Cancel order 12345 and tell me the refund total.",
    conversationId: null          // pass an id to replay + persist multi-turn history
]).call()

r.assistantMessage   // the model's final text
r.structuredResult   // parsed Map when the agent declares a responseSchema
r.agentRunId         // the persisted run (drill in via AiOps > Runs)
r.statusId           // AI_RUN_COMPLETED | AI_RUN_TRUNCATED | AI_RUN_ABORTED | AI_RUN_FAILED | AI_RUN_SUSPENDED
r.tokensIn / r.tokensOut / r.estimatedCost
```

When a run proposes a tool that `requiresApproval`, it returns `statusId =
AI_RUN_SUSPENDED` with `approvalIds`; decide it via `ai.ApprovalServices.approve#ToolCall`
/ `reject#ToolCall`, which resumes the run.

### 3. Open the console

The component mounts the **AiOps** operational console at **`/apps/AiOps`** (added via this
component's `MoquiConf.xml`, so no webroot file is touched).

---

## Providers

Three providers ship, under `src/main/groovy/org/moqui/ai/provider/`:

- **`anthropic`** — Anthropic Messages API; maps tools to `tool_use` / `tool_result`
  blocks, supports extended thinking and structured output.
- **`openai`** — OpenAI Chat Completions API; maps tools to function calls, supports
  `reasoning_effort` and JSON-Schema structured output. Point `ai_openai_base_url` at any
  OpenAI-compatible endpoint to reuse this provider.
- **`mock`** — deterministic, scripted responses for tests; always registered.

---

## Feature overview

- **Provider-agnostic agentic loop** — `AgentRunner`; no enclosing tx; per-run
  `maxIterations` / `maxTokens` / `maxToolCallsPerTurn` ceilings.
- **Agent + tool registry** — DB-backed catalog with opaque `toolId` / `agentId` and
  `verb_noun` tool names. `store#AiTool` is the single authoring gate: it validates the
  backing service, derives effect (read-only vs mutating), applies an `AiToolDenylist`
  non-overridable floor, and gates exposure with an `exposable` flag. `store#AiAgent`
  sequences ids and enforces unique names.
- **Composer Assistant** — build agents conversationally: draft → preview on real data
  (mutating tool calls are force-held, never executed) → approve to activate
  (`preview#Agent`, `activate#Agent`, `discard#Draft`).
- **Builder Knowledgebase / domain Glossary** — curated domain terms (`AiDomainTerm`),
  synonyms (`AiTermSynonym`), and captured naming signals (`AiNamingSignal`) that record
  what the Composer proposed vs. what the author kept.
- **Human-approval gate** — tools flagged `requiresApproval` suspend the run; an operator
  approves/rejects and the run resumes (`AiToolApproval`).
- **Cost awareness** — per-model pricing (`store#AiModelPrice`), `estimatedCost` on each
  run, and a queryable `get#AiSpend` (grouped by agent or user).
- **Context management** — `contextStrategy = window` (bound replayed history) or
  `summarize` (compaction into a rolling conversation summary). A built-in `remember` tool
  pins durable facts (`AiConversationFact`) injected into later turns.
- **Provider fallback chain** — `AiAgentModel` rows define an ordered candidate list;
  failover is sticky (once a candidate works the run stays on it).
- **Reasoning** — `AiAgent.reasoningEffort` (low / medium / high) maps to OpenAI
  `reasoning_effort` and Anthropic extended-thinking budget.
- **Structured output** — `AiAgent.responseSchema` (JSON Schema) yields a typed
  `structuredResult` Map, translated to each provider's native mechanism.
- **AiOps console** — operational screens mounted at `/apps/AiOps`: Playground, Composer,
  Agents, Runs (with a RunDetail drill-in), Approvals, Cost, Conversations, Glossary.

### Observability entities

`AiAgentRun` (one per run) → `AiAgentRunStep` (LLM call / tool call / context trim /
compaction / failover) → `AiToolCall` (each dispatched tool, with args, result, timing) and
`AiToolApproval` (each human-gated decision).

---

## Design docs & plans

- **Specs** — `docs/specs/` (framework design, registry, Builder Knowledgebase, Composer)
- **Per-phase plans** — `docs/plans/` (agentic loop, conversations, cost, approval,
  operational UI, context window, fallback chain, reasoning, …)
- **Decisions** — `docs/decisions/0001-context-window-management.md`
