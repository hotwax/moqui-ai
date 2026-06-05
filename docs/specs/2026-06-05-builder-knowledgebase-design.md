# Builder Knowledgebase / Domain Glossary — Design Spec

> The builder's "brain" — how the Composer Assistant *knows your business* and *gets better over
> time*. It is the soft-control source for naming ("control, but not too much"): suggestions are
> drawn from a curated, OMS-grounded glossary that grows, rather than from a fixed vocabulary.
>
> Sits on the registry keystone (`docs/specs/2026-06-05-agent-tool-registry-design.md`) and is
> consumed by the Composer Assistant (`docs/specs/2026-06-05-composer-assistant-moqui-design.md`).
> It is the inward-facing first use of the knowledge-retrieval direction (Phase 6 backlog).

- **Date:** 2026-06-05
- **Status:** Draft design — authored autonomously; **needs your review** (see §3 calls I made for you)
- **Component:** `moqui-ai` (branch `feature/ai-agent-framework`)
- **Platform:** HotWax fork of Moqui, **JDK 11**

---

## 0. Calls I made on your behalf (review these first)

You delegated this one, so here are the decisions baked in — flip any and I'll revise:
- **Scope is the *authoring* glossary**, not general RAG-over-business-documents. It exists to name and
  ground *agents and tools* well. (A general doc-QA RAG can reuse the same retrieval substrate later.)
- **v1 retrieval is lexical** (term + synonym matching), not embeddings. Embeddings/semantic search are
  a clean later upgrade (ties to the Phase 6 backlog). Rationale: ship the loop now; the LLM in the
  Composer already adds semantic flexibility on top.
- **Learning is suggest-only.** Authoring signals propose glossary additions; a human (Curator)
  approves them. Nothing auto-enters the approved glossary. (Dogfoods the approval gate.)
- **Single-tenant v1**, with an `ownerScope` field reserved so per-deployment/tenant glossaries drop
  in later without a re-model.

## 1. Purpose

When the Composer Assistant proposes a name, a description, or which capability fits an intent, those
suggestions must speak the **OMS domain** and the **specific deployment's dialect** — not generic
SaaS-speak. This spec defines the knowledgebase that supplies that grounding and improves it as people
use the builder: it seeds from what we already know (the OMS ontology), learns each deployment's words
from real authoring, and is curated so quality compounds.

## 2. Scope

**In:** the glossary data model; seeding from the OMS ontology; capturing naming signals from
authoring; lexical retrieval backing the Composer's `propose#Naming` / `list#DomainTerm`; the curation
loop (suggest → approve) + a Glossary screen.

**Out (separate specs / later):** the Composer Assistant itself; the registry keystone; embeddings /
semantic retrieval; general RAG over business documents; cross-deployment learning.

## 3. Decisions

- **D1 — Glossary = terms + synonyms, typed NOUN/VERB.** A small, curated vocabulary of domain nouns
  (`order`, `return`, `shipment`, `facility`) and capability verbs (`list`, `cancel`, `allocate`,
  `refund`), each with synonyms (the deployment's dialect: "return" ⇆ "RMA").
- **D2 — Three provenances:** `SEEDED` (from the ontology), `LEARNED` (from authoring signals),
  `CURATED` (human-edited). All gated by a `SUGGESTED → APPROVED` status; only APPROVED terms are
  served.
- **D3 — Learn from authoring, don't guess in a vacuum.** Every name the Composer proposes vs. what
  the human finally chose is logged as a signal; frequent chosen terms get *proposed* into the
  glossary for approval.
- **D4 — Retrieval is lexical in v1** (match on term + synonyms, filter by kind + APPROVED, rank by
  match strength × usage). Embeddings later.
- **D5 — The Composer consumes it through two tools** (defined in the Composer spec): `list#DomainTerm`
  and `propose#Naming`. This spec provides their backing services so the Composer's stubs become real.

## 4. Data model

All entities `package="moqui.ai"`.

### 4.1 `AiDomainTerm` (the glossary)

| Field | Type | Role |
|---|---|---|
| `termId` | `id` (PK) | identity |
| `term` | `text-short` | canonical term (`order`, `cancel`) — unique per `termKind` + scope |
| `termKind` | `id` | `AI_TERM_NOUN` \| `AI_TERM_VERB` |
| `description` | `text-long` | what it means in this business |
| `sourceType` | `id` | `AI_TSRC_SEEDED` \| `AI_TSRC_LEARNED` \| `AI_TSRC_CURATED` |
| `statusId` | `id` | `AI_TERM_SUGGESTED` \| `AI_TERM_APPROVED` \| `AI_TERM_REJECTED` |
| `usageCount` | `number-integer` | reinforcement (chosen-in-authoring count) |
| `ownerScope` | `id` | reserved for tenant/deployment scope (null = global in v1) |

### 4.2 `AiTermSynonym` (the dialect)

| Field | Type | Role |
|---|---|---|
| `termId` | `id` (PK) | the canonical term |
| `synonym` | `text-short` (PK) | an alias ("RMA" → `return`) |
| `sourceType`, `statusId` | `id` | same vocab as the term |

### 4.3 `AiNamingSignal` (the learning log)

| Field | Type | Role |
|---|---|---|
| `signalId` | `id` (PK) | identity |
| `signalType` | `id` | `AI_SIG_TOOL_NAME` \| `AI_SIG_AGENT_NAME` |
| `intentText` | `text-long` | the user's described intent / the backing service |
| `suggestedName` | `text-medium` | what the Composer proposed |
| `chosenName` | `text-medium` | what the human kept |
| `wasOverridden` | `text-indicator` | Y if chosen ≠ suggested |
| `userId` | `id` | who |
| `fromDate` | `date-time` | when |

### 4.4 Statuses / enums (seed data)

- `StatusItem` `AiDomainTermStatus`: `AI_TERM_SUGGESTED`, `AI_TERM_APPROVED`, `AI_TERM_REJECTED`.
- Enumerations: term kind (`AI_TERM_NOUN`/`AI_TERM_VERB`), source (`AI_TSRC_*`), signal type
  (`AI_SIG_*`). (Enumeration archetype per the UDM guide — fixed, code-referenced types.)

## 5. Seeding — narrow to our ecosystem

A `seed#DomainGlossary` service builds the starting glossary from what we already have, so the builder
speaks OMS out of the box:
- **Nouns** ← the deployment's entity model (domain objects: order, return, shipment, facility, item,
  inventory, …) + key concepts from the **UDM domain-practices guide**.
- **Verbs** ← the verbs of existing exposable services in the catalog (`list`, `get`, `cancel`,
  `allocate`, `refund`, …) — a soft, observed vocabulary, not a hard enum.
Seeded terms are `SEEDED` + `APPROVED`. Run on component install (as data/service) and re-runnable to
absorb new entities/services.

## 6. Learning — from real authoring

- When the Composer (or a human) creates/updates a tool or agent, an **`AiNamingSignal`** is written
  (hook on `store#AiTool` / `store#AiAgent` — the same single gate the keystone defines).
- A `promote#TermsFromSignals` service periodically scans signals: chosen terms/synonyms that recur
  above a threshold and aren't already in the glossary are inserted as `LEARNED` + **`SUGGESTED`**.
- Overrides (`wasOverridden=Y`) are the richest signal — they reveal the business's preferred word
  (e.g. repeatedly renaming `list_returns` → `list_rmas` teaches the "RMA" synonym).

## 7. Retrieval — backing the Composer

`find#DomainTerm(text, kind?)`: lexical match of `text` tokens against `term` + `AiTermSynonym`,
filtered to `APPROVED` (+ scope), ranked by match strength × `usageCount`. Backs:
- the Composer's **`list#DomainTerm`** (return the relevant glossary slice for grounding), and
- **`propose#Naming`** (the Composer's LLM proposes verb/noun, then snaps to the nearest approved
  terms/synonyms — grounded suggestion, human still edits).

## 8. Curation — how it gets better

- `store#DomainTerm` / `approve#DomainTerm` / `reject#DomainTerm` services + a **Glossary** tab in the
  AI Ops console: review `SUGGESTED` terms, approve/correct, merge synonyms.
- The Composer may *propose* a new term as part of a build; proposals enter `SUGGESTED` and surface in
  the same approval flow (dogfoods the approval gate again).
- Reinforcement: approved terms gain `usageCount` as they're chosen, so good names rise; stale/rejected
  ones fall. The glossary converges on the deployment's real language over time.

## 9. v1 vs. later

- **v1:** entities + seeding + signal capture + `find#DomainTerm` (lexical) + `promote#TermsFromSignals`
  + curation services/screen. The Composer's naming stubs become real.
- **Later:** embeddings/semantic retrieval (Phase 6), auto-promote tuning, per-tenant glossaries,
  import/export, and reuse of the retrieval substrate for general business-document RAG.

## 10. Testing

- **Unit:** `seed#DomainGlossary` derives nouns/verbs from the model; a `store#AiTool` override writes
  an `AiNamingSignal`; `promote#TermsFromSignals` inserts SUGGESTED terms past threshold;
  `find#DomainTerm` ranks by match × usage and excludes non-APPROVED.
- **Integration:** author a tool with an overridden name → signal logged → promote → Curator approves
  the synonym → a later `propose#Naming` for a similar intent reflects the learned word.

## 11. Boundaries & open questions

- **Boundary:** authoring glossary, not document QA. Shares retrieval substrate with a future general
  RAG, but scope here is naming/grounding for agents + tools.
- **Open:** (1) embeddings in v1 or strictly later; (2) promote threshold + whether any auto-approval is
  ever allowed; (3) tenant scoping rollout; (4) how much the Composer's `propose#Naming` leans on the
  LLM vs. the glossary before enough is learned.
