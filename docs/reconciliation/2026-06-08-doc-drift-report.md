# moqui-ai Documentation Drift Report

- **Date:** 2026-06-08
- **Method:** 5 parallel read-only audits (one per feature cluster). The **code is the source of truth**; every file in `docs/` was compared against the shipped `service/`, `entity/`, `screen/`, `src/main/groovy/`, `data/`, `MoquiConf.xml`, and `build.gradle`.
- **Purpose:** ground the documentation reconciliation (hybrid scope — build a current source-of-truth, fix spec drift, archive the plans).

---

## Executive summary

1. **The 18 plans in `docs/plans/` are historical build-logs.** Most are self-marked SHIPPED/SUPERSEDED and embed *pre-refactor* code and entity definitions. Accurate as history, misleading as a current reference.
2. **The 6 specs are mostly salvageable but drifted.** The single largest mechanical drift, present in nearly every doc, is the registry keystone's **`agentName`/`toolName` → opaque `agentId`/`toolId` PK migration**.
3. **The highest-value docs don't exist yet.** No README/entry point, no as-built reference, and a large amount of shipped behavior is undocumented — especially recent and cross-cutting work (authz model, deploy hygiene, preview mode, the demo-hardening fixes).

## Per-doc verdict → action

| Doc | Verdict | Action |
|---|---|---|
| `specs/2026-06-03-enterprise-decisions-gap-report.md` | accurate (authoritative decision record) | **keep** → seed `explanation/decisions` |
| `decisions/0001-context-window-management.md` (ADR) | accurate (layers 4 & Phase-6 unbuilt, by design) | **keep** |
| `product/composer-assistant-overview.md` | accurate / evergreen | **keep** |
| `specs/2026-06-02-ai-agent-framework-design.md` | minor drift (§5 interface, §9 step types, §11 out-params, missing `AiAgentModel`) | **reconcile** |
| `specs/2026-06-05-agent-tool-registry-design.md` | major drift (status still "Draft"; misses store#AiAgent defaults + naming seam) | **reconcile + mark Shipped** |
| `specs/2026-06-05-composer-assistant-moqui-design.md` | minor drift (find#Capability tokenize; grant wrapper) | **reconcile** |
| `specs/2026-06-05-builder-knowledgebase-design.md` | minor drift (§6 "single hook" framing) | **reconcile** |
| `specs/2026-06-03-responses-api-migration-audit.md` | closed/declined (already banner'd; stale entity table) | **keep as-is** |
| `plans/` (all 18) | historical build-logs | **move to `docs/archive/plans/`** |

---

## DRIFTED — doc claims that no longer match the code

**Core loop / providers / observability**
- `specs/…ai-agent-framework-design.md` §5: the `LlmProvider` request/response contract is stale — request now also carries `responseSchema` + `reasoning`; response also carries `providerRunId` + `structuredResult`.
- §9 observability table: `AiAgentRunStep.stepType` lists only `llm_call`/`tool_call`; code writes five (`llm_call`, `tool_call`, `llm_call_failed`, `context_trim`, `compaction`).
- §9/§16: `AiAgentModel` (failover candidates) is missing from the entity inventory.
- §11: `run#Agent` out-params omit `estimatedCost`, `awaitingApproval`, `approvalIds`.
- `plans/…openai-provider` (+ responses-audit, phase1): `AbstractLlmProvider.sanitizeName` described as a load-bearing FQN translator; it is now a defensive no-op (wire names are `verb_noun`).
- `plans/…anthropic-fix`: `servedByModelId` "= modelName until fallback exists" is stale (fallback shipped; seeded from the chain primary, updated to the served candidate); `applyStructured` now preserves co-emitted business tool calls.

**Conversations / context / cost**
- `plans/…phase2-conversations`: keyed by `agentName` (now `agentId`); `run(agentName,…)` (now `run(agentId,…)`, `useCache(false)`); `conversationId` added via `<extend-entity>` (shipped directly on `AiAgentRun` — approach **inverted**); `create#Conversation` param/target field differs.
- `plans/…phase3-cost-awareness`: built on an `AiSpendSummary` **view-entity that was never built** (shipped `get#AiSpend` aggregates in Groovy); prices off the configured agent model (shipped prices off the **served** model post-fallback).
- All four context/cost plans: precise "currently line NN" edit anchors are stale (file restructured: `continueAgent`, `resume`, `withRememberTool`, `runPreview`).

**Approval gate / UI**
- `plans/…human-approval-gate` (the shipped one): `get#PendingApproval` lacks the shipped **preview-run exclusion**; all three approval services lack the shipped **`transaction="ignore"`**; the gate omits the **preview/`forceApprovalOnMutating`/`AI_TOOL_MUTATING`** path; `resume()` omits the shipped **fail-closed `anyUndecided` guard**; `getTool()` renamed `getToolByName()`.
- `plans/…operational-ui` (the shipped one): claims **five tabs**, shipped has **eight** (adds Composer, Agents, Glossary); Playground specced generic but shipped **NotNaked-hardwired**; `apps` mount + restart narrative uncorroborated; delegates most screen specs to a SUPERSEDED doc.

**Registry / Composer**
- `find#Capability`: **tokenized + ranked** matching shipped; composer plan still shows the abandoned whole-phrase `like '%query%'`. **Undocumented in specs.**
- `store#AiAgent`: **defaults a runnable provider/model/maxIterations/systemPrompt on create**; registry spec §4.2 lists those fields as "**unchanged**" and the keystone plan defaults only `statusId`. **Undocumented + contradicted.**
- `grant_capability` seed/doc says entity-auto `store#moqui.ai.AiAgentTool`; shipped is an **explicit** exposable-gated `ai.AgentServices.store#AiAgentTool` wrapper.
- `run#Agent` agentName resolution: plan `useCache(true)`, shipped `useCache(false)`.

**Knowledgebase / glossary**
- `plans/…builder-knowledgebase`: prescribes a `MoquiConf.xml` `<load-entity>` EECA registration **not used** (auto-scan of `entity/*.eecas.xml` ships instead); `store#DomainTerm` update would clobber unset fields (shipped guards against it); `find#DomainTerm` synonym match adds a near-match branch; menu-index 7 (shipped 8).
- `specs/…builder-knowledgebase-design.md` §6: "single hook on store#…" — shipped is a **two-part** in-service hook + EECA floor.

## UNDOCUMENTED — built, no doc coverage

- **Today's hardening (no doc anywhere):** `find#Capability` tokenized/ranked search; `store#AiAgent` runnable-defaults; the **authz/membership model** — `AI_OPS_SCREENS` grant + members (`moqui.ai.*`, `moqui.basic.Status.*`, **`moqui.screen.form.*`** for form-list rendering, `ai.*`, `notnaked.OmsAiServices.*`), the separate **VIEW-only `AI_OPS_DATA_READ`** over `org.apache.ofbiz.order.*`, and the load-bearing fact that **this framework version has no `inheritAuthz` column → group membership is the mechanism**; **deploy hygiene** (`build.gradle` `refreshLibJar` mirrors the jar into `lib/`; `lib/moqui-ai.jar` gitignored); **`ai_default_provider`/`ai_default_model`** config.
- **Preview mode:** `AgentRunner.runPreview` / `forceApprovalOnMutating` / `AiAgentRun.isPreview` (preview suspends every mutating tool; excluded from the operator queue; deleted by `discard#Draft`).
- **remember tool loop:** `withRememberTool`/`rememberFact` inject a `remember` tool for `window`+`summarize` strategies, write an `AiToolCall` audit row, are approval-gateable, survive suspend/resume.
- **Compaction:** `summarizeOverflow` (uses the primary model, best-effort, falls back to windowing, once-per-run watermark guard); its tokens fold silently into `estimatedCost`/spend.
- **resume() fail-closed `anyUndecided` guard.**
- **Screens never documented:** `Composer.xml`, `Agents.xml` (authoring — both UI plans filed this under "NOT in this phase"), `Glossary.xml`, the richer `RunDetail.xml` (conversation + error widgets), `AiRunTrace.xml`; Playground is NotNaked-hardwired; Cost breakdown columns differ; Approvals rows link to RunDetail.
- **Naming-signal seam:** `store#AiTool`/`store#AiAgent` accept `suggestedName`/`intentText` and call `capture#NamingSignal` (+ `signalCaptured` guard); the EECA floor.
- **`AiGlossaryJobData.xml`** auto-seed `ServiceJob` (cron) — shipped but left as an "open question" in the plan.
- Per-run **`estimatedCost` stamping** off the served model; explicit `store#AiTool` create/update branching (preserves `createdByUserId`) + inline catalog refresh.

## Correctly recorded as NOT BUILT (no action; listed for completeness)

Responses API migration (closed/declined); Google/Gemini provider; streaming; **`maxCost` enforcement** (field + pass-through param exist, never read); `AiAgentKnowledge`; ADR layer-4 tool-result clearing + Phase-6 RAG; Anthropic reasoning+tools.

## Cross-cutting note

Every audited plan/spec entity snippet uses the old **`agentName`/`toolName` PKs**; shipped entities use opaque **`agentId`/`toolId`** (the registry keystone). This is the single largest, most pervasive divergence between the docs and the code.
