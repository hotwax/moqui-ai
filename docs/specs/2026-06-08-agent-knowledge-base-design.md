# Agent Knowledge Base — Design Spec

> An agent's "working knowledge" — a curated, operator-managed body of domain facts/policies
> an agent draws on **to do its job**, assigned per-agent and injected into the system prompt.
>
> **Relationship to the glossary (sibling, not a separate category):** the glossary
> ("Builder Knowledgebase," `AiDomainTerm`/`AiTermSynonym`) and this Agent Knowledge Base are
> **two kinds of agent knowledge** — they differ by *content shape + how they're consumed*, not
> by *who uses them* (the Composer/builder is itself just an agent). The glossary is a
> **structured controlled vocabulary**, tool-queried for *naming*; the Agent KB is **prose**,
> always-injected for *answering/acting*. Governance is identical; they likely converge at the
> `search#Knowledge` retrieval layer (§13). Also distinct from *pinned conversation facts*
> (`AiConversationFact`, per-conversation, agent-written at runtime).
>
> Content is stored the **Moqui-native way** — bodies live as `.md` files in the component's
> `knowledge/` folder, read via `ec.resource.getLocationText("component://moqui-ai/knowledge/…")`
> (the same `ResourceFacade` path used for screens and services); a thin `moqui.ai` entity is the
> queryable spine (assignment + lifecycle metadata + a pointer to the file). Nothing is duplicated.

- **Date:** 2026-06-08
- **Status:** Draft design — ready for implementation planning (pending your review)
- **Component:** `moqui-ai` (branch `feature/ai-agent-framework`)
- **Platform:** HotWax fork of Moqui, **JDK 11**
- **First consumer:** the **Composer** (builder) agent — see §10. The mechanism is agent-agnostic;
  operational agents (CS/returns, ops) follow.
- **Supersedes:** the never-built `AiAgentKnowledge` entity + Phase-6 RAG slice
  (`docs/plans/2026-06-02-phase6-knowledge-retrieval.md`, "NOT IMPLEMENTED") for the
  curated-facts tier. Semantic/RAG retrieval remains a clean later upgrade (§13).

---

## 0. Decisions locked in this brainstorm

- **D-A — Content is curated facts & policies.** Short, exact, human-written, not long
  documents ⇒ always-injected; **no embeddings, no vector index, no ElasticSearch dependency.**
- **D-B — Shared library + grants.** Knowledge lives in one curated catalog of **topics**; an
  agent is **granted topics**. Mirrors the `AiTool` + `AiAgentTool` model.
- **D-C — Always-inject the granted set.** At run start, the granted + approved + effective
  topics are prepended to the system context (the path pinned facts already take through
  `ContextAssembler`). Deterministic; the agent always "knows" its topics.
- **D-D — Component-bundled filesystem files; entity is the queryable spine.** The body lives as
  a `.md` file in `knowledge/` under the component root, read via
  `ec.resource.getLocationText("component://moqui-ai/knowledge/<slug>.md", false)` — the same
  `ResourceFacade` scheme that resolves component screens and services. A thin `moqui.ai` entity
  stores only what the file *can't* model — the agent assignment and the lifecycle metadata — plus
  a `contentLocation` pointer. **The `.md` is body-only: no frontmatter, nothing parsed into the
  entity, no field duplicated** (§5). Files are git-versioned and ship with the component; git
  history IS the version history. (OFBiz `Content`, `DbResource`, and the wiki were evaluated and
  set aside — §12.)
- **D-E — One knowledge entity, not two.** A **topic is the unit** of authorship, review,
  approval, effective-dating, and grant. No per-fact `AiKnowledgeItem` in v1 — facts are bullet
  lines in a topic's body. A per-fact child entity returns only when the lookup/self-learning
  tier needs queryable per-fact rows (§12, §13).
- **D-F — Glossary and Agent KB are siblings.** They share governance and will converge at the
  retrieval layer; they differ by content shape (structured vocab vs. prose) and consumption
  (tool-queried vs. always-injected), not by who uses them.

---

## 1. Purpose

Today an agent's only durable, curated knowledge is its `AiAgent.systemPrompt`. Everything else
is per-conversation (`AiConversationFact`), naming-only (the glossary), or live-fetch (a tool).
This spec adds a managed knowledge base — curated in the AiOps console, assigned per-agent — that
an agent draws on automatically while running.

**First consumer — the Composer.** Since the builder is just an agent, its knowledge is *this*
mechanism: prose topics that help it understand the HotWax OMS domain and house authoring
conventions when helping users create agents (§10). Operational agents (a CS/returns assistant
grounded in NotNaked's return window, shipping SLAs, brand voice) are the next consumers.

## 2. Scope

**In:** the data model (topic + grant); body storage in the Moqui resource layer; the curation
lifecycle (`DRAFT → APPROVED → ARCHIVED`); always-inject runtime assembly (`ContextAssembler` +
`AgentRunner`); an AiOps **Knowledge** screen + topic grants on the **Agents** screen; a v1 seed
(Composer-first); tests.

**Out (later / separate, §13):** embeddings / semantic retrieval and a `search#Knowledge` tool;
a lexical `lookup#Knowledge` tool; a per-fact `AiKnowledgeItem`; self-learning; external-doc
import + a frontmatter interchange format; wiki articles (the "documents tier"); per-tenant
scoping (a reserved field only); redaction/retention.

## 3. Decisions

- **D1 — Curated facts, always-injected, lexical.** No embedding pipeline, vector index, or ES.
  The grant/topic layer bounds injected volume.
- **D2 — Shared catalog, topic-level grants.** Mirrors `AiTool` + `AiAgentTool`.
- **D3 — Knowledge is the base context layer, independent of `contextStrategy`.** Injected for
  **every** agent with grants, even a stateless one (`contextStrategy=off`); pinned facts +
  summary (which need `window`/`summarize`) layer on top.
- **D4 — Body as `component://` file; entity holds metadata + a pointer.** Per D-D / §5.
- **D5 — Status-gated curation, human-authored in v1.** `DRAFT → APPROVED → ARCHIVED`; only
  `APPROVED` + currently-effective topics are served, so editing is safe on a live system.
- **D6 — Per-run injection guardrail.** A char cap (config default + optional per-agent
  override); over budget ⇒ deterministic truncation + a logged step.

## 4. Data model

`package="moqui.ai"`, mirroring existing conventions: opaque `id` PKs (sequenced; stable ids in
seed), `statusId type="id"` + `<relationship to moqui.basic.StatusItem>`, `one-nofk` grant edges
(resilient to catalog edits), unique index on the human name, and the OFBiz/Moqui effective-dating
names `fromDate` / `thruDate`.

### 4.1 `AiKnowledgeTopic` — a curated knowledge entry (the grant unit; the queryable spine)

| Field | Type | Role |
|---|---|---|
| `topicId` | `id` (PK) | opaque, sequenced; stable ids in seed |
| `topicName` | `text-short` | unique — "OMS Domain Primer", "Returns & Refunds" |
| `description` | `text-long` | one-line operator-facing note (not the body) |
| `contentLocation` | `text-medium` | `component://moqui-ai/knowledge/<slug>.md` — **pointer** to the body file (§5) |
| `statusId` | `id` | `StatusItem` statusTypeId=`AiKnowledgeStatus`: `AI_KNOW_DRAFT` \| `AI_KNOW_APPROVED` \| `AI_KNOW_ARCHIVED` |
| `fromDate` | `date-time` | optional effective-from; inject only when current ≥ `fromDate` (null = open) |
| `thruDate` | `date-time` | optional effective-thru; inject only when current < `thruDate` (null = open) |
| `ownerScope` | `id` | reserved for tenant/deployment scope; null = global in v1 (matches the glossary) |
| `createdByUserId` | `id` | provenance |

Relationships: `one` → `StatusItem`. Unique index on `topicName`. **No body field** — the body
lives in DbResource (§5).

### 4.2 `AiAgentKnowledge` — the assignment (mirrors `AiAgentTool`)

| Field | Type | Role |
|---|---|---|
| `agentId` | `id` (PK) | the agent |
| `topicId` | `id` (PK) | the assigned topic |

Relationships: `one` → `AiAgent`; `one-nofk` → `AiKnowledgeTopic`. This is the irreducible
minimum — DbResource has no concept of an "agent," so the agent→knowledge link must be a table.

### 4.3 `AiAgent` extension (in place)

Add one field to `AiAgent` in `entity/AiEntities.xml` (we own the entity and are pre-release, so
we edit in place rather than `extend-entity`):

| Field | Type | Role |
|---|---|---|
| `knowledgeMaxChars` | `number-integer` | optional per-agent override of the injected-knowledge cap (§8); null ⇒ config default |

### 4.4 Status seed (new `data/AiKnowledgeData.xml`, `ext-seed`)

- `StatusType AiKnowledgeStatus` → `AI_KNOW_DRAFT`, `AI_KNOW_APPROVED`, `AI_KNOW_ARCHIVED`;
  `StatusFlow AiKnowledgeFlow` + `StatusFlowTransition`: `DRAFT→APPROVED`, `APPROVED→ARCHIVED`,
  `ARCHIVED→APPROVED` (restore). Same seed shape as `data/AiGlossaryData.xml`.

## 5. Content storage — component-bundled filesystem files

**The single most important clarification — what lives where (no field has two homes):**

| Concern | Home | Why |
|---|---|---|
| The knowledge **body** (the text) | **`.md` file** in `knowledge/`, read via `component://` | static, git-versioned, ships with the component |
| **Which agent** gets it | **`AiAgentKnowledge`** | the file has no concept of agents |
| Approved / draft / archived | **`AiKnowledgeTopic.statusId`** | the file has no status field |
| Effective dates | **`AiKnowledgeTopic.fromDate` / `thruDate`** | the file has no dates |
| Name / description / scope | **`AiKnowledgeTopic`** | the file has only a filename |
| Link to the body | **`AiKnowledgeTopic.contentLocation`** → `component://moqui-ai/knowledge/<slug>.md` | a **pointer**, not a copy |

The entity stores **only the facts the file structurally cannot model** (assignment + lifecycle
metadata) plus the pointer. **The body never goes in the entity, and the `.md` is body-only —
no YAML frontmatter, nothing parsed from the file into the entity.**

**File layout — the `knowledge/` folder:**

```
runtime/component/moqui-ai/
└── knowledge/
    ├── oms-domain-primer.md          ← first Composer topic (already created)
    └── ...                           ← future topics; one file per topic, slug-named
```

Files are **edited as source code** — authored in the git repo, reviewed in PR diffs, deployed
with the component. `component://moqui-ai/knowledge/<slug>.md` resolves to the file via
`ResourceFacade`, exactly as `component://` paths work for screens and services.

**Mechanics, via `ec.resource`:**

- **Read** (injection, §8): `ec.resource.getLocationText("component://moqui-ai/knowledge/<slug>.md", false)`
  — body as a `String`. `false` = no framework cache (files are static at runtime; `true` also
  works if caching is desired for repeated reads within a request).
- **Write**: files are created/edited in the git repo and deployed with the component.
  `store#KnowledgeTopic` **does not write the body** — it creates or updates the entity row
  (metadata + `contentLocation` pointer only).
- **Version history**: git history IS the version history (reviewed in diffs, no
  `DbResourceFileHistory` needed).

**Implication for the AiOps console (v1):** the Knowledge screen shows the body **read-only**
(resolved from the file via `ec.resource`). There is no in-browser body editor in v1; editing
requires a git commit and redeploy. This is intentional for component-bundled curator-authored
content — changes are reviewable and auditable through git.

**When to switch to a writable store:** if operators need to edit knowledge bodies through the UI
at runtime (without a redeploy), switch `contentLocation` to a writable scheme —
`file://${moqui.runtime}/knowledge/<slug>.md` (runtime-writable directory) or `dbresource://`
(DB blob, versioned). The `contentLocation` field abstracts the scheme;
`ec.resource.getLocationText` handles all of them transparently. No data-model change needed —
swap the URI per topic.

## 6. Curation lifecycle

`DRAFT → APPROVED → ARCHIVED`, operator-authored. **Only `AI_KNOW_APPROVED` + currently-effective
topics are injected**, so drafting/editing is safe on a live system — the glossary's discipline,
at the topic grain.

## 7. Services — mirror `GlossaryServices` verbs

```
# Catalog / curation
ai.KnowledgeServices.store#KnowledgeTopic   in: topicId?, topicName, description?,
                                                contentLocation, fromDate?, thruDate?
                                            (creates/updates the entity row only; body is
                                             the file at contentLocation — not written here;
                                             defaults statusId=AI_KNOW_DRAFT)        → topicId
ai.KnowledgeServices.approve#KnowledgeTopic in: topicId                             (DRAFT → APPROVED)
ai.KnowledgeServices.archive#KnowledgeTopic in: topicId                             (→ ARCHIVED)

# Assignment (parallel to store#AiAgentTool / its revoke)
ai.KnowledgeServices.store#AgentKnowledge   in: agentId, topicId   (rejects an archived/unknown topic)
ai.KnowledgeServices.revoke#AgentKnowledge  in: agentId, topicId

# Runtime read (single source of truth; reused by the console)
ai.KnowledgeServices.find#AgentKnowledge    in: agentId   → topics: List<Map>[topicId, topicName, content]
                                            (APPROVED + effective, assigned to the agent,
                                             ordered by topicName; body resolved via
                                             ec.resource.getLocationText(contentLocation, false))

# Console listing
ai.KnowledgeServices.list#KnowledgeTopic    in: statusId?   → topics
```

New file `service/ai/KnowledgeServices.xml`. `find#AgentKnowledge` is the one place that filters +
resolves bodies, so the runner and console never diverge.

## 8. Runtime integration

- **`ContextAssembler.withKnowledge(systemPrompt, topics)`** — a new pure function mirroring
  `withFacts`. No-op when empty. Appends:
  ```
  ## Knowledge base (authoritative — follow these)
  ### <topicName>
  <content>
  ```
- **`AgentRunner` hook.** Right after `String sysCtx = agent.systemPrompt as String`
  (`src/main/groovy/org/moqui/ai/AgentRunner.groovy:125`, and the resume/preview sites), wrap
  **unconditionally** — *outside* the `contextStrategy` branch:
  ```groovy
  sysCtx = ContextAssembler.withKnowledge(sysCtx, loadAgentKnowledge(agentId, capChars))
  // existing: when ctxOn → withFacts(withSummary(sysCtx, summary), facts)
  ```
  Knowledge is the **base/authoritative layer**; facts + summary layer on top.
  `loadAgentKnowledge` invokes `find#AgentKnowledge` — the single source of truth (§7) for
  filtering + body resolution. (Contrast `loadFacts` at `AgentRunner.groovy:402`, which reads
  `AiConversationFact` inline; knowledge centralizes in the service because it resolves
  `component://` bodies via `ec.resource.getLocationText`.)
- **Guardrail (D6).** `capChars = agent.knowledgeMaxChars ?: <config ai_knowledge_max_chars,
  default 24000>`. Over the cap ⇒ truncate deterministically (topic order) and record a
  `context_trim` `AiAgentRunStep` (existing step vocabulary) so the trim shows in RunDetail.
  Never silently drop.

## 9. AiOps console

- **New `screen/AiOps/Knowledge.xml`** (mirrors `Glossary.xml`): list topics with status;
  create/edit a topic record (name, description, `contentLocation`, effective dates); `store` /
  `approve` / `archive` transitions. The body is displayed **read-only** (resolved from the file
  via `ec.resource.getLocationText`); there is no in-browser body editor in v1 — bodies are
  authored as `.md` files in git. Added to the AiOps subscreen menu.
- **Topic grants on the existing `Agents` screen**, beside tool grants (transitions call
  `store#AgentKnowledge` / `revoke#AgentKnowledge`).

## 10. v1 seed — the Composer's knowledge base (focus-first)

Seed (as `ext-seed` `data/AiKnowledgeData.xml`, loads once at setup) a set of **prose topics
that make the builder agent domain-aware** — e.g. **OMS Domain Primer**, **Common OMS Agent
Patterns**, **Agent & Tool Authoring Conventions** — as `AI_KNOW_APPROVED` topics, **granted to
the seeded `composer-assistant` agent**. Each seed row carries `contentLocation =
component://moqui-ai/knowledge/<slug>.md`; the `.md` files ship with the component in
`knowledge/`. No `DbResource` rows needed. The first file (`oms-domain-primer.md`) is already
created.

*Then move to others:* a NotNaked operational set (**Returns & Refunds**, **Shipping & Delivery**,
**Brand Voice**) granted to a CS/returns assistant — the next consumer, same mechanism.

## 11. Testing — mirror `AiGlossaryTests`

Add `AiKnowledgeTests.groovy` to `MoquiSuite`:

- **Unit (pure):** `ContextAssembler.withKnowledge` formats topics correctly; no-op on empty.
- **Service:** `store#KnowledgeTopic` creates a `DRAFT` row with the given `contentLocation`;
  `ec.resource.getLocationText(contentLocation, false)` resolves the body from the `component://`
  file; `approve` → `APPROVED`; `archive` → `ARCHIVED`.
- **Retrieval filtering:** `find#AgentKnowledge` returns only `APPROVED` + currently-effective,
  assigned topics; excludes drafts, archived, expired (`thruDate` past / `fromDate` future), and
  unassigned; body text resolved.
- **Assignment:** `store#AgentKnowledge` rejects an archived/unknown topic; `revoke` removes it.
- **Runtime (Mock provider):** an agent assigned a topic has the body text in its run's system
  context; a stateless agent (`contextStrategy=off`) still gets it.
- **Guardrail:** an assignment exceeding `knowledgeMaxChars` truncates deterministically and
  records the trim step.

## 12. Alternatives considered

- **Two-entity model (`AiKnowledgeTopic` + per-fact `AiKnowledgeItem`)** — *simplified away
  (D-E).* A per-fact entity would add per-fact lifecycle, expiry, queryable metadata, and stable
  ids; none are exercised by v1. **Trade-off:** approval + effective-dating are per-topic, not
  per-fact. Reintroduce as a child when the lookup/self-learning tier needs it (§13).
- **Grant-only (no `AiKnowledgeTopic`; `AiAgentKnowledge` → DbResource directly)** — *considered;
  not chosen.* Truly minimal (DbResource files + one assignment table), but DbResource has no
  status or dates, so it **loses the approval gate and effective dating** ("if it's granted, it's
  live"). Keeping a thin `AiKnowledgeTopic` buys those for one small table. Revisit if the gate
  proves unnecessary.
- **Self-describing `.md` with YAML frontmatter** (llms.txt / Diátaxis-informed) — *considered as
  an authoring/import interchange format; deferred.* v1 stores **body-only** markdown with all
  metadata in the entity (no parsing, no duplication — §5). A frontmatter interchange format
  returns with the external-doc importer in the documents tier (§13), parsed once on import.
- **Moqui `DbResource` / `DbResourceFile` (`dbresource://`)** — *evaluated; set aside for v1.*
  Framework-native versioned blob storage with `DbResourceFileHistory` (per-version audit) and a
  modification-aware cache. Chosen initially, then replaced by filesystem: for curator-authored
  content that ships with the component, files are simpler — no blob overhead, no
  `nontransactional` concern, git history IS the version history. **DbResource remains the right
  choice when operators need to edit knowledge bodies at runtime through the UI** (without a
  redeploy). Revisit if that becomes a requirement; `contentLocation` abstracts the scheme so the
  switch is a URI swap with no data-model change.
- **OFBiz `Content` / `DataResource` / `ElectronicText`** (in `ofbiz-oms-udm`) — *rejected.* A
  full CMS, but adopting it makes `moqui-ai` depend on an OMS component (breaking self-containment);
  a one-line fact becomes 3 joined rows using none of `DataResource`'s reason to exist; foreign
  `org.apache.ofbiz.content.*` conventions; search needs ES anyway.
- **The wiki (`WikiSpace` / `WikiPage`)** (framework-native) — *deferred to the documents tier.*
  Moqui's fullest content CMS and the natural home for human-readable KB articles, but
  page-and-human oriented and heavier than one-line agent facts need; no wiki UI app is installed
  here. When the documents tier lands (§13), a `WikiSpace` is the natural choice.

## 13. Boundaries & later

- **Per-fact `AiKnowledgeItem`** — a child of `AiKnowledgeTopic` (id, title, `keywords`,
  `sourceType` CURATED/LEARNED, status, `fromDate`/`thruDate`) for when facts need individual
  approval, expiry, or queryability. Brings back an `AiKnowledgeSource` enum.
- **Documents tier + external-doc import** — longer `.md` bodies through the same
  `contentLocation` + `ec.resource` path; a **frontmatter interchange format** (§12) for bulk
  import from sources like `docs.hotwax.co/documents/learn-hotwax-oms` (its glossary entries seed
  the *glossary*; its deeper articles feed *this* documents tier — the sibling split in the wild).
  Optionally a `WikiSpace` for human-browsable articles. No data-model change to topics.
- **`search#Knowledge` over Solr (the platform's search engine)** — when a KB outgrows what
  always-inject should carry, search it via the HotWax Solr integration already in the platform
  (`co.hotwax.search.SearchServices`: `index#SolrDoc` / `run#SolrQuery` / `autoComplete#SolrFacet`,
  over the HC Solr REST API, configured by `solr.url`), reusing the `DataDocument → Solr` indexing
  pattern `oms` uses for products. **Keeps `moqui-ai` self-contained:** expose KB search as an
  ordinary granted `AiTool` over the platform search service (code-free tool wiring, present only
  where Solr is), and drive indexing from a deployment-side SECA/job — or an optional `moqui-ai`
  service that no-ops when `solr.url` is unset. This **replaces the earlier ElasticSearch /
  `ec.elastic` assumption.**
- **Self-learning** — promote a confirmed `AiConversationFact` (or an agent proposal) into a draft
  `LEARNED` fact for Curator approval, mirroring `promote#TermsFromSignals`.
- **Semantic / RAG retrieval** — lexical Solr covers the `search#Knowledge` tier; true semantic
  search is a further step: **Solr 9 dense-vector kNN if the hosted HC Solr exposes it**, otherwise
  embeddings + a vector store. Mastra's RAG pipeline is the reference shape; add reranking +
  metadata filtering when this lands.
- **Per-tenant scoping** — activate `ownerScope` (topic-level), filter at grant/inject time.
- **Redaction / retention** — inherits the component-wide observability TODO before production use.

## 14. Definition of done (v1)

- `AiKnowledgeTopic` + `AiAgentKnowledge` + `AiAgent.knowledgeMaxChars` + the `AiKnowledgeStatus`
  seed exist; bodies live as `.md` files in `knowledge/` and are read via
  `ec.resource.getLocationText(contentLocation, false)`; **no body or duplicated metadata in the
  entity.**
- The curation services + assignment services + `find#AgentKnowledge` are implemented and tested.
- `AgentRunner` injects an agent's assigned + approved + effective topics into the system context
  on every run (any `contextStrategy`), bounded by the cap.
- The AiOps **Knowledge** screen manages the catalog; topic grants are authored on **Agents**.
- The Composer's knowledge topics are seeded and granted to `composer-assistant`.
- `AiKnowledgeTests` is green in `MoquiSuite`.

## 15. Prior art & validation (Mastra)

Mastra (current docs) splits "what an agent knows" into **working memory** (always-in-context,
injected into the system message each turn) and **RAG** (`createVectorQueryTool` over an embedded
corpus). Our v1 maps to working memory's *delivery* (always-inject) and our deferred Solr/documents
tier maps to RAG — so the two-tier roadmap is mainstream, not idiosyncratic. **What we add that
Mastra's knowledge layers don't:** human **governance** (the `DRAFT→APPROVED→ARCHIVED` gate),
a **shared library + per-agent grants**, and **Moqui-native, permission-bound** storage — the
right bias for an OMS where unreviewed policy text is a liability. When the RAG tier lands,
Mastra's pipeline (chunk → embed → vector query, + reranking + metadata filtering) is the
reference shape.
