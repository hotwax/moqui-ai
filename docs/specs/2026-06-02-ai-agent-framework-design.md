# Moqui-Native AI Agent Framework — Design Spec

- **Date:** 2026-06-02
- **Status:** Approved design — ready for implementation planning
- **Component:** `moqui-ai` (https://github.com/hotwax/moqui-ai, branch `main`)
- **Platform:** HotWax fork of Moqui (`hotwax/moqui-framework` + `hotwax/moqui-runtime`, branch `main`), **JDK 11**

---

## 1. Purpose

Give Moqui developers a first-class way to build agentic AI applications using nothing but
Moqui's own idioms — XML definitions, Groovy, the entity engine, and ordinary service calls.
A developer who already knows Moqui should add AI agent capability by writing the same kinds
of files they write every day. No Spring, no annotations, no foreign SDK imports in business
logic.

The entire framework ships as the **`moqui-ai` component**. If it is not deployed, deployments
are completely unaffected.

## 2. Guiding principles

1. **Zero framework intrusion** — pure component; deployments without it are unaffected.
2. **Provider independence** — OpenAI and Anthropic today; Google/Gemini, Ollama, or any
   OpenAI-compatible endpoint later. Configuration drives the choice, never code.
3. **Moqui-native invocation** — callable as a standard service from Groovy, ECA, or screen
   action. No AI SDK imports in business logic.
4. **Declarative tool use** — the AI calls Moqui services as tools, declared in XML. Adding a
   tool requires no change to agent infrastructure.
5. **Full observability** — every run, tool call, and token persisted to the database.
6. **Permission enforcement** — tool execution respects Moqui's permission/auth model; the AI
   gets no elevated access.
7. **Cost awareness** — token usage trackable per agent, per user, per time window; estimated
   spend queryable as a service.
8. **Conversation continuity** — named conversations; the caller passes a conversation ID, not history.
9. **Human approval gate** — irreversible tools require human approval; the agent suspends,
   persists state, and resumes after a decision.
10. **Operational UI** — monitoring, approvals, cost review, and conversation history without a
    database client.

### Engineering constraints

- **Fail loudly at startup.** Bad XML, unknown service references, or missing provider config →
  exception at boot. Never fail silently at runtime.
- **Persistence failures never abort runs.** Entity write errors are logged as warnings; the
  agent continues.
- **Configuration only to add providers.** Provider config lives in `MoquiConf.xml`.
- **JDK 11 compatible.** Use Moqui's built-in `RestClient` for transport — no new HTTP
  dependency, no provider SDKs.
- **Follow existing Moqui/HotWax conventions — introduce no new code patterns.** The binding
  reference is the *UDM Domain Object Practices Guide*
  (`/Users/anilpatel/maarg-sd/docs/udm-domain-object-practices.md`): entity archetypes/PK shape,
  type-vs-enumeration, status via `StatusItem`/`StatusFlow`/`StatusFlowTransition`, service verb
  naming (`create#`/`update#`/`store#`/domain verbs), field types, and the test convention
  (`*Tests.groovy` + `MoquiSuite`). Entities use `package="moqui.ai"` (framework-component
  style, matching `moqui.basic`/`moqui.security` and the `org.moqui.ai` Groovy package).

## 3. Architecture overview

`moqui-ai` registers a singleton via Moqui's **`ToolFactory` SPI**, mirroring `moqui-kie`:

```
MoquiConf.xml:  <tools><tool-factory class="org.moqui.ai.AiToolFactory" init-priority="30"/></tools>

AiToolFactory implements ToolFactory<AiToolFactory>
    getName()      → "AI"
    init(ecf)      → register provider clients (Mock always; Anthropic/OpenAI when a key is set).
                     No ai/ file scan and no DB read — there is no ExecutionContext at init.
                     The tool catalog is lazy-loaded from AiTool rows on first access.
    getInstance()  → returns the singleton (this)
    refreshCatalog() → (re)build the in-memory catalog from AiTool rows (atomic swap);
                     called on first access and after every store#AiTool
    destroy()      → close provider clients / pools

Reachable as:  ec.factory.getTool("AI", AiToolFactory.class)
Primary entry: service  ai.AgentServices.run#Agent  (delegates to the singleton)
```

The service `run#Agent` is the public, Moqui-native entry point; the ToolFactory is the runtime
that backs it. **There is no boot-time `ai/` file scan.** The tool catalog is a DB-backed
registry built lazily from `AiTool` rows (see §4 and the registry keystone,
`docs/specs/2026-06-05-agent-tool-registry-design.md`); fail-loud validation moved from boot to
authoring time (`store#AiTool`).

## 4. Authoring & storage model

> **Superseded for tools/agents** by the registry keystone
> (`docs/specs/2026-06-05-agent-tool-registry-design.md`): tools and agents are now **mutable
> DB records with stable opaque ids**, authored entirely at runtime through a single service
> gate each. The original "tools are file-defined XML" door was dropped — the `ai/` directory
> ships empty. This section is rewritten to the shipped model.

The runtime source of truth is the **entity layer**. Tools, agents, and grants are all DB
records; there are no `ai/*.xml` definition files. Each kind has exactly one authoring service
that acts as its gate.

| Concern | Source of truth | Authoring gate | Rationale |
|---|---|---|---|
| **Tools** (`AiTool`) | **DB records** | `ai.ToolServices.store#AiTool` (Curator-gated) | Exposing a Moqui service to the LLM is a security + system-load decision. The single gate service-validates the backing service, derives effect, applies the non-overridable denylist floor, defaults exposability, and derives a unique `toolName`. |
| **Agents** (`AiAgent`) | **DB records** | `ai.AgentServices.store#AiAgent` | Composition (prompt, model, tool selection) is flexible; authored at runtime. |
| **Agent→tool grants** (`AiAgentTool`) | **DB records** | `ai.AgentServices.store#AiAgentTool` | Each grant must reference a tool that is `exposable=Y` and `AI_TOOL_ACTIVE`. Granting a non-exposable, inactive, or unknown tool is rejected. |

### One door per kind (no file scan)

There is **no `ai/` file door** and **no boot-time scan**. Everything is created or edited as
database records at runtime through the gate services above — instantly live, no restart, no
filesystem access. Seed/demo rows are loaded as ordinary Moqui entity data (`data/*.xml`), not
as a bespoke definition format.

The `AiTool` catalog the runner consults is an **in-memory map built from `AiTool` rows**,
lazy-loaded on first access and rebuilt (atomic swap) by `AiToolFactory.refreshCatalog()` — which
`store#AiTool` calls after every write, so a new/edited tool is immediately grant-eligible.
Only `exposable=Y` + `AI_TOOL_ACTIVE` rows are loaded. Power users compose agents from this
approved catalog; they cannot invent new callable services. The Curator gates what the AI can
ever touch (via `store#AiTool` + the denylist); agent authors gate which approved tools a given
agent uses.

### Validation semantics (fail-loud at authoring time, defensive at read time)

| When | On bad reference |
|---|---|
| **Tool authoring** (`store#AiTool`) | Resolve the backing service (`ToolSchemaBuilder.build`); an unknown/invalid service → reject with a validation error. The denylist floor and `toolName` uniqueness are enforced here too. This is the fail-loud point. |
| **Catalog read** (`refreshCatalog` / `DefinitionLoader.loadCatalog`) | A seeded tool whose service was later removed is **logged and skipped**, not fatal — a defensive read, since the authoring gate already validated at write time. |
| **Agent / grant save** (`store#AiAgent` / `store#AiAgentTool`) | Reject the save; return validation errors; running agents unaffected. |

The runner reads agents/tools/grants from the entities (the tool catalog via the lazy in-memory
map, agents/grants via the entity engine) so authoring edits are live with no restart.

## 5. The agentic loop & provider abstraction

The loop is **provider-agnostic**. `AgentRunner` works only in a normalized internal message
shape; each provider adapter translates to/from its wire format.

```
run#Agent(agentName, userMessage)
   │
   ▼ load AiAgent + granted AiTools (catalog cache)
   ▼ build tool JSON-schemas from each tool's service in-parameters
   ▼ assemble system context = systemPrompt + pinned conversation facts (§8)
   │
   ┌──────────────── loop (up to max-iterations) ────────────────┐
   │ 1. provider.chat(systemContext, messages, toolSchemas)      │
   │ 2. normalized response →                                     │
   │      • text only      → DONE; return assistantMessage        │
   │      • tool call(s)   → for each call:                       │
   │            ec.service.sync().name(serviceName)               │
   │              .parameters(args).call()  ← Moqui authz applies │
   │            append tool result to messages                    │
   │      • (continue loop)                                       │
   └──────────────────────────────────────────────────────────────┘
   │ (max-iterations reached → return truncated=true)
   ▼ persist run + steps + tool calls (observability)
   ▼ return result
```

### `LlmProvider` interface — the only thing a new provider implements

All data is **Map-based** (Moqui idiom, like `ElasticFacade`'s `Map`/`List<Map>`) — no
data-holder classes. The interface is the only type; requests/responses/messages/tool-calls
are Groovy map literals.

```groovy
interface LlmProvider {
    String getName()        // "openai" | "anthropic" | "google" | "mock"
    Map chat(Map request)   // request Map in, response Map out
}
// request  Map: [model, systemContext, messages: List<Map>, tools: List<Map>]
// response Map: [assistantText (null if only tool calls), toolCalls: List<Map>,
//                tokensIn, tokensOut, finishReason]
// message  Map: [role, content, toolCalls: List<Map>, toolCallId]
// toolCall Map: [id, name, arguments: Map]
```

Adapters shipped in Phase 1: `AnthropicProvider`, `OpenAiProvider`, `MockProvider`. Each maps
the normalized request to its API and the response back:

- Anthropic — `tool_use` / `tool_result` content blocks.
- OpenAI — `tool_calls` on assistant messages, `tool` role results.

A Google/Gemini adapter (`functionCall` / `functionResponse` parts) is **deferred — not yet
implemented**; the `"google"` registry key is reserved in the interface comment above for when
it lands. Adding it later = a thin adapter + a config key, with no loop change.

The loop never knows which provider it is talking to.

## 6. Tool schema generation

For each `AiTool`, the framework resolves the referenced Moqui service and generates the JSON
schema the LLM needs from the service's **`in-parameters`** (name, type, required, description)
via `ToolSchemaBuilder`. The author registers a tool with one `store#AiTool` call (a backing
service + an LLM-facing description); no additional code. Schemas are **generated on demand from
the live service** when the catalog is (re)built — never stored — so a service signature change
is picked up automatically (registry keystone §schema-on-demand).

## 7. Structured output

When an agent declares an output schema, the runner requests a typed result on the final turn
(native JSON / structured mode where the provider supports it; a forced final "respond" tool
otherwise), validates it against the schema, and returns it as a Moqui field map. Downstream
services consume `result.output` as data, not prose. Invalid output → one automatic re-ask,
then a clean error.

## 8. Domain knowledge / context

> The `AiAgentKnowledge` entity and the `<knowledge>` XML block in the original plan were
> **never built**. Domain context is delivered by the mechanisms below instead.

1. **System prompt** — role + instructions (`AiAgent.systemPrompt`).
2. **Pinned conversation facts** (`AiConversationFact`) — durable, confirmed business values an
   agent records mid-conversation via the built-in **`remember` tool** (`ai.FactServices.remember#Fact`).
   Conversation-scoped, keyed, store-or-supersede; injected into every call's system context and
   never compressed or dropped (the fidelity guarantee in
   `docs/decisions/0001-context-window-management.md`). This is the runtime, per-conversation
   knowledge layer.
3. **Lexical domain Glossary** (`AiDomainTerm` / `AiTermSynonym`) — curated domain nouns and
   capability verbs plus their synonyms ("rma" → return), typed, provenanced, and status-gated.
   It is the soft-control source for naming during authoring and the inward-facing first use of
   the knowledge-retrieval direction (see the Builder Knowledgebase spec,
   `docs/specs/2026-06-05-builder-knowledgebase-design.md`).
4. **Context-on-demand via tools** — any Moqui service exposed as a tool can fetch live context
   (current company config, today's promotions, this customer's tier). Works for free with the
   tool mechanism.
5. **Retrieved knowledge (RAG)** — large corpus, chunked + embedded into `ec.elastic`'s
   vector index, semantically searched at runtime. **Deferred to its own later phase** (heavier:
   embeddings, chunking, vector index).

## 9. Observability entity model

Every run, step, and tool call is persisted so "what did the AI do and when" is a query.

| Entity | One row per | Key fields |
|---|---|---|
| `AiAgentRun` | agent invocation | `agentRunId` (PK), `agentName`, `userId`, `fromDate`, `thruDate`, `statusId` → `StatusItem` (`AI_RUN_*`), `userMessage`, `assistantMessage`, `provider`, `model`, `iterations`, `tokensIn`, `tokensOut`, `estimatedCost`, `errorText` |
| `AiAgentRunStep` | one loop iteration | `agentRunId` + `stepSeqId` (PK), `stepType` (llm_call / tool_call), `fromDate`, `thruDate`, `tokensIn`, `tokensOut`, `finishReason` |
| `AiToolCall` | one tool dispatch | `agentRunId` + `stepSeqId` + `toolCallId` (PK), `toolId`, `toolName`, `serviceName`, `arguments` (JSON), `result` (JSON), `success`, `errorText`, `durationMs` |

- Token + estimated-cost capture lives here in Phase 1 (raw data); the later cost phase
  aggregates over it.
- `arguments` / `result` stored as JSON text fields for full audit fidelity.
- **Persistence never aborts a run:** writes occur in a guarded block that logs a warning via
  `ec.logger` on failure and continues.
- **Status fields follow the framework convention** (per the UDM Domain Object Practices Guide
  §1.5, confirmed in `framework/entity/BasicEntities.xml`): `statusId type="id"` with a
  `<relationship to moqui.basic.StatusItem>`. Status values are seed `StatusItem` records, with
  `StatusFlow` + `StatusFlowTransition` defining legal transitions. `AiAgentRun.statusId` ∈
  {`AI_RUN_RUNNING`, `AI_RUN_COMPLETED`, `AI_RUN_FAILED`, `AI_RUN_TRUNCATED`, `AI_RUN_ABORTED`}
  (statusTypeId `AiAgentRunStatus`); `AiAgent.statusId` ∈ {`AI_AGENT_ACTIVE`, `AI_AGENT_DISABLED`}
  (statusTypeId `AiAgentStatus`). No freeform status strings.
- **The status sets above are the Phase-1 subset.** Two values were added by later phases and
  now ship in `data/AiStatusData.xml`: `AI_RUN_SUSPENDED` (the Phase-4 human-approval gate — the
  run suspends awaiting an approve/reject, with `RUNNING ↔ SUSPENDED` transitions) and
  `AI_AGENT_DRAFT` (the Composer's default state for a freshly authored agent, transitioning to
  `AI_AGENT_ACTIVE`).

## 10. Permission & security model

Because every tool runs through `ec.service.sync()`, the call executes as the invoking user and
Moqui's artifact authorization applies unchanged. The AI gets exactly the caller's rights — no
elevation, no separate permission layer to build.

The security boundary on *which services can ever become tools* is the **Curator-gated
`store#AiTool` authoring service plus the `AiToolDenylist` floor**, not code-review of XML files
(there are no tool files). `store#AiTool` validates the backing service, derives its effect
(`AI_TOOL_READ_ONLY` vs `AI_TOOL_MUTATING`), defaults mutating tools to non-exposable and
approval-required, and applies the denylist — a non-overridable set of service-name patterns
(deletes, framework-internal `org.moqui.impl.*`, password/credential, `UserAccount`,
`ArtifactAuthz`) that force `exposable=N` and refuse any override. Runtime flexibility is limited
to wiring already-exposable, active tools into agents via `store#AiAgentTool`. See the registry
keystone (`docs/specs/2026-06-05-agent-tool-registry-design.md`) for the full gate design.

## 11. Invocation contracts (Phase 1 services)

```
ai.AgentServices.run#Agent
  in:  agentId | agentName (one req), userMessage (req), conversationId?
  out: assistantMessage, structuredResult (map; when the agent has a responseSchema),
       agentRunId, conversationId, tokensIn, tokensOut, estimatedCost, iterations,
       truncated, statusId, providerName, servedByModelId, providerRunId

ai.ToolServices.store#AiTool          (the Curator authoring gate — register/edit a tool)
ai.AgentServices.store#AiAgent        (author/edit an agent; defaults AI_AGENT_DRAFT)
ai.AgentServices.store#AiAgentTool    (grant an exposable+active tool to an agent)
```

There is **no `reload#Definitions` service** — the catalog is rebuilt in-process by
`store#AiTool` via `refreshCatalog()`, and there are no `ai/` files to re-scan. Conversation,
cost, approval, composer, and glossary services arrive in their respective phases/specs.

## 12. Provider configuration & transport

`MoquiConf.xml`, secrets via `is-secret`, no code to switch:

```xml
<default-property name="ai_anthropic_key" value="" is-secret="true"/>
<default-property name="ai_openai_key"    value="" is-secret="true"/>
<!-- plus non-secret base-url / version / timeout defaults; see the component MoquiConf.xml -->
```

There is **no `ai_google_key`** — the Google/Gemini provider is deferred (§5). Providers are
configured by name; an agent's `providerName` / `modelName` selects one, and a provider is
registered at boot **only when its key is set** (Mock always; a missing key for an unused
provider never blocks startup). Adding Google/Gemini, Ollama, or any OpenAI-compatible endpoint
later = its config key + a thin adapter, no loop change. Transport uses Moqui's built-in
`RestClient` (JDK 11-safe, no new dependency).

## 13. Error & failure semantics

| Situation | Behavior |
|---|---|
| Unknown/invalid backing service at **tool authoring** (`store#AiTool`) | Reject the write with a validation error (fail-loud at authoring time, not boot) |
| Seeded tool whose service was later removed, seen at **catalog read** | Log a warning and skip that tool; the rest of the catalog loads |
| Bad agent / grant save at **runtime** | Reject; running agents unaffected; return errors |
| Provider HTTP error mid-run | Mark run `failed`, persist `errorText`, return clean error |
| Tool service throws | Capture in `AiToolCall`; feed the error back to the LLM as the tool result so the agent can recover |
| Max iterations reached | Return with `truncated=true` |
| Persistence write fails | Log warning via `ec.logger`, continue |

## 14. AI Ops console

> The original plan called this `AiAdmin` with four screens. As shipped it is **`AiOps`**,
> mounted at **`/apps/AiOps`** (a `subscreens-item` on `webroot/apps.xml` declared in the
> component's own `MoquiConf.xml`, so no webroot file is touched). It grew to **nine screens**
> as later phases landed (approvals, conversations, cost, composer, glossary).

A screen tree in `moqui-ai` (`screen/AiOps.xml` + `screen/AiOps/*.xml`) for developers and
operators — management and testing. v1 security is `require-authentication` only (the
`AI_OPERATOR` group + `ArtifactAuthz` gating is a deferred follow-up).

| Screen | Purpose | Built from |
|---|---|---|
| **Playground** *(default)* | Pick an agent, type a `userMessage`, **Run**, and see the `assistantMessage`, full step/tool-call trace, tokens, and cost | Form + transition calling `run#Agent`, rendering the run via the shared `AiRunTrace` include |
| **Composer** | Build a new agent by talking to the Composer Assistant — chat, live draft, sandbox preview | `ComposerServices` (see `docs/specs/2026-06-05-composer-assistant-moqui-design.md`) |
| **Agents** | List / author / edit agents; grant tools from the catalog | Forms over `AiAgent` + `AiAgentTool`; transitions call `store#AiAgent` / `store#AiAgentTool` |
| **Runs** | Browse run history; drill into steps and tool calls | List over `AiAgentRun`, linking to **RunDetail** |
| **RunDetail** | One run's steps + tool calls (args → results) | `AiAgentRun` → `AiAgentRunStep` → `AiToolCall`; excluded from the menu (`default-menu-include="false"`) |
| **Approvals** | Operator queue for Phase-4 approval-gated tool calls (suspended runs) | `ApprovalServices` over `AiToolApproval` / `AI_RUN_SUSPENDED` runs |
| **Cost** | Spend / token aggregation review | `CostServices` over the run token capture |
| **Conversations** | Browse named conversations and their replayed messages | `AiConversation` → `AiConversationMessage` |
| **Glossary** | Builder Knowledgebase curation: review/approve/reject suggested terms; list approved | `GlossaryServices` over `AiDomainTerm` / `AiTermSynonym` |

There is **no Tools screen** — tools are authored through the `store#AiTool` service gate (§4,
§10), not browsed/edited as a console list in this phase.

The **Playground** is the in-app test harness: author in **Agents** (or **Composer**), run in
**Playground**, inspect in **Runs / RunDetail** — no Groovy harness or command-line service
calls needed.

**Design-review decisions (2026-06-02, `/plan-design-review`, focused IA + states):**

- **Information architecture — minimal.** Standard Moqui subscreen menu (the original review
  scoped the then-four screens; the menu now spans the nine screens above); Moqui's default
  navigation; no custom landing screen. The soft suggestion was adopted: the run-trace view is
  a shared screen-include (`screen/includes/AiRunTrace.xml`) so Playground and RunDetail render
  it the same way.
- **Playground run model — synchronous.** The Playground calls `run#Agent` inline and the
  page blocks until it returns. Acceptable for a dev console running modest test agents.
  **Caveat:** long runs (high `max-iterations` + slow tools) can exceed proxy/HTTP timeouts;
  production or long-running invocations should call the `run#Agent` service directly (or a
  future async job + poll path), not the Playground.
- **Interaction states — implementer's call.** Not enumerated in this plan. Note the one
  footgun: render a `truncated` run (hit max-iterations) visually distinct from a clean
  `completed` run so a developer doesn't mistake a cut-off run for a finished one. The data
  already supports this (`run#Agent` returns `truncated`; `AiAgentRun.statusId` carries
  `truncated` / `failed`).

## 15. Testing strategy

A built-in **`MockProvider`** (the third adapter, alongside Anthropic and OpenAI) returns scripted responses, including scripted
tool-call requests. Spock / integration tests drive the full loop, tool dispatch, structured
output, and observability **deterministically — no API keys, no cost**. Real-provider smoke
tests are opt-in when keys are present.

## 16. Component layout

```
runtime/component/moqui-ai/
├── component.xml
├── MoquiConf.xml                        ← registers AiToolFactory + mounts /apps/AiOps
├── ai/                                  ← EMPTY (no file-defined definitions; DB is the source of truth)
├── src/main/groovy/org/moqui/ai/
│   ├── AiToolFactory.groovy             ← ToolFactory<AiToolFactory>, name "AI", singleton + lazy catalog
│   ├── LlmProvider.groovy               ← provider interface (Map chat(Map))
│   ├── ToolSchemaBuilder.groovy         ← service in-params → JSON schema Map (on demand)
│   ├── AgentRunner.groovy               ← the provider-agnostic agentic loop (Map-based)
│   ├── ContextAssembler.groovy          ← assembles system context (prompt + pinned facts)
│   ├── CostCalc.groovy                  ← token → estimated-cost helper
│   ├── DefinitionLoader.groovy          ← build catalog Map from AiTool rows (no file scan)
│   └── provider/
│       ├── AbstractLlmProvider.groovy   ← RestClient transport base
│       ├── AnthropicProvider.groovy
│       ├── OpenAiProvider.groovy
│       └── MockProvider.groovy          ← (no GoogleProvider — Gemini deferred, §5)
│   (no data-holder classes — agents/tools/messages/results are Maps + entities)
│   (no schema/ dir — tools are not file-defined, so there is no tool/agent XSD)
├── service/ai/AgentServices.xml         ← run#Agent, store#AiAgent, store#AiAgentTool, create#Conversation
├── service/ai/ToolServices.xml          ← store#AiTool (the Curator authoring gate)
├── service/ai/{Approval,Composer,Cost,Fact,Glossary}Services.xml  ← later-phase services
├── entity/AiEntities.xml                ← AiAgent, AiTool, AiAgentTool, AiAgentModel,
│                                          AiAgentRun, AiAgentRunStep, AiToolCall   (no AiAgentKnowledge)
├── entity/Ai{Tool,Conversation,Approval,Composer,Glossary,Price}Entities.xml      ← registry/phase entities
├── screen/AiOps.xml + screen/AiOps/*.xml  ← Playground, Composer, Agents, Runs, RunDetail,
│                                            Approvals, Cost, Conversations, Glossary (9 screens)
├── screen/includes/AiRunTrace.xml       ← shared run-trace include (Playground + RunDetail)
└── data/                                ← seed enums/status types + denylist + demo rows
```

## 17. Phase roadmap

| # | Phase | Delivers |
|---|---|---|
| **1** | **Core agent runtime + dev console** | Everything in this spec: ToolFactory registration, DB-backed tool/agent authoring (`store#AiTool` / `store#AiAgent`), on-demand tool schema gen, provider-agnostic loop with Anthropic + OpenAI + Mock (Google/Gemini deferred, §5), structured output, observability, permission via `ec.service.sync()`, and the AI Ops console. *(Domain context ships as pinned facts + the glossary, not the dropped `AiAgentKnowledge`; see §8.)* |
| **2** | **Conversation continuity** | Named conversations; caller passes `conversationId`; history persisted and replayed. |
| **3** | **Cost awareness** | Spend aggregation + queryable estimate service (per agent / user / time window) over Phase 1's token capture. |
| **4** | **Human approval gate** | `requiresApproval` tools; the agent suspends, persists state, and resumes after an approve/reject service call. |
| **5** | **Operational UI** | Operator-facing screens: monitoring dashboards, approvals queue, cost review, conversation history; role-secured. |
| **6** | **Knowledge retrieval (RAG)** | Chunk + embed knowledge into `ec.elastic` vector index; auto-retrieve top-k per turn or a `search#Knowledge` tool. |

Phase 1 is the foundational slice; phases 2–6 are additive and never change the loop.

## 18. Non-goals (Phase 1)

- No conversations / multi-turn replay (Phase 2).
- No cost aggregation/query service — only raw token/cost capture (Phase 3).
- No human-approval suspend/resume in Phase 1 (delivered in Phase 4 via `AiToolApproval` +
  `AI_RUN_SUSPENDED` + `AiAgentRun.pendingState`, not the originally-planned `approvalStatus` field).
- No operator UI — developer console only (Phase 5).
- No RAG / embeddings / vector search (Phase 6).
- No framework-core changes of any kind.

## 19. Engineering-review decisions (2026-06-02, `/plan-eng-review`)

These amend the sections above where noted.

- **Provider sequencing (amends §17 Phase 1).** Build the `LlmProvider` abstraction +
  `MockProvider` + **Anthropic** first; prove the full loop, tool-calling, structured
  output, and observability end-to-end; then add OpenAI + Google as fast-follows *within*
  Phase 1. Avoids committing the normalized request/response shape to three adapters before
  one has exercised it.
- **Definition storage (resolves §3 ↔ §4 contradiction).** The file-defined **tool catalog**
  lives in an immutable in-memory map, rebuilt on reload. **Agents and knowledge** are read
  from their entities via Moqui's entity cache per run (cleared on write), so runtime edits
  are live without restart. §3's "all definitions in immutable in-memory maps" is superseded
  for agents/knowledge.
- **Transaction model (amends §5, §13).** `run#Agent` holds **no enclosing transaction**.
  LLM HTTP calls run outside any tx; each tool call runs in its own transaction (Moqui
  service default); each observability write uses its own short require-new tx. No pooled DB
  connection is ever held across an LLM HTTP call.
- **Per-run safety ceiling (amends §5, §13).** Beyond `max-iterations`, the loop enforces
  hard per-run limits: max total tokens, max estimated cost, and max tool-calls-per-turn
  (config defaults + per-agent override). Breaching any → stop, mark the run `aborted`,
  persist what happened.
- **Provider scaffolding (amends §16).** An `AbstractLlmProvider` base owns transport
  (`RestClient`), auth headers, HTTP-error mapping, token extraction, and timeout/retry;
  concrete adapters implement only wire-format encode/decode. `MockProvider` overrides
  transport only.

## 20. Test coverage requirement (Phase 1)

Every code path below ships with tests; the **edge** rows are mandatory because they prove the
stated principles. `MockProvider` drives the loop deterministically (no keys, no cost).

- **Loader (unit):** valid `ai/*.xml` → entities upserted; unknown service ref at **boot** →
  throws (fail-loud); bad ref at **reload** → last-good kept, errors returned *(edge)*; schema
  gen from service `in-parameters` (types/required).
- **Loop (Mock):** text-only → `assistantMessage`; tool call → `ec.service.sync` → result fed
  back; multi-iteration until done; max-iterations → `truncated=true` *(edge)*; per-run ceiling
  → `aborted` *(edge)*; tool service throws → error fed back to LLM *(edge)*; provider HTTP
  error → run `failed`, `errorText` persisted *(edge)*.
- **Structured output (Mock):** valid → output map; invalid → one re-ask → clean error *(edge)*.
- **Providers:** `MockProvider` (drives the above); Anthropic encode/decode normalization
  (stubbed HTTP); OpenAI + Google when added; **opt-in smoke** against live Anthropic when a
  key is present.
- **Observability:** run/step/tool-call persisted with tokens; persistence write failure →
  warning, run continues *(edge)*.
- **Permission (E2E):** restricted user → tool dispatch blocked by `ec.service` authorization.
- **Authoring:** `store#AiTool` rejects an unknown/denylisted service; `store#AiAgent` enforces a
  unique name and `store#AiAgentTool` rejects a non-exposable grant; `refreshCatalog()` reflects
  authoring changes (there is no `reload#Definitions`).

## 21. Deferred / TODO (from eng review)

- **Observability redaction + retention** — tool `arguments`/`result` store full JSON in
  Phase 1 (internal dev tool). Before any production/operator use (≤ Phase 5): add
  sensitive-field redaction and a retention/purge policy.
- **Provider-init failure isolation** — fail-loud at boot only for providers an agent actually
  references; a missing key for an unused provider should not block startup.

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 1 | clean | 5 issues resolved, 0 critical gaps |
| Design Review | `/plan-design-review` | UI/UX gaps (focused: IA + states) | 1 | clean | score: 4/10 → 8/10, 3 decisions |

**Eng review** (5 issues, all resolved → §19): provider sequencing (Anthropic + Mock first);
definition-storage split (catalog in-memory, agents/knowledge via entity cache); no-enclosing-
transaction loop model; per-run cost/token/tool-call ceiling; shared `AbstractLlmProvider`
base. Test-coverage requirement added (§20). 2 TODOs deferred (§21: observability
redaction/retention; provider-init failure isolation). Scope reduced: none — sequencing only.

**Design review** (3 decisions → §14): minimal Moqui-subscreen IA; synchronous Playground run
model (timeout caveat); interaction states left to implementer (truncated-vs-completed footgun
flagged). ~90% backend; visual-mockup/aesthetic passes skipped as N/A to framework-rendered
screens.

- **UNRESOLVED:** 0
- **VERDICT:** ENG + DESIGN CLEARED — architecture and dev-console decisions captured; spec
  ready for implementation planning (`writing-plans`).
