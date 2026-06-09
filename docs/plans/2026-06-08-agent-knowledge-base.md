# Agent Knowledge Base — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:subagent-driven-development`
> (recommended) or `superpowers:executing-plans`. Steps use checkbox (`- [ ]`) syntax for
> tracking. Build TDD — tests run green before the next task starts.

**Goal:** Add a managed knowledge base to `moqui-ai` so agents can be assigned curated prose
topics that are automatically injected into the system context on every run. The Composer agent
is the first consumer; operational agents follow the same mechanism.

**Architecture:**

```
knowledge/oms-domain-primer.md  ←── component:// file (git-versioned)
           │
           │  contentLocation pointer
           ▼
AiKnowledgeTopic  ──────────────────────────────────────────────────
  topicId │ topicName │ contentLocation │ statusId │ fromDate/thruDate
           │
           │  AiAgentKnowledge (grant table, mirrors AiAgentTool)
           ▼
AiAgent  ──────────────────────────────────────────────────────────
  agentId │ … │ knowledgeMaxChars (new field)
           │
           │  KnowledgeServices.find#AgentKnowledge
           │  (filters: APPROVED + effective + assigned → resolves bodies)
           ▼
ContextAssembler.withKnowledge(systemPrompt, topics)
           │
           ▼
AgentRunner (line 125, unconditional — outside contextStrategy branch)
  sysCtx = ContextAssembler.withKnowledge(sysCtx, loadAgentKnowledge(agentId, cap))
  // then: withFacts / withSummary layered on top (contextStrategy=window only)
```

**Storage:** Bodies live as `.md` files in `knowledge/` under the component root, read via
`ec.resource.getLocationText("component://moqui-ai/knowledge/<slug>.md", true)`. No DB blob.
The entity (`AiKnowledgeTopic`) holds only what the file cannot: agent assignment,
lifecycle status, effective dates, and the `contentLocation` pointer. The `.md` is body-only
— no frontmatter, nothing parsed into the entity.

**Truncation (D1 — decided):** When the total body exceeds `knowledgeMaxChars`, drop whole
topics (topic-order, ascending by `topicName`) until the remainder fits. Every included topic
is complete. Partial bodies are never emitted. Topics dropped are listed in a `context_trim`
`AiAgentRunStep`.

**Tech Stack:** Groovy 3, Moqui XML services, Moqui screen DSL, Spock 2.1. No new
infrastructure — `component://` is a pure `:framework` API.

**Source of truth:** `docs/specs/2026-06-08-agent-knowledge-base-design.md`.

**Mirrors:** `AiGlossaryEntities.xml` → entity shape; `AiGlossaryData.xml` → seed shape;
`GlossaryServices.xml` → service verbs; `AiGlossaryTests.groovy` → test structure;
`screen/AiOps/Glossary.xml` → screen shape.

**Not in scope (this plan):**
- Self-learning / promote `AiConversationFact` → draft knowledge topic
- Solr / semantic search tier (`search#Knowledge` tool)
- Per-fact `AiKnowledgeItem` child entity
- Per-tenant `ownerScope` activation
- Runtime-writable bodies (dbresource:// or file:// location)
- In-browser body editor in the AiOps console (body is read-only in v1)
- External-doc import / frontmatter interchange format
- Wiki / documents tier

---

## File Structure

```
runtime/component/moqui-ai/
│
├── knowledge/                                          ← new; bodies here
│   └── oms-domain-primer.md                           ← already created (Task 4)
│
├── entity/
│   ├── AiKnowledgeEntities.xml                        ← new (Task 1)
│   └── AiEntities.xml                                 ← modify: add knowledgeMaxChars (Task 1)
│
├── data/
│   ├── AiKnowledgeData.xml                            ← new: status/flow seed (Task 1)
│   └── AiComposerKnowledgeData.xml                    ← new: topic + grant seed (Task 4)
│
├── MoquiConf.xml                                      ← modify: add ai_knowledge_max_chars (Task 1)
│
├── service/ai/
│   └── KnowledgeServices.xml                          ← new (Task 2)
│
├── src/main/groovy/org/moqui/ai/
│   ├── ContextAssembler.groovy                        ← modify: add withKnowledge (Task 3)
│   └── AgentRunner.groovy                             ← modify: hook + loadAgentKnowledge (Task 3)
│
├── screen/AiOps/
│   ├── Knowledge.xml                                  ← new: topic list (Task 5)
│   ├── KnowledgeTopic.xml                             ← new: topic detail / edit (Task 5)
│   └── Agents.xml                                     ← modify: add knowledge grants section (Task 5)
│
├── service/ai/
│   └── KnowledgeRestApi.xml                           ← new: REST endpoints (Task 6)
│
└── src/test/groovy/org/moqui/ai/
    ├── AiKnowledgeTests.groovy                        ← new (Task 7)
    └── MoquiSuite.groovy                              ← modify: add AiKnowledgeTests (Task 7)
```

---

## Task 1 — Entities, config, and status seed

Everything else depends on the data model. Build this first; all later tasks reference these
entity names and status values.

**Files:**
- Create `entity/AiKnowledgeEntities.xml`
- Modify `entity/AiEntities.xml` (add `knowledgeMaxChars` to `AiAgent`)
- Create `data/AiKnowledgeData.xml`
- Modify `MoquiConf.xml` (add `ai_knowledge_max_chars`)

### Step 1 — `AiKnowledgeEntities.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<entities xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/entity-definition-3.xsd">

    <entity entity-name="AiKnowledgeTopic" package="moqui.ai">
        <field name="topicId"         type="id"          is-pk="true"/>
        <field name="topicName"       type="text-short"/>   <!-- unique; human label -->
        <field name="description"     type="text-long"/>    <!-- operator-facing note -->
        <field name="contentLocation" type="text-medium"/>  <!-- component://moqui-ai/knowledge/<slug>.md -->
        <field name="statusId"        type="id"/>           <!-- AiKnowledgeStatus -->
        <field name="fromDate"        type="date-time"/>    <!-- null = open -->
        <field name="thruDate"        type="date-time"/>    <!-- null = open -->
        <field name="ownerScope"      type="id"/>           <!-- reserved; null = global in v1 -->
        <field name="createdByUserId" type="id"/>
        <relationship type="one" related="moqui.basic.StatusItem" short-alias="status"/>
        <index name="AI_KNOW_TOPIC_NAME" unique="true">
            <index-field name="topicName"/>
        </index>
    </entity>

    <entity entity-name="AiAgentKnowledge" package="moqui.ai">
        <field name="agentId"  type="id" is-pk="true"/>
        <field name="topicId"  type="id" is-pk="true"/>
        <relationship type="one"      related="moqui.ai.AiAgent"         short-alias="agent"/>
        <relationship type="one-nofk" related="moqui.ai.AiKnowledgeTopic" short-alias="topic"/>
    </entity>

</entities>
```

- [ ] Write `entity/AiKnowledgeEntities.xml` with the XML above.

### Step 2 — Add `knowledgeMaxChars` to `AiAgent`

Add this field after `contextWindowChars` in `entity/AiEntities.xml`:

```xml
<field name="knowledgeMaxChars" type="number-integer">
    <description>Optional per-agent override of the injected-knowledge char cap.
    Null = use ai_knowledge_max_chars config default (24000).</description>
</field>
```

- [ ] Edit `entity/AiEntities.xml` to add the field in place (pre-release; no extend-entity needed).

### Step 3 — `data/AiKnowledgeData.xml`

Status seed follows `data/AiGlossaryData.xml` exactly. Use `ext-seed` reader type.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<entity-facade-xml type="ext-seed">

    <moqui.basic.StatusType statusTypeId="AiKnowledgeStatus"
        description="AI Knowledge Topic Status"/>

    <moqui.basic.StatusItem statusTypeId="AiKnowledgeStatus"
        statusId="AI_KNOW_DRAFT"    statusCode="DRAFT"    description="Draft"    sequenceNum="1"/>
    <moqui.basic.StatusItem statusTypeId="AiKnowledgeStatus"
        statusId="AI_KNOW_APPROVED" statusCode="APPROVED" description="Approved" sequenceNum="2"/>
    <moqui.basic.StatusItem statusTypeId="AiKnowledgeStatus"
        statusId="AI_KNOW_ARCHIVED" statusCode="ARCHIVED" description="Archived" sequenceNum="3"/>

    <moqui.basic.StatusFlow statusFlowId="AiKnowledgeFlow"
        statusTypeId="AiKnowledgeStatus" description="AI Knowledge Topic Flow"/>

    <moqui.basic.StatusFlowTransition statusFlowId="AiKnowledgeFlow"
        statusId="AI_KNOW_DRAFT"    toStatusId="AI_KNOW_APPROVED"/>
    <moqui.basic.StatusFlowTransition statusFlowId="AiKnowledgeFlow"
        statusId="AI_KNOW_APPROVED" toStatusId="AI_KNOW_ARCHIVED"/>
    <moqui.basic.StatusFlowTransition statusFlowId="AiKnowledgeFlow"
        statusId="AI_KNOW_ARCHIVED" toStatusId="AI_KNOW_APPROVED"/>

</entity-facade-xml>
```

- [ ] Write `data/AiKnowledgeData.xml`.

### Step 4 — Add config default to `MoquiConf.xml`

```xml
<default-property name="ai_knowledge_max_chars" value="24000"/>
```

Add after the existing `ai_*` properties.

- [ ] Edit `MoquiConf.xml`.

### Step 5 — Verify

- [ ] `./gradlew load` (or equivalent test setup) — entities load with no errors.
- [ ] Confirm `AI_KNOW_DRAFT`, `AI_KNOW_APPROVED`, `AI_KNOW_ARCHIVED` StatusItems exist.
- [ ] Confirm `AiAgent.knowledgeMaxChars` field is present in the entity definition.

---

## Task 2 — Services (`KnowledgeServices.xml`)

Services are the central layer — screens, REST, and the runner all call the same ones.
Mirrors `GlossaryServices.xml` verbs and structure.

**Files:** Create `service/ai/KnowledgeServices.xml`

### Data-flow diagram

```
store#KnowledgeTopic
  validate contentLocation exists (ec.resource.getLocationReference(loc).exists())
  upsert AiKnowledgeTopic row (statusId defaults AI_KNOW_DRAFT)
  → topicId

approve#KnowledgeTopic
  check current statusId = AI_KNOW_DRAFT (else error)
  transition → AI_KNOW_APPROVED via StatusFlowTransition

archive#KnowledgeTopic
  transition → AI_KNOW_ARCHIVED

store#AgentKnowledge
  check topic exists AND statusId != AI_KNOW_ARCHIVED
  upsert AiAgentKnowledge (agentId, topicId)

revoke#AgentKnowledge
  delete AiAgentKnowledge (agentId, topicId)

find#AgentKnowledge                                ← single source of truth
  entity-find AiAgentKnowledge WHERE agentId = ?
    JOIN AiKnowledgeTopic WHERE statusId = AI_KNOW_APPROVED
      AND (fromDate IS NULL OR fromDate <= now)
      AND (thruDate IS NULL OR thruDate > now)
  ORDER BY topicName ASC
  FOR EACH topic:
    content = ec.resource.getLocationText(contentLocation, true)
    IF content IS NULL → ec.logger.warn + skip (never fail run)
  → topics: List<Map>[topicId, topicName, content]
```

### Step 1 — `store#KnowledgeTopic`

```xml
<service verb="store" noun="KnowledgeTopic" authenticate="true">
    <in-parameters>
        <parameter name="topicId"/>
        <parameter name="topicName" required="true"/>
        <parameter name="description"/>
        <parameter name="contentLocation" required="true"/>
        <parameter name="fromDate" type="Timestamp"/>
        <parameter name="thruDate" type="Timestamp"/>
    </in-parameters>
    <out-parameters>
        <parameter name="topicId"/>
    </out-parameters>
    <actions>
        <!-- validate body file exists -->
        <if condition="!ec.resource.getLocationReference(contentLocation).exists()">
            <return error="true"
                message="Knowledge body file not found: ${contentLocation}. Create the .md file under knowledge/ before registering the topic."/>
        </if>
        <if condition="!topicId">
            <set field="topicId" from="ec.entity.sequencedIdPrimary('moqui.ai.AiKnowledgeTopic', null, null)"/>
        </if>
        <entity-make-value entity-name="moqui.ai.AiKnowledgeTopic" value-field="topic">
            <field-map field-name="topicId"/>
        </entity-make-value>
        <entity-set value-field="topic" include="topicId,topicName,description,contentLocation,fromDate,thruDate"/>
        <if condition="!topic.statusId"><set field="topic.statusId" value="AI_KNOW_DRAFT"/></if>
        <if condition="!topic.createdByUserId">
            <set field="topic.createdByUserId" from="ec.user.userId"/>
        </if>
        <entity-create-or-update value-field="topic"/>
    </actions>
</service>
```

- [ ] Write `store#KnowledgeTopic` with `contentLocation` validation via `getLocationReference().exists()`.

### Step 2 — `approve#KnowledgeTopic` and `archive#KnowledgeTopic`

```xml
<service verb="approve" noun="KnowledgeTopic" authenticate="true">
    <in-parameters><parameter name="topicId" required="true"/></in-parameters>
    <actions>
        <entity-find-one entity-name="moqui.ai.AiKnowledgeTopic" value-field="topic"/>
        <if condition="topic == null"><return error="true" message="Topic ${topicId} not found"/></if>
        <service-call name="org.moqui.impl.StatusServices.transition#StatusItem"
            in-map="[statusId: topic.statusId, toStatusId: 'AI_KNOW_APPROVED',
                     statusFlowId: 'AiKnowledgeFlow']"/>
        <set field="topic.statusId" value="AI_KNOW_APPROVED"/>
        <entity-update value-field="topic"/>
    </actions>
</service>

<service verb="archive" noun="KnowledgeTopic" authenticate="true">
    <!-- same shape; toStatusId: AI_KNOW_ARCHIVED -->
</service>
```

- [ ] Write `approve#KnowledgeTopic` and `archive#KnowledgeTopic`.

### Step 3 — `store#AgentKnowledge` and `revoke#AgentKnowledge`

`store#AgentKnowledge` rejects ARCHIVED topics (you can grant DRAFT; it won't inject
until approved — `find#AgentKnowledge` filters to APPROVED only):

```xml
<service verb="store" noun="AgentKnowledge" authenticate="true">
    <in-parameters>
        <parameter name="agentId" required="true"/>
        <parameter name="topicId" required="true"/>
    </in-parameters>
    <actions>
        <entity-find-one entity-name="moqui.ai.AiKnowledgeTopic" value-field="topic"/>
        <if condition="topic == null">
            <return error="true" message="Topic ${topicId} not found"/></if>
        <if condition="topic.statusId == 'AI_KNOW_ARCHIVED'">
            <return error="true" message="Cannot grant an archived topic (${topicId}). Restore it to Approved first."/></if>
        <entity-make-value entity-name="moqui.ai.AiAgentKnowledge" value-field="grant">
            <field-map field-name="agentId"/><field-map field-name="topicId"/>
        </entity-make-value>
        <entity-create-or-update value-field="grant"/>
    </actions>
</service>
```

- [ ] Write `store#AgentKnowledge` and `revoke#AgentKnowledge`.

### Step 4 — `find#AgentKnowledge`

This is the single source of truth for filtering + body resolution. Both the runner and the
console call this — they never diverge.

```xml
<service verb="find" noun="AgentKnowledge" authenticate="true">
    <in-parameters>
        <parameter name="agentId" required="true"/>
    </in-parameters>
    <out-parameters>
        <parameter name="topics" type="List"/>
    </out-parameters>
    <actions>
        <set field="now" from="ec.user.nowTimestamp"/>
        <entity-find entity-name="moqui.ai.AiAgentKnowledge" list="grants">
            <econdition field-name="agentId"/>
        </entity-find>
        <set field="topics" from="[]"/>
        <iterate list="grants" entry="grant">
            <entity-find-one entity-name="moqui.ai.AiKnowledgeTopic" value-field="topic">
                <field-map field-name="topicId" from="grant.topicId"/>
            </entity-find-one>
            <if condition="topic == null">
                <continue/>
            </if>
            <!-- status gate -->
            <if condition="topic.statusId != 'AI_KNOW_APPROVED'"><continue/></if>
            <!-- effective date gate -->
            <if condition="topic.fromDate != null &amp;&amp; topic.fromDate > now"><continue/></if>
            <if condition="topic.thruDate != null &amp;&amp; topic.thruDate &lt;= now"><continue/></if>
            <!-- resolve body -->
            <set field="content" from="ec.resource.getLocationText(topic.contentLocation, true)"/>
            <if condition="!content">
                <log level="warn" message="Knowledge topic ${topic.topicId} body not found at ${topic.contentLocation} — skipping"/>
                <continue/>
            </if>
            <script>topics.add([topicId: topic.topicId, topicName: topic.topicName, content: content])</script>
        </iterate>
        <!-- sort by topicName for deterministic injection order -->
        <set field="topics" from="topics.sort { a, b -> a.topicName &lt;=&gt; b.topicName }"/>
    </actions>
</service>
```

> **Note:** A view-entity join (`AiAgentKnowledge` + `AiKnowledgeTopic`) would eliminate the
> inner loop — consider refactoring if the topic count grows. For v1 the N is small (< 20
> per agent) and the file reads dominate anyway.

- [ ] Write `find#AgentKnowledge` with status gate, effective date gate, graceful null skip.

### Step 5 — `list#KnowledgeTopic`

For the AiOps console listing:

```xml
<service verb="list" noun="KnowledgeTopic" authenticate="true">
    <in-parameters>
        <parameter name="statusId"/>   <!-- optional filter -->
    </in-parameters>
    <out-parameters>
        <parameter name="topics" type="List"/>
    </out-parameters>
    <actions>
        <entity-find entity-name="moqui.ai.AiKnowledgeTopic" list="topics">
            <econdition field-name="statusId" ignore-if-empty="true"/>
            <order-by field-name="topicName"/>
        </entity-find>
    </actions>
</service>
```

- [ ] Write `list#KnowledgeTopic`.

---

## Task 3 — Runtime integration

Wire knowledge injection into the agentic loop. This is what makes topics actually reach
the LLM. Two files change: `ContextAssembler.groovy` (pure, testable) and `AgentRunner.groovy`
(the hook).

**Files:**
- Modify `src/main/groovy/org/moqui/ai/ContextAssembler.groovy`
- Modify `src/main/groovy/org/moqui/ai/AgentRunner.groovy`

### Context assembly order

```
AiAgent.systemPrompt
        │
        │  ContextAssembler.withKnowledge(sysCtx, topics)   ← UNCONDITIONAL (this task)
        ▼
[## Knowledge base]
[### OMS Domain Primer]
[... body ...]
        │
        │  when contextStrategy=window:
        │  ContextAssembler.withSummary(sysCtx, summary)
        │  ContextAssembler.withFacts(sysCtx, facts)
        ▼
Final system context sent to LLM
```

Knowledge is the **base/authoritative layer** — it's always there, for every agent with
grants, regardless of `contextStrategy`. Pinned facts + summary layer on top of it.

### Step 1 — `ContextAssembler.withKnowledge`

Add to `ContextAssembler.groovy` after `withFacts`:

```groovy
/**
 * Appends approved, effective knowledge topics to the system prompt.
 * No-op when topics is null or empty.
 * Each topic is rendered as a level-2 heading so the LLM has clear boundaries.
 *
 * topics: List<Map> with keys topicId, topicName, content
 */
static String withKnowledge(String systemPrompt, List<Map> topics) {
    if (!topics) return systemPrompt
    StringBuilder sb = new StringBuilder(systemPrompt)
    sb.append('\n\n## Knowledge base (authoritative — follow these definitions)\n')
    for (Map topic in topics) {
        sb.append('\n### ').append(topic.topicName).append('\n')
        sb.append(topic.content).append('\n')
    }
    return sb.toString()
}
```

- [ ] Add `withKnowledge` to `ContextAssembler.groovy`.

### Step 2 — `AgentRunner.loadAgentKnowledge`

Add private method to `AgentRunner.groovy`:

```groovy
/**
 * Loads approved + effective knowledge topics for the agent, applies the char cap,
 * and records a context_trim step if topics are dropped.
 *
 * Truncation: whole-topic drop only — never emit a partial body.
 * Topics arrive sorted by topicName (find#AgentKnowledge guarantees this).
 */
private List<Map> loadAgentKnowledge(String agentId, String agentRunId, int capChars) {
    Map result = ec.service.sync().name('ai.KnowledgeServices.find#AgentKnowledge')
        .parameters([agentId: agentId]).call()
    List<Map> all = result?.topics ?: []
    if (!all) return []

    List<Map> included = []
    List<String> dropped = []
    int used = 0

    for (Map topic in all) {
        int topicLen = (topic.content as String)?.length() ?: 0
        if (used + topicLen <= capChars) {
            included.add(topic)
            used += topicLen
        } else {
            dropped.add(topic.topicName as String)
        }
    }

    if (dropped) {
        ec.logger.warn("Knowledge cap (${capChars} chars) exceeded for agent ${agentId}. " +
            "Dropped topics: ${dropped.join(', ')}")
        // Record a context_trim step so the trim shows in RunDetail
        ec.service.sync().name('create#moqui.ai.AiAgentRunStep').parameters([
            agentRunId: agentRunId,
            stepSeqId:  ec.entity.sequencedIdPrimary('moqui.ai.AiAgentRunStep', null, null),
            stepType:   'context_trim',
            fromDate:   ec.user.nowTimestamp,
            finishReason: "knowledge_cap: dropped ${dropped.size()} topic(s): ${dropped.join(', ')}"
        ]).call()
    }
    return included
}
```

- [ ] Add `loadAgentKnowledge` to `AgentRunner.groovy`.

### Step 3 — Hook at `AgentRunner.groovy:125`

The existing line reads:
```groovy
String sysCtx = agent.systemPrompt as String
```

Immediately after it (outside the `contextStrategy` branch), add:

```groovy
// --- Knowledge injection (unconditional — any contextStrategy, even off) ---
int knowledgeCap = (agent.knowledgeMaxChars as Integer)
    ?: (ec.factory.getConf('ai_knowledge_max_chars') as Integer ?: 24000)
List<Map> knowledgeTopics = loadAgentKnowledge(agentId, agentRunId, knowledgeCap)
sysCtx = ContextAssembler.withKnowledge(sysCtx, knowledgeTopics)
// --- end knowledge injection ---
```

- [ ] Add the three-line hook at `AgentRunner.groovy` line 125.
- [ ] Verify the same hook is added at the **resume** site (search `AgentRunner.groovy` for
  other `systemPrompt` assignments — apply the same pattern there).
- [ ] Verify the same hook is added at the **preview** site (`runPreview`).

---

## Task 4 — Knowledge files and Composer seed data

The sample file is already created. This task registers it in the DB via seed data and
grants it to the `composer-assistant` agent.

**Files:**
- `knowledge/oms-domain-primer.md` — already exists; verify content
- Create `data/AiComposerKnowledgeData.xml`

### Step 1 — Verify `oms-domain-primer.md`

Confirm the file exists at `knowledge/oms-domain-primer.md` and contains the 15 OMS terms
across three H2 sections (Inventory & availability, Fulfillment & routing, Order types).
The `component://moqui-ai/knowledge/oms-domain-primer.md` URI must resolve correctly via
`ec.resource.getLocationText`.

- [ ] Read `knowledge/oms-domain-primer.md` and verify content (15 terms, 3 sections).
- [ ] Manually verify `ec.resource.getLocationText('component://moqui-ai/knowledge/oms-domain-primer.md', true)` 
  returns non-null in a running instance (or rely on the test in Task 7).

### Step 2 — `data/AiComposerKnowledgeData.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<entity-facade-xml type="ext-seed">

    <!-- Knowledge topic: OMS Domain Primer -->
    <moqui.ai.AiKnowledgeTopic
        topicId="KNOW_OMS_PRIMER"
        topicName="OMS Domain Primer"
        description="Core HotWax Commerce OMS vocabulary — inventory, orders, and fulfillment terms for reasoning about OMS domain concepts."
        contentLocation="component://moqui-ai/knowledge/oms-domain-primer.md"
        statusId="AI_KNOW_APPROVED"/>

    <!-- Grant to the Composer agent -->
    <moqui.ai.AiAgentKnowledge
        agentId="composer-assistant"
        topicId="KNOW_OMS_PRIMER"/>

</entity-facade-xml>
```

> Future topics (Common OMS Agent Patterns, Agent & Tool Authoring Conventions) follow the
> same pattern: one `.md` file in `knowledge/`, one `AiKnowledgeTopic` row, one
> `AiAgentKnowledge` grant.

- [ ] Write `data/AiComposerKnowledgeData.xml`.

---

## Task 5 — AiOps screens

Console for curating topics and managing grants. Read-only body in v1 (edit the `.md` in git).

**Files:**
- Create `screen/AiOps/Knowledge.xml`
- Create `screen/AiOps/KnowledgeTopic.xml`
- Modify `screen/AiOps/Agents.xml`
- Modify `screen/AiOps/AiOps.xml` (menu)

### Step 1 — `Knowledge.xml` (topic list)

Mirror `screen/AiOps/Glossary.xml` structure:
- Table of topics with columns: topicName, statusId, fromDate, thruDate, contentLocation
- Status filter (All / Draft / Approved / Archived)
- Buttons: New Topic, Approve, Archive
- Row click → `KnowledgeTopic.xml` detail

- [ ] Write `screen/AiOps/Knowledge.xml`.

### Step 2 — `KnowledgeTopic.xml` (detail / edit)

Fields: `topicName`, `description`, `contentLocation` (text input), `fromDate`, `thruDate`,
`statusId` (display only — transitions via Approve/Archive buttons).

Body preview (read-only): a `<text>` or `<html>` render of
`ec.resource.getLocationText(topic.contentLocation, true)` in a `<pre>` block — shows
the raw markdown so the author can verify what the agent will see.

Buttons: Save (`store#KnowledgeTopic`), Approve, Archive.

- [ ] Write `screen/AiOps/KnowledgeTopic.xml` with read-only body preview.

### Step 3 — Knowledge grants on `Agents.xml`

Add a "Knowledge" section below the existing "Tools" section:
- Table of granted topics with columns: topicName, statusId (badge: Draft/Approved/Archived)
- Add Topic button → select from approved topics → `store#AgentKnowledge`
- Revoke button per row → `revoke#AgentKnowledge`

- [ ] Modify `screen/AiOps/Agents.xml` to add the Knowledge grants section.

### Step 4 — Add Knowledge to AiOps menu

Add a "Knowledge" menu item to `screen/AiOps/AiOps.xml` beside Glossary.

- [ ] Modify `screen/AiOps/AiOps.xml`.

---

## Task 6 — REST API (PWA-facing)

Thin REST wrappers around the services from Task 2. No business logic here — all validation
and state transitions are in the services.

**Files:** Create `service/ai/KnowledgeRestApi.xml`

### Endpoints

| Method | Path | Service | Notes |
|--------|------|---------|-------|
| `GET`  | `/ai/knowledge` | `list#KnowledgeTopic` | `?statusId=` optional filter |
| `POST` | `/ai/knowledge` | `store#KnowledgeTopic` | body: `{topicName, description, contentLocation, fromDate?, thruDate?}` |
| `GET`  | `/ai/knowledge/{topicId}` | entity-find-one | returns topic row |
| `PUT`  | `/ai/knowledge/{topicId}/approve` | `approve#KnowledgeTopic` | |
| `PUT`  | `/ai/knowledge/{topicId}/archive` | `archive#KnowledgeTopic` | |
| `GET`  | `/ai/agent/{agentId}/knowledge` | `find#AgentKnowledge` | returns resolved topics |
| `POST` | `/ai/agent/{agentId}/knowledge` | `store#AgentKnowledge` | body: `{topicId}` |
| `DELETE` | `/ai/agent/{agentId}/knowledge/{topicId}` | `revoke#AgentKnowledge` | |

All endpoints follow the existing `service/ai/AgentRestApi.xml` pattern for verb mappings and
path parameters.

- [ ] Write `KnowledgeRestApi.xml` with all 8 endpoints.
- [ ] Register in `MoquiConf.xml` REST API path (same `ai` prefix as existing agent REST).

---

## Task 7 — Tests

Mirror `AiGlossaryTests.groovy`. Add `AiKnowledgeTests` to `MoquiSuite.@SelectClasses`.

**Files:**
- Create `src/test/groovy/org/moqui/ai/AiKnowledgeTests.groovy`
- Modify `src/test/groovy/MoquiSuite.groovy`

### Coverage diagram

```
CODE PATHS                                            COVERAGE TARGET
─────────────────────────────────────────────────────────────────────
ContextAssembler.withKnowledge
  ├── [★★★] topics list → formatted output              Unit test
  ├── [★★★] empty list → systemPrompt unchanged          Unit test
  └── [★★★] multiple topics → H3 section per topic       Unit test

KnowledgeServices.store#KnowledgeTopic
  ├── [★★★] create (topicId null) → DRAFT row            Service test
  ├── [★★★] update (topicId provided) → row updated      Service test
  ├── [★★★] invalid contentLocation → error returned     Service test
  └── [★★★] defaults statusId=AI_KNOW_DRAFT              Service test

KnowledgeServices.approve#KnowledgeTopic
  ├── [★★★] DRAFT → APPROVED                             Service test
  └── [★★★] APPROVED → APPROVED (already) → no-op/error Service test

KnowledgeServices.archive#KnowledgeTopic
  └── [★★★] APPROVED → ARCHIVED                          Service test

KnowledgeServices.store#AgentKnowledge
  ├── [★★★] happy path (DRAFT topic) → granted            Service test
  ├── [★★★] happy path (APPROVED topic) → granted         Service test
  └── [★★★] ARCHIVED topic → error returned               Service test

KnowledgeServices.revoke#AgentKnowledge
  └── [★★★] grant removed                                  Service test

KnowledgeServices.find#AgentKnowledge
  ├── [★★★] returns only APPROVED + effective + assigned   Retrieval test
  ├── [★★★] excludes DRAFT topics                          Retrieval test
  ├── [★★★] excludes ARCHIVED topics                       Retrieval test
  ├── [★★★] excludes thruDate past                         Retrieval test
  ├── [★★★] excludes fromDate future                       Retrieval test
  ├── [★★★] excludes unassigned topics                     Retrieval test
  ├── [★★★] body text resolved from component:// file      Retrieval test
  └── [★★★] missing body file → skip topic (warn, no error) Retrieval test

AgentRunner (Mock provider)
  ├── [★★★] agent with grant → body in system context      Runtime test
  ├── [★★★] agent with no grants → systemPrompt unchanged  Runtime test
  └── [★★★] contextStrategy=off → still gets knowledge     Runtime test

Guardrail
  ├── [★★★] total chars within cap → all topics included   Guardrail test
  ├── [★★★] total chars over cap → excess topics dropped   Guardrail test
  ├── [★★★] dropped topics listed in context_trim step     Guardrail test
  └── [★★★] partial topic body never emitted               Guardrail test

COVERAGE: 26 paths  |  Unit: 3  |  Service: 11  |  Runtime: 5  |  Guardrail: 4  |  Retrieval: 8
```

### Test structure

```groovy
class AiKnowledgeTests extends Specification {

    // ── Unit: ContextAssembler.withKnowledge ─────────────────────────────
    def "withKnowledge formats topics as H3 sections"() { … }
    def "withKnowledge is a no-op for empty list"() { … }
    def "withKnowledge is a no-op for null"() { … }

    // ── Service: lifecycle ────────────────────────────────────────────────
    def "store#KnowledgeTopic creates a DRAFT row"() { … }
    def "store#KnowledgeTopic updates existing row"() { … }
    def "store#KnowledgeTopic rejects missing contentLocation"() { … }
    def "approve#KnowledgeTopic transitions DRAFT to APPROVED"() { … }
    def "archive#KnowledgeTopic transitions APPROVED to ARCHIVED"() { … }

    // ── Assignment ────────────────────────────────────────────────────────
    def "store#AgentKnowledge grants a DRAFT or APPROVED topic"() { … }
    def "store#AgentKnowledge rejects an ARCHIVED topic"() { … }
    def "revoke#AgentKnowledge removes the grant"() { … }

    // ── Retrieval filtering ───────────────────────────────────────────────
    def "find#AgentKnowledge returns APPROVED effective assigned topics only"() { … }
    def "find#AgentKnowledge excludes past thruDate"() { … }
    def "find#AgentKnowledge excludes future fromDate"() { … }
    def "find#AgentKnowledge skips topic with missing body file (no error)"() { … }

    // ── Runtime injection (Mock provider) ─────────────────────────────────
    def "agent with knowledge grant has body text in system context"() { … }
    def "stateless agent (contextStrategy=off) still gets knowledge"() { … }
    def "agent with no grants has unchanged systemPrompt"() { … }

    // ── Guardrail ─────────────────────────────────────────────────────────
    def "topics within cap are all included"() { … }
    def "topics over cap are dropped whole (not truncated mid-body)"() { … }
    def "context_trim step is recorded when topics are dropped"() { … }
}
```

### Steps

- [ ] Create `AiKnowledgeTests.groovy` with all 21 test cases above.
- [ ] For the `component://` body resolution tests, use a test fixture file at
  `knowledge/test-knowledge-fixture.md` (a minimal `.md` added to the component for tests only;
  clean up in `cleanupSpec`).
- [ ] Add `AiKnowledgeTests` to `MoquiSuite.@SelectClasses`.
- [ ] Run `./gradlew test` — all 122 existing tests + new knowledge tests green.

---

## Parallelization strategy

```
Lane A (entities + data) ──── Task 1 ──── Task 4 ─────────────────────────┐
Lane B (services)         ──── Task 2 (depends on Task 1) ────────────────┤
Lane C (runtime)          ──── Task 3 (depends on Task 2) ────────────────┤─── Task 7 (tests)
Lane D (screens)          ────────────── Task 5 (depends on Task 2) ──────┤
Lane E (REST)             ────────────── Task 6 (depends on Task 2) ──────┘
```

**Safe to parallelize after Task 1 completes:**
- Task 4 (seed data) — just XML, no Groovy dependency
- Task 2 (services) — entity dependency only

**Safe to parallelize after Task 2 completes:**
- Task 3 (runtime) + Task 5 (screens) + Task 6 (REST) — all call the same services, no
  shared Groovy files between them

**Sequential gates:**
- Task 1 must be complete before Task 2 starts (entities underpin all services)
- Task 2 must be complete before Task 3/5/6 start (services are the interface)
- Task 7 runs after all implementation tasks (full suite)

---

## What already exists (no reinvention)

| Existing | Reuse / mirror |
|---|---|
| `entity/AiGlossaryEntities.xml` | Entity shape (`id` PK, `statusId`, `one-nofk` grants, unique index) |
| `data/AiGlossaryData.xml` | Seed shape (`StatusType/Item/Flow/Transition`, `ext-seed` type) |
| `service/ai/GlossaryServices.xml` | Service verbs (`store#`, `approve#`, `archive#`, `find#`, `list#`) |
| `src/main/groovy/org/moqui/ai/ContextAssembler.groovy` | `withFacts` → mirrors pattern for `withKnowledge` |
| `src/main/groovy/org/moqui/ai/AgentRunner.groovy` | `loadFacts` at line 402 → mirrors pattern for `loadAgentKnowledge` |
| `src/test/groovy/org/moqui/ai/AiGlossaryTests.groovy` | Test class structure, `cleanupSpec` patterns |
| `screen/AiOps/Glossary.xml` | Screen structure for list + detail/edit |
| `service/ai/AgentRestApi.xml` | REST endpoint patterns, path param conventions |
| `knowledge/oms-domain-primer.md` | First knowledge body file — already created |

---

## NOT in scope (explicitly deferred)

| Item | Rationale |
|---|---|
| Self-learning (`AiConversationFact` → draft topic) | Needs `promote#` service + Curator review UI; separate feature |
| Solr / semantic search (`search#Knowledge` tool) | Requires Solr setup; `search.hotwax.*` integration separate task |
| Per-fact `AiKnowledgeItem` child entity | YAGNI — return when lookup/self-learning tier needs queryable per-fact rows |
| Per-tenant `ownerScope` activation | Field reserved; activate when multi-tenant KB needed |
| Runtime-writable bodies (`dbresource://`, `file://`) | Files are static in v1; add when operators need UI editing without redeploy |
| In-browser body editor in AiOps console | Body read-only in v1; edit via git PR |
| External-doc import / frontmatter interchange | Return with documents tier |
| Wiki / documents tier (`WikiSpace`) | Separate feature; `contentLocation` scheme abstraction means no model change needed |

---

## Failure modes and mitigations

| Failure | Test covers? | Handler | User-visible? |
|---|---|---|---|
| `contentLocation` file missing at author time | ✓ | `store#KnowledgeTopic` rejects with clear error | Error returned to screen/API |
| `contentLocation` file missing at inject time | ✓ | `find#AgentKnowledge` logs warn + skips topic | Silent (agent runs without that topic) |
| Total topics exceed `knowledgeMaxChars` | ✓ | Whole-topic drop; `context_trim` step recorded | Visible in RunDetail screen |
| ARCHIVED topic granted | ✓ | `store#AgentKnowledge` rejects with error | Error returned to screen/API |
| Status transition invalid (e.g., ARCHIVED→DRAFT) | ✓ (via StatusFlowTransition) | Framework rejects via `transition#StatusItem` | Error returned |
| Agent has no knowledge grants | ✓ | `withKnowledge` no-op; run proceeds normally | None |

**No critical gaps** — every failure mode has either a test or a framework guard (or both).

---

## Definition of done

- [ ] `AiKnowledgeTopic` + `AiAgentKnowledge` + `AiAgent.knowledgeMaxChars` + `AiKnowledgeStatus`
  seed load without errors.
- [ ] `store#` / `approve#` / `archive#` / `find#AgentKnowledge` service tests green.
- [ ] `ContextAssembler.withKnowledge` unit tests green.
- [ ] `AgentRunner` injects knowledge on every run (any `contextStrategy`, including `off`),
  bounded by the char cap, with whole-topic truncation.
- [ ] `oms-domain-primer.md` is seeded as `AI_KNOW_APPROVED` and granted to `composer-assistant`.
- [ ] AiOps **Knowledge** screen lists, views, and approves topics; **Agents** screen manages grants.
- [ ] REST API endpoints respond correctly to create / approve / grant / find calls.
- [ ] `AiKnowledgeTests` (26 paths, 21 test methods) is green in `MoquiSuite`.
- [ ] All 122 pre-existing tests still pass.
