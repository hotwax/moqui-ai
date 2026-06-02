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
2. **Provider independence** — OpenAI, Anthropic, Google today; Ollama / any OpenAI-compatible
   endpoint later. Configuration drives the choice, never code.
3. **Moqui-native invocation** — callable as a standard service from Groovy, ECA, or screen
   action. No AI SDK imports in business logic.
4. **Declarative tool use** — the AI calls Moqui services as tools, declared in XML. Adding a
   tool requires no change to agent infrastructure.
5. **Full observability** — every run, tool call, and token persisted to the database.
6. **Permission enforcement** — tool execution respects Moqui's permission/auth model; the AI
   gets no elevated access.
7. **Cost awareness** — token usage trackable per agent, per user, per time window; estimated
   spend queryable as a service.
8. **Conversation continuity** — named threads; the caller passes a thread ID, not history.
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
    init(ecf)      → scan ai/ dirs, seed entities, validate (fail-loud), build provider clients
    getInstance()  → returns the singleton (this)
    destroy()      → close provider clients / pools

Reachable as:  ec.factory.getTool("AI", AiToolFactory.class)
Primary entry: service  ai.AgentServices.run#Agent  (delegates to the singleton)
```

The service `run#Agent` is the public, Moqui-native entry point; the ToolFactory is the runtime
that backs it.

## 4. Authoring & storage model

The runtime source of truth is the **entity layer**. Definitions reach the entities by two doors.

| Concern | Source of truth | Who defines it | Rationale |
|---|---|---|---|
| **Tools** (`AiTool`) | **XML files only** (`ai/*.tools.xml`) | Developers, in code review | Exposing a Moqui service to the LLM is a security + system-load decision. The universe of callable tools is fixed, reviewed, and auditable. |
| **Agents** (`AiAgent`) | **DB records** (seeded from `ai/*.agent.xml`) | Power users at runtime, or seeded from files | Composition (prompt, model, tool selection, knowledge) is flexible. |
| **Agent→tool grants** (`AiAgentTool`) | **DB records** | Power users | Each grant must reference a tool that exists in the file-defined catalog. Granting an unknown tool is rejected. |
| **Attached knowledge** (`AiAgentKnowledge`) | **DB records** (file-seedable) | Power users | Domain context injected into the system prompt; editable on the fly. |

### Two doors into the entities

1. **XML files** in any component's `ai/` directory. On boot (and on reload), the files are
   parsed, XSD-validated, and **upserted** into the entities (idempotent `store`). This is the
   version-controlled baseline a component ships with itself.
2. **UI / services** — `create#Agent`, `update#Agent`, etc. create or edit agents and their
   tool grants / knowledge as database records at runtime. Instantly live, no restart, no
   filesystem access.

The `AiTool` catalog is refreshed **only** from files. Power users compose agents from the
approved catalog; they cannot invent new callable services. Developers gate what the AI can ever
touch; power users gate which approved tools a given agent uses.

### Validation semantics (fail-loud at boot, last-good at runtime)

| When | On bad reference |
|---|---|
| **Boot** (file scan) | Throw — Moqui will not start with broken AI config. |
| **Runtime** (`reload#Definitions`) | Reject the reload; keep the last-good definitions live; return errors to the caller. |
| **Runtime** (agent save via service/UI) | Reject the save; return validation errors; running agents unaffected. |

The runner reads agents/tools/knowledge from the entities through Moqui's **entity cache**
(cleared on write), so both doors produce live agents with no restart.

## 5. The agentic loop & provider abstraction

The loop is **provider-agnostic**. `AgentRunner` works only in a normalized internal message
shape; each provider adapter translates to/from its wire format.

```
run#Agent(agentName, userMessage)
   │
   ▼ load AiAgent + granted AiTools + AiAgentKnowledge (cached)
   ▼ build tool JSON-schemas from each tool's service in-parameters
   ▼ assemble system context = systemPrompt + attached knowledge (in order)
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

```groovy
interface LlmProvider {
    String getName()                       // "openai" | "anthropic" | "google" | "mock"
    LlmResponse chat(LlmRequest request)   // normalized in, normalized out
}
// LlmRequest:  systemContext, List<Message>, List<ToolSchema>, model,
//              structuredOutputSchema? (nullable)
// LlmResponse: assistantText? (nullable), List<ToolCall>, tokensIn, tokensOut, finishReason
```

Adapters in Phase 1: `OpenAiProvider`, `AnthropicProvider`, `GoogleProvider`, `MockProvider`.
Each maps the normalized request to its API and the response back:

- Anthropic — `tool_use` / `tool_result` content blocks.
- OpenAI — `tool_calls` on assistant messages, `tool` role results.
- Google (Gemini) — `functionCall` / `functionResponse` parts.

The loop never knows which provider it is talking to.

## 6. Tool schema generation

For each `AiTool`, the framework resolves the referenced Moqui service and generates the JSON
schema the LLM needs from the service's **`in-parameters`** (name, type, required, description).
The developer adds a tool with one line of XML and an LLM-facing description; no additional
code. Schemas are cached and rebuilt on reload.

## 7. Structured output

When an agent declares an output schema, the runner requests a typed result on the final turn
(native JSON / structured mode where the provider supports it; a forced final "respond" tool
otherwise), validates it against the schema, and returns it as a Moqui field map. Downstream
services consume `result.output` as data, not prose. Invalid output → one automatic re-ask,
then a clean error.

## 8. Domain knowledge / context

Four layers, three of which exist in Phase 1:

1. **System prompt** — role + instructions (`AiAgent.systemPrompt`).
2. **Attached knowledge** (`AiAgentKnowledge`) — company facts, policies, glossary, brand voice.
   DB-backed (power-user editable), file-seedable via a `<knowledge>` block in the agent XML.
   The runner concatenates the agent's active knowledge entries into the system context, in
   sequence order, on every call. (Phase 1.)
3. **Context-on-demand via tools** — any Moqui service exposed as a tool can fetch live context
   (current company config, today's promotions, this customer's tier). Works for free with the
   tool mechanism. (Phase 1.)
4. **Retrieved knowledge (RAG)** — large corpus, chunked + embedded into `ec.elastic`'s
   vector index, semantically searched at runtime (auto-retrieve top-k or a `search#Knowledge`
   tool). **Deferred to its own later phase** (heavier: embeddings, chunking, vector index).

## 9. Observability entity model

Every run, step, and tool call is persisted so "what did the AI do and when" is a query.

| Entity | One row per | Key fields |
|---|---|---|
| `AiAgentRun` | agent invocation | `agentRunId` (PK), `agentName`, `userId`, `fromDate`, `thruDate`, `statusId` → `StatusItem` (`AI_RUN_*`), `userMessage`, `assistantMessage`, `provider`, `model`, `iterations`, `tokensIn`, `tokensOut`, `estimatedCost`, `errorText` |
| `AiAgentRunStep` | one loop iteration | `agentRunId` + `stepSeqId` (PK), `stepType` (llm_call / tool_call), `fromDate`, `thruDate`, `tokensIn`, `tokensOut`, `finishReason` |
| `AiToolCall` | one tool dispatch | `agentRunId` + `stepSeqId` + `toolCallId` (PK), `toolName`, `serviceName`, `arguments` (JSON), `result` (JSON), `success`, `errorText`, `durationMs`, `approvalStatus` (reserved for the human-approval phase) |

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

## 10. Permission & security model

Because every tool runs through `ec.service.sync()`, the call executes as the invoking user and
Moqui's artifact authorization applies unchanged. The AI gets exactly the caller's rights — no
elevation, no separate permission layer to build. The **set of callable tools is file-defined
and code-reviewed**; runtime flexibility is limited to wiring approved tools into agents.

## 11. Invocation contracts (Phase 1 services)

```
ai.AgentServices.run#Agent
  in:  agentName (req), userMessage (req), configOverride?, structured?
  out: assistantMessage, output (map; when structured), agentRunId,
       tokensIn, tokensOut, estimatedCost, iterations, truncated

ai.AgentServices.create#Agent / update#Agent   (agent + tool grants + knowledge CRUD)
ai.AgentServices.reload#Definitions            (re-scan ai/ dirs; validate; atomic swap)
```

Thread, cost, and approval services arrive in their respective phases.

## 12. Provider configuration & transport

`MoquiConf.xml`, secrets via `is-secret`, no code to switch:

```xml
<default-property name="ai_anthropic_key" value="" is-secret="true"/>
<default-property name="ai_openai_key"    value="" is-secret="true"/>
<default-property name="ai_google_key"    value="" is-secret="true"/>
```

Providers are configured by name; an agent's `provider` / `model` selects one. Adding
Ollama / any OpenAI-compatible endpoint later = config + a thin adapter, no loop change.
Transport uses Moqui's built-in `RestClient` (JDK 11-safe, no new dependency).

## 13. Error & failure semantics

| Situation | Behavior |
|---|---|
| Bad XML / unknown tool service at **boot** | Throw — Moqui will not start |
| Bad agent edit / reload at **runtime** | Reject; keep last-good live; return errors |
| Provider HTTP error mid-run | Mark run `failed`, persist `errorText`, return clean error |
| Tool service throws | Capture in `AiToolCall`; feed the error back to the LLM as the tool result so the agent can recover |
| Max iterations reached | Return with `truncated=true` |
| Persistence write fails | Log warning via `ec.logger`, continue |

## 14. Developer console (Phase 1)

A plain screen tree in `moqui-ai` (`screen/AiAdmin.*`) for developers — management and testing:

| Screen | Purpose | Built from |
|---|---|---|
| **Agents** | List / create / edit agents; grant tools from the catalog; edit attached knowledge | Auto-form over `AiAgent` + `AiAgentTool` + `AiAgentKnowledge`; transitions call `create#Agent` / `update#Agent` |
| **Tools** | Read-only catalog of file-defined tools, each with its generated JSON schema and `requiresApproval` flag | List over `AiTool` (read-only) |
| **Playground** | Pick an agent, type a `userMessage`, **Run**, and see the `assistantMessage`, full step/tool-call trace (args → results), tokens, and cost | Form + transition calling `run#Agent`, rendering the run |
| **Runs** | Browse run history; drill into steps and tool calls | List over `AiAgentRun` → `AiAgentRunStep` → `AiToolCall` |

The **Playground** is the in-app test harness: edit in **Agents**, run in **Playground**,
inspect in **Runs** — no Groovy harness or command-line service calls needed.

**Design-review decisions (2026-06-02, `/plan-design-review`, focused IA + states):**

- **Information architecture — minimal.** Standard Moqui subscreen menu over the four
  screens; Moqui's default navigation; no custom landing screen. (Soft suggestion, not
  required: make the run-trace view a shared screen-include so Playground and Runs render it
  the same way — near-free in Moqui and avoids building it twice.)
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

A built-in **`MockProvider`** (4th adapter) returns scripted responses, including scripted
tool-call requests. Spock / integration tests drive the full loop, tool dispatch, structured
output, and observability **deterministically — no API keys, no cost**. Real-provider smoke
tests are opt-in when keys are present.

## 16. Component layout

```
runtime/component/moqui-ai/
├── component.xml
├── MoquiConf.xml                        ← registers AiToolFactory
├── src/main/groovy/org/moqui/ai/
│   ├── AiToolFactory.groovy             ← ToolFactory<AiToolFactory>, name "AI", singleton
│   ├── AgentDefinition.groovy           ← parsed agent (immutable runtime view)
│   ├── ToolDefinition.groovy            ← parsed tool + generated JSON schema
│   ├── AgentRunner.groovy               ← the provider-agnostic agentic loop
│   ├── DefinitionLoader.groovy          ← scan ai/ dirs, validate, upsert entities
│   └── provider/
│       ├── LlmProvider.groovy           ← interface
│       ├── OpenAiProvider.groovy
│       ├── AnthropicProvider.groovy
│       ├── GoogleProvider.groovy
│       └── MockProvider.groovy
├── schema/                              ← XSD for ai/*.tools.xml and ai/*.agent.xml
├── service/ai/AgentServices.xml         ← run#Agent, create#Agent, update#Agent, reload#Definitions
├── entity/AiEntities.xml                ← AiTool, AiAgent, AiAgentTool, AiAgentKnowledge,
│                                          AiAgentRun, AiAgentRunStep, AiToolCall
├── screen/AiAdmin.xml (+ subscreens)    ← Agents, Tools, Playground, Runs
└── data/                                ← seed enums/status types
```

## 17. Phase roadmap

| # | Phase | Delivers |
|---|---|---|
| **1** | **Core agent runtime + dev console** | Everything in this spec: ToolFactory registration, file/DB authoring model, tool schema gen, provider-agnostic loop with OpenAI/Anthropic/Google + Mock, structured output, attached knowledge, observability, permission via `ec.service.sync()`, and the developer console (Agents, Tools, Playground, Runs). |
| **2** | **Conversation continuity** | Named threads; caller passes `threadId`; history persisted and replayed. |
| **3** | **Cost awareness** | Spend aggregation + queryable estimate service (per agent / user / time window) over Phase 1's token capture. |
| **4** | **Human approval gate** | `requiresApproval` tools; the agent suspends, persists state, and resumes after an approve/reject service call. |
| **5** | **Operational UI** | Operator-facing screens: monitoring dashboards, approvals queue, cost review, conversation history; role-secured. |
| **6** | **Knowledge retrieval (RAG)** | Chunk + embed knowledge into `ec.elastic` vector index; auto-retrieve top-k per turn or a `search#Knowledge` tool. |

Phase 1 is the foundational slice; phases 2–6 are additive and never change the loop.

## 18. Non-goals (Phase 1)

- No conversation threads / multi-turn replay (Phase 2).
- No cost aggregation/query service — only raw token/cost capture (Phase 3).
- No human-approval suspend/resume — `approvalStatus` field reserved only (Phase 4).
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
- **Admin:** `create#Agent`/`update#Agent` rejects an unknown tool grant; `reload#Definitions`
  atomic swap.

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
