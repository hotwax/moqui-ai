# moqui-ai — Service Catalog (Reference)

> **Canonical as-built reference.** Derived directly from the service definitions under
> `service/ai/*.xml` (plus the test fixtures in `service/moqui/ai/test/TestServices.xml`).
> The **code is the source of truth** — every service name, parameter, auth flag, transaction
> setting, and behavior below was read off the actual `<service>` element. Where the older
> specs/plans disagreed, the code wins (see [Notes where code differs from the plan/drift report](#notes-where-code-differs-from-the-planndrift-report)).

This is a Moqui-native AI agent framework. Agents and tools are declared as data and invoked
as ordinary Moqui services (`ec.service.sync()`). There is no core facade; the heavy lifting
lives in `org.moqui.ai.*` Groovy classes (notably `AgentRunner`, `AiToolFactory`,
`ToolSchemaBuilder`), which the services below orchestrate.

## Conventions

- **Service name format** in this doc: `package.File.verb#noun`. The framework wire form is
  `verb#noun`; the package path matches the file (e.g. `ai.AgentServices`, `ai.GlossaryServices`).
- **Auth column** reflects the `authenticate` attribute on the `<service>`. All public-facing
  services are `authenticate="true"` except `ai.GlossaryServices.capture#NamingSignal`
  (`authenticate="false"` — it is an internal hook invoked from EECAs/in-service capture).
- **Transaction column** reflects the `transaction` attribute. Most services use the framework
  default (a service-managed transaction). Three families set `transaction="ignore"` — those
  run an LLM loop that must hold **no enclosing transaction** (`run#Agent`, `preview#Agent`, and
  the three approval services). This matches the confirmed Moqui pattern: `transaction` belongs
  on the `<service>` element, not on a `<service-call>`.
- **REST verb conventions:** these services use `store` (idempotent create-or-update), `create`
  (new record only), and `update` (mutate existing). `store#*` is the create-or-update entry
  point used when the caller may not know whether the record exists yet.
- **Bulk operations are the framework's job:** none of these services accept a `List` of items
  to iterate. When a REST request body is a JSON array, Moqui automatically calls the mapped
  single-item service once per element and returns an array of results. Every service here is
  written to handle exactly **one** item.

## Service files at a glance

| File | Domain | Services |
|---|---|---|
| `AgentServices.xml` | Agent execution + authoring | `run#Agent`, `store#AiAgent`, `store#AiAgentTool`, `create#Conversation` |
| `ToolServices.xml` | Tool authoring (catalog) | `store#AiTool` |
| `ComposerServices.xml` | Composer assistant meta-tools | `find#Capability`, `describe#Capability`, `list#DomainTerm`, `propose#Naming`, `set#Guardrail`, `request#Capability`, `preview#Agent`, `activate#Agent`, `discard#Draft` |
| `ToolCallRequestServices.xml` | Human approval gate | `approve#ToolCallRequest`, `reject#ToolCallRequest`, `decide#ToolCallRequest`, `get#PendingToolCallRequest` |
| `CostServices.xml` | Pricing + spend | `store#AiModelPrice`, `get#AiSpend` |
| `FactServices.xml` | Conversation pinned facts | `remember#Fact` |
| `GlossaryServices.xml` | Builder Knowledgebase glossary | `seed#DomainGlossary`, `find#DomainTerm`, `capture#NamingSignal`, `promote#TermsFromSignals`, `list#DomainTerm`, `propose#Naming`, `store#DomainTerm`, `approve#DomainTerm`, `reject#DomainTerm` |
| `service/moqui/ai/test/TestServices.xml` | Test fixtures | `get#Echo`, `update#Noop`, `set#Echo`, `get#GatedEcho` |

---

## `AgentServices.xml` — `ai.AgentServices`

The execution entry point (`run#Agent`) plus the Agent Composer's authoring gates
(`store#AiAgent`, `store#AiAgentTool`) and conversation creation.

### `run#Agent`

Runs an agent against a user message, driving the full agentic loop via
`org.moqui.ai.AgentRunner`.

- **Auth:** `authenticate="true"`
- **Transaction:** `ignore` (the loop holds no enclosing tx; tool calls + observability writes
  each manage their own tx in `AgentRunner`).

**In parameters**

| Name | Type | Req | Notes |
|---|---|---|---|
| `agentId` | String | yes | The stable opaque id — the only invocation key. `run#Agent` does not resolve human names; resolve an `agentName` to its id first (see Behavior). |
| `userMessage` | String | yes | The turn's user message. |
| `conversationId` | String | — | When set, prior turns are replayed and this turn is persisted. |

**Out parameters**

| Name | Type | Notes |
|---|---|---|
| `assistantMessage` | String | The final assistant text. |
| `agentRunId` | String | The `AiAgentRun` row id. |
| `conversationId` | String | Echoed/created conversation id. |
| `tokensIn` | Long | |
| `tokensOut` | Long | |
| `iterations` | Integer | Loop iterations executed. |
| `truncated` | Boolean | True if the loop hit `maxIterations`. |
| `statusId` | String | Run status (`AI_RUN_*`, e.g. `AI_RUN_SUSPENDED` when awaiting approval). |
| `servedByModelId` | String | The model that actually answered (after failover). |
| `providerName` | String | The served provider (mapped from `AgentRunner`'s `servedProviderName`). |
| `estimatedCost` | BigDecimal | Stamped off the **served** model's price. |
| `providerRunId` | String | Upstream provider's run id, when supplied. |
| `structuredResult` | Map | Parsed structured output when the agent has a `responseSchema`. |
| `awaitingApproval` | Boolean | True when the run suspended on a would-be mutating tool call. |
| `toolCallRequestIds` | List | Ids of the `AiToolCallRequest` rows created when suspending. |

**Behavior**

1. Requires `agentId` (the stable opaque id). `run#Agent` is the low-level executor and does **not**
   resolve human names — resolve an `agentName` to its id beforehand (e.g. via `create#Conversation`,
   which accepts a name, or the AiOps Agents console). This keeps invocation bound to the unambiguous
   id, never the editable label.
2. Constructs `new AgentRunner(ec, ai).run(agentId, userMessage, conversationId)` and maps
   the returned result onto the out-params (note: `providerName ← r.servedProviderName`).
3. The `AgentRunner` assembles context, calls the provider, dispatches tool calls, repeats to
   `maxIterations`/`maxToolCallsPerTurn`, audits each step, and stamps `estimatedCost` off the
   served model. If a mutating tool requires approval, the run **suspends**
   (`statusId = AI_RUN_SUSPENDED`, `awaitingApproval = true`, `toolCallRequestIds` populated) rather
   than executing the call.

### `store#AiAgent`

Create-or-update an agent. This is the Agent Composer's authoring gate: it sequences the
`agentId`, enforces a unique `agentName`, defaults a draft to **runnable** on create, and
records a naming signal.

- **Auth:** `authenticate="true"`
- **Transaction:** framework default.

**In parameters**

| Name | Type | Notes |
|---|---|---|
| `agentId` | String | Omit to **create** (sequenced); set to **update** by stable id. |
| `agentName` | String | Unique across agents (enforced). |
| `description` | String | Also used as the create-time `systemPrompt` fallback. |
| `providerName` | String | Defaulted on create (see below). |
| `modelName` | String | Defaulted on create (see below). |
| `systemPrompt` | String | Defaults to `description` on create. |
| `responseSchema` | String | |
| `contextStrategy` | String | `off` / `window` / `summarize`. |
| `contextWindowMessages` | Integer | |
| `contextWindowChars` | Integer | |
| `reasoningEffort` | String | |
| `maxIterations` | Integer | Defaults to `5` on create. |
| `maxTokens` | Integer | |
| `maxCost` | BigDecimal | Stored but **not enforced** at runtime. |
| `maxToolCallsPerTurn` | Integer | |
| `statusId` | String | Defaults to `AI_AGENT_DRAFT` on create. |
| `suggestedName` | String | Builder Knowledgebase pass-through — what the Composer proposed (null if human-authored). Read only by `capture#NamingSignal`; inert to authoring. |
| `intentText` | String | The user's described intent for this agent. |

**Out parameters:** `agentId`.

**Behavior**

1. **Unique-name check:** when `agentName` is given, finds all `AiAgent` rows with that name; if
   any belongs to a *different* `agentId`, returns the error
   `An agent named '${agentName}' already exists (agentId …); choose a different name.`
2. **Create-only defaults (when `agentId` is absent):** sequences a new `agentId`; sets
   `statusId ?: AI_AGENT_DRAFT`; fills `providerName` from `System.getProperty('ai_default_provider')`
   falling back to `openai`; fills `modelName` from `System.getProperty('ai_default_model')`
   falling back to `gpt-4o-mini`; `maxIterations ?: 5`; `systemPrompt ?: description`. These never
   override an explicit value and never run on update.
3. Sets `ec.context.signalCaptured = true` **before** the write (so the `AiGlossaryEcas` EECA on
   `AiAgent` skips it — the in-service capture below is preferred over the EECA floor).
4. Calls `store#moqui.ai.AiAgent` (entity-auto) with the full context.
5. Calls `ai.GlossaryServices.capture#NamingSignal` with `signalType = AI_SIG_AGENT_NAME`,
   `chosenName = agentName`, plus `suggestedName`/`intentText`. Non-fatal.

> The defaults make a freshly drafted agent runnable without hand-editing entities: the
> user/Composer describes *what* the agent does and the system picks a model, so `preview#Agent`
> and `activate#Agent` work out of the box.

### `store#AiAgentTool`

Grant a catalog tool to an agent — the Composer's `grant_capability` backing service. An
**explicit** wrapper (not entity-auto) so that it can itself be exposed as a tool
(`ToolSchemaBuilder` cannot introspect entity-auto services, which have no `ServiceDefinition`).

- **Auth:** `authenticate="true"`
- **Transaction:** framework default.

**In parameters**

| Name | Type | Req | Notes |
|---|---|---|---|
| `agentId` | String | yes | |
| `toolId` | String | yes | |
| `requiresApprovalOverride` | String | — | Optional `Y`/`N` — stricter than the tool's own default. |

**Out parameters:** `agentId`, `toolId`.

**Behavior**

1. Looks up the agent; unknown → error `Unknown agent ${agentId}`.
2. Looks up the tool; unknown → error `Unknown tool ${toolId}`.
3. **Safety floor:** if the tool is not `exposable = 'Y'` **and** `statusId = 'AI_TOOL_ACTIVE'`,
   returns an error and refuses the grant (a draft can never reference a service the Curator
   hasn't blessed).
4. Calls `store#moqui.ai.AiAgentTool` (idempotent create-or-update) with the three fields.

### `create#Conversation`

Create a conversation for an agent.

- **Auth:** `authenticate="true"`
- **Transaction:** framework default.

**In parameters**

| Name | Type | Notes |
|---|---|---|
| `agentName` | String | Resolved to `agentId` when `agentId` is absent. |
| `agentId` | String | |
| `title` | String | |

**Out parameters:** `conversationId`.

**Behavior**

1. If `agentId` is absent but `agentName` is given, resolves `agentId` from `AiAgent` (cached);
   unknown → error `Unknown agent: ${agentName}`.
2. Sequences a `conversationId` and calls `create#moqui.ai.AiConversation` with `agentId`,
   `title`, `userId = ec.user.userId`, `createdDate = ec.user.nowTimestamp`, and
   `statusId = AI_CONV_ACTIVE`.

---

## `ToolServices.xml` — `ai.ToolServices`

The single authoring gate that turns a Moqui service into an `AiTool` catalog row.

### `store#AiTool`

Create-or-update a tool. Validates the backing service, derives the tool's effect from its verb,
applies the denylist safety floor, derives and uniqueness-checks the `toolName`, branches
explicitly create-vs-update, refreshes the in-memory catalog, and records a naming signal.

- **Auth:** `authenticate="true"`
- **Transaction:** framework default.

**In parameters**

| Name | Type | Req | Notes |
|---|---|---|---|
| `toolId` | String | — | Omit to **create** (sequenced); set to **update** by stable id. |
| `verb` | String | yes | |
| `noun` | String | yes | |
| `serviceName` | String | yes | The Moqui service this tool wraps. |
| `description` | String | — | |
| `exposable` | String | — | Requested exposability; floored to `N` by the denylist and by the `MUTATING` default. |
| `requiresApproval` | String | — | |
| `effectEnumId` | String | — | Optional Curator override; defaults to the verb-derived effect. |
| `sourceComponent` | String | — | |
| `statusId` | String | — | Defaults to `AI_TOOL_ACTIVE`. |
| `suggestedName` | String | — | Builder Knowledgebase pass-through (Composer's proposal). |
| `intentText` | String | — | Described intent for this tool. |

**Out parameters:** `toolId`, `toolName`.

**Behavior**

1. **Validate the service resolves:** calls `ToolSchemaBuilder.build(ec.factory, serviceName)`;
   on failure adds the error `Unknown or invalid service for tool: ${serviceName} (…)` and
   returns.
2. **Derive `toolName`:** `(verb + "_" + noun)` lowercased with non-`[a-z0-9_]` replaced by `_`
   (snake_case, wire-safe).
3. **Uniqueness:** any existing tool with the same `toolName` but a *different* `toolId` →
   error `A tool named '${toolName}' already exists (toolId …); choose a different verb/noun.`
4. **Derive effect** (only when `effectEnumId` is not supplied): read verbs
   `get, find, list, view, search, check, calculate` → `AI_TOOL_READ_ONLY`; everything else →
   `AI_TOOL_MUTATING`.
5. **Effect-based defaults** (applied before the denylist): if `exposable` is null →
   `Y` for read-only, `N` for mutating; if `requiresApproval` is null → `N` for read-only,
   `Y` for mutating.
6. **Denylist floor:** for each `AiToolDenylist` row, if `serviceName` matches `servicePattern`
   (Groovy `==~` regex), force `exposable = 'N'` (non-overridable).
7. Defaults `statusId ?: AI_TOOL_ACTIVE`, and sets `ec.context.signalCaptured = true` before the
   write (so the defensive `AiTool` EECA skips — the in-service capture below is preferred).
8. **Explicit create/update branch by `toolId`:** create sets `createdByUserId = ec.user.userId`;
   update **omits** `createdByUserId` so it is preserved (never nulled). Both write all derived
   fields.
9. **Refresh catalog:** calls `ec.factory.getTool("AI", AiToolFactory).refreshCatalog()` so the
   new/edited tool is immediately grant-eligible.
10. Calls `ai.GlossaryServices.capture#NamingSignal` with `signalType = AI_SIG_TOOL_NAME`,
    `chosenName = toolName`, plus `suggestedName`/`intentText`. Non-fatal.

---

## `ComposerServices.xml` — `ai.ComposerServices`

The Composer Assistant's meta-tools — the framework pointed at itself. Each of these is also an
`AiTool` catalog row (seeded in `data/AiComposerData.xml`) granted to the `composer-assistant`
agent. All are `authenticate="true"` (per-role `ArtifactAuthz` gating is a deferred v1 follow-up).

> **Naming note:** `AiTool` stores the effect as `effectEnumId`. These services surface it to the
> LLM/screen under the ergonomic out-param/alias name `effect`.

### `find#Capability`

Search the catalog by intent/keyword/noun. Returns only **grant-eligible** tools
(`exposable = 'Y'` and `statusId = 'AI_TOOL_ACTIVE'`).

- **Auth:** `authenticate="true"` · **Transaction:** framework default.

**In parameters:** `query` (intent/keyword/noun), `maxResults` (Integer, default `25`).

**Out parameters:** `capabilityList` (List of tool Maps; each carries an `effect` alias of
`effectEnumId`).

**Behavior**

1. Loads the grant-eligible catalog (`exposable='Y'`, `statusId='AI_TOOL_ACTIVE'`), ordered by
   `toolName`.
2. **Tokenized + ranked matching in Groovy:** lowercases/trims the query and splits it on
   whitespace into terms. A tool is included if **any** term is a substring of its
   `toolName verb noun description` haystack; the match score is the **count of distinct terms
   that hit**. Results are sorted by hit count descending (stable for ties → `toolName` order)
   and truncated to `maxResults`.
3. An empty query returns the whole grant-eligible catalog (filtering is done in Groovy
   specifically so an empty query doesn't render a literal `'%null%'` LIKE).

> This tokenized approach replaced an earlier whole-phrase `contains()`/`like '%query%'` match,
> which missed obvious hits (e.g. `"summarize recent orders"` vs a description containing
> "summarize" and "orders" non-contiguously returned nothing).

### `describe#Capability`

One tool's purpose plus its on-demand input schema, so the assistant can reason about fit.

- **Auth:** `authenticate="true"` · **Transaction:** framework default.

**In parameters:** `toolId`, or `toolName` (alternative lookup key).

**Out parameters:** `toolId`, `toolName`, `description`, `effect`, `serviceName`, `exposable`,
`inputSchema` (Map).

**Behavior:** finds the tool by `toolId` and/or `toolName` (both `ignore-if-empty`); none →
error `No such capability: …`. Maps the first match's fields (`effect ← effectEnumId`) and
generates `inputSchema` on demand from the live service definition via
`ToolSchemaBuilder.build(...)` (never stored).

### `list#DomainTerm`

The business vocabulary the assistant grounds in. **Catalog-noun contract** — distinct from
`ai.GlossaryServices.list#DomainTerm`, which returns ranked term Maps. The APPROVED domain
glossary is the primary source, with grant-eligible catalog nouns merged in as a floor.

- **Auth:** `authenticate="true"` · **Transaction:** framework default.

**In parameters:** `query`.

**Out parameters:** `termList` (List), `source` (`"catalog"` when the glossary is empty |
`"knowledgebase"` when the glossary contributed).

**Behavior**

1. Builds a **catalog floor**: distinct nouns from grant-eligible `AiTool` rows, keyword-filtered
   and de-duped in Groovy.
2. Builds **knowledgebase terms**: with a query, delegates to
   `ai.GlossaryServices.find#DomainTerm` (snap via lexical find) and collects the `term` strings;
   without a query, returns all `AI_TERM_APPROVED` `AiDomainTerm` rows ordered by `term`.
3. `source = "knowledgebase"` if the glossary produced any term, else `"catalog"`. `termList` is
   the glossary terms followed by the catalog nouns, de-duped. An un-seeded deployment behaves
   exactly like the original catalog-only stub. The `termList + source` contract is unchanged so
   the assistant needs no edit.

### `propose#Naming`

Best-guess agent naming: a Knowledgebase-grounded heuristic floor plus optional LLM refinement.
Wire-safe by construction; never aborts the build.

- **Auth:** `authenticate="true"` · **Transaction:** framework default.

**In parameters:** `intent` (required — what the user wants the agent to do).

**Out parameters:** `agentNameSuggestion`, `descriptionSuggestion`.

**Behavior**

1. **Heuristic floor:** calls `ai.ComposerServices.list#DomainTerm` (no query → full grounding
   slice) and picks the term that appears in `intent`, else `"assistant"`.
2. **Glossary snap:** when the picked noun isn't `"assistant"`, calls
   `ai.GlossaryServices.propose#Naming` to snap a dialect word to its canonical glossary term
   (e.g. `"rma"` → `"return"`); guarded with a `try/catch` that only warns.
3. Builds the slug `${noun}-assistant` (or `"assistant"`), lowercased and hyphen-normalized;
   sets `descriptionSuggestion = "Assistant for: ${intent}"`.
4. **Optional LLM refinement** (only if a provider key is configured): picks `openai` when
   `ai_openai_key` is set, else `anthropic` when `ai_anthropic_key` is set, else skips. Calls the
   provider's `chat()` with model **`gpt-4o-mini`** (OpenAI) or **`claude-3-5-haiku-latest`**
   (Anthropic), asking for JSON `{"name","description"}` grounded in the glossary terms. Parses
   the response to override the suggestions. Wrapped in `try/catch` that only warns.

### `set#Guardrail`

Set per-grant approval strictness on a draft. An agent can be **stricter** than the tool default,
never looser. Writes `AiAgentTool.requiresApprovalOverride`.

- **Auth:** `authenticate="true"` · **Transaction:** framework default.

**In parameters:** `agentId` (required), `toolId` (required), `requiresApproval` (required,
`Y`/`N`).

**Out parameters:** none.

**Behavior:** finds the `AiAgentTool` grant; none → error
`Agent ${agentId} has no grant for tool ${toolId}`. Otherwise calls
`update#moqui.ai.AiAgentTool` setting `requiresApprovalOverride`.

### `request#Capability`

Record a capability gap for the Curator — the assistant calls this instead of fabricating a tool.

- **Auth:** `authenticate="true"` · **Transaction:** framework default.

**In parameters:** `intent` (required), `suggestedVerb`, `suggestedNoun`, `notes`, `agentRunId`,
`conversationId`.

**Out parameters:** `capabilityRequestId`.

**Behavior:** sequences a `capabilityRequestId` and calls `create#moqui.ai.AiCapabilityRequest`
with the inputs plus `requestedByUserId = ec.user.userId`, `requestedDate = ec.user.nowTimestamp`,
and `statusId = AI_CAPREQ_OPEN`.

### `preview#Agent`

Sandbox-run a draft on a user-supplied test message. Mutating tools are **held by the approval
gate** (`AgentRunner.runPreview`, which sets `forceApprovalOnMutating`); read-only tools run on
real data. Returns the run result plus the held (would-be) calls so the screen can show
"would call X(...)".

- **Auth:** `authenticate="true"`
- **Transaction:** `ignore` (drives LLM calls; mirrors `run#Agent`).

**In parameters:** `agentId` (required), `testMessage` (required).

**Out parameters:** `agentRunId`, `statusId`, `assistantMessage`, `heldCalls` (List — each:
`toolName`, `serviceName`, `arguments` (JSON)).

**Behavior:** calls `new AgentRunner(ec, ai).runPreview(agentId, testMessage)`, then collects the
`AiToolCallRequest` rows for that run with `statusId = AI_TCREQ_PENDING` (ordered by `requestedDate`)
into `heldCalls`. The run is marked `isPreview = 'Y'`, which keeps its pending approvals out of
the operator queue (`get#PendingToolCallRequest`) and lets `discard#Draft` delete them.

### `activate#Agent`

Commit a draft to active. Re-checks that every granted tool is still exposable + active.

- **Auth:** `authenticate="true"` · **Transaction:** framework default.

**In parameters:** `agentId` (required).

**Out parameters:** `agentId`, `statusId`.

**Behavior**

1. Loads the agent; unknown → error `Unknown agent ${agentId}`.
2. If `statusId != 'AI_AGENT_DRAFT'` → error `Agent ${agentId} is not a draft (status …)`.
3. **Re-checks grants:** for every `AiAgentTool` grant, if the tool is missing or not
   `exposable='Y'` + `statusId='AI_TOOL_ACTIVE'` → error
   `Cannot activate: granted tool ${toolId} is not exposable/active` (a tool can be un-exposed or
   disabled by the Curator after it was granted).
4. Updates the agent to `AI_AGENT_ACTIVE`.

> **Human approval is enforced upstream:** `activate_agent` is itself a `requiresApproval` `AiTool`,
> so when the Composer Assistant proposes it the assistant's own run suspends via the approval
> gate; this service runs only after a human approves. It is also callable directly by an operator
> (the screen's Activate button).

### `discard#Draft`

Drop a draft, its grants, and the preview runs it spawned (with their steps, tool-calls, and held
approvals). This is what keeps abandoned preview approvals out of the operator queue.

- **Auth:** `authenticate="true"` · **Transaction:** framework default.

**In parameters:** `agentId` (required).

**Out parameters:** none.

**Behavior**

1. Loads the agent; if absent, returns silently (idempotent). If `statusId != 'AI_AGENT_DRAFT'` →
   error `Refusing to discard a non-draft agent ${agentId}`.
2. For each `AiAgentRun` of the agent, deletes its `AiToolCallRequest`, `AiToolCall`, and
   `AiAgentRunStep` rows, then deletes the `AiAgentRun` rows (steps before the run, because
   `AiAgentRunStep` has a real FK to `AiAgentRun`).
3. Deletes every `AiAgentTool` grant via `delete#moqui.ai.AiAgentTool`.
4. Deletes the agent via `delete#moqui.ai.AiAgent`.

---

## `ToolCallRequestServices.xml` — `ai.ToolCallRequestServices`

The human approval gate. The three decision services are all `transaction="ignore"`: deciding an
approval resumes the suspended run (`AgentRunner.resume()`), whose LLM calls must hold **no
enclosing transaction**. Each entity write inside still runs in its own tx via the entity-auto
service-call.

### `approve#ToolCallRequest`

Convenience wrapper that approves one pending call.

- **Auth:** `authenticate="true"` · **Transaction:** `ignore`.
- **In:** `toolCallRequestId` (required), `decisionNote`. **Out:** `agentRunId`, `runStatusId`.
- **Behavior:** delegates to `decide#ToolCallRequest` with `statusId = AI_TCREQ_APPROVED`.

### `reject#ToolCallRequest`

Convenience wrapper that rejects one pending call.

- **Auth:** `authenticate="true"` · **Transaction:** `ignore`.
- **In:** `toolCallRequestId` (required), `decisionNote`. **Out:** `agentRunId`, `runStatusId`.
- **Behavior:** delegates to `decide#ToolCallRequest` with `statusId = AI_TCREQ_REJECTED`.

### `decide#ToolCallRequest`

Records one decision and resumes the run when no pending approvals remain for it.

- **Auth:** `authenticate="true"` · **Transaction:** `ignore`.

**In parameters:** `toolCallRequestId` (required), `statusId` (required — the decision, e.g.
`AI_TCREQ_APPROVED`/`AI_TCREQ_REJECTED`), `decisionNote`.

**Out parameters:** `agentRunId`, `runStatusId`.

**Behavior**

1. Loads the `AiToolCallRequest`; unknown → error `Unknown toolCallRequestId ${toolCallRequestId}`; already-decided
   (`statusId != 'AI_TCREQ_PENDING'`) → error `Approval already decided`.
2. Updates the approval with the decision, `decidedByUserId = ec.user.userId`,
   `decidedDate = ec.user.nowTimestamp`.
3. Counts approvals still `AI_TCREQ_PENDING` for the same `agentRunId`. If any remain, leaves the
   run `runStatusId = AI_RUN_SUSPENDED`. Otherwise calls
   `new AgentRunner(ec, aiTool).resume(agentRunId)` and returns its resulting `statusId`.

> `resume()` itself applies a fail-closed `anyUndecided` guard: if any tool call in the resumed
> turn is still undecided it re-suspends rather than proceeding.

### `get#PendingToolCallRequest`

The operator queue (rendered by `screen/AiOps/Approvals.xml`).

- **Auth:** `authenticate="true"` · **Transaction:** framework default.

**In parameters:** `agentRunId` (optional — scopes to one run; omit for all runs).

**Out parameters:** `approvalList` (List of `AiToolCallRequest`).

**Behavior:** finds `AiToolCallRequest` rows with `statusId = AI_TCREQ_PENDING` ordered by
`requestedDate`; optionally scopes to `agentRunId`. **Excludes preview-run approvals:** collects
the ids of all `AiAgentRun` rows with `isPreview = 'Y'` and, when that list is non-empty, adds a
null-safe `agentRunId not-in (previewRunIds)` condition (runs with a null `isPreview` are kept).
Preview suspensions exist only to *show* would-be mutating calls, so they must not pollute the
real queue — this is data hygiene, not a permission gate (per-approver permissions are deferred
to Phase 5).

---

## `CostServices.xml` — `ai.CostServices`

Model pricing and queryable estimated spend.

### `store#AiModelPrice`

Add or replace a model price (effective-dated).

- **Auth:** `authenticate="true"` · **Transaction:** framework default.

**In parameters:** `providerName` (required), `modelName` (required), `fromDate` (Timestamp),
`inputPricePerMillion` (BigDecimal, required), `outputPricePerMillion` (BigDecimal, required),
`currencyUomId` (default `USD`).

**Out parameters:** `fromDate` (Timestamp).

**Behavior:** defaults `fromDate` to `ec.user.nowTimestamp` when absent (a new effective-dated row
per call), then calls `store#moqui.ai.AiModelPrice`. Passing an explicit `fromDate` updates that
row.

### `get#AiSpend`

Queryable estimated spend, aggregated in Groovy over `AiAgentRun`. All filters optional.

- **Auth:** `authenticate="true"` · **Transaction:** framework default.

**In parameters:** `agentName`, `userId`, `fromDate` (Timestamp), `thruDate` (Timestamp),
`groupBy` (`none` | `agent` | `user`, default `none`).

**Out parameters:** `totalCost` (BigDecimal), `totalTokensIn` (Long), `totalTokensOut` (Long),
`runCount` (Long), `rows` (List — when grouped:
`[key, totalCost, totalTokensIn, totalTokensOut, runCount]`).

**Behavior:** finds `AiAgentRun` rows filtered by `agentName`/`userId` (ignore-if-empty) and the
`fromDate` window (`>=` `fromDate`, `<` `thruDate`). Sums `estimatedCost`, `tokensIn`, `tokensOut`,
and counts rows. When `groupBy` is `agent` or `user`, groups by `agentName`/`userId` and emits a
per-group `rows` entry. The aggregation is pure Groovy over the per-run `estimatedCost` (which was
itself stamped off the **served** model in `AgentRunner.finish()`); there is no spend view-entity.

---

## `FactServices.xml` — `ai.FactServices`

Conversation-scoped pinned facts (ADR 0001 context management).

### `remember#Fact`

Record or supersede a pinned fact, keyed by `(conversationId, factKey)`.

- **Auth:** `authenticate="true"` · **Transaction:** framework default.

**In parameters:** `conversationId` (required), `factKey` (required), `factValue` (required),
`agentRunId`.

**Out parameters:** `factKey`.

**Behavior:** finds `AiConversationFact` by its PK (`conversationId`, `factKey`). If it exists,
updates `factValue` and `agentRunId` in place (preserving `createdDate`). Otherwise calls
`create#moqui.ai.AiConversationFact` with `createdDate = ec.user.nowTimestamp`.

> At runtime `AgentRunner` injects a server-side `remember` tool (for the `window` and `summarize`
> context strategies) that calls this service and writes an `AiToolCall` audit row; the tool is
> approval-gateable and survives suspend/resume.

---

## `GlossaryServices.xml` — `ai.GlossaryServices`

The Builder Knowledgebase / domain glossary that backs the Composer's grounding and naming. A
curated, OMS-grounded glossary of typed terms (`AI_TERM_NOUN` / `AI_TERM_VERB`) plus dialect
synonyms. **v1 is lexical + suggest-only** — nothing auto-enters the APPROVED glossary.

### `seed#DomainGlossary`

Build/refresh the starting glossary from the live entity model + service catalog. Seeds
`AI_TSRC_SEEDED` + `AI_TERM_APPROVED` terms. Idempotent and re-runnable.

- **Auth:** `authenticate="true"` · **Transaction:** framework default.

**In parameters:** `ownerScope` (null = global in v1).

**Out parameters:** `nounsAdded` (Integer), `verbsAdded` (Integer).

**Behavior:** sets `signalGuard = true` (so `capture#NamingSignal` no-ops during seeding).
**Nouns:** a deliberate `entityNoun` map (e.g. `OrderHeader→order`, `ReturnHeader→return`,
`Shipment→shipment`, …) is seeded only when the backing entity exists in this deployment, plus a
fixed list of curated UDM concepts (`allocation`, `reservation`, `fulfillment`, `brokering`,
`store`, `warehouse`, `carrier`, `kit`, `variant`, `catalog`). **Verbs:** the lowercased verbs of
all known service names. A term already present (any status, for the same kind/scope) is left
untouched. Creates each missing term via `create#moqui.ai.AiDomainTerm`. Intended to be called
once at install (via an ext data service-call) and on demand.

### `find#DomainTerm`

Lexical retrieval backing the Composer's grounding + `propose#Naming`. Matches text tokens against
the term and its approved synonyms; filters APPROVED; ranks by match × usage.

- **Auth:** `authenticate="true"` · **Transaction:** framework default.

**In parameters:** `text` (required), `kind` (`AI_TERM_NOUN` | `AI_TERM_VERB`; null = both),
`ownerScope`, `maxResults` (Integer, default `20`).

**Out parameters:** `terms` (List — ranked `[termId, term, termKind, description, usageCount,
score]`).

**Behavior:** tokenizes `text` (lowercased, non-alphanumeric → space). For each APPROVED term
(filtered by `kind`/`ownerScope` when given): scores **+2** for an exact token match on the term,
else **+1** for a substring near-match either way; collects approved `AiTermSynonym` aliases and
adds **+2** for a synonym hit (exact token == alias, or a conservative near-match where both
strings are length ≥ 3 and one contains the other — handles dialect inflections like
`rmas`↔`rma`). Terms scoring 0 are dropped. Final ranking is `score × (1 + usageCount)`, sorted
descending, truncated to `maxResults`.

### `capture#NamingSignal`

Record one naming signal. Shared by the in-service hook (rich context) and the defensive
`AiGlossaryEcas` EECA (the floor).

- **Auth:** `authenticate="false"` — internal hook (the only `authenticate="false"` service in the
  catalog).
- **Transaction:** framework default.

**In parameters:** `signalType` (required — `AI_SIG_TOOL_NAME` | `AI_SIG_AGENT_NAME`), `chosenName`
(required), `suggestedName`, `intentText`.

**Out parameters:** `signalId`.

**Behavior:** **no-ops and returns** when `ec.context.signalGuard == true` (set during seeding).
Otherwise sequences a `signalId`, computes `wasOverridden = 'Y'` when `suggestedName` is non-null
and differs from `chosenName` (else `'N'`), and calls `create#moqui.ai.AiNamingSignal` with
`userId = ec.user.userId` and `createdDate = ec.user.nowTimestamp`.

> The `store#AiTool` / `store#AiAgent` services set `ec.context.signalCaptured = true` before
> their entity write so the defensive `AiGlossaryEcas` EECA (which also calls this service) skips —
> the rich in-service capture is preferred over the EECA floor. (The EECA on `AiTool`/`AiAgent`
> auto-scans writes; it is not registered in `MoquiConf` but loaded automatically from
> `entity/*.eecas.xml`.)

### `promote#TermsFromSignals`

Scan naming signals; chosen-name tokens that recur ≥ threshold and aren't already glossary terms
are **proposed** (`AI_TSRC_LEARNED` + `AI_TERM_SUGGESTED`) for a Curator to approve. Suggest-only;
re-runnable.

- **Auth:** `authenticate="true"` · **Transaction:** framework default.

**In parameters:** `threshold` (Integer, default `3`), `ownerScope`.

**Out parameters:** `proposed` (Integer).

**Behavior:** builds the set of APPROVED verb terms (the "known verbs" to strip out). Tokenizes
every `AiNamingSignal.chosenName` on `[_\s]+`, skipping known verbs, and counts token frequency.
For each token whose count ≥ `threshold` that is not already an `AI_TERM_NOUN` term (for the
scope), creates a `AI_TERM_SUGGESTED` noun term with `usageCount = count` and description
`Learned from authoring (x${count})`.

### `list#DomainTerm`

Grounding slice for the assistant: APPROVED glossary terms for the given text/kind. A thin wrapper
over `find#DomainTerm`. **Distinct** from `ai.ComposerServices.list#DomainTerm` (catalog-noun
contract) — this one returns the ranked term Maps that the Composer's stub delegates to.

- **Auth:** `authenticate="true"` · **Transaction:** framework default.

**In parameters:** `text` (required), `kind`, `ownerScope`, `maxResults` (Integer, default `20`).

**Out parameters:** `terms` (List).

**Behavior:** delegates entirely to `ai.GlossaryServices.find#DomainTerm` (out-map mapped to
context).

### `propose#Naming`

Snap a raw verb/noun guess (from the Composer's LLM) to the nearest APPROVED glossary
term/synonym, so the suggestion speaks the deployment's dialect. The human still edits. The
LLM-proposal half lives in the Composer; this is the glossary-snap it delegates to.

- **Auth:** `authenticate="true"` · **Transaction:** framework default.

**In parameters:** `proposedVerb`, `proposedNoun`, `intentText`, `ownerScope`.

**Out parameters:** `verb`, `noun`, `toolName`, `groundingTerms` (List).

**Behavior:** seeds `verb`/`noun` from the proposals. When `proposedNoun` is set, calls
`find#DomainTerm` (`kind = AI_TERM_NOUN`, `maxResults = 1`) and, on a hit, replaces `noun` with the
canonical term and records the grounding term. Same for `proposedVerb`
(`kind = AI_TERM_VERB`). When both `verb` and `noun` are present, derives the wire-safe
`toolName = (verb + "_" + noun)` lowercased with non-`[a-z0-9_]` → `_`.

### `store#DomainTerm`

Curator: create-or-update a `AI_TSRC_CURATED` term (and optionally attach an approved synonym).
Idempotent.

- **Auth:** `authenticate="true"` · **Transaction:** framework default.

**In parameters:** `termId` (set to update / attach a synonym), `term`, `termKind`, `description`,
`synonym`, `ownerScope`, `statusId` (default `AI_TERM_APPROVED`).

**Out parameters:** `termId`.

**Behavior:** **create** (no `termId`): sequences a `termId` and creates the term with
`sourceType = AI_TSRC_CURATED`, `usageCount = 0`. **Update** (`termId` set): updates **only the
fields actually provided** — it builds the update map conditionally (`statusId`/`term`/`termKind`/
`description` each added only when non-null) specifically to avoid the entity-auto `setIfEmpty`
behavior clobbering an existing term when the call is only attaching a synonym. When `synonym` is
given, calls `store#moqui.ai.AiTermSynonym` (lowercased, `AI_TSRC_CURATED`, `AI_TERM_APPROVED`).

### `approve#DomainTerm`

Curator: approve a suggested term.

- **Auth:** `authenticate="true"` · **Transaction:** framework default.
- **In:** `termId` (required). **Out:** none.
- **Behavior:** `update#moqui.ai.AiDomainTerm` → `statusId = AI_TERM_APPROVED`.

### `reject#DomainTerm`

Curator: reject a suggested term.

- **Auth:** `authenticate="true"` · **Transaction:** framework default.
- **In:** `termId` (required). **Out:** none.
- **Behavior:** `update#moqui.ai.AiDomainTerm` → `statusId = AI_TERM_REJECTED`.

---

## `service/moqui/ai/test/TestServices.xml` — `moqui.ai.test.TestServices`

Deterministic fixtures used by the automated test suite (not part of the production runtime API).
None set a non-default transaction; none set `authenticate` (framework default applies).

| Service | Purpose |
|---|---|
| `get#Echo` | Echoes `text`, optionally repeated `repeat` times. Errors when `repeat < 0`. A read-verb fixture (`store#AiTool` derives effect `READ_ONLY`). |
| `update#Noop` | No-op write-verb service so `store#AiTool` derives effect `MUTATING` from the `update` verb. |
| `set#Echo` | A deterministic MUTATING fixture for Composer preview tests (verb `set` → `MUTATING`); body is a harmless echo, no real write. |
| `get#GatedEcho` | Identical to `get#Echo` but a distinct `serviceName`, so it can be seeded (via `AiTestToolData.xml`) as an approval-gated tool without colliding with the ungated `get#Echo` entry. |

---

## Notes where code differs from the plan/drift report

The reconciliation plan (Task 2) and drift report were used as a checklist; every fact below was
re-derived from the live `<service>` definitions. The plan and drift report were accurate for the
service layer — the only adjustments were precision/clarification, not factual reversals:

- **`run#Agent` out-param `providerName`.** The plan lists `providerName` as an out-param; the
  code confirms it but the value is mapped from `AgentRunner`'s `servedProviderName` (the served
  provider after failover), not a stored agent field. Documented as such.
- **`store#AiAgent` default model.** The plan says provider/model default from
  `ai_default_provider`/`ai_default_model` → fallback `openai`/`gpt-4o-mini`. The code resolves
  these via `System.getProperty(...)` (system property), matching the documented `AiToolFactory`
  resolution order — noted explicitly rather than implying an arbitrary lookup.
- **`maxCost`.** Confirmed against `store#AiAgent`: the parameter exists and is stored, but no
  service in this catalog reads or enforces it (enforcement is deferred). Stated in the param
  table.
- **`get#PendingToolCallRequest` preview exclusion.** The plan says preview-run approvals are excluded;
  the code does this with a **null-safe `not-in`** that is only applied when the preview-id list is
  non-empty (so runs with a null `isPreview` are kept). Documented precisely.
- **`store#DomainTerm` update.** The plan flags the "don't clobber unset fields" guard; the code
  implements it by conditionally assembling the update map (not by an entity attribute), which is
  the load-bearing detail and is documented.
- **`capture#NamingSignal` auth.** It is the single `authenticate="false"` service in the catalog
  (an internal hook); the plan grouped it with the rest. Called out explicitly.
- **Two distinct `propose#Naming` and `list#DomainTerm` services.** Both `ComposerServices` and
  `GlossaryServices` define a `propose#Naming` and a `list#DomainTerm`. They are different services
  with different contracts; the plan notes the `list#DomainTerm` split — both pairs are documented
  separately to avoid ambiguity.

No service name, parameter, required-flag, default, auth flag, or transaction setting in this
document contradicts the code; where the historical specs/plans used the old `agentName`/`toolName`
primary keys, this catalog reflects the shipped opaque `agentId`/`toolId` model (resolution-by-name
is an explicit lookup branch, not the key).
