# moqui-ai

A **Moqui-native AI agent framework** for the HotWax/Maarg platform. Tools are ordinary Moqui services and agents are database records; you run an agent as a normal Moqui service (`ai.AgentServices.run#Agent`) that loops over an LLM provider — assembling context, dispatching tool calls, tracking cost, and gating mutating actions behind human approval. On top sits the **Composer Assistant**, an agent-builder that turns a plain-English description into a working agent through a draft → preview → approve-to-activate flow.

This `docs/` tree is the current source of truth. **The code is canonical** — where a design spec and the code disagree, the code wins (see [`reconciliation/`](reconciliation/) for the 2026-06-08 audit that established this).

## Feature map

| Capability | What it does | Where |
|---|---|---|
| Agentic loop | `AgentRunner` runs the provider → tool-dispatch loop to a max-iteration ceiling, auditing every step | [explanation/architecture.md](explanation/architecture.md) |
| Providers | OpenAI, Anthropic, Mock behind one `LlmProvider` interface (no Google in v1) | [reference/services.md](reference/services.md) |
| Structured output | `responseSchema` → validated `structuredResult` | [explanation/architecture.md](explanation/architecture.md) |
| Reasoning effort | OpenAI `reasoning_effort`; Anthropic thinking-budget (no-tools agents in v1) | [reference/entities.md](reference/entities.md) |
| Multi-provider failover | priority-ordered `AiAgentModel` chain, sticky after the first success | [explanation/architecture.md](explanation/architecture.md) |
| Conversations | threaded continuity across runs | [reference/entities.md](reference/entities.md) |
| Context window | windowing + rolling compaction (`summarize`) + pinned facts + a `remember` tool | [explanation/architecture.md](explanation/architecture.md) |
| Cost | per-run `estimatedCost` priced off the *served* model; `get#AiSpend` aggregation | [reference/services.md](reference/services.md) |
| Human approval gate | mutating tools suspend the run → approve/reject → resume; fail-closed | [explanation/architecture.md](explanation/architecture.md) |
| Operational UI | the AI Ops console (Playground, Composer, Agents, Runs, Approvals, Cost, Conversations, Glossary) | [reference/screens.md](reference/screens.md) |
| Registry keystone | opaque `agentId`/`toolId`, DB-backed catalog, `exposable` + denylist safety floor | [reference/entities.md](reference/entities.md) |
| Composer Assistant | describe → draft → preview-on-real-data → approve-to-activate | [product/composer-assistant-overview.md](product/composer-assistant-overview.md) |
| Builder Knowledgebase | domain glossary that grounds tool/agent naming | [explanation/architecture.md](explanation/architecture.md) |

## Quick start

1. **Provider keys.** Add your keys to the component's `dev.env` (git-ignored) as shell exports, e.g. `export ai_openai_key=…` (and/or `ai_anthropic_key=…`).
2. **Source + run** from your Moqui project root: `source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon run`. Keys resolve **system-property → environment-variable → MoquiConf default**; `--no-daemon` keeps a stale Gradle daemon from swallowing the env. See [reference/configuration.md](reference/configuration.md).
3. **Open the console:** `/apps/AiOps`.
4. **New agents** default to `openai` / `gpt-4o-mini` (override via `ai_default_provider` / `ai_default_model`).

## Documentation map

- **[capabilities.md](capabilities.md)** — **start here**: what moqui-ai can do and how to use it, for operators *and* developers — every capability with a concrete scenario, workflow, and "how it helps"
- **[reference/](reference/)** — as-built, code-derived: `entities.md`, `services.md`, `screens.md`, `configuration.md`
- **[explanation/](explanation/)** — the *why*: `architecture.md`, `security-model.md`, `decisions.md`
- **[specs/](specs/)** — design history, reconciled to point here: the framework design, registry keystone, Composer, Knowledgebase, the enterprise-decisions gap report, and the closed Responses-API audit
- **[decisions/](decisions/)** — ADRs (`0001-context-window-management.md`)
- **[product/](product/)** — `composer-assistant-overview.md` (product narrative)
- **[archive/plans/](archive/plans/)** — the 18 historical build plans (provenance; they embed pre-refactor code)
- **[reconciliation/](reconciliation/)** — the 2026-06-08 documentation audit + this reconciliation's plan

## Status

Shipped v1 (test suite green). **Deferred:** `maxCost` enforcement, tool-result clearing (ADR layer 4), retrieval/RAG (ADR Phase 6), Google/Gemini provider, scoped `AI_OPERATOR` authz, streaming. **Declined:** Responses-API migration, `AiAgentKnowledge`.
