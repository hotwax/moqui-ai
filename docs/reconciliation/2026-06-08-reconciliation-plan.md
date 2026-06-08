# moqui-ai Documentation Reconciliation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax. This is **documentation** work — every "verify" step means *read the cited source file and confirm the doc's claim matches the code*, not run a unit test.

**Goal:** Make `docs/` an accurate, navigable description of the as-built moqui-ai component — add the missing current source-of-truth (README + reference + explanation), fix the drift in the 4 live specs, and archive the historical plans.

**Architecture:** The **code is canonical**. New `reference/` docs are derived directly from `entity/`, `service/`, `screen/`, `MoquiConf.xml`, `build.gradle`, `data/`. New `explanation/` docs distill the "why" from the (accurate) gap-report + ADR + code. The 6 specs become reconciled design-history that point to the new reference. The 18 plans move to `archive/`. Drives off `docs/reconciliation/2026-06-08-doc-drift-report.md`.

**Tech Stack:** Markdown; Moqui (HotWax fork) component conventions; `gh` CLI for the ship step. No code/tests change — docs only.

**Verification rule (applies to every doc task):** for each factual claim in the doc, open the cited source file and confirm field names / service params / status values / behavior match. If they don't, the code wins — fix the doc.

**Source-of-truth inputs:** the drift report (`docs/reconciliation/2026-06-08-doc-drift-report.md`) lists exactly what's drifted/undocumented per area — use it as the checklist.

---

## File Structure

**Create (the source-of-truth):**
- `docs/README.md` — entry point: what moqui-ai is, feature map, quick start, doc map, status.
- `docs/reference/entities.md` — the entity model (from `entity/*.xml` + status/enum data).
- `docs/reference/services.md` — the service catalog (from `service/ai/*.xml`).
- `docs/reference/screens.md` — the AI Ops console (from `screen/AiOps*.xml`).
- `docs/reference/configuration.md` — config, secrets, deploy hygiene, data/reader-types (from `MoquiConf.xml`, component `dev.env`, `build.gradle`, `data/`).
- `docs/explanation/architecture.md` — the agentic loop, providers, context, registry, composer, KB (the "how/why").
- `docs/explanation/security-model.md` — the authz/membership model + agent-runs-as-user + the gates.
- `docs/explanation/decisions.md` — distilled decision record (from the gap-report + ADR + key choices).
- `docs/archive/README.md` — one paragraph: "historical build plans; current state lives in `../reference/`."

**Modify (reconcile — fix drift + add a Reconciled banner pointing at `reference/`):**
- `docs/specs/2026-06-02-ai-agent-framework-design.md`
- `docs/specs/2026-06-05-agent-tool-registry-design.md`
- `docs/specs/2026-06-05-composer-assistant-moqui-design.md`
- `docs/specs/2026-06-05-builder-knowledgebase-design.md`

**Move (archive, with `git mv`, preserving history):**
- all 18 files in `docs/plans/` → `docs/archive/plans/`

**Leave untouched (verified accurate / evergreen):**
- `docs/specs/2026-06-03-enterprise-decisions-gap-report.md`, `docs/specs/2026-06-03-responses-api-migration-audit.md` (already closed-banner'd), `docs/decisions/0001-context-window-management.md`, `docs/product/composer-assistant-overview.md`.

> Tasks 1-8 (the new docs) are **independent and parallelizable** — one subagent per file, each reads its source files + the drift report and writes the doc. Tasks 9-12 (spec edits) are independent of each other. Task 13 (archive) and Task 14 (verify) must come after. Task 15 (ship) is last.

---

### Task 0: Scaffold the new structure

**Files:** Create dirs `docs/reference/`, `docs/explanation/`, `docs/archive/plans/`.

- [ ] **Step 1:** `mkdir -p docs/reference docs/explanation docs/archive/plans` (under the component root).
- [ ] **Step 2:** Create `docs/archive/README.md` with exactly: a title `# Archived plans`, and one paragraph stating these are historical TDD build-logs kept for provenance, that they embed pre-refactor code/entities (notably the old `agentName`/`toolName` PKs), and that the current as-built state lives in `docs/reference/` and `docs/explanation/`.
- [ ] **Step 3:** Commit: `git add docs/archive/README.md && git commit -m "docs(ai): scaffold reference/explanation/archive structure"`.

---

### Task 1: `reference/entities.md`

**Files:** Create `docs/reference/entities.md`. Derive from + verify against: `entity/AiEntities.xml`, `AiToolEntities.xml`, `AiComposerEntities.xml`, `AiConversationEntities.xml`, `AiApprovalEntities.xml`, `AiPriceEntities.xml`, `AiGlossaryEntities.xml`, `AiGlossaryEcas.eecas.xml`, and the status/enum data in `data/AiStatusData.xml`, `AiConversationStatusData.xml`, `AiGlossaryData.xml`.

- [ ] **Step 1: Write the doc.** One section per entity, each listing PK, fields (name/type/purpose), relationships, and status/enum values. Must cover, accurately:
  - **AiAgent** — `agentId` (opaque PK), `agentName` (unique), `providerName`/`modelName`, `systemPrompt`, `responseSchema`, `contextStrategy` (`off|window|summarize`) + `contextWindowMessages`/`contextWindowChars`, `reasoningEffort`, `maxIterations`/`maxTokens`/`maxCost` (note: **`maxCost` is stored but not enforced**)/`maxToolCallsPerTurn`, `statusId` → **AiAgentStatus** = `AI_AGENT_DRAFT|AI_AGENT_ACTIVE|AI_AGENT_DISABLED`.
  - **AiTool** — `toolId` (opaque PK), `toolName` (`verb_noun`, unique), `verb`/`noun`, `description`, `serviceName` (attribute, **not** a key), `effectEnumId` → **AiToolEffect** = `AI_TOOL_READ_ONLY|AI_TOOL_MUTATING`, `exposable`, `requiresApproval`, `sourceComponent`/`createdByUserId`, `statusId` → **AiToolStatus** = `AI_TOOL_ACTIVE|AI_TOOL_DISABLED`.
  - **AiAgentTool** (`agentId,toolId` PK; `requiresApprovalOverride`), **AiAgentModel** (`agentId,priority` PK; provider/model — the failover chain), **AiToolDenylist**, **AiCapabilityRequest** (+ **AiCapReqStatus**).
  - **AiAgentRun** — `agentRunId` PK; `agentId` + `agentName` (run-time snapshot); `userId`; `fromDate`/`thruDate`; `statusId` → **AiAgentRunStatus** = `AI_RUN_RUNNING|COMPLETED|FAILED|TRUNCATED|ABORTED|SUSPENDED`; `providerName`/`modelName`/`servedByModelId`/`providerRunId`; `userMessage`/`assistantMessage`; `iterations`/`tokensIn`/`tokensOut`/`estimatedCost`; `errorText`; `pendingState`; `conversationId`; **`isPreview`**.
  - **AiAgentRunStep** (`stepType` = `llm_call|tool_call|llm_call_failed|context_trim|compaction`), **AiToolCall** (`toolId` + `toolName`/`serviceName` snapshots, `arguments`/`result`/`success`/`errorText`/`durationMs`).
  - **AiConversation / AiConversationMessage / AiConversationFact** (+ rolling-summary fields), **AiToolApproval** (+ **AiApprovalStatus** = `PENDING|APPROVED|REJECTED`), **AiModelPrice**, **AiDomainTerm / AiTermSynonym / AiNamingSignal** (+ the `AiGlossaryEcas` EECA that auto-scans on `AiTool`/`AiAgent` writes).
  - A short note: status transitions use `moqui.basic.StatusFlowTransition` (not `StatusValidChange`).
- [ ] **Step 2: Verify** every field name/type and every status/enum value against the cited entity + data files. Correct any mismatch (code wins).
- [ ] **Step 3: Commit** `git add docs/reference/entities.md && git commit -m "docs(ai): reference — entity model"`.

---

### Task 2: `reference/services.md`

**Files:** Create `docs/reference/services.md`. Derive from + verify against every `service/ai/*.xml`.

- [ ] **Step 1: Write the doc.** Group by service file; for each service give the verb#noun, in/out params, auth, and behavior. Must cover, accurately:
  - **AgentServices:** `run#Agent` (accepts `agentId` OR `agentName`; out-params include `assistantMessage`, `agentRunId`, `conversationId`, `tokensIn/Out`, `iterations`, `truncated`, `statusId`, `servedByModelId`, `providerName`, `providerRunId`, `structuredResult`, `estimatedCost`, **`awaitingApproval`**, **`approvalIds`**); `store#AiAgent` (**create-time defaults provider/model from `ai_default_provider`/`ai_default_model` → fallback `openai`/`gpt-4o-mini`, `maxIterations`→5, `systemPrompt`→description; defaults `AI_AGENT_DRAFT`; unique-name enforcement; create-only**; captures naming signal); `store#AiAgentTool` (explicit, exposable+active gated grant); `create#Conversation`.
  - **ToolServices:** `store#AiTool` (derives `effectEnumId` from the service verb; denylist/exposable safety floor; explicit create/update branches preserving `createdByUserId`; inline `refreshCatalog()`; naming-signal capture).
  - **ComposerServices:** `find#Capability` (**tokenizes the query, matches ANY term across toolName/verb/noun/description, ranks by hit count** — only exposable+active tools), `describe#Capability`, `list#DomainTerm` (catalog-noun contract; delegates to glossary), `propose#Naming` (snaps to canonical glossary term; model `gpt-4o-mini`/`claude-3-5-haiku-latest`), `preview#Agent` (→ `runPreview`, force-gates mutating tools), `activate#Agent` (requires approval), `set#Guardrail`, `request#Capability`, `discard#Draft` (deletes preview runs + held approvals).
  - **ApprovalServices:** `approve#`/`reject#`/`decide#ToolCall` (all `transaction="ignore"` — resume must hold no enclosing tx), `get#PendingApproval` (**excludes preview-run approvals**).
  - **CostServices:** `store#AiModelPrice`, `get#AiSpend` (Groovy aggregation over `AiAgentRun`, priced off the **served** model).
  - **FactServices:** `remember#Fact`.
  - **GlossaryServices:** `find#DomainTerm` (lexical rank + conservative near-match), `seed#DomainGlossary`, `propose#Naming`, `capture#NamingSignal`, `promote#TermsFromSignals`, `store#`/`approve#`/`reject#DomainTerm`, `list#DomainTerm` (ranked-term shape — distinct from the Composer's `list#DomainTerm`).
  - A "REST conventions" note (store/create/update verbs; framework handles JSON-array bulk loop).
- [ ] **Step 2: Verify** each service's params + behavior against the cited service file. Fix mismatches.
- [ ] **Step 3: Commit** `git add docs/reference/services.md && git commit -m "docs(ai): reference — service catalog"`.

---

### Task 3: `reference/screens.md`

**Files:** Create `docs/reference/screens.md`. Derive from + verify against `screen/AiOps.xml`, every `screen/AiOps/*.xml`, `screen/includes/AiRunTrace.xml`.

- [ ] **Step 1: Write the doc.** List the mount (`/apps/AiOps`, `require-authentication`, v1 security note) and **all 8 subscreens** in menu order with their purpose, key transitions, and backing services:
  - **Playground** (currently NotNaked-hardwired: `notnaked.OmsAiServices.run#OmsAssistant`), **Composer** (chat + live draft panel + preview pane; `send`/`preview`/`discard` → ComposerServices + create#Conversation/run#Agent; accepts `draftAgentId` param), **Agents** (authoring; `saveAgent` → `store#AiAgent`; tool-grant list), **Runs** + **RunDetail** (summary + conversation + error widgets + `AiRunTrace` include; drill-in sibling), **Approvals** (queue; approve/reject; row links to RunDetail), **Cost** (`get#AiSpend` + filters), **Conversations**, **Glossary** (curation: approve/reject/addTerm/seed/promote → GlossaryServices).
- [ ] **Step 2: Verify** each screen's existence, transitions, and backing services against the cited files (the drift report flags that Composer/Agents/Glossary/RunDetail were undocumented and Playground is NotNaked-specific — confirm).
- [ ] **Step 3: Commit** `git add docs/reference/screens.md && git commit -m "docs(ai): reference — AI Ops console screens"`.

---

### Task 4: `reference/configuration.md`

**Files:** Create `docs/reference/configuration.md`. Derive from + verify against `MoquiConf.xml`, the component `dev.env` (names only — never print secret values), `build.gradle`, `.gitignore`, `data/*.xml`, `src/main/groovy/org/moqui/ai/AiToolFactory.groovy` (the `prop()` resolution).

- [ ] **Step 1: Write the doc.** Cover, accurately:
  - **MoquiConf default-properties:** `ai_openai_key`/`ai_openai_base_url`, `ai_anthropic_key`/`ai_anthropic_base_url`/`ai_anthropic_version`, `ai_timeout_seconds`, **`ai_default_provider`/`ai_default_model`** (defaults for newly drafted agents); the `AiToolFactory` tool-factory registration; the AiOps screen mount under `apps`.
  - **Secrets / keys:** resolution order is system property → environment variable (`AiToolFactory.prop()`); keys live in the component `dev.env` as `export ai_openai_key=…` and must be **sourced into the shell before launch** (e.g. `source runtime/component/moqui-ai/dev.env`) — nothing auto-loads it; use `--no-daemon` so a stale Gradle daemon env doesn't swallow them.
  - **Deploy hygiene:** the runtime loads compiled classes from `lib/*.jar`; `build.gradle`'s **`refreshLibJar`** mirrors the built jar into `lib/` on every build (`jar.finalizedBy`); `lib/moqui-ai.jar` is gitignored. Document the pitfall: a stale `lib/` jar once shadowed current classes and caused `Unknown agent`.
  - **Data & reader types:** which `data/*.xml` are `ext-seed` (AiComposerData, AiGlossaryData) vs `install` (AiSecurityData) vs status data; the `AiGlossaryJobData.xml` cron `ServiceJob` (auto-seeds the glossary); the load-once model — to change deployed envs, add an upgrade step (not a `data/` edit).
- [ ] **Step 2: Verify** each property/task/data-file against the cited sources. Confirm no secret values are printed.
- [ ] **Step 3: Commit** `git add docs/reference/configuration.md && git commit -m "docs(ai): reference — configuration, secrets, deploy hygiene"`.

---

### Task 5: `explanation/architecture.md`

**Files:** Create `docs/explanation/architecture.md`. Derive from `src/main/groovy/org/moqui/ai/*.groovy` (+ provider/) and the gap-report; verify behavioral claims against `AgentRunner.groovy`.

- [ ] **Step 1: Write the doc.** Explain (prose + small diagrams), not exhaustively list:
  - **The agentic loop** — `AgentRunner`: assemble context → provider `chat()` → dispatch tool calls → repeat to `maxIterations`/`maxToolCallsPerTurn`; per-step `AiAgentRun`/`AiAgentRunStep`/`AiToolCall` audit; `estimatedCost` stamped off the served model.
  - **Providers & structured output** — `LlmProvider`/`AbstractLlmProvider`/OpenAI/Anthropic/Mock; `ToolSchemaBuilder`; `responseSchema`→`structuredResult`; `reasoningEffort` (OpenAI `reasoning_effort`; Anthropic thinking-budget, no-tools-only).
  - **Multi-provider failover** — `AiAgentModel` chain, sticky `callWithFailover`, `llm_call_failed` steps, served-model persistence.
  - **Context management** — `window` vs `summarize` (`ContextAssembler`), pinned `AiConversationFact`, the server-injected `remember` tool (writes an `AiToolCall`).
  - **Human approval gate & preview** — suspend → `pendingState` → `resume()` (fail-closed `anyUndecided` guard); `runPreview`/`forceApprovalOnMutating`/`isPreview`.
  - **The registry model** — opaque ids, `verb_noun` wire names, DB-backed catalog (`AiToolFactory`/`DefinitionLoader`), the `exposable` + denylist safety floor.
  - **The Composer** — the framework pointed at itself (its tools are authoring meta-services); draft → preview → approve-to-activate.
  - **The Builder Knowledgebase** — glossary grounding for naming; naming-signal capture → promote → approve loop.
- [ ] **Step 2: Verify** the load-bearing behavioral claims (failover, suspend/resume, preview gate, compaction watermark) against `AgentRunner.groovy`.
- [ ] **Step 3: Commit** `git add docs/explanation/architecture.md && git commit -m "docs(ai): explanation — architecture"`.

---

### Task 6: `explanation/security-model.md`

**Files:** Create `docs/explanation/security-model.md`. Derive from + verify against `data/AiSecurityData.xml`, the screens' `require-authentication`, and `AgentRunner`/`ComposerServices` (agent-runs-as-user, the gates).

- [ ] **Step 1: Write the doc.** Cover, accurately:
  - **v1 posture:** screens are `require-authentication="true"` (any logged-in user); the dedicated `AI_OPERATOR` group + `ArtifactAuthz` gating is **deferred**.
  - **The membership mechanism (load-bearing):** this framework version's `ArtifactAuthz` has **no `inheritAuthz` column**, so authz does **not** auto-cascade — every entity/service the screens touch must be an explicit `ArtifactGroupMember`. `AI_OPS_SCREENS` therefore lists screens, transitions, `moqui.ai.*`, `moqui.basic.Status.*`, **`moqui.screen.form.*`** (form-list rendering), `ai.*`, `notnaked.OmsAiServices.*`. A separate **VIEW-only `AI_OPS_DATA_READ`** grants `org.apache.ofbiz.order.*`.
  - **Agent runs as the signed-in user:** an agent inherits the operator's data access — it cannot read what the user can't (demonstrated live: the order tool was denied until the order-read grant existed). This is a feature, not a bug.
  - **The gates:** mutating tools can require approval (`requiresApproval` / `requiresApprovalOverride`); preview force-gates all mutating tools; activation requires human approval.
  - **Known v1 gap:** the order-read grant is `ALL_USERS` for the demo — scope to an operator group for production.
- [ ] **Step 2: Verify** the grant members against `AiSecurityData.xml`; confirm the no-`inheritAuthz` claim against the committed comment.
- [ ] **Step 3: Commit** `git add docs/explanation/security-model.md && git commit -m "docs(ai): explanation — security/authz model"`.

---

### Task 7: `explanation/decisions.md`

**Files:** Create `docs/explanation/decisions.md`. Distill from `specs/2026-06-03-enterprise-decisions-gap-report.md` (rated accurate) + `decisions/0001-context-window-management.md` + the registry/composer specs; verify "built vs not" against code.

- [ ] **Step 1: Write the doc.** A concise decision register (decision → rationale → status), covering: opaque ids over service-FQN keys; safety = effect-derived-from-service + denylist floor; activation requires human approval; context phases 1-2 shipped (window + compaction), **layer-4 tool-result-clearing + Phase-6 RAG deferred**; cost stamped off served model, **`maxCost` enforcement deferred**; multi-provider sticky failover; reasoning-effort (Anthropic limited to no-tools agents in v1); **Responses-API migration declined**; providers shipped = OpenAI/Anthropic/Mock (**no Google/Gemini**); `AiAgentKnowledge` never built. Link each to the relevant spec/ADR.
- [ ] **Step 2: Verify** each "deferred/declined/not-built" against code (e.g. grep confirms no `GoogleProvider`, `maxCost` unread, no `ResponsesProvider`).
- [ ] **Step 3: Commit** `git add docs/explanation/decisions.md && git commit -m "docs(ai): explanation — decision record"`.

---

### Task 8: `README.md` (write last — it links everything)

**Files:** Create `docs/README.md`. Depends on Tasks 1-7 existing (for links).

- [ ] **Step 1: Write the doc.** Cover: one-paragraph "what moqui-ai is" (Moqui-native AI agent framework + the Composer agent-builder); a **feature map** (loop, providers, structured output, reasoning, failover, conversations, context window/compaction, cost, approval gate, operational UI, registry keystone, Composer, Knowledgebase); a **quick start** (set keys in `dev.env` + source it; `gradlew load`; `gradlew --no-daemon run`; open `/apps/AiOps`); a **doc map** with links to `reference/`, `explanation/`, `specs/` (design history), `decisions/`, `archive/`; and a **status** line (shipped v1 + the deferred list).
- [ ] **Step 2: Verify** every internal link resolves and the feature map matches what `reference/` actually documents.
- [ ] **Step 3: Commit** `git add docs/README.md && git commit -m "docs(ai): README entry point + feature map + doc map"`.

---

### Task 9: Reconcile `specs/2026-06-02-ai-agent-framework-design.md`

**Files:** Modify `docs/specs/2026-06-02-ai-agent-framework-design.md`.

- [ ] **Step 1:** Add a top banner: `> Reconciled 2026-06-08 — design history; canonical as-built state is docs/reference/ + docs/explanation/.` Then fix the un-flagged drift: §5 `LlmProvider` contract (add `responseSchema`/`reasoning` request fields, `providerRunId`/`structuredResult` response fields); §9 `AiAgentRunStep.stepType` list (all five values); add `AiAgentModel` to the §9/§16 entity inventory; §11 `run#Agent` out-params (add `estimatedCost`/`awaitingApproval`/`approvalIds`).
- [ ] **Step 2: Verify** the edits against `LlmProvider.groovy`, `AiEntities.xml`, `AgentServices.xml`.
- [ ] **Step 3: Commit** `git add … && git commit -m "docs(ai): reconcile framework-design spec with as-built"`.

---

### Task 10: Reconcile `specs/2026-06-05-agent-tool-registry-design.md`

**Files:** Modify `docs/specs/2026-06-05-agent-tool-registry-design.md`.

- [ ] **Step 1:** Change Status `Draft — under review` → `Shipped (reconciled 2026-06-08)` + the reference banner. Fix: §4.2 no longer says provider/model/maxIterations/systemPrompt are "unchanged" — note `store#AiAgent` **defaults them on create**; document the `suggestedName`/`intentText` + `capture#NamingSignal` seam on the store services; note `grant_capability` is the **explicit** `store#AiAgentTool` wrapper (not entity-auto).
- [ ] **Step 2: Verify** against `AgentServices.xml`/`ToolServices.xml`.
- [ ] **Step 3: Commit** `git add … && git commit -m "docs(ai): reconcile registry-keystone spec (Shipped) with as-built"`.

---

### Task 11: Reconcile `specs/2026-06-05-composer-assistant-moqui-design.md`

**Files:** Modify `docs/specs/2026-06-05-composer-assistant-moqui-design.md`.

- [ ] **Step 1:** Add the reference banner. Update the `find#Capability` description to the **tokenized + ranked** matching; note the concrete `propose#Naming` model ids; correct the `grant_capability` description to the explicit exposable-gated wrapper.
- [ ] **Step 2: Verify** against `ComposerServices.xml`.
- [ ] **Step 3: Commit** `git add … && git commit -m "docs(ai): reconcile composer-assistant spec with as-built"`.

---

### Task 12: Reconcile `specs/2026-06-05-builder-knowledgebase-design.md`

**Files:** Modify `docs/specs/2026-06-05-builder-knowledgebase-design.md`.

- [ ] **Step 1:** Add the reference banner. Fix §6 from "single hook" to the shipped **two-part mechanism** (in-service `capture#NamingSignal` + the `AiGlossaryEcas` EECA floor, auto-scanned — no MoquiConf registration); note the `AiGlossaryJobData` cron `ServiceJob` as the install-time seed.
- [ ] **Step 2: Verify** against `GlossaryServices.xml`, `AiGlossaryEcas.eecas.xml`, `AiGlossaryJobData.xml`.
- [ ] **Step 3: Commit** `git add … && git commit -m "docs(ai): reconcile builder-knowledgebase spec with as-built"`.

---

### Task 13: Archive the plans

**Files:** Move all 18 `docs/plans/*.md` → `docs/archive/plans/`.

- [ ] **Step 1:** `git mv docs/plans/*.md docs/archive/plans/` (preserves history); remove the now-empty `docs/plans/` if git leaves it.
- [ ] **Step 2: Verify** `docs/plans/` is empty/gone and all 18 files are under `docs/archive/plans/`; confirm `docs/README.md`'s doc-map link points at `archive/plans/`.
- [ ] **Step 3: Commit** `git add -A docs/plans docs/archive && git commit -m "docs(ai): archive 18 historical implementation plans"`.

---

### Task 14: Verify the whole doc set against the code (final pass)

**Files:** none (read-only check).

- [ ] **Step 1:** Re-audit the **new** docs (`README`, `reference/*`, `explanation/*`) and the **reconciled** specs against the code — for each factual claim, confirm the cited source matches. (Parallelizable: one verifier subagent per reference/explanation file, mirroring the original 5-way audit. The verifier is read-only and reports any remaining drift.)
- [ ] **Step 2:** Fix any drift the verifier finds (code wins), committing per fix.
- [ ] **Step 3:** Confirm zero remaining drift before shipping.

---

### Task 15: Ship

**Files:** none (git/gh).

- [ ] **Step 1:** Create a tracking issue in `hotwax/moqui-ai` ("Reconcile moqui-ai documentation with as-built code") referencing the drift report.
- [ ] **Step 2:** Create a branch at the current HEAD **without switching the shared working copy** (`git branch docs/reconcile-as-built HEAD`), push it.
- [ ] **Step 3:** Open a PR (base `main`, head the docs branch, "Closes #<issue>"); confirm `MERGEABLE`/`CLEAN`.
- [ ] **Step 4:** Merge (rebase) to `main`; delete the merged branch.

---

## Self-Review

**1. Spec coverage (against the drift report):**
- Every UNDOCUMENTED item maps to a reference/explanation task: today's hardening (find#Capability → Task 2/11; store#AiAgent defaults → Task 2/10; authz model → Task 6; deploy hygiene + `ai_default_*` → Task 4); preview mode/remember/compaction/resume-guard → Task 5; undocumented screens → Task 3; naming-signal seam → Task 2/10/12; glossary ServiceJob → Task 4/12. ✓
- Every DRIFTED spec has a reconcile task (Tasks 9-12). ✓
- The 18 plans are archived (Task 13). ✓
- "Not built" items captured in the decision record (Task 7). ✓
- The pervasive `agentName→agentId` PK drift is corrected wherever specs are edited (Tasks 9-12) and stated correctly in `reference/entities.md` (Task 1). ✓

**2. Placeholder scan:** each doc task names its source files and the concrete must-cover facts (no "document X" / "TBD"). The full prose is produced at execution, but the required content is enumerated. ✓

**3. Consistency:** entity/status names (`AiAgentStatus`, `AiToolEffect`, `Ai_RUN_SUSPENDED`, etc.), service names, and screen names are used identically across Tasks 1-8 and the reconcile tasks. ✓

**Open choice for the reviewer:** Tasks 1-8 can run as parallel subagents (fast) or inline (more control). Recommended: subagent-driven, one file per task, with the drift report + cited source files handed to each.
