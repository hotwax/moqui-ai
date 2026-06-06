# Builder Knowledgebase / Domain Glossary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. PLAN ONLY — do not implement from this document until execution is started.

**Goal:** Give the Composer Assistant a domain "brain": a curated, OMS-grounded glossary of typed terms (NOUN/VERB) + dialect synonyms that (a) **seeds** from the live entity model + the existing exposable service verbs so the builder speaks OMS out of the box, (b) **learns** each deployment's words by capturing a naming signal every time a tool/agent is authored, (c) **serves** lexical, ranked, APPROVED-only terms to the Composer's `list#DomainTerm` and `propose#Naming` tools, and (d) **gets better** through a suggest→approve curation loop (services + a Glossary tab in the AI Ops console). v1 is **lexical + suggest-only**: nothing auto-enters the approved glossary.

**Architecture:** Three new entities under `package="moqui.ai"` — `AiDomainTerm` (the glossary), `AiTermSynonym` (the dialect), `AiNamingSignal` (the learning log). A `seed#DomainGlossary` service derives `SEEDED`+`APPROVED` nouns from `ec.entity.getAllEntityNames()` (non-view entities) + a curated UDM-concept list, and `SEEDED`+`APPROVED` verbs from `ec.service.getKnownServiceNames()` via `ServiceDefinition.getVerbFromName(...)`; it is idempotent (re-runnable). Capture is a **two-part hook on the keystone's single authoring gate**: a tiny addition *inside* `store#AiTool`/`store#AiAgent` records the rich signal (it is the only place `suggestedName`/`intentText` exist), and a defensive **EECA** on `AiTool`/`AiAgent` is the catch-all floor — because `EntityValueBase.create()/update()` fire EECA on *every* write (UI, builder, direct `EntityValue.store()`), while a SECA fires only when the named service is called and would miss direct writes. The seed loader and `seed#DomainGlossary` writes are excluded from signal capture by an `ec.context` flag. `find#DomainTerm(text, kind?)` tokenizes `text`, matches against `term` + `AiTermSynonym.synonym`, filters `statusId=AI_TERM_APPROVED` (+ kind, + scope), and ranks by `matchStrength × usageCount`; it backs the Composer's `list#DomainTerm` and `propose#Naming`. `promote#TermsFromSignals` scans `AiNamingSignal`, and for chosen terms recurring at/above a threshold that are not already glossary terms, inserts them `LEARNED`+`SUGGESTED`. `store#`/`approve#`/`reject#DomainTerm` curation services + a **Glossary** screen under `AiOps` close the loop; `usageCount` reinforcement on approved terms lets good names rise.

**Tech Stack:** Groovy 3 / JDK 11, Moqui (HotWax fork), Spock + JUnit Platform Suite, H2 dev DB. Entities/statuses/enums via `StatusItem`/`StatusFlowTransition` + `Enumeration` (UDM guide archetypes). Tests are Spock `*Tests.groovy` in `src/test/groovy/`, registered in `MoquiSuite.groovy`.

---

## Depends on / assumes already built (the registry keystone)

Per `docs/specs/2026-06-05-agent-tool-registry-design.md` — **reference, do not re-plan**:
- `AiTool` / `AiAgent` restructured to **opaque stable ids** (`toolId`/`agentId`) with editable names (`toolName`, `verb`, `noun`; `agentName`).
- **`store#AiTool` / `store#AiAgent`** exist as the *single authoring gate* (validate service, derive `effect`, denylist floor, derive+unique `toolName`, defaults). The naming signal attaches here.
- DB is the source of truth; tools/agents are seeded as standard entity data and authored at runtime via the `store#` services.

> **Sequencing note (load-bearing).** The component tree is currently at the **pre-keystone baseline**: `AiTool.toolName`/`AiAgent.agentName` are still PKs and **`store#AiTool`/`store#AiAgent` do not yet exist** (the only `store#` in the screens is Moqui's auto `store#moqui.ai.AiAgent`). This KB plan therefore lands **after** the keystone. Task 4's in-service signal write edits the keystone's `store#AiTool`/`store#AiAgent` (which will exist by then); the **EECA in Task 4** is written against the entity (`AiTool`/`AiAgent`) and is robust to whatever the keystone names its fields, reading `toolName`/`agentName` if present. If a worker picks this plan up before the keystone lands, **stop and build the keystone first** — Tasks 1–3, 6, 7 are keystone-independent and can proceed, but Task 4 (capture) and Task 5 (`find#DomainTerm` backing the Composer) assume it.

## Consumed by (the Composer)

Per `docs/specs/2026-06-05-composer-assistant-moqui-design.md` — this plan makes the Composer's stubs real:
- **`list#DomainTerm`** (tool `list_domain_terms`) → thin wrapper over `find#DomainTerm`.
- **`propose#Naming`** (tool `propose_naming`) → LLM proposes verb/noun, then **snaps to** the nearest approved term/synonym via `find#DomainTerm`. This plan provides `find#DomainTerm` + the snapping helper; the Composer spec owns the LLM-proposal half. (If `propose#Naming` does not yet exist, Task 5 creates a v1 `propose#Naming` that does glossary-snapping only — the Composer spec deepens it later.)

## Locked decisions (from the design spec §0/§3)

- **Authoring glossary**, not general doc RAG. Shares retrieval substrate with a future RAG; scope here is naming/grounding for tools + agents.
- **v1 retrieval is lexical** (term + synonym match), not embeddings.
- **Learning is suggest-only** — signals propose; a human Curator approves. Nothing auto-enters APPROVED.
- **Single-tenant v1**, with `ownerScope` reserved (null = global) so per-tenant glossaries drop in later without a re-model.
- **Three provenances** `SEEDED`/`LEARNED`/`CURATED`, all gated by `SUGGESTED → APPROVED (→ REJECTED)`; **only APPROVED is served**.

## Conventions (same as prior work)

- Run the suite from the moqui root `/Users/anilpatel/maarg-sd/notnaked` (this is the project root that owns this component; `:runtime:component:moqui-ai`):
  ```bash
  source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
  ```
  Live tests are key-gated (keys in `dev.env`); the KB tests added here are **all MockProvider / pure-entity** — no live key needed — but run the whole suite so the live tests stay green.
- New entity/fields/statuses just need the boot the test triggers (Moqui auto-adds columns; tests load `data/*StatusData.xml` + the new `data/AiGlossaryData.xml` explicitly via `makeDataLoader().location(...)`, matching `AiEntitiesTests`/`AiConversationTests`).
- Tests: Spock `*Tests.groovy` in `src/test/groovy/`, registered in `MoquiSuite.groovy`. New class `AiGlossaryTests` MUST be added to the suite.
- Commit **inside the component repo** (`runtime/component/moqui-ai`, branch `feature/ai-agent-framework`), surgically (explicit pathspecs, never `git add -A` — parallel sessions edit this tree). **This plan is PLAN-ONLY: do not commit until execution begins.**
- After every file change during execution, show a diff before saving (project rule 3).

---

## File Structure

| File | Change |
|---|---|
| `entity/AiGlossaryEntities.xml` | New — `AiDomainTerm`, `AiTermSynonym`, `AiNamingSignal` |
| `data/AiGlossaryData.xml` | New — `AiDomainTermStatus` StatusItems + flow; `Enumeration`s for term-kind / source-type / signal-type |
| `service/ai/GlossaryServices.xml` | New — `seed#DomainGlossary`, `find#DomainTerm`, `promote#TermsFromSignals`, `store#`/`approve#`/`reject#DomainTerm`, `list#DomainTerm`, (v1) `propose#Naming`, `capture#NamingSignal` (shared helper) |
| `entity/AiGlossaryEcas.eecas.xml` | New — defensive EECA on `AiTool`/`AiAgent` → `capture#NamingSignal` (catch-all floor) |
| `MoquiConf.xml` | Register the `.eecas.xml` via `<entity-facade><entity-eca-list>` (or confirm auto-scan) |
| `service/ai/AgentServices.xml` *(keystone file)* | `store#AiTool`/`store#AiAgent` gain an inline `capture#NamingSignal` call (the rich-context hook) |
| `screen/AiOps.xml` | Add a `Glossary` subscreens-item |
| `screen/AiOps/Glossary.xml` | New — review/approve/reject SUGGESTED terms; list APPROVED; add synonym |
| `data/AiSecurityData.xml` | (already grants `ai\..*` services + `moqui\.ai\..*` entities — confirm the new screen path matches `component://moqui-ai/screen/.*`; no change expected) |
| `src/test/groovy/AiGlossaryTests.groovy` | New — entities, seed, capture, find, promote, curation, the full loop |
| `src/test/groovy/MoquiSuite.groovy` | Register `AiGlossaryTests` |

---

## Data model (target)

All entities `package="moqui.ai"`.

**`AiDomainTerm`** — `termId` (id, PK), `term` (text-short), `termKind` (id → enum `AI_TERM_NOUN`|`AI_TERM_VERB`), `description` (text-long), `sourceType` (id → enum `AI_TSRC_SEEDED`|`AI_TSRC_LEARNED`|`AI_TSRC_CURATED`), `statusId` (id → StatusItem `AiDomainTermStatus`), `usageCount` (number-integer), `ownerScope` (id, null=global). Unique index on `(term, termKind, ownerScope)`.

**`AiTermSynonym`** — `termId` (id, PK), `synonym` (text-short, PK), `sourceType` (id), `statusId` (id). Relationship `one` → `AiDomainTerm`.

**`AiNamingSignal`** — `signalId` (id, PK), `signalType` (id → enum `AI_SIG_TOOL_NAME`|`AI_SIG_AGENT_NAME`), `intentText` (text-long), `suggestedName` (text-medium), `chosenName` (text-medium), `wasOverridden` (text-indicator), `userId` (id), `fromDate` (date-time). `one-nofk` → relationships kept loose (append-only log; must not block the authoring lifecycle).

**Statuses / enums (seed data).**
- `StatusType` `AiDomainTermStatus` + `StatusItem`s `AI_TERM_SUGGESTED`/`AI_TERM_APPROVED`/`AI_TERM_REJECTED`; `StatusFlow` `AiDomainTermFlow` with transitions SUGGESTED→APPROVED, SUGGESTED→REJECTED (+ APPROVED→REJECTED for retiring a stale term). `StatusValidChange` is **absent** from this codebase (UDM guide) — use `StatusFlowTransition`.
- `Enumeration` (archetype = fixed, code-referenced type, per the UDM guide): `EnumerationType` `AiTermKind` → `AI_TERM_NOUN`/`AI_TERM_VERB`; `AiTermSource` → `AI_TSRC_SEEDED`/`AI_TSRC_LEARNED`/`AI_TSRC_CURATED`; `AiSignalType` → `AI_SIG_TOOL_NAME`/`AI_SIG_AGENT_NAME`. (Confirm `moqui.basic.Enumeration`/`EnumerationType` field names at implementation — `enumId`, `enumTypeId`, `description`, `enumCode`.)

---

## Task 1: The three entities + statuses + enums (seed data)

**Files:**
- Create: `entity/AiGlossaryEntities.xml`, `data/AiGlossaryData.xml`
- Test: `src/test/groovy/AiGlossaryTests.groovy` (new), `src/test/groovy/MoquiSuite.groovy`

- [ ] **Step 1: Write a failing entity + status/enum round-trip test, register the class**

Create `src/test/groovy/AiGlossaryTests.groovy` (mirror `AiEntitiesTests` scaffolding — `@Shared ec`, `setupSpec` disables authz + loads `data/AiStatusData.xml` and `data/AiGlossaryData.xml`, `cleanupSpec` destroys, per-test authz toggles). First test:
```groovy
import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.entity.EntityValue

class AiGlossaryTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiGlossaryData.xml").load()
        ec.artifactExecution.enableAuthz()
    }
    def cleanupSpec() { if (ec != null) ec.destroy() }
    def setup() { ec.artifactExecution.disableAuthz() }
    def cleanup() { ec.artifactExecution.enableAuthz() }

    def "glossary entities + status/enum seed data exist"() {
        when:
        ec.entity.makeValue("moqui.ai.AiDomainTerm").setAll([termId: "TERMNOUN1", term: "return",
            termKind: "AI_TERM_NOUN", sourceType: "AI_TSRC_SEEDED", statusId: "AI_TERM_APPROVED", usageCount: 0]).create()
        ec.entity.makeValue("moqui.ai.AiTermSynonym").setAll([termId: "TERMNOUN1", synonym: "rma",
            sourceType: "AI_TSRC_CURATED", statusId: "AI_TERM_APPROVED"]).create()
        ec.entity.makeValue("moqui.ai.AiNamingSignal").setAll([signalId: "SIG1", signalType: "AI_SIG_TOOL_NAME",
            intentText: "list returns", suggestedName: "list_returns", chosenName: "list_rmas",
            wasOverridden: "Y", userId: "U1", fromDate: ec.user.nowTimestamp]).create()
        then:
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", "TERMNOUN1").one().term == "return"
        ec.entity.find("moqui.ai.AiTermSynonym").condition("termId", "TERMNOUN1").condition("synonym", "rma").one() != null
        ec.entity.find("moqui.ai.AiNamingSignal").condition("signalId", "SIG1").one().wasOverridden == "Y"
        ec.entity.find("moqui.basic.StatusItem").condition("statusId", "AI_TERM_APPROVED").one() != null
        ec.entity.find("moqui.basic.Enumeration").condition("enumId", "AI_TERM_NOUN").one() != null
        ec.entity.find("moqui.basic.Enumeration").condition("enumId", "AI_TSRC_SEEDED").one() != null
        ec.entity.find("moqui.basic.Enumeration").condition("enumId", "AI_SIG_TOOL_NAME").one() != null
        cleanup:
        ec.entity.find("moqui.ai.AiTermSynonym").condition("termId", "TERMNOUN1").deleteAll()
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", "TERMNOUN1").deleteAll()
        ec.entity.find("moqui.ai.AiNamingSignal").condition("signalId", "SIG1").deleteAll()
    }
}
```
Add `AiGlossaryTests.class` to the `@SelectClasses([...])` list in `MoquiSuite.groovy`.

- [ ] **Step 2: Run to confirm it fails**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — `AiDomainTerm`/`AiTermSynonym`/`AiNamingSignal` entities and the `AI_TERM_*`/`AI_TSRC_*`/`AI_SIG_*` seed rows do not exist. (Likely an `EntityNotFoundException` / unknown-entity error on `makeValue`.)

- [ ] **Step 3: Create the entities**

`entity/AiGlossaryEntities.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<entities xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/entity-definition-3.xsd">

    <!-- The glossary: curated domain nouns + capability verbs, typed + provenanced + status-gated. -->
    <entity entity-name="AiDomainTerm" package="moqui.ai">
        <field name="termId" type="id" is-pk="true"/>
        <field name="term" type="text-short"><description>canonical term (order, cancel); unique per termKind + ownerScope</description></field>
        <field name="termKind" type="id"><description>Enumeration enumTypeId=AiTermKind: AI_TERM_NOUN | AI_TERM_VERB</description></field>
        <field name="description" type="text-long"/>
        <field name="sourceType" type="id"><description>Enumeration enumTypeId=AiTermSource: AI_TSRC_SEEDED | AI_TSRC_LEARNED | AI_TSRC_CURATED</description></field>
        <field name="statusId" type="id"><description>StatusItem statusTypeId=AiDomainTermStatus: AI_TERM_SUGGESTED | AI_TERM_APPROVED | AI_TERM_REJECTED</description></field>
        <field name="usageCount" type="number-integer"><description>chosen-in-authoring reinforcement count</description></field>
        <field name="ownerScope" type="id"><description>reserved for tenant/deployment scope; null = global in v1</description></field>
        <relationship type="one" related="moqui.basic.StatusItem" short-alias="status"/>
        <relationship type="one-nofk" title="AiTermKind" related="moqui.basic.Enumeration" short-alias="kindEnum">
            <key-map field-name="termKind" related="enumId"/></relationship>
        <index name="AI_TERM_UNIQUE" unique="true">
            <index-field name="term"/><index-field name="termKind"/><index-field name="ownerScope"/></index>
    </entity>

    <!-- The dialect: aliases that map to a canonical term ("rma" -> return). -->
    <entity entity-name="AiTermSynonym" package="moqui.ai">
        <field name="termId" type="id" is-pk="true"/>
        <field name="synonym" type="text-short" is-pk="true"/>
        <field name="sourceType" type="id"/>
        <field name="statusId" type="id"/>
        <relationship type="one" related="moqui.ai.AiDomainTerm" short-alias="domainTerm"/>
        <relationship type="one" related="moqui.basic.StatusItem" short-alias="status"/>
    </entity>

    <!-- The learning log: what the Composer proposed vs. what the human kept, per authoring event. -->
    <entity entity-name="AiNamingSignal" package="moqui.ai">
        <field name="signalId" type="id" is-pk="true"/>
        <field name="signalType" type="id"><description>Enumeration enumTypeId=AiSignalType: AI_SIG_TOOL_NAME | AI_SIG_AGENT_NAME</description></field>
        <field name="intentText" type="text-long"><description>the user's described intent / the backing service</description></field>
        <field name="suggestedName" type="text-medium"><description>what the Composer proposed (null if human-authored directly)</description></field>
        <field name="chosenName" type="text-medium"><description>what the human kept</description></field>
        <field name="wasOverridden" type="text-indicator"><description>Y if chosen != suggested</description></field>
        <field name="userId" type="id"/>
        <field name="fromDate" type="date-time"/>
    </entity>
</entities>
```

- [ ] **Step 4: Create the status + enum seed data**

`data/AiGlossaryData.xml` (match the element style of `data/AiStatusData.xml`; `type="ext-seed"` so it loads on install — see the seed re-run note in Task 2 about why the *terms* are NOT seeded here):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<entity-facade-xml type="ext-seed">
    <!-- term lifecycle -->
    <moqui.basic.StatusType statusTypeId="AiDomainTermStatus" description="AI Domain Term Status"/>
    <moqui.basic.StatusItem statusId="AI_TERM_SUGGESTED" statusTypeId="AiDomainTermStatus" sequenceNum="1" description="Suggested"/>
    <moqui.basic.StatusItem statusId="AI_TERM_APPROVED"  statusTypeId="AiDomainTermStatus" sequenceNum="2" description="Approved"/>
    <moqui.basic.StatusItem statusId="AI_TERM_REJECTED"  statusTypeId="AiDomainTermStatus" sequenceNum="3" description="Rejected"/>
    <moqui.basic.StatusFlow statusFlowId="AiDomainTermFlow" statusTypeId="AiDomainTermStatus" description="Glossary term curation"/>
    <moqui.basic.StatusFlowTransition statusFlowId="AiDomainTermFlow" statusId="AI_TERM_SUGGESTED" toStatusId="AI_TERM_APPROVED" transitionSequence="1" transitionName="Approve"/>
    <moqui.basic.StatusFlowTransition statusFlowId="AiDomainTermFlow" statusId="AI_TERM_SUGGESTED" toStatusId="AI_TERM_REJECTED" transitionSequence="2" transitionName="Reject"/>
    <moqui.basic.StatusFlowTransition statusFlowId="AiDomainTermFlow" statusId="AI_TERM_APPROVED"  toStatusId="AI_TERM_REJECTED" transitionSequence="3" transitionName="Retire"/>

    <!-- fixed, code-referenced types (Enumeration archetype per the UDM guide) -->
    <moqui.basic.EnumerationType enumTypeId="AiTermKind" description="AI Glossary Term Kind"/>
    <moqui.basic.Enumeration enumId="AI_TERM_NOUN" enumTypeId="AiTermKind" sequenceNum="1" enumCode="NOUN" description="Domain noun"/>
    <moqui.basic.Enumeration enumId="AI_TERM_VERB" enumTypeId="AiTermKind" sequenceNum="2" enumCode="VERB" description="Capability verb"/>

    <moqui.basic.EnumerationType enumTypeId="AiTermSource" description="AI Glossary Term Source"/>
    <moqui.basic.Enumeration enumId="AI_TSRC_SEEDED"  enumTypeId="AiTermSource" sequenceNum="1" enumCode="SEEDED"  description="Seeded from the ontology"/>
    <moqui.basic.Enumeration enumId="AI_TSRC_LEARNED" enumTypeId="AiTermSource" sequenceNum="2" enumCode="LEARNED" description="Learned from authoring signals"/>
    <moqui.basic.Enumeration enumId="AI_TSRC_CURATED" enumTypeId="AiTermSource" sequenceNum="3" enumCode="CURATED" description="Human-edited"/>

    <moqui.basic.EnumerationType enumTypeId="AiSignalType" description="AI Naming Signal Type"/>
    <moqui.basic.Enumeration enumId="AI_SIG_TOOL_NAME"  enumTypeId="AiSignalType" sequenceNum="1" enumCode="TOOL"  description="Tool name signal"/>
    <moqui.basic.Enumeration enumId="AI_SIG_AGENT_NAME" enumTypeId="AiSignalType" sequenceNum="2" enumCode="AGENT" description="Agent name signal"/>
</entity-facade-xml>
```
> Confirm `Enumeration`/`EnumerationType` field names against `moqui.basic` at implementation (`enumId`, `enumTypeId`, `enumCode`, `sequenceNum`, `description`). If `enumCode` is not a column, drop it.

- [ ] **Step 5: Run — passes**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS — `AiGlossaryTests > glossary entities + status/enum seed data exist` green; all prior tests still green.

- [ ] **Step 6: Commit** *(execution only)*

```bash
cd /Users/anilpatel/maarg-sd/notnaked/runtime/component/moqui-ai && \
git add entity/AiGlossaryEntities.xml data/AiGlossaryData.xml \
        src/test/groovy/AiGlossaryTests.groovy src/test/groovy/MoquiSuite.groovy && \
git commit -m "feat(ai): glossary entities (AiDomainTerm/AiTermSynonym/AiNamingSignal) + status/enum seed data"
```

---

## Task 2: `seed#DomainGlossary` — derive nouns + verbs, idempotent

**Files:**
- Create: `service/ai/GlossaryServices.xml`
- Test: `src/test/groovy/AiGlossaryTests.groovy`

Why a service (not static data): the noun set must track the *deployment's* entity model and the verb set must track the *deployment's* exposable services — both vary per install and grow over time. So terms are derived at install (call `seed#DomainGlossary` as an `install`/`ext` `service-call` in a data file or post-load step) and the service is re-runnable to absorb new entities/services. (Statuses/enums stay static — Task 1.)

- [ ] **Step 1: Write a failing seed test**

Add to `AiGlossaryTests`:
```groovy
    def "seed#DomainGlossary derives noun + verb terms, SEEDED + APPROVED, and is idempotent"() {
        given: "a clean glossary"
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiDomainTerm").condition("sourceType", "AI_TSRC_SEEDED").deleteAll()
        when: "first run"
        Map r1 = ec.service.sync().name("ai.GlossaryServices.seed#DomainGlossary").parameters([:]).call()
        then: "nouns from the entity model (OrderHeader -> order) + the curated UDM concepts are present, APPROVED + SEEDED"
        (r1.nounsAdded as int) > 0
        (r1.verbsAdded as int) > 0
        def order = ec.entity.find("moqui.ai.AiDomainTerm").condition("term", "order").condition("termKind", "AI_TERM_NOUN").one()
        order != null && order.statusId == "AI_TERM_APPROVED" && order.sourceType == "AI_TSRC_SEEDED"
        // a verb observed from the existing exposable services (e.g. get / find / list)
        ec.entity.find("moqui.ai.AiDomainTerm").condition("term", "list").condition("termKind", "AI_TERM_VERB").one() != null
        when: "second run absorbs nothing new"
        Map r2 = ec.service.sync().name("ai.GlossaryServices.seed#DomainGlossary").parameters([:]).call()
        then: "idempotent — no duplicate rows, nothing re-added"
        (r2.nounsAdded as int) == 0
        (r2.verbsAdded as int) == 0
        ec.entity.find("moqui.ai.AiDomainTerm").condition("term", "order").condition("termKind", "AI_TERM_NOUN").list().size() == 1
        cleanup:
        ec.artifactExecution.enableAuthz()
    }
```

- [ ] **Step 2: Run to confirm it fails**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — `ai.GlossaryServices.seed#DomainGlossary` not found.

- [ ] **Step 3: Create `GlossaryServices.xml` with `seed#DomainGlossary`**

`service/ai/GlossaryServices.xml` (the seed service derives nouns from the entity model + a curated UDM-concept list, verbs from exposable service names; writes SEEDED+APPROVED; skips terms that already exist):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<services xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/service-definition-3.xsd">

    <!-- Build/refresh the starting glossary from the live model + service catalog. SEEDED + APPROVED.
         Idempotent: a term already present (any status) is left untouched. Re-runnable to absorb new
         entities/services. Intended to be called once at install (ext data service-call) and on demand. -->
    <service verb="seed" noun="DomainGlossary" authenticate="true">
        <in-parameters>
            <parameter name="ownerScope"/><!-- null = global (v1) -->
        </in-parameters>
        <out-parameters>
            <parameter name="nounsAdded" type="Integer"/>
            <parameter name="verbsAdded" type="Integer"/>
        </out-parameters>
        <actions>
            <set field="nounsAdded" from="0"/><set field="verbsAdded" from="0"/>
            <set field="signalGuard" value="true"/><!-- not a real authoring write: skip capture (Task 4) -->
            <script><![CDATA[
                import org.moqui.impl.service.ServiceDefinition

                // ----- NOUNS: domain objects from the non-view entity model + curated UDM concepts -----
                // Map known OMS entity names to their domain noun (singularized, lowercased). Keep this a
                // small, deliberate set — we want domain nouns, not every framework table.
                Map<String,String> entityNoun = [
                    "OrderHeader":"order", "OrderItem":"order item", "ReturnHeader":"return",
                    "ReturnItem":"return item", "Shipment":"shipment", "ShipmentItem":"shipment item",
                    "Facility":"facility", "InventoryItem":"inventory", "Product":"product",
                    "Party":"party", "Picklist":"picklist", "Invoice":"invoice", "Payment":"payment"]
                // Curated concepts from the UDM domain-practices guide (not 1:1 with an entity name).
                List<String> udmConcepts = ["allocation", "reservation", "fulfillment", "brokering",
                    "store", "warehouse", "carrier", "kit", "variant", "catalog"]

                Set<String> presentEntityNames = ec.entity.getAllEntityNames() as Set
                Map<String,String> nouns = [:]
                for (Map.Entry<String,String> e in entityNoun.entrySet()) {
                    // only seed a noun whose backing entity actually exists in THIS deployment
                    if (presentEntityNames.contains("mantle.order." + e.key) || presentEntityNames.any { it.endsWith("." + e.key) })
                        nouns.put(e.value, "Domain object: ${e.key}".toString())
                }
                for (String c in udmConcepts) nouns.put(c, "UDM domain concept".toString())

                for (Map.Entry<String,String> n in nouns.entrySet()) {
                    boolean exists = ec.entity.find("moqui.ai.AiDomainTerm")
                        .condition("term", n.key).condition("termKind", "AI_TERM_NOUN").condition("ownerScope", ownerScope).one() != null
                    if (exists) continue
                    String termId = ec.entity.sequencedIdPrimary("moqui.ai.AiDomainTerm", null, null)
                    ec.service.sync().name("create#moqui.ai.AiDomainTerm").parameters([termId: termId, term: n.key,
                        termKind: "AI_TERM_NOUN", description: n.value, sourceType: "AI_TSRC_SEEDED",
                        statusId: "AI_TERM_APPROVED", usageCount: 0, ownerScope: ownerScope]).call()
                    nounsAdded = (nounsAdded as int) + 1
                }

                // ----- VERBS: the verbs of existing exposable services (soft, observed vocabulary) -----
                Set<String> verbs = new TreeSet<String>()
                for (String sn in ec.service.getKnownServiceNames()) {
                    String v = ServiceDefinition.getVerbFromName(sn)
                    if (v) verbs.add(v.toLowerCase())
                }
                for (String v in verbs) {
                    boolean exists = ec.entity.find("moqui.ai.AiDomainTerm")
                        .condition("term", v).condition("termKind", "AI_TERM_VERB").condition("ownerScope", ownerScope).one() != null
                    if (exists) continue
                    String termId = ec.entity.sequencedIdPrimary("moqui.ai.AiDomainTerm", null, null)
                    ec.service.sync().name("create#moqui.ai.AiDomainTerm").parameters([termId: termId, term: v,
                        termKind: "AI_TERM_VERB", description: "Observed capability verb".toString(),
                        sourceType: "AI_TSRC_SEEDED", statusId: "AI_TERM_APPROVED", usageCount: 0, ownerScope: ownerScope]).call()
                    verbsAdded = (verbsAdded as int) + 1
                }
                ec.logger.info("seed#DomainGlossary: +${nounsAdded} nouns, +${verbsAdded} verbs")
            ]]></script>
        </actions>
    </service>
</services>
```
> The `entityNoun` map + `udmConcepts` list are a deliberate curated seed, not a reflection sweep over every table — the design says "domain objects … + key concepts from the UDM guide", not "every entity". The `presentEntityNames.any { it.endsWith ... }` guard keeps the seed honest to what the deployment actually has. `signalGuard` is set in context so the EECA capture (Task 4) skips these create writes.

- [ ] **Step 4: Run — passes**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS — first run adds nouns + verbs (`order` APPROVED+SEEDED; `list` verb present), second run adds zero and leaves a single `order` row.

- [ ] **Step 5: Commit** *(execution only)*

```bash
cd /Users/anilpatel/maarg-sd/notnaked/runtime/component/moqui-ai && \
git add service/ai/GlossaryServices.xml src/test/groovy/AiGlossaryTests.groovy && \
git commit -m "feat(ai): seed#DomainGlossary — derive nouns from entity model + UDM concepts, verbs from service catalog (idempotent)"
```

---

## Task 3: `find#DomainTerm` — lexical, APPROVED-only, ranked

**Files:**
- Modify: `service/ai/GlossaryServices.xml`
- Test: `src/test/groovy/AiGlossaryTests.groovy`

- [ ] **Step 1: Write a failing retrieval test (match + rank + APPROVED filter)**

Add to `AiGlossaryTests`:
```groovy
    def "find#DomainTerm matches term+synonym, filters APPROVED + kind, ranks by match x usageCount"() {
        given: "two approved nouns (one reinforced) + one suggested (must be excluded) + a synonym"
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", like: "FT%").deleteAll()
        ec.entity.makeValue("moqui.ai.AiDomainTerm").setAll([termId: "FT1", term: "return", termKind: "AI_TERM_NOUN",
            sourceType: "AI_TSRC_SEEDED", statusId: "AI_TERM_APPROVED", usageCount: 5]).create()
        ec.entity.makeValue("moqui.ai.AiDomainTerm").setAll([termId: "FT2", term: "refund", termKind: "AI_TERM_NOUN",
            sourceType: "AI_TSRC_SEEDED", statusId: "AI_TERM_APPROVED", usageCount: 0]).create()
        ec.entity.makeValue("moqui.ai.AiDomainTerm").setAll([termId: "FT3", term: "rebate", termKind: "AI_TERM_NOUN",
            sourceType: "AI_TSRC_LEARNED", statusId: "AI_TERM_SUGGESTED", usageCount: 99]).create()
        ec.entity.makeValue("moqui.ai.AiTermSynonym").setAll([termId: "FT1", synonym: "rma",
            sourceType: "AI_TSRC_CURATED", statusId: "AI_TERM_APPROVED"]).create()
        when: "search for an RMA (a synonym of return) — should resolve via synonym"
        Map bySyn = ec.service.sync().name("ai.GlossaryServices.find#DomainTerm")
            .parameters([text: "create an rma for the customer", kind: "AI_TERM_NOUN"]).call()
        then: "return matched (via synonym), suggested 'rebate' excluded despite huge usageCount"
        (bySyn.terms as List).find { it.term == "return" } != null
        (bySyn.terms as List).find { it.term == "rebate" } == null
        when: "a query that hits both 'return' and 'refund' literally"
        Map both = ec.service.sync().name("ai.GlossaryServices.find#DomainTerm")
            .parameters([text: "return refund", kind: "AI_TERM_NOUN"]).call()
        then: "'return' ranks above 'refund' (equal match, higher usageCount)"
        List terms = both.terms as List
        terms.findIndexOf { it.term == "return" } < terms.findIndexOf { it.term == "refund" }
        cleanup:
        ec.entity.find("moqui.ai.AiTermSynonym").condition("termId", "FT1").deleteAll()
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", like: "FT%").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```

- [ ] **Step 2: Run to confirm it fails**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — `find#DomainTerm` not found.

- [ ] **Step 3: Add `find#DomainTerm`**

Add to `GlossaryServices.xml` (tokenize `text`; for each approved term of the requested kind, score = token overlap on `term` OR any approved `AiTermSynonym.synonym`; rank by `score × (1 + usageCount)`; drop zero-score):
```xml
    <!-- Lexical retrieval backing the Composer's list#DomainTerm + propose#Naming. Match text tokens
         against term + approved synonyms; filter APPROVED (+ kind + scope); rank by match x usage. -->
    <service verb="find" noun="DomainTerm" authenticate="true">
        <in-parameters>
            <parameter name="text" required="true"/>
            <parameter name="kind"><description>AI_TERM_NOUN | AI_TERM_VERB; null = both</description></parameter>
            <parameter name="ownerScope"/>
            <parameter name="maxResults" type="Integer" default="20"/>
        </in-parameters>
        <out-parameters>
            <parameter name="terms" type="List"><description>ranked [termId, term, termKind, description, usageCount, score]</description></parameter>
        </out-parameters>
        <actions>
            <script><![CDATA[
                Set<String> tokens = (text.toLowerCase().replaceAll(/[^a-z0-9 ]/, " ").split(/\s+/) as List)
                    .findAll { it } as Set
                def find = ec.entity.find("moqui.ai.AiDomainTerm").condition("statusId", "AI_TERM_APPROVED")
                if (kind) find.condition("termKind", kind)
                if (ownerScope != null) find.condition("ownerScope", ownerScope)
                List<Map> scored = []
                for (def t in find.list()) {
                    // synonyms (approved only) for this term
                    Set<String> aliases = ec.entity.find("moqui.ai.AiTermSynonym")
                        .condition("termId", t.termId).condition("statusId", "AI_TERM_APPROVED")
                        .list().collect { (it.synonym as String).toLowerCase() } as Set
                    int score = 0
                    String tl = (t.term as String).toLowerCase()
                    if (tokens.contains(tl)) score += 2
                    else if (tokens.any { tl.contains(it) || it.contains(tl) }) score += 1
                    if (aliases.any { tokens.contains(it) }) score += 2
                    if (score == 0) continue
                    long usage = (t.usageCount ?: 0) as long
                    scored.add([termId: t.termId, term: t.term, termKind: t.termKind, description: t.description,
                        usageCount: usage, score: (score * (1 + usage))])
                }
                scored.sort { a, b -> (b.score as long) <=> (a.score as long) }
                terms = scored.take(maxResults as int)
            ]]></script>
        </actions>
    </service>
```
> Lexical-only by design (D4). The scoring is intentionally simple (exact token = 2, substring = 1, synonym hit = 2, weighted by `1+usageCount`); embeddings/semantic ranking is the Phase-6 upgrade and slots in behind this same service signature.

- [ ] **Step 4: Run — passes**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS — `rma` resolves to `return` via synonym; SUGGESTED `rebate` excluded; `return` ranks above `refund` on usage.

- [ ] **Step 5: Commit** *(execution only)*

```bash
cd /Users/anilpatel/maarg-sd/notnaked/runtime/component/moqui-ai && \
git add service/ai/GlossaryServices.xml src/test/groovy/AiGlossaryTests.groovy && \
git commit -m "feat(ai): find#DomainTerm — lexical match on term+synonym, APPROVED+kind filter, ranked by match x usage"
```

---

## Task 4: Capture naming signals — in-service hook + defensive EECA floor

**The capture-mechanism decision (resolved from framework internals — see open questions for the full reasoning):**

- `EntityValueBase.create()/update()` call `efi.runEecaRules(...)` directly (`framework/.../entity/EntityValueBase.java:1522,1541,1623,1696`). So an **EECA on `AiTool`/`AiAgent` fires on every write** — the keystone `store#` services, the Moqui auto `store#` service, a direct `EntityValue.store()`, and the data loader (unless `disableEntityEca(true)`).
- A **SECA on `store#AiTool`** fires *only* when that exact service is called — it would **miss** direct `EntityValue` writes and any future builder path that writes the entity without going through the service.
- **But** the rich signal context (`suggestedName`, `intentText`) exists **only at the `store#` service call** — the entity row alone cannot tell you what the Composer *proposed*. And we must **not** capture during seeding (`seed`/`ext` data loads, `seed#DomainGlossary`).

**Decision: two-part capture through one shared helper `capture#NamingSignal`.**
1. **Rich, in-service hook (primary).** Inside the keystone's `store#AiTool`/`store#AiAgent`, after the row is written, call `capture#NamingSignal` with the full context (`suggestedName`, `intentText`, `chosenName`, `userId`). This is where overrides become learnable.
2. **Defensive EECA (floor).** An `on-create`/`on-update` EECA on `AiTool`/`AiAgent` calls `capture#NamingSignal` with only what the row carries (`chosenName` = the name; `suggestedName` null) **unless** a context guard says "already captured / not real authoring". This guarantees a signal even for writes that bypass the service, without double-logging.
   - Guard: `capture#NamingSignal` is a **no-op** when `ec.context.signalGuard == true` (set by `seed#DomainGlossary` and by data-load steps) OR when an in-service capture for this exact write already ran (the in-service call sets `ec.context.signalCaptured = true` for the duration of the `store#` call; the EECA checks it).

This keeps the **single authoring gate** (keystone §6) as the semantic capture point while the EECA backstops non-service writes — matching the conventions' "prefer EECA … choose the mechanism that reliably captures both UI-authored and builder-authored writes."

**Files:**
- Modify: `service/ai/GlossaryServices.xml` (add `capture#NamingSignal`)
- Create: `entity/AiGlossaryEcas.eecas.xml`
- Modify: `MoquiConf.xml` (register the eeca file)
- Modify: `service/ai/AgentServices.xml` *(keystone file — the `store#AiTool`/`store#AiAgent` services)* — add the inline capture call
- Test: `src/test/groovy/AiGlossaryTests.groovy`

- [ ] **Step 1: Write a failing capture test**

Two cases: (a) calling `store#AiTool` with a proposed-vs-chosen name writes one `AiNamingSignal` with `wasOverridden=Y`; (b) a direct `EntityValue` create of an `AiAgent` (bypassing the service) still produces a signal via the EECA; (c) `seed#DomainGlossary` writes do **not** produce signals.
```groovy
    def "authoring a tool with an overridden name writes one AiNamingSignal (Y)"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiNamingSignal").condition("userId", "CapUser").deleteAll()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        when: "store#AiTool with a Composer proposal that the human overrode"
        // store#AiTool is the keystone service; it must accept suggestedName + intentText pass-through.
        ec.service.sync().name("store#AiTool").parameters([
            serviceName: "moqui.ai.test.TestServices.get#Echo", verb: "list", noun: "echoes",
            suggestedName: "get_echo", intentText: "list the echoes", description: "x"]).call()
        then: "exactly one signal, overridden, tool-name type"
        def sigs = ec.entity.find("moqui.ai.AiNamingSignal").condition("signalType", "AI_SIG_TOOL_NAME")
            .condition("intentText", "list the echoes").list()
        sigs.size() == 1
        sigs[0].suggestedName == "get_echo"
        sigs[0].chosenName == "list_echoes"   // toolName derived verb_noun (keystone §5)
        sigs[0].wasOverridden == "Y"
        cleanup:
        ec.entity.find("moqui.ai.AiNamingSignal").condition("intentText", "list the echoes").deleteAll()
        // delete the created tool per keystone PK (toolId) — look it up by serviceName
        ec.entity.find("moqui.ai.AiTool").condition("serviceName", "moqui.ai.test.TestServices.get#Echo").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "seed#DomainGlossary writes do not produce naming signals"() {
        given:
        ec.artifactExecution.disableAuthz()
        long before = ec.entity.find("moqui.ai.AiNamingSignal").count()
        when:
        ec.service.sync().name("ai.GlossaryServices.seed#DomainGlossary").parameters([:]).call()
        then:
        ec.entity.find("moqui.ai.AiNamingSignal").count() == before
        cleanup:
        ec.artifactExecution.enableAuthz()
    }
```
> The first test couples to keystone `store#AiTool` semantics (derived `toolName=verb_noun`, accepts `suggestedName`/`intentText` pass-through). If the keystone's `store#AiTool` does not yet accept `suggestedName`/`intentText`, add those as optional in-parameters there as part of this task (they are inert to the keystone's own logic; only `capture#NamingSignal` reads them). Keep this test if the keystone is present; otherwise gate Task 4 on the keystone (see Sequencing note).

- [ ] **Step 2: Run to confirm it fails**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — no `capture#NamingSignal`; no signal rows written by `store#AiTool`.

- [ ] **Step 3: Add `capture#NamingSignal` (the shared helper)**

Add to `GlossaryServices.xml`:
```xml
    <!-- Record one naming signal. Shared by the in-service hook (rich context) and the defensive EECA
         (floor). No-op when signalGuard is set (seeding) or when this write was already captured in-service. -->
    <service verb="capture" noun="NamingSignal" authenticate="false">
        <in-parameters>
            <parameter name="signalType" required="true"/><!-- AI_SIG_TOOL_NAME | AI_SIG_AGENT_NAME -->
            <parameter name="chosenName" required="true"/>
            <parameter name="suggestedName"/>
            <parameter name="intentText"/>
        </in-parameters>
        <out-parameters><parameter name="signalId"/></out-parameters>
        <actions>
            <if condition="ec.context.signalGuard == true"><return/></if>
            <set field="signalId" from="ec.entity.sequencedIdPrimary('moqui.ai.AiNamingSignal', null, null)"/>
            <set field="overridden" from="(suggestedName != null &amp;&amp; suggestedName != chosenName) ? 'Y' : 'N'"/>
            <service-call name="create#moqui.ai.AiNamingSignal" in-map="[signalId: signalId, signalType: signalType,
                intentText: intentText, suggestedName: suggestedName, chosenName: chosenName,
                wasOverridden: overridden, userId: ec.user.userId, fromDate: ec.user.nowTimestamp]"/>
        </actions>
    </service>
```

- [ ] **Step 4: Wire the in-service hook into the keystone `store#AiTool`/`store#AiAgent`**

In `service/ai/AgentServices.xml` (keystone), at the end of `store#AiTool`'s actions (after the row is written and `toolName` derived), add:
```xml
            <set field="ec.context.signalCaptured" value="true"/>
            <service-call name="ai.GlossaryServices.capture#NamingSignal" in-map="[signalType: 'AI_SIG_TOOL_NAME',
                chosenName: toolName, suggestedName: suggestedName, intentText: intentText]"/>
```
and the analogue in `store#AiAgent` (`signalType: 'AI_SIG_AGENT_NAME'`, `chosenName: agentName`). Add optional `suggestedName` + `intentText` in-parameters to both keystone services if absent. (Signal write failure must never fail authoring — wrap defensively or rely on `capture#`'s own non-fatal nature; do **not** add to `ec.message` from here per the SECA-logging convention.)

- [ ] **Step 5: Add the defensive EECA + register it**

`entity/AiGlossaryEcas.eecas.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<eecas xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/entity-eca-3.xsd">
    <!-- Floor: capture a naming signal for ANY AiTool write not already captured in-service and not a seed.
         The in-service hook (rich suggestedName/intentText) is preferred; this backstops direct writes. -->
    <eeca id="AiToolNamingSignal" entity="moqui.ai.AiTool" on-create="true" on-update="true">
        <condition><expression>ec.context.signalGuard != true &amp;&amp; ec.context.signalCaptured != true</expression></condition>
        <actions>
            <service-call name="ai.GlossaryServices.capture#NamingSignal"
                in-map="[signalType: 'AI_SIG_TOOL_NAME', chosenName: toolName]"/>
        </actions>
    </eeca>
    <eeca id="AiAgentNamingSignal" entity="moqui.ai.AiAgent" on-create="true" on-update="true">
        <condition><expression>ec.context.signalGuard != true &amp;&amp; ec.context.signalCaptured != true</expression></condition>
        <actions>
            <service-call name="ai.GlossaryServices.capture#NamingSignal"
                in-map="[signalType: 'AI_SIG_AGENT_NAME', chosenName: agentName]"/>
        </actions>
    </eeca>
</eecas>
```
Register in `MoquiConf.xml` under `<moqui-conf>` (confirm the exact element; Moqui auto-scans `entity/*.eecas.xml` in a component, but register explicitly if the boot log shows it isn't picked up):
```xml
    <entity-facade>
        <load-entity location="component://moqui-ai/entity/AiGlossaryEcas.eecas.xml"/>
    </entity-facade>
```
> Verify at implementation: many Moqui setups auto-load `entity/*.eecas.xml` by component scan (same way `entity/*Entities.xml` are found). If so, the `MoquiConf` entry is unnecessary — confirm via a boot-time log line or by the EECA test passing without it. The `condition` element wraps an `<expression>` per the entity-eca XSD; the EECA context has the entity fields flattened (so `toolName`/`agentName` are directly readable).

- [ ] **Step 6: Run — passes**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS — `store#AiTool` writes exactly one overridden signal (in-service path, not double-counted by the EECA); seeding writes none (guard). If the EECA double-logs, the `signalCaptured` guard is not visible to the EECA — fix by setting `ec.context.signalCaptured` *before* the entity write in the `store#` service, or de-dup in `capture#` by `(intentText, chosenName, fromDate-second)`.

- [ ] **Step 7: Commit** *(execution only)*

```bash
cd /Users/anilpatel/maarg-sd/notnaked/runtime/component/moqui-ai && \
git add service/ai/GlossaryServices.xml service/ai/AgentServices.xml \
        entity/AiGlossaryEcas.eecas.xml MoquiConf.xml src/test/groovy/AiGlossaryTests.groovy && \
git commit -m "feat(ai): capture#NamingSignal — in-service hook on store#AiTool/store#AiAgent + defensive EECA floor; seed-guarded"
```

---

## Task 5: Back the Composer — `list#DomainTerm` + `propose#Naming`

**Files:**
- Modify: `service/ai/GlossaryServices.xml`
- Test: `src/test/groovy/AiGlossaryTests.groovy`

- [ ] **Step 1: Write failing tests for the two Composer-facing services**

```groovy
    def "list#DomainTerm returns the grounding slice (thin wrapper over find#DomainTerm)"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeValue("moqui.ai.AiDomainTerm").setAll([termId: "LT1", term: "shipment", termKind: "AI_TERM_NOUN",
            sourceType: "AI_TSRC_SEEDED", statusId: "AI_TERM_APPROVED", usageCount: 1]).create()
        when:
        Map out = ec.service.sync().name("ai.GlossaryServices.list#DomainTerm")
            .parameters([text: "track a shipment", kind: "AI_TERM_NOUN"]).call()
        then:
        (out.terms as List).find { it.term == "shipment" } != null
        cleanup:
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", "LT1").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "propose#Naming snaps a raw verb/noun to the nearest approved glossary terms"() {
        given: "approved 'return' noun with synonym 'rma', approved 'list' verb"
        ec.artifactExecution.disableAuthz()
        ec.entity.makeValue("moqui.ai.AiDomainTerm").setAll([termId: "PN1", term: "return", termKind: "AI_TERM_NOUN",
            sourceType: "AI_TSRC_CURATED", statusId: "AI_TERM_APPROVED", usageCount: 3]).create()
        ec.entity.makeValue("moqui.ai.AiTermSynonym").setAll([termId: "PN1", synonym: "rma",
            sourceType: "AI_TSRC_CURATED", statusId: "AI_TERM_APPROVED"]).create()
        ec.entity.makeValue("moqui.ai.AiDomainTerm").setAll([termId: "PN2", term: "list", termKind: "AI_TERM_VERB",
            sourceType: "AI_TSRC_SEEDED", statusId: "AI_TERM_APPROVED", usageCount: 0]).create()
        when: "the Composer proposes a raw verb+noun using the dialect word 'rmas'"
        Map out = ec.service.sync().name("ai.GlossaryServices.propose#Naming")
            .parameters([proposedVerb: "list", proposedNoun: "rmas", intentText: "list all rmas"]).call()
        then: "noun snaps to the canonical 'return' (via synonym 'rma'); verb stays 'list'; grounded name emitted"
        out.verb == "list"
        out.noun == "return"
        out.toolName == "list_return"
        (out.groundingTerms as List).size() >= 1
        cleanup:
        ec.entity.find("moqui.ai.AiTermSynonym").condition("termId", "PN1").deleteAll()
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", like: "PN%").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```

- [ ] **Step 2: Run to confirm it fails**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — `list#DomainTerm` / `propose#Naming` not found.

- [ ] **Step 3: Add `list#DomainTerm` + `propose#Naming`**

Add to `GlossaryServices.xml`:
```xml
    <!-- Composer tool list_domain_terms: thin wrapper over find#DomainTerm (the grounding slice). -->
    <service verb="list" noun="DomainTerm" authenticate="true">
        <in-parameters>
            <parameter name="text" required="true"/><parameter name="kind"/><parameter name="ownerScope"/>
            <parameter name="maxResults" type="Integer" default="20"/>
        </in-parameters>
        <out-parameters><parameter name="terms" type="List"/></out-parameters>
        <actions>
            <service-call name="ai.GlossaryServices.find#DomainTerm" out-map="context"
                in-map="[text: text, kind: kind, ownerScope: ownerScope, maxResults: maxResults]"/>
        </actions>
    </service>

    <!-- Composer tool propose_naming (v1): given a raw verb/noun guess (from the LLM), snap each to the
         nearest APPROVED glossary term/synonym so the suggestion speaks the deployment's dialect. The
         human still edits. The LLM-proposal half lives in the Composer spec; this is the glossary-snap. -->
    <service verb="propose" noun="Naming" authenticate="true">
        <in-parameters>
            <parameter name="proposedVerb"/><parameter name="proposedNoun"/>
            <parameter name="intentText"/><parameter name="ownerScope"/>
        </in-parameters>
        <out-parameters>
            <parameter name="verb"/><parameter name="noun"/><parameter name="toolName"/>
            <parameter name="groundingTerms" type="List"/>
        </out-parameters>
        <actions>
            <set field="verb" from="proposedVerb"/><set field="noun" from="proposedNoun"/>
            <set field="groundingTerms" from="[]"/>
            <script><![CDATA[
                // snap noun
                if (proposedNoun) {
                    def n = ec.service.sync().name("ai.GlossaryServices.find#DomainTerm")
                        .parameters([text: proposedNoun, kind: "AI_TERM_NOUN", ownerScope: ownerScope, maxResults: 1]).call()
                    if (n.terms) { noun = (n.terms[0].term); groundingTerms.add(n.terms[0]) }
                }
                // snap verb
                if (proposedVerb) {
                    def v = ec.service.sync().name("ai.GlossaryServices.find#DomainTerm")
                        .parameters([text: proposedVerb, kind: "AI_TERM_VERB", ownerScope: ownerScope, maxResults: 1]).call()
                    if (v.terms) { verb = (v.terms[0].term); groundingTerms.add(v.terms[0]) }
                }
                if (verb && noun) toolName = (verb + "_" + noun).toLowerCase().replaceAll(/[^a-z0-9_]/, "_")
            ]]></script>
        </actions>
    </service>
```
> If the Composer spec already ships a `propose#Naming`, **do not duplicate it** — instead expose this snapping as a `snap#Naming` helper and have the Composer's `propose#Naming` call it after its LLM proposal. The design (§7) is explicit that `propose#Naming` "snaps to the nearest approved terms/synonyms". Decide the boundary at implementation; the test above asserts the snapping behavior regardless of which service name owns it.

- [ ] **Step 4: Run — passes**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS — `list#DomainTerm` returns the slice; `propose#Naming` snaps `rmas`→`return`, keeps `list`, emits `list_return` + grounding terms.

- [ ] **Step 5: Commit** *(execution only)*

```bash
cd /Users/anilpatel/maarg-sd/notnaked/runtime/component/moqui-ai && \
git add service/ai/GlossaryServices.xml src/test/groovy/AiGlossaryTests.groovy && \
git commit -m "feat(ai): list#DomainTerm + propose#Naming — back the Composer's grounding + glossary-snapped naming"
```

---

## Task 6: `promote#TermsFromSignals` — threshold → LEARNED + SUGGESTED

**Files:**
- Modify: `service/ai/GlossaryServices.xml`
- Test: `src/test/groovy/AiGlossaryTests.groovy`

- [ ] **Step 1: Write a failing promote test**

```groovy
    def "promote#TermsFromSignals inserts recurring chosen names as LEARNED + SUGGESTED past threshold"() {
        given: "three signals choosing 'rmas' (>= threshold 3), one choosing 'widget' (below)"
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiNamingSignal").condition("userId", "PromUser").deleteAll()
        3.times { i ->
            ec.entity.makeValue("moqui.ai.AiNamingSignal").setAll([signalId: "PS${i}", signalType: "AI_SIG_TOOL_NAME",
                intentText: "list rmas", suggestedName: "list_returns", chosenName: "list_rmas",
                wasOverridden: "Y", userId: "PromUser", fromDate: ec.user.nowTimestamp]).create()
        }
        ec.entity.makeValue("moqui.ai.AiNamingSignal").setAll([signalId: "PSW", signalType: "AI_SIG_TOOL_NAME",
            intentText: "make widget", suggestedName: "create_widget", chosenName: "create_widget",
            wasOverridden: "N", userId: "PromUser", fromDate: ec.user.nowTimestamp]).create()
        when:
        Map out = ec.service.sync().name("ai.GlossaryServices.promote#TermsFromSignals")
            .parameters([threshold: 3]).call()
        then: "the recurring chosen NOUN token ('rmas') becomes a LEARNED + SUGGESTED term; widget does not"
        (out.proposed as int) >= 1
        def rmas = ec.entity.find("moqui.ai.AiDomainTerm").condition("term", "rmas").one()
        rmas != null && rmas.statusId == "AI_TERM_SUGGESTED" && rmas.sourceType == "AI_TSRC_LEARNED"
        ec.entity.find("moqui.ai.AiDomainTerm").condition("term", "widget").one() == null
        when: "re-run is idempotent (already proposed -> not re-added)"
        Map out2 = ec.service.sync().name("ai.GlossaryServices.promote#TermsFromSignals").parameters([threshold: 3]).call()
        then:
        ec.entity.find("moqui.ai.AiDomainTerm").condition("term", "rmas").list().size() == 1
        cleanup:
        ec.entity.find("moqui.ai.AiDomainTerm").condition("term", "rmas").deleteAll()
        ec.entity.find("moqui.ai.AiNamingSignal").condition("userId", "PromUser").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```

- [ ] **Step 2: Run to confirm it fails**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — `promote#TermsFromSignals` not found.

- [ ] **Step 3: Add `promote#TermsFromSignals`**

Tokenize each signal's `chosenName` (split on `_`), drop tokens that are already approved verbs (so we propose the *noun*, e.g. `rmas` not `list`), count occurrences across signals, and for tokens at/above `threshold` that are not already glossary terms (any status), insert `LEARNED`+`SUGGESTED`:
```xml
    <!-- Scan naming signals; chosen-name tokens that recur >= threshold and aren't already glossary terms
         are PROPOSED (LEARNED + SUGGESTED) for a Curator to approve. Suggest-only (D2). Re-runnable. -->
    <service verb="promote" noun="TermsFromSignals" authenticate="true">
        <in-parameters>
            <parameter name="threshold" type="Integer" default="3"/>
            <parameter name="ownerScope"/>
        </in-parameters>
        <out-parameters><parameter name="proposed" type="Integer"/></out-parameters>
        <actions>
            <set field="proposed" from="0"/>
            <script><![CDATA[
                // approved verbs are the "known verb" set we strip out, leaving the noun token to learn
                Set<String> approvedVerbs = ec.entity.find("moqui.ai.AiDomainTerm")
                    .condition("termKind", "AI_TERM_VERB").condition("statusId", "AI_TERM_APPROVED")
                    .list().collect { (it.term as String).toLowerCase() } as Set
                Map<String,Integer> counts = [:]
                for (def s in ec.entity.find("moqui.ai.AiNamingSignal").list()) {
                    String chosen = (s.chosenName ?: "") as String
                    for (String tok in chosen.toLowerCase().split(/[_\s]+/)) {
                        if (!tok || approvedVerbs.contains(tok)) continue
                        counts.put(tok, (counts.get(tok) ?: 0) + 1)
                    }
                }
                for (Map.Entry<String,Integer> e in counts.entrySet()) {
                    if (e.value < (threshold as int)) continue
                    boolean exists = ec.entity.find("moqui.ai.AiDomainTerm")
                        .condition("term", e.key).condition("termKind", "AI_TERM_NOUN").condition("ownerScope", ownerScope).one() != null
                    if (exists) continue
                    String termId = ec.entity.sequencedIdPrimary("moqui.ai.AiDomainTerm", null, null)
                    ec.service.sync().name("create#moqui.ai.AiDomainTerm").parameters([termId: termId, term: e.key,
                        termKind: "AI_TERM_NOUN", description: "Learned from authoring (x${e.value})".toString(),
                        sourceType: "AI_TSRC_LEARNED", statusId: "AI_TERM_SUGGESTED", usageCount: e.value, ownerScope: ownerScope]).call()
                    proposed = (proposed as int) + 1
                }
            ]]></script>
        </actions>
    </service>
```
> v1 promotes the chosen *noun* token as a SUGGESTED noun (the richest learnable unit — e.g. the deployment's word "rmas"). Promoting it as a **synonym of an existing noun** (the deeper "rma ⇆ return" learning the design highlights) is a natural follow-up: when an overridden signal's suggested noun maps to a known term and the chosen noun differs, propose a SUGGESTED `AiTermSynonym` instead of a bare term. Noted as a refinement, not v1 scope.

- [ ] **Step 4: Run — passes**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS — `rmas` proposed as LEARNED+SUGGESTED; `widget` below threshold; re-run idempotent.

- [ ] **Step 5: Commit** *(execution only)*

```bash
cd /Users/anilpatel/maarg-sd/notnaked/runtime/component/moqui-ai && \
git add service/ai/GlossaryServices.xml src/test/groovy/AiGlossaryTests.groovy && \
git commit -m "feat(ai): promote#TermsFromSignals — recurring chosen names -> LEARNED+SUGGESTED (suggest-only, threshold)"
```

---

## Task 7: Curation services + Glossary screen + the full loop

**Files:**
- Modify: `service/ai/GlossaryServices.xml` (`store#`/`approve#`/`reject#DomainTerm`)
- Create: `screen/AiOps/Glossary.xml`; Modify: `screen/AiOps.xml`
- Test: `src/test/groovy/AiGlossaryTests.groovy`

- [ ] **Step 1: Write failing curation + end-to-end loop tests**

Curation unit tests + the integration loop the design §10 calls for (override → signal → promote → approve → reflected in the next `propose#Naming`):
```groovy
    def "approve#DomainTerm flips SUGGESTED -> APPROVED; reject#DomainTerm -> REJECTED"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeValue("moqui.ai.AiDomainTerm").setAll([termId: "CU1", term: "rmas", termKind: "AI_TERM_NOUN",
            sourceType: "AI_TSRC_LEARNED", statusId: "AI_TERM_SUGGESTED", usageCount: 3]).create()
        when:
        ec.service.sync().name("ai.GlossaryServices.approve#DomainTerm").parameters([termId: "CU1"]).call()
        then:
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", "CU1").one().statusId == "AI_TERM_APPROVED"
        when:
        ec.service.sync().name("ai.GlossaryServices.reject#DomainTerm").parameters([termId: "CU1"]).call()
        then:
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", "CU1").one().statusId == "AI_TERM_REJECTED"
        cleanup:
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", "CU1").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "store#DomainTerm creates a CURATED term and can attach a synonym"() {
        given: ec.artifactExecution.disableAuthz()
        when:
        Map r = ec.service.sync().name("ai.GlossaryServices.store#DomainTerm")
            .parameters([term: "backorder", termKind: "AI_TERM_NOUN", description: "demand beyond stock", synonym: "bo"]).call()
        then:
        def t = ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", r.termId).one()
        t.sourceType == "AI_TSRC_CURATED" && t.statusId == "AI_TERM_APPROVED"
        ec.entity.find("moqui.ai.AiTermSynonym").condition("termId", r.termId).condition("synonym", "bo").one() != null
        cleanup:
        ec.entity.find("moqui.ai.AiTermSynonym").condition("termId", r.termId).deleteAll()
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", r.termId).deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "the full loop: override signal -> promote -> approve as synonym -> reflected in next propose#Naming"() {
        given: "an approved canonical noun 'return' (no dialect yet) + verb 'list'"
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", like: "LOOP%").deleteAll()
        ec.entity.find("moqui.ai.AiNamingSignal").condition("userId", "LoopUser").deleteAll()
        ec.entity.makeValue("moqui.ai.AiDomainTerm").setAll([termId: "LOOP_R", term: "return", termKind: "AI_TERM_NOUN",
            sourceType: "AI_TSRC_SEEDED", statusId: "AI_TERM_APPROVED", usageCount: 0]).create()
        ec.entity.makeValue("moqui.ai.AiDomainTerm").setAll([termId: "LOOP_L", term: "list", termKind: "AI_TERM_VERB",
            sourceType: "AI_TSRC_SEEDED", statusId: "AI_TERM_APPROVED", usageCount: 0]).create()
        and: "the deployment repeatedly renames list_returns -> list_rmas (signals)"
        3.times { i ->
            ec.service.sync().name("ai.GlossaryServices.capture#NamingSignal").parameters([signalType: "AI_SIG_TOOL_NAME",
                chosenName: "list_rmas", suggestedName: "list_returns", intentText: "list rmas"]).call()
        }
        when: "promote proposes 'rmas'; the Curator approves it AS A SYNONYM of return"
        ec.service.sync().name("ai.GlossaryServices.promote#TermsFromSignals").parameters([threshold: 3]).call()
        // Curator decides 'rmas' is the dialect for 'return': add it as an approved synonym, reject the bare term
        ec.service.sync().name("ai.GlossaryServices.store#DomainTerm")
            .parameters([termId: "LOOP_R", synonym: "rmas"]).call()   // store onto existing canonical term
        def bare = ec.entity.find("moqui.ai.AiDomainTerm").condition("term", "rmas").condition("termKind", "AI_TERM_NOUN").one()
        if (bare) ec.service.sync().name("ai.GlossaryServices.reject#DomainTerm").parameters([termId: bare.termId]).call()
        and: "a later propose#Naming for the same dialect intent now snaps to 'return'"
        Map out = ec.service.sync().name("ai.GlossaryServices.propose#Naming")
            .parameters([proposedVerb: "list", proposedNoun: "rmas", intentText: "list all rmas"]).call()
        then: "the learned dialect resolved: 'rmas' -> canonical 'return'"
        out.noun == "return"
        out.toolName == "list_return"
        cleanup:
        ec.entity.find("moqui.ai.AiTermSynonym").condition("termId", "LOOP_R").deleteAll()
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", like: "LOOP%").deleteAll()
        ec.entity.find("moqui.ai.AiDomainTerm").condition("term", "rmas").deleteAll()
        ec.entity.find("moqui.ai.AiNamingSignal").condition("userId", "LoopUser").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```

- [ ] **Step 2: Run to confirm it fails**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — `store#`/`approve#`/`reject#DomainTerm` not found.

- [ ] **Step 3: Add the curation services**

Add to `GlossaryServices.xml`:
```xml
    <!-- Curator: create-or-update a CURATED term (and optionally attach an approved synonym). store = idempotent. -->
    <service verb="store" noun="DomainTerm" authenticate="true">
        <in-parameters>
            <parameter name="termId"/><!-- when set, update / attach synonym to this term -->
            <parameter name="term"/><parameter name="termKind"/><parameter name="description"/>
            <parameter name="synonym"/><parameter name="ownerScope"/>
            <parameter name="statusId" default-value="AI_TERM_APPROVED"/>
        </in-parameters>
        <out-parameters><parameter name="termId"/></out-parameters>
        <actions>
            <if condition="!termId">
                <set field="termId" from="ec.entity.sequencedIdPrimary('moqui.ai.AiDomainTerm', null, null)"/>
                <service-call name="create#moqui.ai.AiDomainTerm" in-map="[termId: termId, term: term, termKind: termKind,
                    description: description, sourceType: 'AI_TSRC_CURATED', statusId: statusId, usageCount: 0, ownerScope: ownerScope]"/>
                <else>
                    <!-- update existing term fields if provided -->
                    <service-call name="update#moqui.ai.AiDomainTerm" in-map="[termId: termId, term: term,
                        termKind: termKind, description: description, statusId: statusId]"/>
                </else>
            </if>
            <if condition="synonym">
                <service-call name="store#moqui.ai.AiTermSynonym" in-map="[termId: termId, synonym: synonym.toLowerCase(),
                    sourceType: 'AI_TSRC_CURATED', statusId: 'AI_TERM_APPROVED']"/>
            </if>
        </actions>
    </service>

    <service verb="approve" noun="DomainTerm" authenticate="true">
        <in-parameters><parameter name="termId" required="true"/></in-parameters>
        <actions><service-call name="update#moqui.ai.AiDomainTerm" in-map="[termId: termId, statusId: 'AI_TERM_APPROVED']"/></actions>
    </service>

    <service verb="reject" noun="DomainTerm" authenticate="true">
        <in-parameters><parameter name="termId" required="true"/></in-parameters>
        <actions><service-call name="update#moqui.ai.AiDomainTerm" in-map="[termId: termId, statusId: 'AI_TERM_REJECTED']"/></actions>
    </service>
```
> `update#`/`store#` on the entity-auto services only set fields that are non-null in the in-map (Moqui skips unset). The `store#DomainTerm` with `termId` + `synonym` only (the loop test) attaches a synonym to the existing canonical term without touching its other fields.

- [ ] **Step 4: Add the Glossary screen + register it**

`screen/AiOps/Glossary.xml` (mirror `Approvals.xml`: per-row approve/reject transitions, two `entity-find`s — SUGGESTED queue + APPROVED list — and a form to add a CURATED term). Transitions call the curation services:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd"
        default-menu-title="Glossary" require-authentication="true">
    <transition name="approve"><service-call name="ai.GlossaryServices.approve#DomainTerm" in-map="[termId:termId]"/><default-response url="."/></transition>
    <transition name="reject"><service-call name="ai.GlossaryServices.reject#DomainTerm" in-map="[termId:termId]"/><default-response url="."/></transition>
    <transition name="addTerm"><service-call name="ai.GlossaryServices.store#DomainTerm"
        in-map="[term:term, termKind:termKind, description:description, synonym:synonym]"/><default-response url="."/></transition>
    <transition name="seed"><service-call name="ai.GlossaryServices.seed#DomainGlossary"/><default-response url="."/></transition>
    <transition name="promote"><service-call name="ai.GlossaryServices.promote#TermsFromSignals" in-map="[threshold:3]"/><default-response url="."/></transition>

    <actions>
        <entity-find entity-name="moqui.ai.AiDomainTerm" list="suggestedList">
            <econdition field-name="statusId" value="AI_TERM_SUGGESTED"/><order-by field-name="-usageCount,term"/></entity-find>
        <entity-find entity-name="moqui.ai.AiDomainTerm" list="approvedList">
            <econdition field-name="statusId" value="AI_TERM_APPROVED"/><order-by field-name="termKind,term"/></entity-find>
    </actions>
    <widgets>
        <container-box><box-header title="Suggested terms — approve, reject, or curate"/><box-body>
            <label text="No suggestions pending." type="p" condition="!suggestedList"/>
            <form-list name="Suggested" list="suggestedList" skip-form="true" condition="suggestedList">
                <field name="term"><default-field><display/></default-field></field>
                <field name="termKind"><default-field title="Kind"><display/></default-field></field>
                <field name="sourceType"><default-field title="Source"><display/></default-field></field>
                <field name="usageCount"><default-field title="Seen"><display/></default-field></field>
                <field name="description"><default-field><display/></default-field></field>
                <field name="approveAction"><default-field title="">
                    <link url="approve" text="Approve" link-type="hidden-form-link" btn-type="success"><parameter name="termId"/></link></default-field></field>
                <field name="rejectAction"><default-field title="">
                    <link url="reject" text="Reject" link-type="hidden-form-link" btn-type="danger"><parameter name="termId"/></link></default-field></field>
            </form-list>
        </box-body></container-box>

        <container-box><box-header title="Approved glossary"/><box-body>
            <form-list name="Approved" list="approvedList" skip-form="true">
                <field name="term"><default-field><display/></default-field></field>
                <field name="termKind"><default-field title="Kind"><display/></default-field></field>
                <field name="usageCount"><default-field title="Usage"><display/></default-field></field>
                <field name="description"><default-field><display/></default-field></field>
            </form-list>
        </box-body></container-box>

        <container-box><box-header title="Add a curated term / maintain the glossary"/><box-body>
            <form-single name="AddTerm" transition="addTerm">
                <field name="term"><default-field title="Term"><text-line size="20"/></default-field></field>
                <field name="termKind"><default-field title="Kind"><drop-down>
                    <option key="AI_TERM_NOUN" text="Noun"/><option key="AI_TERM_VERB" text="Verb"/></drop-down></default-field></field>
                <field name="synonym"><default-field title="Synonym (optional)"><text-line size="20"/></default-field></field>
                <field name="description"><default-field title="Description"><text-area rows="2" cols="60"/></default-field></field>
                <field name="submitField"><default-field title=""><submit text="Add term"/></default-field></field>
            </form-single>
            <form-single name="Maintenance" transition="seed">
                <field name="reseed"><default-field title=""><submit text="Re-seed from model + services"/></default-field></field></form-single>
            <form-single name="PromoteForm" transition="promote">
                <field name="promote"><default-field title=""><submit text="Promote terms from signals"/></default-field></field></form-single>
        </box-body></container-box>
    </widgets>
</screen>
```
Add to `screen/AiOps.xml` subscreens (after Conversations, `menu-index="7"`):
```xml
        <subscreens-item name="Glossary" menu-title="Glossary" menu-index="7" location="component://moqui-ai/screen/AiOps/Glossary.xml"/>
```
> Security: `data/AiSecurityData.xml` already grants `ALL_USERS` to `component://moqui-ai/screen/.*` (screens + transitions) and `ai\..*` services — the new screen + its transitions match, so no security change is needed. Confirm by loading the screen in the running app during acceptance.

- [ ] **Step 5: Run — full suite green**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS — curation unit tests green; the full-loop test green (override signals → promote proposes `rmas` → Curator attaches it as an approved synonym of `return` + rejects the bare term → a later `propose#Naming` snaps `rmas`→`return`→`list_return`). Report total test count; confirm the pre-existing live tests still pass.

- [ ] **Step 6: Commit** *(execution only)*

```bash
cd /Users/anilpatel/maarg-sd/notnaked/runtime/component/moqui-ai && \
git add service/ai/GlossaryServices.xml screen/AiOps/Glossary.xml screen/AiOps.xml \
        src/test/groovy/AiGlossaryTests.groovy && \
git commit -m "feat(ai): curation services (store/approve/reject#DomainTerm) + Glossary console tab + end-to-end learning-loop test"
```

---

## Self-Review

**Spec coverage** (vs. the design spec §4–§10):
- Three entities `AiDomainTerm`/`AiTermSynonym`/`AiNamingSignal` + statuses (StatusItem) + enums (Enumeration) → Task 1. ✅
- `seed#DomainGlossary` (nouns from entity model + UDM concepts; verbs from exposable services; SEEDED+APPROVED; idempotent/re-runnable) → Task 2. ✅
- Signal capture hook on `store#AiTool`/`store#AiAgent` (the single authoring gate), reliably catching UI + builder writes → Task 4 (in-service rich hook + EECA floor + seed guard). ✅
- `find#DomainTerm` (lexical term+synonym, APPROVED+kind filter, rank by match × usageCount) → Task 3. ✅
- Backs the Composer's `list#DomainTerm` + `propose#Naming` (glossary snapping) → Task 5. ✅
- `promote#TermsFromSignals` (threshold → LEARNED+SUGGESTED, suggest-only) → Task 6. ✅
- Curation `store#`/`approve#`/`reject#DomainTerm` + Glossary screen under AiOps → Task 7. ✅
- Unit tests + the integration loop (override→signal→promote→approve→reflected-in-next-suggestion) → Tasks 3,4,6 units + Task 7 loop. ✅
- v1 = lexical + suggest-only; embeddings/auto-promote/tenant scoping deferred → respected throughout; `ownerScope` reserved. ✅

**Placeholder scan:** entities, seed data, and all eight services are concrete code. The Glossary screen is concrete. The one coupling point is the keystone `store#AiTool`/`store#AiAgent` (Task 4 Step 4) — concrete additions to a sibling-spec file, gated by the Sequencing note.

**Type/name consistency:** statuses `AI_TERM_SUGGESTED/APPROVED/REJECTED`; enums `AI_TERM_NOUN/VERB`, `AI_TSRC_SEEDED/LEARNED/CURATED`, `AI_SIG_TOOL_NAME/AGENT_NAME` used identically across entities, seed data, services, EECA, and tests. Service names follow verb conventions (`seed`/`find`/`list`/`propose`/`promote`/`store`/`approve`/`reject`/`capture`). `find#DomainTerm` out `terms` shape (`[termId, term, termKind, description, usageCount, score]`) consumed unchanged by `list#DomainTerm` and `propose#Naming`.

**Risk notes:** (1) the EECA double-log guard relies on `ec.context.signalCaptured` being visible to the after-write EECA — verified the EECA runs in the service's context, but confirm at implementation and fall back to a `(intentText,chosenName)` de-dup in `capture#` if needed. (2) `seed#DomainGlossary` over-/under-seeding nouns is curated by hand (the `entityNoun` map) — deliberate, but revisit the list against the live NotNaked model. (3) verb seeding pulls *every* service verb including framework/internal ones — acceptable as a soft observed vocabulary, but consider filtering to exposable/`AiTool`-eligible services in a follow-up so the verb glossary isn't noisy.

---

## Open questions / risks

1. **EECA vs. service-hook capture mechanism (resolved, but confirm one detail).** The framework facts are settled: `EntityValueBase.create()/update()` fire EECA on every write (so an entity EECA catches direct writes the loader/builder make), while a SECA on `store#AiTool` only fires when that service is invoked (missing direct writes); and the rich `suggestedName`/`intentText` context exists **only** at the `store#` call, not on the row. Hence the chosen **two-part** design: rich in-service capture (primary) + a defensive EECA floor (catch-all), de-duped by an `ec.context` guard and seed-guarded. **The one thing to verify at implementation:** that the after-write EECA can see `ec.context.signalCaptured` set by the enclosing `store#` service (same ExecutionContext). If Moqui clears/forks context between the service body and the EECA, switch the de-dup to a content check in `capture#NamingSignal` (`(intentText, chosenName, same-second fromDate)`), or set `signalCaptured` in `pre-service` rather than mid-actions. Also confirm whether `entity/*.eecas.xml` is auto-scanned per component (then the `MoquiConf` `<load-entity>` is redundant).
2. **Lexical vs. embeddings boundary (the v1 line).** v1 retrieval is deliberately lexical (token overlap on term + approved synonyms, weighted by `usageCount`) — fast, explainable, zero new infra, and the Composer's LLM already adds semantic flex on top. The risk is recall: a query whose words don't lexically overlap any term/synonym returns nothing (e.g. "send back a product" won't hit `return` without the synonym). Embeddings (Phase 6, `docs/plans/2026-06-02-phase6-knowledge-retrieval.md`) close that gap and slot in **behind the same `find#DomainTerm` signature** — the service contract is the seam. **Decision needed:** do we ship v1 lexical-only (recommended — the design says so) and treat thin recall as the forcing function for curation (people add the missing synonym), or pull a minimal embedding index forward? Recommend lexical-only for v1.
3. **Promote granularity — bare term vs. synonym (design's richest signal).** v1 `promote#TermsFromSignals` proposes the recurring chosen *noun token* as a SUGGESTED noun. The design highlights overrides (`list_returns`→`list_rmas`) as teaching the **synonym** "rma ⇆ return". Promoting directly to a *suggested synonym of the matched canonical term* (when the overridden suggestion maps to a known term) is more faithful but needs a mapping heuristic (which canonical term does the suggested name belong to?). The Task 7 loop test exercises the **human** doing that mapping (Curator attaches `rmas` as a synonym). **Decision needed:** is human-mediated synonym mapping enough for v1 (recommended), or should `promote#` attempt auto-suggesting synonyms?
4. **Promote threshold + any auto-approval.** Threshold defaults to 3 and is a parameter. The design asks whether auto-approval is *ever* allowed (e.g. a `SEEDED`-equivalent confidence). v1 = never (suggest-only, D2). Confirm the default threshold and whether `promote#` should run on a schedule (a Moqui `ServiceJob`) vs. only the Glossary-screen button + on-demand.
5. **`seed#DomainGlossary` invocation + the noun curation list.** Confirm where the install-time call lives (an `install`/`ext` data file `service-call`, or a post-load `SystemMessage`/job) so a fresh install gets a populated glossary without a manual button press. Separately, the curated `entityNoun`/`udmConcepts` lists in the seed service are hand-maintained — confirm they match the live NotNaked/UDM model, and decide whether to read concept terms from the UDM guide doc directly (out of scope here; would couple the service to a doc parse).
6. **Tenant scoping rollout.** `ownerScope` is reserved (null=global) and threaded through `find#`/`seed#`/`promote#`/`store#`. When multi-tenant lands (its own spec), confirm the unique index `(term, termKind, ownerScope)` and the retrieval scope-fallback (global + tenant overlay) behave as intended — noted, not built.
7. **Verb-glossary noise.** Seeding verbs from *all* known services includes framework/internal verbs. v1 accepts this as a soft vocabulary; a follow-up could restrict to verbs of services that are `AiTool`-eligible (exposable) once the keystone catalog is the source of truth.
