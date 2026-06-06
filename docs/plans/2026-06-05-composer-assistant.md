# Composer Assistant Implementation Plan (TDD)

> **Update:** `list#DomainTerm` / `propose#Naming` shipped here as catalog-noun stubs are now backed by the real domain Glossary — see docs/plans/2026-06-05-builder-knowledgebase.md.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the **Composer Assistant** — itself an `AiAgent` (seeded `composer-assistant`) whose granted tools are the registry's authoring/introspection services — so a business user can **describe → answer questions → preview → activate** a new agent through ordinary conversation. The in-progress agent is a real `AI_AGENT_DRAFT`-status `AiAgent` row (Option A). Preview runs the draft through the existing `AgentRunner` with every mutating tool forced to `requiresApproval` (nothing irreversible fires; read-only tools run on real data). Activation flips `draft → active`, re-checks grants are `exposable`, and **requires human approval** via the existing approval gate. A **Composer** screen (chat + live draft panel + preview pane) lands under the AI Ops console.

**Architecture:** No new subsystem — the framework pointed at itself. Each meta-tool is an `AiTool` catalog row backed by a Moqui service in a new `service/ai/ComposerServices.xml`; the `composer-assistant` agent is granted exactly these tools and runs through the unchanged `run#Agent` / `AgentRunner` loop, conversation threading, approval gate, and `CostCalc`. The draft agent and its `AiAgentTool` grants are persisted via the keystone's `store#AiAgent` / grant writes. Preview is a thin service (`preview#Agent`) that wraps `AgentRunner` with a per-run "force-approval" override so the suspend machinery holds every mutating call. Activation (`activate#Agent`) is itself a `requiresApproval` tool — proposing it suspends the Composer's own run via the existing gate; a human approves; on resume the draft flips to active. The Composer screen is plain Moqui XML (`<screen>`/`<form-list>`/`<transition>`) reusing `AiRunTrace.xml`.

**Tech Stack:** Groovy 3 / JDK 11, Moqui (HotWax fork), Spock + JUnit Platform Suite, H2 dev DB. No new dependencies, no jar rebuild for screens (runtime resources; hot-reloaded in dev).

---

## Builds on (ASSUMED ALREADY BUILT — do not re-plan)

This plan sits **directly on top of the Agent & Tool Registry keystone**
(`docs/specs/2026-06-05-agent-tool-registry-design.md`). It assumes that plan has shipped and the
following are in place. **If any is missing, stop and land the registry first** — every task below
references the id-based model.

- `AiTool` re-keyed to **`toolId`** (opaque PK) with mutable `toolName` (`verb_noun`, unique),
  `verb`, `noun`, `description`, `serviceName` (attribute), `effect` (`AI_TOOL_READ_ONLY` |
  `AI_TOOL_MUTATING`), `exposable` (Y/N), `requiresApproval`, `sourceComponent`, `createdByUserId`,
  `statusId` (`AI_TOOL_ACTIVE` | `AI_TOOL_DISABLED`).
- `AiAgent` re-keyed to **`agentId`** (opaque PK) with mutable unique `agentName`, `description`,
  and the existing config fields; `statusId` now includes **`AI_AGENT_DRAFT`** (`AiAgentStatus`).
- `AiAgentTool` re-keyed to `(agentId, toolId)` with the optional `requiresApprovalOverride` (Y/N).
- `AiAgentRun.agentId` + denormalized `agentName` snapshot; `AiConversation.agentId`;
  `AiToolCall.toolId` + `serviceName` (+ `toolName` snapshot).
- `AiToolDenylist` (`servicePattern` PK, `reason`).
- `store#moqui.ai.AiTool` and `store#moqui.ai.AiAgent` authoring services (validate serviceName,
  derive `effect`, apply denylist, derive+unique `toolName`, defaults) and a grant write
  (`store#moqui.ai.AiAgentTool` or equivalent keyed by ids).
- `AiToolFactory` reads the catalog from the **DB** and builds JSON schemas on demand via
  `ToolSchemaBuilder`; `getTool(...)` resolves by `toolId` and exposes `toolName`, `description`,
  `serviceName`, `effect`, `requiresApproval`, `exposable`.
- `AgentRunner` keyed by `agentId`/`toolId`; `run#Agent` accepts `agentName` **or** `agentId`.
- The **human approval gate** (this repo, shipped): `AiToolApproval`, `AiAgentRun.pendingState`,
  `AI_RUN_SUSPENDED`, `AI_APPR_PENDING/APPROVED/REJECTED`, `continueAgent`/`resume`,
  `approve#`/`reject#`/`decide#ToolCall`, `get#PendingApproval`, and the in-loop gate
  `needApproval = toolCalls.findAll { ai.getTool(it.toolId)?.requiresApproval }`.
  (See `docs/plans/2026-06-03-human-approval-gate.md`.)

> **Naming note used throughout:** meta-tools are referenced by their **`toolName`** (`verb_noun`,
> e.g. `find_capability`) — the LLM-facing, wire-safe name the registry derives. Their `toolId`s are
> stable seed ids (see Task 9). The backing services live in `ai.ComposerServices` and use Moqui
> `verb#Noun` (e.g. `find#Capability`).

## Out of scope (own specs)

- The **Builder Knowledgebase / domain glossary** — `list#DomainTerm` ships as a **stub returning
  catalog nouns**; `propose#Naming` ships as a **best-guess (LLM + catalog heuristic)**. Both read
  through a seam so the KB drops in later without reshaping the assistant.
- A **Curator assistant** (v1 is Composer-only; `request#Capability` only *records* the gap).
- Deep multi-tenant catalog scoping.

## Conventions (same as prior work in this component)

- Tests: Spock `*Tests.groovy` in `src/test/groovy/`, registered in `MoquiSuite.groovy`. New class
  `AiComposerTests` MUST be added to the suite.
- Run the suite from the moqui root `/Users/anilpatel/maarg-sd/moqui`:
  ```bash
  source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
  ```
  Live tests are key-gated; both accounts funded — run for real, don't skip. The integration test
  (Task 8) uses the **MockProvider** (deterministic, no key).
- New entity/fields/statuses just need the boot the test triggers (Moqui auto-adds columns; tests
  load `data/AiStatusData.xml`).
- Commit **inside the component repo** (`runtime/component/moqui-ai`, branch
  `feature/ai-agent-framework`). Commit surgically with explicit pathspecs — never `git add -A`
  (parallel sessions edit this tree).
- The Composer screen is authored in the **notnaked clone** (`.../notnaked/runtime/component/moqui-ai/screen/...`) where it runs live; verify against `localhost:8080` via `/browse`.

## Resolved decisions (from the design spec §12)

1. **Draft state** → a real `AI_AGENT_DRAFT`-status `AiAgent` row (Option A). Cleanup via a discard
   action + optional stale-draft TTL (planning detail; TTL is **deferred**, discard ships).
2. **Commit approval** → `activate#Agent` **requires human approval** (it is a `requiresApproval`
   tool; proposing it suspends the Composer's run via the existing gate).
3. **Naming in v1** → best-guess (LLM + catalog heuristic) now; KB-grounded later.
4. **Preview on real data** → read-only tools run on real data; mutating tools held by the gate.

---

## File Structure

| File | Change |
|---|---|
| `service/ai/ComposerServices.xml` | New — `find#`/`describe#Capability`, `list#DomainTerm`, `propose#Naming`, `set#Guardrail`, `preview#Agent`, `activate#Agent`, `request#Capability`, `discard#Draft` |
| `entity/AiComposerEntities.xml` | New — `AiCapabilityRequest` (gap records for the Curator) |
| `data/AiStatusData.xml` | Add `AI_AGENT_DRAFT` transitions + `AiCapabilityRequest` statuses (if registry didn't already add `AI_AGENT_DRAFT` item) |
| `data/AiComposerData.xml` | New — the `composer-assistant` `AiAgent` seed + the 10 meta-tool `AiTool` rows + grants (`ext-seed`) |
| `src/main/groovy/org/moqui/ai/AgentRunner.groovy` | Add a `forceApprovalEffect` preview override (one new optional ctor/param + one line in the gate) |
| `screen/AiOps/Composer.xml` | New — chat + live draft panel + preview pane |
| `screen/AiOps.xml` | Add the `Composer` subscreens-item |
| `src/test/groovy/AiComposerTests.groovy` | New — unit + integration tests |
| `src/test/groovy/MoquiSuite.groovy` | Register `AiComposerTests` |
| `docs/specs/2026-06-05-composer-assistant-moqui-design.md` | Mark shipped (final task) |

---

## Task 1: `find#Capability` + `describe#Capability` (catalog introspection)

The assistant's "what can an agent here actually do?" tools. Read-only, filter to `exposable=Y`
**and** `statusId=AI_TOOL_ACTIVE` (so the assistant can only ever surface grant-eligible tools).

**Files:**
- Create: `service/ai/ComposerServices.xml`
- Test: `src/test/groovy/AiComposerTests.groovy` (new), `src/test/groovy/MoquiSuite.groovy`

- [ ] **Step 1: Write a failing test + register the suite class**

Create `src/test/groovy/AiComposerTests.groovy` mirroring `RunAgentServiceTests`/`AiApprovalTests`
scaffolding (`@Shared ec`, `@Shared ai`, `setupSpec` loads `AiStatusData.xml` + makes/login
`AiTestUser`, `cleanupSpec` destroys, `setup` re-logins + `clearErrors`, `cleanup` `MockProvider.reset()`).
Add a helper that seeds two `AiTool` rows via `store#moqui.ai.AiTool` (one read-only/exposable, one
denylisted/non-exposable) and the test:
```groovy
    def "find#Capability returns only exposable+active tools, matched by keyword"() {
        given:
        ec.artifactExecution.disableAuthz()
        // a curated, exposable read-only tool
        ec.service.sync().name("store#moqui.ai.AiTool").parameters([
            serviceName: "notnaked.OmsAiServices.get#OrderSummaryList",
            verb: "list", noun: "orders",
            description: "List recent orders (orderId, status, date, total)."]).call()
        ec.message.clearErrors()
        when:
        Map out = ec.service.sync().name("ai.ComposerServices.find#Capability")
            .parameters([query: "order"]).call()
        then:
        out.capabilityList.size() >= 1
        out.capabilityList.every { it.exposable == "Y" && it.statusId == "AI_TOOL_ACTIVE" }
        out.capabilityList.any { it.toolName == "list_orders" }
        // a non-matching keyword excludes it
        ec.service.sync().name("ai.ComposerServices.find#Capability")
            .parameters([query: "zzznomatch"]).call().capabilityList.isEmpty()
        cleanup:
        ec.entity.find("moqui.ai.AiTool").condition("toolName", "list_orders").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "describe#Capability returns purpose + input schema for one tool"() {
        given:
        ec.artifactExecution.disableAuthz()
        Map s = ec.service.sync().name("store#moqui.ai.AiTool").parameters([
            serviceName: "notnaked.OmsAiServices.get#OrderSummaryList",
            verb: "list", noun: "orders", description: "List recent orders."]).call()
        ec.message.clearErrors()
        when:
        Map out = ec.service.sync().name("ai.ComposerServices.describe#Capability")
            .parameters([toolId: s.toolId]).call()
        then:
        out.toolName == "list_orders"
        out.description == "List recent orders."
        out.effect == "AI_TOOL_READ_ONLY"
        // schema is generated on demand from the live service definition
        (out.inputSchema as Map).properties.containsKey("maxOrders")
        cleanup:
        ec.entity.find("moqui.ai.AiTool").condition("toolId", s.toolId).deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```
Add `AiComposerTests.class` to `@SelectClasses` in `MoquiSuite.groovy`.

- [ ] **Step 2: Run to confirm it fails**
```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — `ai.ComposerServices.find#Capability` / `describe#Capability` not defined (service-not-found).

- [ ] **Step 3: Create the services**

`service/ai/ComposerServices.xml` (start the file; later tasks append services):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<services xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/service-definition-3.xsd">

    <!-- The Composer Assistant's meta-tools. Each is also an AiTool catalog row (data/AiComposerData.xml)
         granted to the composer-assistant agent. Authoring tools are ArtifactAuthz-gated to the Composer
         role (deferred to v1 follow-up like the rest of the console; authenticate="true" for now). -->

    <!-- Search the catalog by intent/keyword/noun. ONLY exposable + active tools (grant-eligible). -->
    <service verb="find" noun="Capability" authenticate="true">
        <in-parameters>
            <parameter name="query"><description>intent/keyword/noun, matched against toolName, verb, noun, description</description></parameter>
            <parameter name="maxResults" type="Integer" default-value="25"/>
        </in-parameters>
        <out-parameters><parameter name="capabilityList" type="List"/></out-parameters>
        <actions>
            <entity-find entity-name="moqui.ai.AiTool" list="toolList" limit="maxResults">
                <econdition field-name="exposable" value="Y"/>
                <econdition field-name="statusId" value="AI_TOOL_ACTIVE"/>
                <econditions combine="or" ignore-if-empty="true">
                    <econdition field-name="toolName" operator="like" value="%${query}%" ignore-case="true"/>
                    <econdition field-name="noun" operator="like" value="%${query}%" ignore-case="true"/>
                    <econdition field-name="verb" operator="like" value="%${query}%" ignore-case="true"/>
                    <econdition field-name="description" operator="like" value="%${query}%" ignore-case="true"/>
                </econditions>
                <select-field field-name="toolId,toolName,verb,noun,description,effect,exposable,statusId"/>
                <order-by field-name="toolName"/>
            </entity-find>
            <set field="capabilityList" from="[]"/>
            <iterate list="toolList" entry="t"><script>capabilityList.add(t.getMap())</script></iterate>
        </actions>
    </service>

    <!-- One tool's purpose + on-demand input schema, so the assistant can reason about fit. -->
    <service verb="describe" noun="Capability" authenticate="true">
        <in-parameters>
            <parameter name="toolId"/>
            <parameter name="toolName"><description>alternative lookup key</description></parameter>
        </in-parameters>
        <out-parameters>
            <parameter name="toolId"/><parameter name="toolName"/><parameter name="description"/>
            <parameter name="effect"/><parameter name="serviceName"/><parameter name="exposable"/>
            <parameter name="inputSchema" type="Map"/>
        </out-parameters>
        <actions>
            <entity-find entity-name="moqui.ai.AiTool" list="found">
                <econdition field-name="toolId" ignore-if-empty="true"/>
                <econdition field-name="toolName" ignore-if-empty="true"/>
            </entity-find>
            <if condition="!found"><return error="true" message="No such capability: ${toolId ?: toolName}"/></if>
            <set field="tool" from="found[0]"/>
            <script>
                context.toolId = tool.toolId; context.toolName = tool.toolName
                context.description = tool.description; context.effect = tool.effect
                context.serviceName = tool.serviceName; context.exposable = tool.exposable
                // schema generated on demand from the live service definition (never stored)
                context.inputSchema = org.moqui.ai.ToolSchemaBuilder.build(ec.factory, tool.serviceName as String)
            </script>
        </actions>
    </service>
</services>
```
> `getMap()` on an `EntityValue` returns a plain `Map` (safe to hand to the model). The `or`-combined
> `econditions` with `ignore-if-empty` make an empty `query` return all exposable/active tools.

- [ ] **Step 4: Run — passes**; then commit
```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS — both new tests green; suite count +2.
```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add service/ai/ComposerServices.xml src/test/groovy/AiComposerTests.groovy src/test/groovy/MoquiSuite.groovy && \
git commit -m "feat(ai): find#/describe#Capability composer meta-tools (catalog introspection)"
```

---

## Task 2: `list#DomainTerm` (stub) + `propose#Naming` (best-guess)

The grounding seam (§5). `list#DomainTerm` returns the **catalog nouns** until the knowledgebase
exists; `propose#Naming` is **best-guess** — a catalog heuristic with an optional LLM refinement.
Both read through a single source so the KB drops in later untouched.

**Files:**
- Modify: `service/ai/ComposerServices.xml`
- Test: `src/test/groovy/AiComposerTests.groovy`

- [ ] **Step 1: Write failing tests**
```groovy
    def "list#DomainTerm returns distinct catalog nouns (KB stub)"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.service.sync().name("store#moqui.ai.AiTool").parameters([
            serviceName: "notnaked.OmsAiServices.get#OrderSummaryList",
            verb: "list", noun: "orders", description: "List recent orders."]).call()
        ec.message.clearErrors()
        when:
        Map out = ec.service.sync().name("ai.ComposerServices.list#DomainTerm").parameters([:]).call()
        then:
        out.termList.contains("orders")
        out.source == "catalog"   // documents the stub seam; flips to "knowledgebase" later
        cleanup:
        ec.entity.find("moqui.ai.AiTool").condition("toolName", "list_orders").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "propose#Naming returns a wire-safe verb_noun suggestion grounded in the catalog"() {
        given: ec.artifactExecution.disableAuthz(); ec.message.clearErrors()
        when:
        Map out = ec.service.sync().name("ai.ComposerServices.propose#Naming")
            .parameters([intent: "an assistant that summarizes recent orders for a store manager"]).call()
        then:
        out.agentNameSuggestion != null
        out.descriptionSuggestion != null
        // suggestion is snake_case / wire-safe by construction (provider round-trip is a no-op)
        out.agentNameSuggestion ==~ /^[a-z0-9-]+$/
        cleanup: ec.artifactExecution.enableAuthz()
    }
```

- [ ] **Step 2: Run to confirm it fails**
```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — services not defined.

- [ ] **Step 3: Add the services** (append to `ComposerServices.xml`)
```xml
    <!-- KB SEAM (stub): the business vocabulary the assistant grounds in. v1 = distinct catalog nouns.
         When the Builder Knowledgebase ships, swap the body to read the ontology and set source="knowledgebase";
         the contract (termList) is unchanged so the assistant needs no edit. -->
    <service verb="list" noun="DomainTerm" authenticate="true">
        <in-parameters><parameter name="query"/></in-parameters>
        <out-parameters>
            <parameter name="termList" type="List"/>
            <parameter name="source"><description>"catalog" (v1 stub) | "knowledgebase" (later)</description></parameter>
        </out-parameters>
        <actions>
            <entity-find entity-name="moqui.ai.AiTool" list="toolList" distinct="true">
                <econdition field-name="exposable" value="Y"/>
                <econdition field-name="statusId" value="AI_TOOL_ACTIVE"/>
                <econdition field-name="noun" operator="like" value="%${query}%" ignore-case="true" ignore-if-empty="true"/>
                <select-field field-name="noun"/><order-by field-name="noun"/>
            </entity-find>
            <set field="termList" from="toolList.collect { it.noun }.findAll { it } .unique()"/>
            <set field="source" value="catalog"/>
        </actions>
    </service>

    <!-- Best-guess naming (decision §12.3): catalog heuristic + optional LLM refinement. Wire-safe by
         construction. KB-grounded quality comes later (same contract). Falls back to the heuristic if no
         provider/key is configured, so it never fails the build. -->
    <service verb="propose" noun="Naming" authenticate="true">
        <in-parameters>
            <parameter name="intent" required="true"><description>what the user wants the agent to do</description></parameter>
        </in-parameters>
        <out-parameters>
            <parameter name="agentNameSuggestion"/>
            <parameter name="descriptionSuggestion"/>
        </out-parameters>
        <actions>
            <script><![CDATA[
                // heuristic floor: derive a slug from the intent + grounded nouns
                def terms = ec.service.sync().name("ai.ComposerServices.list#DomainTerm").parameters([:]).call().termList ?: []
                String noun = terms.find { (intent as String).toLowerCase().contains(it as String) } ?: "assistant"
                String slug = (noun == "assistant" ? "assistant" : "${noun}-assistant")
                agentNameSuggestion = slug.toLowerCase().replaceAll(/[^a-z0-9-]+/, "-").replaceAll(/-+/, "-")
                descriptionSuggestion = "Assistant for: ${intent}"
                // optional LLM refinement (best-guess): only if a provider is configured. Guarded — never aborts.
                try {
                    def ai = ec.factory.getTool("AI", org.moqui.ai.AiToolFactory.class)
                    def provider = ai.getProvider(System.getProperty("ai_openai_key") ? "openai" :
                        (System.getProperty("ai_anthropic_key") ? "anthropic" : null) ?: "mock")
                    def resp = provider.chat([model: System.getProperty("ai_openai_key") ? "gpt-4o-mini" : "mock-1",
                        systemContext: "Suggest a short, lowercase, hyphenated agent name (<=4 words) and a one-line description. " +
                            "Ground the name in these business terms when relevant: ${terms.join(', ')}. " +
                            "Reply as JSON {\"name\":\"...\",\"description\":\"...\"}.",
                        messages: [[role: "user", content: intent as String]]])
                    if (resp?.assistantText) {
                        def j = new groovy.json.JsonSlurper().parseText(resp.assistantText as String)
                        if (j.name) agentNameSuggestion = (j.name as String).toLowerCase().replaceAll(/[^a-z0-9-]+/, "-").replaceAll(/-+/, "-")
                        if (j.description) descriptionSuggestion = j.description
                    }
                } catch (Throwable t) { ec.logger.warn("propose#Naming LLM refine skipped: ${t.message}") }
            ]]></script>
        </actions>
    </service>
```
> Logging uses `ec.logger.warn` (not `ec.message.addError`) per the house rule — this service is
> called as a tool inside the agent loop; polluting the message context would corrupt the run.

- [ ] **Step 4: Run — passes**; commit
```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS — naming returns a slug like `orders-assistant`; `list#DomainTerm` contains `orders`.
```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add service/ai/ComposerServices.xml src/test/groovy/AiComposerTests.groovy && \
git commit -m "feat(ai): list#DomainTerm (KB stub) + propose#Naming (best-guess) meta-tools"
```

---

## Task 3: `set#Guardrail` (per-grant `requiresApprovalOverride`)

Mark which of the draft's granted tools need human approval, via the keystone's
`AiAgentTool.requiresApprovalOverride`. (Drafting + granting reuse the keystone's `store#AiAgent` /
grant write directly — no new wrapper; the `draft_agent` and `grant_capability` meta-tools are
**catalog aliases that point at the keystone services**, seeded in Task 9. This task adds only the
guardrail setter, which has no keystone equivalent.)

**Files:**
- Modify: `service/ai/ComposerServices.xml`
- Test: `src/test/groovy/AiComposerTests.groovy`

- [ ] **Step 1: Write a failing test**
```groovy
    def "set#Guardrail flips a grant's requiresApprovalOverride"() {
        given:
        ec.artifactExecution.disableAuthz()
        Map t = ec.service.sync().name("store#moqui.ai.AiTool").parameters([
            serviceName: "notnaked.OmsAiServices.get#OrderSummaryList",
            verb: "list", noun: "orders", description: "List recent orders."]).call()
        Map a = ec.service.sync().name("store#moqui.ai.AiAgent").parameters([
            agentName: "GuardDraft", providerName: "mock", modelName: "mock-1",
            systemPrompt: "x", statusId: "AI_AGENT_DRAFT"]).call()
        ec.service.sync().name("store#moqui.ai.AiAgentTool")
            .parameters([agentId: a.agentId, toolId: t.toolId]).call()
        ec.message.clearErrors()
        when:
        ec.service.sync().name("ai.ComposerServices.set#Guardrail")
            .parameters([agentId: a.agentId, toolId: t.toolId, requiresApproval: "Y"]).call()
        then:
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", a.agentId)
            .condition("toolId", t.toolId).one().requiresApprovalOverride == "Y"
        cleanup:
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", a.agentId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", a.agentId).deleteAll()
        ec.entity.find("moqui.ai.AiTool").condition("toolId", t.toolId).deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```

- [ ] **Step 2: Run to confirm it fails**
```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — `set#Guardrail` not defined.

- [ ] **Step 3: Add the service** (append to `ComposerServices.xml`)
```xml
    <!-- Set per-grant approval strictness on a draft (decision: an agent can be STRICTER than the tool
         default, never looser — a read-only tool stays runnable; only mutating gets gated). Writes the
         keystone's AiAgentTool.requiresApprovalOverride. -->
    <service verb="set" noun="Guardrail" authenticate="true">
        <in-parameters>
            <parameter name="agentId" required="true"/>
            <parameter name="toolId" required="true"/>
            <parameter name="requiresApproval" required="true"><description>Y/N</description></parameter>
        </in-parameters>
        <actions>
            <entity-find-one entity-name="moqui.ai.AiAgentTool" value-field="grant"/>
            <if condition="grant == null"><return error="true" message="Agent ${agentId} has no grant for tool ${toolId}"/></if>
            <service-call name="update#moqui.ai.AiAgentTool"
                in-map="[agentId: agentId, toolId: toolId, requiresApprovalOverride: requiresApproval]"/>
        </actions>
    </service>
```

- [ ] **Step 4: Run — passes**; commit
```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS.
```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add service/ai/ComposerServices.xml src/test/groovy/AiComposerTests.groovy && \
git commit -m "feat(ai): set#Guardrail composer meta-tool (per-grant requiresApprovalOverride)"
```

---

## Task 4: `request#Capability` + `AiCapabilityRequest` (record a gap for the Curator)

When no tool exists for the intent, the assistant **records the gap** instead of inventing one (§5).
v1 only persists it (no Curator UI/agent).

**Files:**
- Create: `entity/AiComposerEntities.xml`
- Modify: `service/ai/ComposerServices.xml`, `data/AiStatusData.xml`
- Test: `src/test/groovy/AiComposerTests.groovy`

- [ ] **Step 1: Write a failing test**
```groovy
    def "request#Capability records a gap in AI_CAPREQ_OPEN status"() {
        given: ec.artifactExecution.disableAuthz(); ec.message.clearErrors()
        when:
        Map out = ec.service.sync().name("ai.ComposerServices.request#Capability")
            .parameters([intent: "cancel an order", suggestedVerb: "cancel", suggestedNoun: "order",
                         notes: "asked during a compose session"]).call()
        then:
        out.capabilityRequestId != null
        def r = ec.entity.find("moqui.ai.AiCapabilityRequest")
            .condition("capabilityRequestId", out.capabilityRequestId).one()
        r.statusId == "AI_CAPREQ_OPEN"
        r.intent == "cancel an order"
        cleanup:
        ec.entity.find("moqui.ai.AiCapabilityRequest").condition("capabilityRequestId", out.capabilityRequestId).deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```

- [ ] **Step 2: Run to confirm it fails**
```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — `AiCapabilityRequest` / `request#Capability` not defined.

- [ ] **Step 3: Create the entity**

`entity/AiComposerEntities.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<entities xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/entity-definition-3.xsd">

    <!-- A gap the Composer Assistant found but cannot fill (only the Curator may create tools).
         Append-only queue for the future Curator. -->
    <entity entity-name="AiCapabilityRequest" package="moqui.ai">
        <field name="capabilityRequestId" type="id" is-pk="true"/>
        <field name="intent" type="text-long"><description>what the user wanted, in their words</description></field>
        <field name="suggestedVerb" type="text-short"/>
        <field name="suggestedNoun" type="text-short"/>
        <field name="notes" type="text-long"/>
        <field name="requestedByUserId" type="id"/>
        <field name="agentRunId" type="id"><description>the compose run that surfaced the gap (provenance)</description></field>
        <field name="conversationId" type="id"/>
        <field name="requestedDate" type="date-time"/>
        <field name="statusId" type="id"><description>AiCapReqStatus: AI_CAPREQ_OPEN | AI_CAPREQ_DONE | AI_CAPREQ_DISMISSED</description></field>
        <relationship type="one" related="moqui.basic.StatusItem" short-alias="status"/>
        <relationship type="one-nofk" related="moqui.ai.AiAgentRun" short-alias="run"/>
    </entity>
</entities>
```

- [ ] **Step 4: Add statuses** to `data/AiStatusData.xml` (match the file's element style)
```xml
    <!-- Capability-request queue (Composer gap → Curator) -->
    <moqui.basic.StatusType statusTypeId="AiCapReqStatus" description="AI Capability Request Status"/>
    <moqui.basic.StatusItem statusId="AI_CAPREQ_OPEN"      statusTypeId="AiCapReqStatus" sequenceNum="1" description="Open"/>
    <moqui.basic.StatusItem statusId="AI_CAPREQ_DONE"      statusTypeId="AiCapReqStatus" sequenceNum="2" description="Fulfilled"/>
    <moqui.basic.StatusItem statusId="AI_CAPREQ_DISMISSED" statusTypeId="AiCapReqStatus" sequenceNum="3" description="Dismissed"/>
    <moqui.basic.StatusFlow statusFlowId="AiCapReqFlow" statusTypeId="AiCapReqStatus" description="Capability request lifecycle"/>
    <moqui.basic.StatusFlowTransition statusFlowId="AiCapReqFlow" statusId="AI_CAPREQ_OPEN" toStatusId="AI_CAPREQ_DONE"      transitionSequence="1" transitionName="Fulfill"/>
    <moqui.basic.StatusFlowTransition statusFlowId="AiCapReqFlow" statusId="AI_CAPREQ_OPEN" toStatusId="AI_CAPREQ_DISMISSED" transitionSequence="2" transitionName="Dismiss"/>
```

- [ ] **Step 5: Add the service** (append to `ComposerServices.xml`)
```xml
    <!-- Record a capability gap for the Curator. The assistant calls this instead of fabricating a tool. -->
    <service verb="request" noun="Capability" authenticate="true">
        <in-parameters>
            <parameter name="intent" required="true"/>
            <parameter name="suggestedVerb"/><parameter name="suggestedNoun"/>
            <parameter name="notes"/><parameter name="agentRunId"/><parameter name="conversationId"/>
        </in-parameters>
        <out-parameters><parameter name="capabilityRequestId"/></out-parameters>
        <actions>
            <set field="capabilityRequestId" from="ec.entity.sequencedIdPrimary('moqui.ai.AiCapabilityRequest', null, null)"/>
            <service-call name="create#moqui.ai.AiCapabilityRequest" in-map="[capabilityRequestId: capabilityRequestId,
                intent: intent, suggestedVerb: suggestedVerb, suggestedNoun: suggestedNoun, notes: notes,
                agentRunId: agentRunId, conversationId: conversationId, requestedByUserId: ec.user.userId,
                requestedDate: ec.user.nowTimestamp, statusId: 'AI_CAPREQ_OPEN']"/>
        </actions>
    </service>
```

- [ ] **Step 6: Run — passes**; commit
```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS.
```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add entity/AiComposerEntities.xml service/ai/ComposerServices.xml data/AiStatusData.xml src/test/groovy/AiComposerTests.groovy && \
git commit -m "feat(ai): request#Capability + AiCapabilityRequest (record gaps for the Curator)"
```

---

## Task 5: Preview override in `AgentRunner` (force every mutating tool to require approval)

The crux of "try it live" (§6). Add a per-run flag so preview reuses the **whole existing loop +
suspend gate** but treats every **mutating** tool as `requiresApproval` regardless of catalog
defaults — so nothing irreversible fires; read-only tools run normally on real data.

**Files:**
- Modify: `src/main/groovy/org/moqui/ai/AgentRunner.groovy`
- Test: `src/test/groovy/AiComposerTests.groovy`

- [ ] **Step 1: Write a failing test (call the runner directly with the override)**

Seed a draft agent granted ONE read-only tool (`get#GatedEcho` is read verb? no — use a real read
tool) and ONE mutating tool, script the MockProvider to call BOTH in one turn, run with the preview
override, and assert: the run **suspends**, exactly the **mutating** call has a pending approval, the
**read-only** call did NOT yet execute (whole-turn suspend), and `forcedApproval` is surfaced.

> Reuse the registry/keystone test tools. For a deterministic mutating tool, grant a mutating test
> service (e.g. `moqui.ai.test.TestServices.set#Echo` — a write-verb echo added alongside `get#Echo`
> in the keystone/test fixtures; if absent, add it the same way `get#GatedEcho` was added in the
> approval plan). For a read-only tool, grant `get#Echo` (read verb, `effect=READ_ONLY`).
```groovy
    def "preview override suspends on a mutating tool but not the read-only one"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        Map ro = ec.service.sync().name("store#moqui.ai.AiTool").parameters([
            serviceName: "moqui.ai.test.TestServices.get#Echo", verb: "get", noun: "echo",
            description: "echo back"]).call()                       // READ_ONLY, exposable=Y
        Map mut = ec.service.sync().name("store#moqui.ai.AiTool").parameters([
            serviceName: "moqui.ai.test.TestServices.set#Echo", verb: "set", noun: "echo",
            description: "write echo", exposable: "Y"]).call()      // MUTATING (curator blesses exposable)
        Map ag = ec.service.sync().name("store#moqui.ai.AiAgent").parameters([
            agentName: "PrevDraft", providerName: "mock", modelName: "mock-1",
            systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_DRAFT"]).call()
        ec.service.sync().name("store#moqui.ai.AiAgentTool").parameters([agentId: ag.agentId, toolId: ro.toolId]).call()
        ec.service.sync().name("store#moqui.ai.AiAgentTool").parameters([agentId: ag.agentId, toolId: mut.toolId]).call()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: null, finishReason: "tool_use", toolCalls: [
            [id: "r1", name: "get_echo", arguments: [text: "read me"]],
            [id: "w1", name: "set_echo", arguments: [text: "write me"]]], tokensIn: 1L, tokensOut: 1L])
        when:
        Map r = new org.moqui.ai.AgentRunner(ec, ai).runPreview(ag.agentId as String, "go")
        then:
        r.statusId == "AI_RUN_SUSPENDED"
        // exactly the mutating call is held; whole turn suspended → nothing executed yet
        def appr = ec.entity.find("moqui.ai.AiToolApproval").condition("agentRunId", r.agentRunId).list()
        appr.size() == 1
        appr[0].toolName == "set_echo"
        ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", r.agentRunId).list().isEmpty()
        cleanup:
        ec.entity.find("moqui.ai.AiToolApproval").condition("agentRunId", r.agentRunId).deleteAll()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", ag.agentId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", ag.agentId).deleteAll()
        ec.entity.find("moqui.ai.AiTool").condition("toolId", ro.toolId).deleteAll()
        ec.entity.find("moqui.ai.AiTool").condition("toolId", mut.toolId).deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```

- [ ] **Step 2: Run to confirm it fails**
```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — `runPreview` does not exist.

- [ ] **Step 3: Add the override to `AgentRunner`**

Add a private flag + a public `runPreview` entry, and gate on it in `continueAgent`. Minimal, surgical:

1. Add a field + setter near the top of the class (after the `ai` field):
```groovy
    /** Preview mode: treat EVERY mutating tool as requiresApproval, so a draft can be sandbox-run on
     *  real data with nothing irreversible executed (mutating calls suspend via the normal gate). */
    private boolean forceApprovalOnMutating = false
```

2. Add a public entry that mirrors `run(...)` but sets the flag (preview is always stateless — no
   conversation; read-only tools run on real data, mutating ones are held):
```groovy
    /** Phase-Composer preview: run an agent (typically a draft) once, with mutating tools forced to
     *  suspend for approval. Returns the run result (statusId AI_RUN_SUSPENDED if it proposed a write). */
    Map runPreview(String agentNameOrId, String userMessage) {
        this.forceApprovalOnMutating = true
        try { return run(agentNameOrId, userMessage, null) }
        finally { this.forceApprovalOnMutating = false }
    }
```

3. In `continueAgent`, change the gate predicate so a forced run also gates mutating tools. Replace:
```groovy
                List<Map> needApproval = (toolCalls as List<Map>).findAll { ai.getTool(it.name as String)?.requiresApproval }
```
with:
```groovy
                List<Map> needApproval = (toolCalls as List<Map>).findAll { Map tc ->
                    Map td = ai.getTool(tc.name as String)
                    if (td == null) return false
                    if (td.requiresApproval) return true
                    // preview: force-gate any MUTATING tool so a draft never executes a write on real data
                    return forceApprovalOnMutating && (td.effect == "AI_TOOL_MUTATING")
                }
```
> `ai.getTool(name)` resolves by the wire `toolName` and now exposes `effect` (registry). Read-only
> tools (`effect == AI_TOOL_READ_ONLY`) are never force-gated → they run on real data, as decided.
> Everything else (loop, suspend serialization, `resume`) is unchanged — preview gets suspend/trace
> for free. (A previewed run is normally **abandoned** at the suspend point — the user has seen the
> intended write; the draft itself is unaffected. The held approvals can be discarded with the draft.)

- [ ] **Step 4: Run — passes** (and every prior approval/loop test still green)
```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS — the mutating call suspends, the read-only call is held (whole-turn), nothing executed.
All existing `AiApprovalTests`/`AgentRunnerTests` green (`forceApprovalOnMutating` defaults false → no behavior change).

- [ ] **Step 5: Commit**
```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add src/main/groovy/org/moqui/ai/AgentRunner.groovy src/test/groovy/AiComposerTests.groovy && \
git commit -m "feat(ai): AgentRunner preview override — force-gate mutating tools (try-it-live safety)"
```

---

## Task 6: `preview#Agent` service (the meta-tool wrapping `runPreview`)

The `preview_agent` tool the assistant calls. Wraps `AgentRunner.runPreview` and returns the result
**plus the held-call trace** so the Composer screen can render "would call `set_echo(...)`".

**Files:**
- Modify: `service/ai/ComposerServices.xml`
- Test: `src/test/groovy/AiComposerTests.groovy`

- [ ] **Step 1: Write a failing test**
```groovy
    def "preview#Agent surfaces the held mutating calls"() {
        given:  // reuse the Task-5 setup (PrevDraft + ro/mut tools); factor a helper if convenient
        ec.artifactExecution.disableAuthz(); org.moqui.ai.provider.MockProvider.reset()
        Map mut = ec.service.sync().name("store#moqui.ai.AiTool").parameters([
            serviceName: "moqui.ai.test.TestServices.set#Echo", verb: "set", noun: "echo",
            description: "write echo", exposable: "Y"]).call()
        Map ag = ec.service.sync().name("store#moqui.ai.AiAgent").parameters([
            agentName: "PrevDraft2", providerName: "mock", modelName: "mock-1",
            systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_DRAFT"]).call()
        ec.service.sync().name("store#moqui.ai.AiAgentTool").parameters([agentId: ag.agentId, toolId: mut.toolId]).call()
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "w1", name: "set_echo", arguments: [text: "x"]]], tokensIn: 1L, tokensOut: 1L])
        when:
        Map out = ec.service.sync().name("ai.ComposerServices.preview#Agent")
            .parameters([agentId: ag.agentId, testMessage: "go"]).call()
        then:
        out.statusId == "AI_RUN_SUSPENDED"
        out.heldCalls.size() == 1
        out.heldCalls[0].toolName == "set_echo"
        out.agentRunId != null
        cleanup:
        ec.entity.find("moqui.ai.AiToolApproval").condition("agentRunId", out.agentRunId).deleteAll()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", ag.agentId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", ag.agentId).deleteAll()
        ec.entity.find("moqui.ai.AiTool").condition("toolId", mut.toolId).deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```

- [ ] **Step 2: Run to confirm it fails**
```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — `preview#Agent` not defined.

- [ ] **Step 3: Add the service** (append to `ComposerServices.xml`)
```xml
    <!-- Sandbox-run a draft on a user-supplied test message. Mutating tools are held by the approval
         gate (AgentRunner.runPreview); read-only tools run on real data (decision §12.4). Returns the
         run result + the held (would-be) calls so the screen can show "would call X(...)".
         transaction="ignore": runPreview drives LLM calls that must hold no enclosing tx (mirrors run#Agent). -->
    <service verb="preview" noun="Agent" transaction="ignore" authenticate="true">
        <in-parameters>
            <parameter name="agentId" required="true"/>
            <parameter name="testMessage" required="true"/>
        </in-parameters>
        <out-parameters>
            <parameter name="agentRunId"/><parameter name="statusId"/>
            <parameter name="assistantMessage"/>
            <parameter name="heldCalls" type="List"><description>each: toolName, serviceName, arguments (JSON)</description></parameter>
        </out-parameters>
        <actions>
            <script><![CDATA[
                def ai = ec.factory.getTool("AI", org.moqui.ai.AiToolFactory.class)
                def r = new org.moqui.ai.AgentRunner(ec, ai).runPreview(agentId as String, testMessage as String)
                agentRunId = r.agentRunId; statusId = r.statusId; assistantMessage = r.assistantMessage
                heldCalls = []
                for (def a in ec.entity.find("moqui.ai.AiToolApproval").condition("agentRunId", agentRunId)
                        .condition("statusId", "AI_APPR_PENDING").orderBy("requestedDate").list())
                    heldCalls.add([toolName: a.toolName, serviceName: a.serviceName, arguments: a.arguments])
            ]]></script>
        </actions>
    </service>
```

- [ ] **Step 4: Run — passes**; commit
```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS — `heldCalls` lists `set_echo`; status SUSPENDED.
```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add service/ai/ComposerServices.xml src/test/groovy/AiComposerTests.groovy && \
git commit -m "feat(ai): preview#Agent meta-tool (sandbox run + held-call trace)"
```

---

## Task 7: `activate#Agent` (draft → active; re-check exposable; REQUIRES human approval)

The commit (§7). Two halves:
- **The state change:** `activate#Agent` validates the draft (status, every grant still
  `exposable=Y` + `AI_TOOL_ACTIVE`), then flips `AI_AGENT_DRAFT → AI_AGENT_ACTIVE`. Fail-loud on any
  non-exposable grant.
- **The required human approval:** `activate_agent` is seeded as a **`requiresApproval` AiTool**
  (Task 9). So when the Composer Assistant *proposes* it mid-conversation, the existing gate
  **suspends the Composer's run** and records an `AiToolApproval`; a human approves via
  `approve#ToolCall`; on `resume` the gated call dispatches `activate#Agent` and the draft goes live.
  No new approval machinery — activation rides the gate already shipped.

This task implements + tests the **service** directly (unit). The end-to-end gated activation is
covered in Task 8.

**Files:**
- Modify: `service/ai/ComposerServices.xml`
- Test: `src/test/groovy/AiComposerTests.groovy`

- [ ] **Step 1: Write failing tests (happy path + non-exposable refusal)**
```groovy
    def "activate#Agent flips a valid draft to active"() {
        given:
        ec.artifactExecution.disableAuthz()
        Map t = ec.service.sync().name("store#moqui.ai.AiTool").parameters([
            serviceName: "notnaked.OmsAiServices.get#OrderSummaryList",
            verb: "list", noun: "orders", description: "List recent orders."]).call()  // exposable=Y
        Map a = ec.service.sync().name("store#moqui.ai.AiAgent").parameters([
            agentName: "ActDraft", providerName: "mock", modelName: "mock-1",
            systemPrompt: "x", statusId: "AI_AGENT_DRAFT"]).call()
        ec.service.sync().name("store#moqui.ai.AiAgentTool").parameters([agentId: a.agentId, toolId: t.toolId]).call()
        ec.message.clearErrors()
        when:
        ec.service.sync().name("ai.ComposerServices.activate#Agent").parameters([agentId: a.agentId]).call()
        then:
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", a.agentId).one().statusId == "AI_AGENT_ACTIVE"
        cleanup:
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", a.agentId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", a.agentId).deleteAll()
        ec.entity.find("moqui.ai.AiTool").condition("toolId", t.toolId).deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "activate#Agent refuses when a granted tool is not exposable"() {
        given:
        ec.artifactExecution.disableAuthz()
        // a tool the curator later un-exposed (or a denylisted one): exposable=N
        Map t = ec.service.sync().name("store#moqui.ai.AiTool").parameters([
            serviceName: "notnaked.OmsAiServices.get#OrderSummaryList",
            verb: "list", noun: "secret", description: "x"]).call()
        ec.entity.find("moqui.ai.AiTool").condition("toolId", t.toolId).updateAll([exposable: "N"])
        Map a = ec.service.sync().name("store#moqui.ai.AiAgent").parameters([
            agentName: "ActDraftBad", providerName: "mock", modelName: "mock-1",
            systemPrompt: "x", statusId: "AI_AGENT_DRAFT"]).call()
        ec.service.sync().name("store#moqui.ai.AiAgentTool").parameters([agentId: a.agentId, toolId: t.toolId]).call()
        ec.message.clearErrors()
        when:
        ec.service.sync().name("ai.ComposerServices.activate#Agent").parameters([agentId: a.agentId]).call()
        then:
        ec.message.hasError()
        ec.message.errorsString.toLowerCase().contains("exposable")
        // still a draft — not activated
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", a.agentId).one().statusId == "AI_AGENT_DRAFT"
        cleanup:
        ec.message.clearErrors()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", a.agentId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", a.agentId).deleteAll()
        ec.entity.find("moqui.ai.AiTool").condition("toolId", t.toolId).deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```

- [ ] **Step 2: Run to confirm it fails**
```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — `activate#Agent` not defined.

- [ ] **Step 3: Add the service** (append to `ComposerServices.xml`)
```xml
    <!-- Commit: draft -> active. Re-checks every grant is still exposable + active (a tool can be
         un-exposed/disabled by the Curator after it was granted). Fail-loud on any non-exposable grant.
         HUMAN APPROVAL is enforced UPSTREAM: activate_agent is a requiresApproval AiTool, so the Composer
         Assistant proposing it suspends its own run via the existing gate; this service runs only after a
         human approves (decision §12.2). Also callable directly by an operator (the screen's Activate button). -->
    <service verb="activate" noun="Agent" authenticate="true">
        <in-parameters><parameter name="agentId" required="true"/></in-parameters>
        <out-parameters><parameter name="agentId"/><parameter name="statusId"/></out-parameters>
        <actions>
            <entity-find-one entity-name="moqui.ai.AiAgent" value-field="agent"/>
            <if condition="agent == null"><return error="true" message="Unknown agent ${agentId}"/></if>
            <if condition="agent.statusId != 'AI_AGENT_DRAFT'">
                <return error="true" message="Agent ${agentId} is not a draft (status ${agent.statusId})"/></if>
            <!-- re-check grants: every granted tool must still be exposable + active -->
            <entity-find entity-name="moqui.ai.AiAgentTool" list="grants">
                <econdition field-name="agentId"/></entity-find>
            <iterate list="grants" entry="g">
                <entity-find-one entity-name="moqui.ai.AiTool" value-field="tool">
                    <field-map field-name="toolId" from="g.toolId"/></entity-find-one>
                <if condition="tool == null || tool.exposable != 'Y' || tool.statusId != 'AI_TOOL_ACTIVE'">
                    <return error="true" message="Cannot activate: granted tool ${g.toolId} is not exposable/active"/></if>
            </iterate>
            <service-call name="update#moqui.ai.AiAgent" in-map="[agentId: agentId, statusId: 'AI_AGENT_ACTIVE']"/>
            <set field="statusId" value="AI_AGENT_ACTIVE"/>
        </actions>
    </service>

    <!-- Cleanup: drop a draft (and its grants/held approvals). The screen "Discard draft" action. -->
    <service verb="discard" noun="Draft" authenticate="true">
        <in-parameters><parameter name="agentId" required="true"/></in-parameters>
        <actions>
            <entity-find-one entity-name="moqui.ai.AiAgent" value-field="agent"/>
            <if condition="agent == null"><return/></if>
            <if condition="agent.statusId != 'AI_AGENT_DRAFT'">
                <return error="true" message="Refusing to discard a non-draft agent ${agentId}"/></if>
            <entity-find entity-name="moqui.ai.AiAgentTool" list="grants"><econdition field-name="agentId"/></entity-find>
            <service-call name="delete#moqui.ai.AiAgentTool" in-map="[*]" multi="true"/>  <!-- per-grant; or loop -->
            <service-call name="delete#moqui.ai.AiAgent" in-map="[agentId: agentId]"/>
        </actions>
    </service>
```
> `delete#...AiAgentTool` should delete all grants for the agentId — implement as an `<iterate>`
> over `grants` calling `delete#moqui.ai.AiAgentTool` per row (the `multi` shorthand above is a
> reminder; use the explicit loop to be safe).

- [ ] **Step 4: Run — passes**; commit
```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS — happy path flips to active; non-exposable grant returns an error containing
"exposable" and the agent stays a draft.
```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add service/ai/ComposerServices.xml src/test/groovy/AiComposerTests.groovy && \
git commit -m "feat(ai): activate#Agent (draft->active, re-check exposable) + discard#Draft"
```

---

## Task 8: Seed the `composer-assistant` agent + meta-tool catalog + grants

The assistant ships as `ext-seed` data (§3): the 10 meta-tools as `AiTool` rows, the
`composer-assistant` `AiAgent` with a strong system prompt, and the grants. `activate_agent` is
seeded `requiresApproval=Y` (the commit gate); `draft_agent` / `grant_capability` are catalog
aliases pointing at the keystone's `store#` services.

**Files:**
- Create: `data/AiComposerData.xml`
- Modify: `data/AiStatusData.xml` (only if the registry didn't already add the `AI_AGENT_DRAFT`
  `StatusItem` + `ACTIVE→DRAFT`/`DRAFT→ACTIVE` transitions — add them here if missing)
- Test: `src/test/groovy/AiComposerTests.groovy`

- [ ] **Step 1: Write a failing test (load the seed, assert the agent + grants)**
```groovy
    def "composer-assistant seed loads with its meta-tool grants"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiComposerData.xml").load()
        when:
        def agent = ec.entity.find("moqui.ai.AiAgent").condition("agentName", "composer-assistant").one()
        def grants = ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", agent.agentId).list()
        def grantedToolNames = grants.collect { g -> ec.entity.find("moqui.ai.AiTool")
            .condition("toolId", g.toolId).one()?.toolName } as Set
        then:
        agent != null
        agent.statusId == "AI_AGENT_ACTIVE"
        agent.systemPrompt.contains("Composer")
        // the full meta-tool set is granted
        grantedToolNames.containsAll(["find_capability","describe_capability","list_domain_terms",
            "propose_naming","draft_agent","grant_capability","set_guardrail","preview_agent",
            "activate_agent","request_capability"] as Set)
        // activation requires approval (the commit gate)
        ec.entity.find("moqui.ai.AiTool").condition("toolName", "activate_agent").one().requiresApproval == "Y"
        cleanup: ec.artifactExecution.enableAuthz()
    }
```

- [ ] **Step 2: Run to confirm it fails**
```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — `AiComposerData.xml` missing → load throws / agent null.

- [ ] **Step 3: Create the seed data**

`data/AiComposerData.xml` — uses explicit **stable seed ids** for `toolId`/`agentId` (per registry
open-question recommendation). Each `AiTool` row is a meta-tool; the read-only introspection tools
are `effect=AI_TOOL_READ_ONLY`/`exposable=Y`; authoring tools point at their `serviceName`;
`activate_agent` is `requiresApproval=Y`.
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- The Composer Assistant ships out of the box: an AiAgent whose tools are the registry's authoring
     + introspection services. ext-seed (install only; mirror critical rows in an ext-upgrade file if they
     must reach existing envs). The deepest dogfood: the builder is configured THROUGH the registry it manages. -->
<entity-facade-xml type="ext-seed">

    <!-- ===== meta-tool catalog rows (stable ids) ===== -->
    <moqui.ai.AiTool toolId="AICMP_FIND_CAP"   toolName="find_capability"     verb="find"     noun="capability"
        serviceName="ai.ComposerServices.find#Capability"     effect="AI_TOOL_READ_ONLY" exposable="Y" requiresApproval="N" statusId="AI_TOOL_ACTIVE" sourceComponent="moqui-ai"
        description="Search the capability catalog by intent/keyword/noun. Use FIRST to discover what an agent here can actually do. Returns only grant-eligible (exposable, active) tools."/>
    <moqui.ai.AiTool toolId="AICMP_DESC_CAP"   toolName="describe_capability" verb="describe" noun="capability"
        serviceName="ai.ComposerServices.describe#Capability" effect="AI_TOOL_READ_ONLY" exposable="Y" requiresApproval="N" statusId="AI_TOOL_ACTIVE" sourceComponent="moqui-ai"
        description="Get one capability's purpose + input schema, to reason about whether it fits the user's need."/>
    <moqui.ai.AiTool toolId="AICMP_LIST_TERM"  toolName="list_domain_terms"   verb="list"     noun="domain_terms"
        serviceName="ai.ComposerServices.list#DomainTerm"     effect="AI_TOOL_READ_ONLY" exposable="Y" requiresApproval="N" statusId="AI_TOOL_ACTIVE" sourceComponent="moqui-ai"
        description="List the business vocabulary/nouns you can ground naming + descriptions in."/>
    <moqui.ai.AiTool toolId="AICMP_PROP_NAME"  toolName="propose_naming"      verb="propose"  noun="naming"
        serviceName="ai.ComposerServices.propose#Naming"      effect="AI_TOOL_READ_ONLY" exposable="Y" requiresApproval="N" statusId="AI_TOOL_ACTIVE" sourceComponent="moqui-ai"
        description="Suggest an agent name + description for an intent, grounded in the business glossary."/>
    <!-- authoring tools: point at the keystone store# services (the same path a human Composer uses) -->
    <moqui.ai.AiTool toolId="AICMP_DRAFT"      toolName="draft_agent"         verb="draft"    noun="agent"
        serviceName="store#moqui.ai.AiAgent"                  effect="AI_TOOL_MUTATING" exposable="Y" requiresApproval="N" statusId="AI_TOOL_ACTIVE" sourceComponent="moqui-ai"
        description="Create or update the DRAFT agent (name, description, system prompt, provider, model). Always pass statusId=AI_AGENT_DRAFT. Save early and refine across turns."/>
    <moqui.ai.AiTool toolId="AICMP_GRANT"      toolName="grant_capability"    verb="grant"    noun="capability"
        serviceName="store#moqui.ai.AiAgentTool"              effect="AI_TOOL_MUTATING" exposable="Y" requiresApproval="N" statusId="AI_TOOL_ACTIVE" sourceComponent="moqui-ai"
        description="Add a catalog tool (by toolId) to the draft agent. Only exposable tools resolve."/>
    <moqui.ai.AiTool toolId="AICMP_GUARD"      toolName="set_guardrail"       verb="set"      noun="guardrail"
        serviceName="ai.ComposerServices.set#Guardrail"       effect="AI_TOOL_MUTATING" exposable="Y" requiresApproval="N" statusId="AI_TOOL_ACTIVE" sourceComponent="moqui-ai"
        description="Mark which of the draft's tools need human approval at run time (requiresApproval Y/N per grant)."/>
    <moqui.ai.AiTool toolId="AICMP_PREVIEW"    toolName="preview_agent"       verb="preview"  noun="agent"
        serviceName="ai.ComposerServices.preview#Agent"       effect="AI_TOOL_READ_ONLY" exposable="Y" requiresApproval="N" statusId="AI_TOOL_ACTIVE" sourceComponent="moqui-ai"
        description="Sandbox-run the draft on a test message. Mutating tools are HELD (shown, not executed); read-only tools run on real data. Use to show the user what the agent would do."/>
    <!-- THE COMMIT GATE: activation requires human approval (requiresApproval=Y) -->
    <moqui.ai.AiTool toolId="AICMP_ACTIVATE"   toolName="activate_agent"      verb="activate" noun="agent"
        serviceName="ai.ComposerServices.activate#Agent"      effect="AI_TOOL_MUTATING" exposable="Y" requiresApproval="Y" statusId="AI_TOOL_ACTIVE" sourceComponent="moqui-ai"
        description="Put the draft to work: flip draft -> active so the team can run it. Requires human approval. Only call when the user has confirmed they are happy with the preview."/>
    <moqui.ai.AiTool toolId="AICMP_REQ_CAP"    toolName="request_capability"  verb="request"  noun="capability"
        serviceName="ai.ComposerServices.request#Capability"  effect="AI_TOOL_MUTATING" exposable="Y" requiresApproval="N" statusId="AI_TOOL_ACTIVE" sourceComponent="moqui-ai"
        description="When NO existing tool covers the user's need, record the gap for the Curator. NEVER invent or claim a capability that does not exist — call this and tell the user honestly."/>

    <!-- ===== the agent ===== -->
    <moqui.ai.AiAgent agentId="AICMP_AGENT" agentName="composer-assistant"
        providerName="openai" modelName="gpt-4o-mini" maxIterations="12"
        contextStrategy="window" statusId="AI_AGENT_ACTIVE"
        description="The builder: composes new agents from the curated capability catalog by talking to the user."
        systemPrompt="You are the Composer Assistant. You help a business user build a NEW AI agent by talking with them — describe, clarify, preview, activate. You build agents; you do not answer their domain questions yourself.

WORKFLOW:
1. Understand the goal. Use find_capability and describe_capability to learn what tools exist here, and list_domain_terms / propose_naming to ground a good name + description.
2. Save a DRAFT early with draft_agent (statusId=AI_AGENT_DRAFT) and refine it across turns. Grant the right tools with grant_capability. Use set_guardrail to require approval for any risky (mutating) tool.
3. When the user wants to see it work, call preview_agent with their test input and show them the result and any HELD (would-be) actions.
4. Only when the user confirms they are happy, call activate_agent. This REQUIRES human approval; tell the user a person must approve before it goes live.

RULES:
- You can only grant tools that already exist and are exposable. If the user needs something no tool covers, call request_capability to log the gap and tell them honestly — NEVER pretend a capability exists or fabricate one.
- Prefer the smallest set of tools that meets the goal. Keep prompts and descriptions concrete.
- Ask one focused question at a time when intent is unclear."/>

    <!-- ===== grants: the agent's tools ARE the meta-tools ===== -->
    <moqui.ai.AiAgentTool agentId="AICMP_AGENT" toolId="AICMP_FIND_CAP"/>
    <moqui.ai.AiAgentTool agentId="AICMP_AGENT" toolId="AICMP_DESC_CAP"/>
    <moqui.ai.AiAgentTool agentId="AICMP_AGENT" toolId="AICMP_LIST_TERM"/>
    <moqui.ai.AiAgentTool agentId="AICMP_AGENT" toolId="AICMP_PROP_NAME"/>
    <moqui.ai.AiAgentTool agentId="AICMP_AGENT" toolId="AICMP_DRAFT"/>
    <moqui.ai.AiAgentTool agentId="AICMP_AGENT" toolId="AICMP_GRANT"/>
    <moqui.ai.AiAgentTool agentId="AICMP_AGENT" toolId="AICMP_GUARD"/>
    <moqui.ai.AiAgentTool agentId="AICMP_AGENT" toolId="AICMP_PREVIEW"/>
    <moqui.ai.AiAgentTool agentId="AICMP_AGENT" toolId="AICMP_ACTIVATE"/>
    <moqui.ai.AiAgentTool agentId="AICMP_AGENT" toolId="AICMP_REQ_CAP"/>
</entity-facade-xml>
```
> `draft_agent`/`grant_capability` are `effect=MUTATING` but `requiresApproval=N` (they only touch
> drafts, which never run as active — safe). `activate_agent` is the single `requiresApproval=Y`
> meta-tool. The denylist (registry) still floors everything: even though these point at `store#`
> services, the Composer can only ever *grant* tools that are themselves `exposable=Y`.

- [ ] **Step 4: `AI_AGENT_DRAFT` status presence.** Confirm `data/AiStatusData.xml` has the
  `AI_AGENT_DRAFT` `StatusItem` + `ACTIVE↔DRAFT`/`DRAFT→ACTIVE` `StatusFlowTransition` rows (the
  registry should have added them). If missing, add:
```xml
    <moqui.basic.StatusItem statusId="AI_AGENT_DRAFT" statusTypeId="AiAgentStatus" sequenceNum="0" description="Draft"/>
    <moqui.basic.StatusFlowTransition statusFlowId="AiAgentFlow" statusId="AI_AGENT_DRAFT"  toStatusId="AI_AGENT_ACTIVE"   transitionSequence="3" transitionName="Activate"/>
    <moqui.basic.StatusFlowTransition statusFlowId="AiAgentFlow" statusId="AI_AGENT_ACTIVE" toStatusId="AI_AGENT_DISABLED" transitionSequence="1" transitionName="Disable"/>
```

- [ ] **Step 5: Run — passes**; commit
```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS — all 10 meta-tools granted; `activate_agent` requiresApproval=Y; agent ACTIVE.
```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add data/AiComposerData.xml data/AiStatusData.xml src/test/groovy/AiComposerTests.groovy && \
git commit -m "feat(ai): seed composer-assistant agent + meta-tool catalog + grants (ext-seed)"
```

---

## Task 9: Integration test — compose → preview → activate (e2e, MockProvider)

The whole flow as one deterministic test: drive the **real `composer-assistant`** through `run#Agent`
with a scripted MockProvider that (1) discovers a capability, (2) drafts an agent + grants a tool,
(3) previews (held mutating call), (4) proposes `activate_agent` → the Composer run **suspends** on
the commit gate → approve → the draft flips to **active** and is runnable.

**Files:**
- Modify: `src/test/groovy/AiComposerTests.groovy`

- [ ] **Step 1: Write the e2e test**

Use a `conversationId` (so the multi-turn build threads). Script MockProvider responses for each turn
the agent takes; the framework dispatches the named meta-tool each time and feeds the result back.
The key assertions are the **state transitions**, not the model's prose.
```groovy
    def "e2e: compose a draft, preview it, then gated-activate it to active"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiComposerData.xml").load()
        // a curated read-only target tool the built agent will use
        Map target = ec.service.sync().name("store#moqui.ai.AiTool").parameters([
            serviceName: "notnaked.OmsAiServices.get#OrderSummaryList",
            verb: "list", noun: "orders", description: "List recent orders."]).call()
        org.moqui.ai.provider.MockProvider.reset()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        // make the composer-assistant use the mock provider for this test
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "composer-assistant")
            .updateAll([providerName: "mock", modelName: "mock-1"])
        String draftAgentId = ec.entity.sequencedIdPrimary("moqui.ai.AiAgent", null, null)
        Map conv = ec.service.sync().name("ai.AgentServices.create#Conversation")
            .parameters([agentName: "composer-assistant"]).call()
        ec.message.clearErrors()

        // Turn 1: discover the capability
        MockProvider.enqueue([finishReason: "tool_use", toolCalls: [[id: "t1", name: "find_capability",
            arguments: [query: "orders"]]], tokensIn: 1L, tokensOut: 1L])
        // Turn 2: draft the agent (status DRAFT) — pass an explicit agentId so the test can track it
        MockProvider.enqueue([finishReason: "tool_use", toolCalls: [[id: "t2", name: "draft_agent",
            arguments: [agentId: draftAgentId, agentName: "orders-summary-bot", providerName: "mock",
                        modelName: "mock-1", systemPrompt: "Summarize orders.", statusId: "AI_AGENT_DRAFT"]]],
            tokensIn: 1L, tokensOut: 1L])
        // Turn 3: grant the target tool to the draft
        MockProvider.enqueue([finishReason: "tool_use", toolCalls: [[id: "t3", name: "grant_capability",
            arguments: [agentId: draftAgentId, toolId: target.toolId]]], tokensIn: 1L, tokensOut: 1L])
        // Turn 4: stop (assistant tells the user the draft is ready to preview)
        MockProvider.enqueue([assistantText: "Draft ready — want to preview it?", finishReason: "stop",
            toolCalls: [], tokensIn: 1L, tokensOut: 1L])

        when: "the build conversation runs"
        ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "composer-assistant", userMessage: "build an order summary agent",
                         conversationId: conv.conversationId]).call()
        then: "the draft exists with its grant"
        def draft = ec.entity.find("moqui.ai.AiAgent").condition("agentId", draftAgentId).one()
        draft.statusId == "AI_AGENT_DRAFT"
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", draftAgentId).list().size() == 1

        when: "the user previews the draft (read-only target → runs on real data, completes)"
        Map prev = ec.service.sync().name("ai.ComposerServices.preview#Agent")
            .parameters([agentId: draftAgentId, testMessage: "summarize orders"]).call()
        then: "no mutating call held (the only tool is read-only); preview completed"
        // (the draft's own provider is mock; enqueue its turns)
        // -- enqueue BEFORE the preview call in practice; shown here for narrative. See note. --
        prev.statusId in ["AI_RUN_COMPLETED", "AI_RUN_SUSPENDED"]

        when: "the assistant proposes activation → gated → approve"
        MockProvider.enqueue([finishReason: "tool_use", toolCalls: [[id: "t5", name: "activate_agent",
            arguments: [agentId: draftAgentId]]], tokensIn: 1L, tokensOut: 1L])
        MockProvider.enqueue([assistantText: "Activated.", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        Map suspended = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "composer-assistant", userMessage: "activate it",
                         conversationId: conv.conversationId]).call()
        then: "activation suspended the Composer run on the commit gate"
        suspended.statusId == "AI_RUN_SUSPENDED"
        (suspended.approvalIds as List).size() == 1
        // draft NOT yet active
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", draftAgentId).one().statusId == "AI_AGENT_DRAFT"

        when: "a human approves → resume dispatches activate#Agent"
        ec.service.sync().name("ai.ApprovalServices.approve#ToolCall")
            .parameters([approvalId: (suspended.approvalIds as List)[0]]).call()
        then: "the draft is now ACTIVE and runnable"
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", draftAgentId).one().statusId == "AI_AGENT_ACTIVE"

        cleanup:
        ec.entity.find("moqui.ai.AiToolApproval").condition("agentRunId", suspended.agentRunId).deleteAll()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", draftAgentId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", draftAgentId).deleteAll()
        ec.entity.find("moqui.ai.AiTool").condition("toolId", target.toolId).deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```
> **Implementer note on MockProvider ordering:** `MockProvider.enqueue` is a FIFO the provider drains
> per `chat` call. Enqueue **all** of a run's turns *before* the `run#Agent`/`preview#Agent` call that
> consumes them. The preview block above is written for narrative clarity — when implementing, move
> its `enqueue` calls to immediately before the `preview#Agent` call, and (since the only granted tool
> is read-only) script the draft's preview as a single `stop` turn so `prev.statusId == "AI_RUN_COMPLETED"`.
> Keep the assertion on the **state machine** (draft → suspended-on-activate → approved → active),
> which is the contract this test exists to prove.

- [ ] **Step 2: Run — passes**
```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS — the e2e walks compose → draft+grant → preview → gated activation → approve → active.
Report the total test count and confirm live (key-gated) provider tests still green.

- [ ] **Step 3: Commit**
```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add src/test/groovy/AiComposerTests.groovy && \
git commit -m "test(ai): e2e compose->preview->gated-activate the composer-assistant (#composer)"
```

---

## Task 10: The Composer screen (chat + live draft panel + preview pane)

A `Composer` screen under the AI Ops console (§8). Three regions in plain Moqui XML: a **chat** with
the `composer-assistant` (reuses `run#Agent` + a conversation), a **live draft panel** (the
`AI_AGENT_DRAFT` row + its grants, editable), and a **preview pane** (test input → result + held
calls, via `preview#Agent`). Reuses `AiRunTrace.xml`.

**Files:**
- Create: `screen/AiOps/Composer.xml`
- Modify: `screen/AiOps.xml`
- Verify: live via `/browse` (no unit test — screens are presentation; the services they call are
  all tested above).

- [ ] **Step 1: Add the subscreen item** to `screen/AiOps.xml`:
```xml
        <subscreens-item name="Composer" menu-title="Composer" menu-index="2" location="component://moqui-ai/screen/AiOps/Composer.xml"/>
```
(Renumber the others' `menu-index` so Composer sits after Playground; keep Playground the
`default-item`.)

- [ ] **Step 2: Create `screen/AiOps/Composer.xml`** (mirror the Playground/Agents idioms exactly:
`<parameter>`, `<transition>` with `<service-call>` + `<default-response>`, `<actions>`
entity-finds, `<container-box>` regions, `AiRunTrace` include):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd"
        default-menu-title="Composer" require-authentication="true">
    <!-- Build a new agent by talking to the Composer Assistant. Three regions: chat, live draft, preview.
         All work goes through the tested ComposerServices + run#Agent. v1 security: require-authentication
         only (inherits the AiOps ALL_USERS grant); the Composer-role ArtifactAuthz is a follow-up. -->
    <parameter name="conversationId"/>     <!-- the build session -->
    <parameter name="draftAgentId"/>       <!-- the draft being shaped -->
    <parameter name="userMessage"/>
    <parameter name="agentRunId"/>         <!-- last composer run, for the chat trace -->
    <parameter name="testMessage"/>
    <parameter name="previewRunId"/>

    <!-- start (or continue) the build conversation with the composer-assistant -->
    <transition name="send">
        <actions>
            <if condition="!conversationId">
                <service-call name="ai.AgentServices.create#Conversation" out-map="context"
                    in-map="[agentName: 'composer-assistant', title: 'Composer session']"/></if>
            <service-call name="ai.AgentServices.run#Agent" out-map="context"
                in-map="[agentName: 'composer-assistant', userMessage: userMessage, conversationId: conversationId]"/>
        </actions>
        <default-response url="."><parameter name="conversationId"/><parameter name="agentRunId"/><parameter name="draftAgentId"/></default-response>
    </transition>
    <!-- preview the current draft on a test input -->
    <transition name="preview">
        <service-call name="ai.ComposerServices.preview#Agent" out-map="context"
            in-map="[agentId: draftAgentId, testMessage: testMessage]"/>
        <default-response url="."><parameter name="conversationId"/><parameter name="draftAgentId"/>
            <parameter name="previewRunId" from="agentRunId"/></default-response>
    </transition>
    <!-- discard the draft -->
    <transition name="discard">
        <service-call name="ai.ComposerServices.discard#Draft" in-map="[agentId: draftAgentId]"/>
        <default-response url="."><parameter name="conversationId"/></default-response>
    </transition>

    <actions>
        <!-- chat history for the live build session -->
        <if condition="conversationId">
            <entity-find entity-name="moqui.ai.AiConversationMessage" list="msgList">
                <econdition field-name="conversationId"/><order-by field-name="messageSeqId"/></entity-find></if>
        <!-- the live draft: the most recent draft tied to this builder (or the passed draftAgentId) -->
        <if condition="!draftAgentId &amp;&amp; conversationId">
            <!-- derive the draft this session created (the draft_agent tool sets its agentId) -->
            <entity-find entity-name="moqui.ai.AiAgent" list="draftList" limit="1">
                <econdition field-name="statusId" value="AI_AGENT_DRAFT"/><order-by field-name="-agentId"/></entity-find>
            <if condition="draftList"><set field="draftAgentId" from="draftList[0].agentId"/></if></if>
        <if condition="draftAgentId">
            <entity-find-one entity-name="moqui.ai.AiAgent" value-field="draft"><field-map field-name="agentId" from="draftAgentId"/></entity-find-one>
            <entity-find entity-name="moqui.ai.AiAgentTool" list="draftGrants"><econdition field-name="agentId" from="draftAgentId"/></entity-find></if>
        <if condition="previewRunId"><entity-find-one entity-name="moqui.ai.AiAgentRun" value-field="prevRun"><field-map field-name="agentRunId" from="previewRunId"/></entity-find-one>
            <entity-find entity-name="moqui.ai.AiToolApproval" list="heldList"><econdition field-name="agentRunId" from="previewRunId"/><econdition field-name="statusId" value="AI_APPR_PENDING"/></entity-find></if>
    </actions>

    <widgets>
        <!-- ===== Region 1: Chat ===== -->
        <container-box><box-header title="Describe the agent you want"/><box-body>
            <label text="Talk to the Composer Assistant. It will discover capabilities, draft an agent, and help you preview + activate it." type="p"/>
            <form-list name="Chat" list="msgList" skip-form="true" condition="msgList">
                <field name="role"><default-field title="Who"><display/></default-field></field>
                <field name="content"><default-field title="Message"><display/></default-field></field>
            </form-list>
            <form-single name="SendForm" transition="send">
                <field name="conversationId"><default-field><hidden/></default-field></field>
                <field name="draftAgentId"><default-field><hidden/></default-field></field>
                <field name="userMessage"><default-field title="Message"><text-area rows="3" cols="80"/></default-field></field>
                <field name="submitField"><default-field title=""><submit text="Send"/></default-field></field>
            </form-single>
        </box-body></container-box>

        <!-- ===== Region 2: Live draft panel ===== -->
        <section name="DraftPanel"><condition><expression>draft</expression></condition><widgets>
            <container-box><box-header title="Draft — ${draft.agentName} (${draft.statusId})"/><box-body>
                <label text="System prompt:" type="strong"/><label text="${draft.systemPrompt}" type="p"/>
                <label text="Provider/model: ${draft.providerName} / ${draft.modelName}" type="p"/>
                <form-list name="DraftGrants" list="draftGrants" skip-form="true" condition="draftGrants">
                    <field name="toolId"><default-field title="Granted tool (id)"><display/></default-field></field>
                    <field name="requiresApprovalOverride"><default-field title="Requires approval"><display/></default-field></field>
                </form-list>
                <form-single name="DiscardForm" transition="discard">
                    <field name="conversationId"><default-field><hidden/></default-field></field>
                    <field name="draftAgentId"><default-field><hidden/></default-field></field>
                    <field name="submitField"><default-field title=""><submit text="Discard draft" confirmation="Discard this draft?"/></default-field></field>
                </form-single>
            </box-body></container-box>
        </widgets></section>

        <!-- ===== Region 3: Preview pane ===== -->
        <section name="PreviewPane"><condition><expression>draft</expression></condition><widgets>
            <container-box><box-header title="Try it live"/><box-body>
                <label text="Run the draft on a test input. Mutating actions are HELD (shown, not executed); read-only tools run on real data." type="p"/>
                <form-single name="PreviewForm" transition="preview">
                    <field name="conversationId"><default-field><hidden/></default-field></field>
                    <field name="draftAgentId"><default-field><hidden/></default-field></field>
                    <field name="testMessage"><default-field title="Test input"><text-area rows="2" cols="80"/></default-field></field>
                    <field name="submitField"><default-field title=""><submit text="Preview"/></default-field></field>
                </form-single>
                <section name="PreviewResult"><condition><expression>prevRun</expression></condition><widgets>
                    <container-box><box-header title="Preview — ${prevRun.statusId}"/><box-body>
                        <label text="${prevRun.assistantMessage}" type="p"/>
                        <label text="Held (would-be) actions — NOT executed:" type="strong" condition="heldList"/>
                        <form-list name="Held" list="heldList" skip-form="true" condition="heldList">
                            <field name="toolName"><default-field title="Would call"><display/></default-field></field>
                            <field name="arguments"><default-field title="With args"><display/></default-field></field>
                        </form-list>
                    </box-body></container-box>
                    <include-screen location="component://moqui-ai/screen/includes/AiRunTrace.xml">
                        <parameter name="agentRunId" from="previewRunId"/></include-screen>
                </widgets></section>
            </box-body></container-box>
        </widgets></section>
    </widgets>
</screen>
```
> **Activation from the screen:** the user activates by telling the assistant in chat ("activate it"),
> which proposes `activate_agent` → the run suspends on the commit gate → the existing **Approvals**
> tab shows the pending decision → approve there → the draft goes live. (No separate Activate button
> is required for v1; the gate + Approvals screen already exist. A direct "Activate" button calling
> `activate#Agent` can be added later, but it would bypass the in-conversation approval UX.)

- [ ] **Step 3: Verify live** (notnaked clone, against `localhost:8080`):
  1. Restart NotNaked (new screen file needs one restart; later edits hot-reload).
  2. Open the AiOps URL → **Composer** tab renders; the three regions appear (draft/preview hidden
     until a draft exists).
  3. Send "build an order summary agent from the orders capability" → the assistant replies, a draft
     appears in the draft panel with the `get#OrderSummaryList` grant.
  4. Preview "summarize orders" → result + trace render; no held action (read-only).
  5. Tell it "activate it" → Approvals tab shows the pending `activate_agent` → approve → the draft
     becomes ACTIVE (visible on the Agents tab) and runnable from the Playground.
  Use `/browse` for the headless walkthrough or ask the user to look.

- [ ] **Step 4: Commit** (from the notnaked clone)
```bash
cd /Users/anilpatel/maarg-sd/notnaked/runtime/component/moqui-ai && \
git add screen/AiOps/Composer.xml screen/AiOps.xml && \
git commit -m "feat(ai): Composer screen — chat + live draft panel + preview pane (AI Ops console)"
```

---

## Task 11: NotNaked live acceptance + mark the spec shipped

The acceptance bar from the design (§11): on the running NotNaked instance, build a small
order-summary agent from the seeded `list_orders` capability, preview it on real orders, activate it
(human-approved), and run it.

**Files:**
- Modify: `docs/specs/2026-06-05-composer-assistant-moqui-design.md`

- [ ] **Step 1: Live acceptance** (NotNaked, `localhost:8080`, real OpenAI key configured so the
  `composer-assistant` uses a real model — not the mock):
  1. `gradlew load` (or the data-load task) so `AiComposerData.xml` (`ext-seed`) is applied → the
     `composer-assistant` is present (Agents tab). Confirm the keystone seeded the NotNaked
     `list_orders` tool (`get#OrderSummaryList`).
  2. Composer tab → "Build an agent that summarizes our recent orders for a store manager."
  3. Through the conversation: the assistant discovers `list_orders`, drafts `orders-summary-bot`,
     grants the tool. Confirm the draft panel fills in.
  4. Preview on "what are our latest orders?" → see real order data summarized; no held actions.
  5. "Activate it." → Approvals tab shows the pending `activate_agent` → approve.
  6. Playground → run `orders-summary-bot` → it answers from real orders.
  7. Negative: ask for something with no tool (e.g. "also cancel order 123") → the assistant calls
     `request_capability` (a row in `AiCapabilityRequest`, `AI_CAPREQ_OPEN`) and says so honestly —
     it does NOT fabricate a cancel tool.
  Capture findings; if anything fails, fix forward (it's all tested services + a presentation screen).

- [ ] **Step 2: Mark the design spec shipped.** In
  `docs/specs/2026-06-05-composer-assistant-moqui-design.md`, change `Status:` to
  `Shipped (<date>)` and add a one-line "Shipped" note under §11 listing what landed (the 10
  meta-tools, the seeded agent + prompt, the preview override, the Composer screen, the e2e +
  acceptance) and what was deferred (KB-grounded naming/`list#DomainTerm`, the Curator
  assistant/`request#Capability` UI, stale-draft TTL, the Composer-role `ArtifactAuthz`, a direct
  Activate button).

- [ ] **Step 3: Commit**
```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add docs/specs/2026-06-05-composer-assistant-moqui-design.md && \
git commit -m "docs(ai): mark Composer Assistant shipped + record deferrals"
```

---

## Self-Review

**Spec coverage** (vs. the design spec §3 meta-tools table + §4/§6/§7/§8/§11):
- `find_capability` / `describe_capability` (exposable+active filter; on-demand schema) → Task 1. ✅
- `list_domain_terms` (KB **stub** = catalog nouns, `source` seam) → Task 2. ✅
- `propose_naming` (best-guess: catalog heuristic + optional LLM, wire-safe) → Task 2. ✅
- `draft_agent` (= `store#AiAgent` status DRAFT) / `grant_capability` (= grant write) → catalog
  aliases seeded in Task 8; exercised in Task 9 e2e. ✅
- `set_guardrail` (`requiresApprovalOverride`) → Task 3. ✅
- `preview_agent` (every mutating tool force-gated; read-only on real data; held-call trace) →
  Task 5 (runner override) + Task 6 (service). ✅
- `activate_agent` (draft→active, re-check exposable, **requires human approval** via the gate) →
  Task 7 (service) + Task 8 (`requiresApproval=Y` seed) + Task 9 (gated e2e). ✅
- `request_capability` (record a gap, never fabricate) → Task 4. ✅
- Draft lifecycle Option A (real `AI_AGENT_DRAFT` row + grants; discard; TTL deferred) → Tasks 3–9. ✅
- `composer-assistant` seed + strong system prompt + grants → Task 8. ✅
- Composer screen (chat + live draft panel + preview pane, under AI Ops) → Task 10. ✅
- Unit + integration (compose→preview→activate) → Tasks 1–7 unit, Task 9 e2e. ✅
- NotNaked live acceptance → Task 11. ✅
- Each meta-tool is itself an `AiTool` catalog entry granted to the agent → Task 8 data. ✅

**Reuse vs. build-new** (spec §9): reuses `AgentRunner`/`continueAgent`/`resume`, conversation
threading, the approval/suspend gate, `CostCalc`, the keystone registry + `store#` services,
`ToolSchemaBuilder`, the providers, and the AI Ops shell + `AiRunTrace`. Builds new: the meta-tool
services, the seed agent, the preview override (one flag + one predicate line), `AiCapabilityRequest`,
and the Composer screen. No new subsystem. ✅

**Convention checks** (CLAUDE.md + UDM guide):
- `request#Capability` / `activate#Agent` verb choices follow Moqui `verb#Noun`; `find`/`describe`/
  `list`/`propose`/`set`/`preview`/`activate`/`request`/`discard` are read/command verbs, not invented
  CRUD aliases. ✅
- Statuses via `StatusItem` + `StatusFlowTransition` (Task 4 `AiCapReqStatus`; Task 8 `AI_AGENT_DRAFT`
  transitions) — never `StatusValidChange`. ✅
- Logging in a SECA/loop-called service uses `ec.logger` (Task 2 `propose#Naming`), never
  `ec.message.addError`. ✅
- Seed data is `ext-seed` (Task 8) with a note to mirror in `ext-upgrade` if rows must reach existing
  envs. ✅
- `transaction="ignore"` on services that drive LLM calls (`preview#Agent`), matching `run#Agent`. ✅
- Surgical commits with explicit pathspecs; framework files (screens) committed from the notnaked
  clone, jar/service/entity from the moqui-root component path. ✅

**Placeholder scan:** services, the entity, the seed data, the runner override, and the screen are
concrete code. Two tests are described against the established setup rather than re-spelled
(`set#Echo` mutating test fixture in Task 5/6, and the MockProvider-ordering refinement in the Task 9
preview block) — both reuse shown idioms with explicit notes. Run commands + expected output are
exact throughout.

---

## Open questions / risks

1. **Registry is a hard prerequisite.** Every task references `toolId`/`agentId`, `effect`,
   `exposable`, `store#AiTool`/`store#AiAgent`, the DB-backed `AiToolFactory`, and `AI_AGENT_DRAFT`.
   None of that exists in the current tree (today `AiAgent` PK = `agentName`, `AiTool` PK =
   `toolName`, catalog is in-memory from `ai/*.tools.xml`). **If the registry keystone has not
   landed, this plan cannot start** — land it first. Confirm the exact `store#` parameter names and
   the grant-write service name (`store#moqui.ai.AiAgentTool`?) before Task 3/8.

2. **A mutating test fixture is assumed.** Tasks 5/6 need a deterministic *mutating* tool
   (`moqui.ai.test.TestServices.set#Echo`, write verb → `effect=MUTATING`). If the keystone/test
   fixtures don't already provide one, add it the same way `get#GatedEcho` was added in the approval
   plan (copy `get#Echo`'s in/out + body under a write verb). Read-only `get#Echo` is assumed present.

3. **`AgentRunner` instance-flag for preview.** The override is a per-instance boolean set by
   `runPreview` and cleared in a `finally`. `AgentRunner` is constructed fresh per call
   (`new AgentRunner(ec, ai)` in every service), so there is no cross-run leakage — but if anything
   ever reuses an instance concurrently, the flag would leak. Documented; acceptable for v1. An
   alternative (pass the flag through `continueAgent`'s `st`/params) is cleaner but touches more
   signatures — deferred unless review prefers it.

4. **Previewed runs leave SUSPENDED rows + held approvals.** A preview that proposes a write
   suspends and is then abandoned (the user only needed to *see* the intended call). Those
   `AI_RUN_SUSPENDED` runs + `AI_APPR_PENDING` approvals accumulate and will surface in the
   operator **Approvals** queue (which lists *all* pending approvals). Options: (a) tag preview runs
   and exclude them from the operator queue; (b) `discard#Draft` also deletes the draft's preview
   runs/approvals; (c) a sweeper. **Recommend (a)+(b)** — needs a small "is preview" marker on
   `AiAgentRun`. Flag for the planning/eng review; not yet in the tasks above.

5. **`list#DomainTerm` / `propose#Naming` are deliberately thin.** v1 grounds only in catalog nouns
   + a heuristic. Naming quality will be mediocre until the Builder Knowledgebase ships. The seam
   (`source` field, single read path) is in place, but set expectations: this is a stub, not the
   "learns your vocabulary" experience.

6. **Composer-role authorization deferred.** Like the rest of the AI Ops console (v1), the Composer
   screen + meta-tool services are `authenticate="true"` only and inherit the `ALL_USERS` grant —
   any logged-in user can author/activate agents. The spec (§7) wants these gated to a **Composer
   role** via `ArtifactAuthz`. Deferred to the same follow-up that adds `AI_OPERATOR`. The hard
   safety floor still holds: the Composer can only grant `exposable=Y` tools and `activate_agent`
   still requires human approval — so even un-gated, it cannot expose a dangerous service.

7. **The Composer agent uses a real model + multi-turn tool use.** The seeded agent is `openai` /
   `gpt-4o-mini` with `maxIterations=12`. Whether a small model reliably drives the
   discover→draft→grant→preview→activate tool sequence is an empirical question — the live
   acceptance (Task 11) is the real test. If it struggles, the lever is the system prompt and/or a
   stronger model in the seed; the framework is unchanged.

8. **Draft uniqueness / `agentName` collisions.** `draft_agent` → `store#AiAgent` enforces unique
   `agentName` (registry). If a user composes two agents with similar intents, the second draft may
   collide on a proposed name; `store#AiAgent` fails loud and the assistant must pick another name.
   The system prompt should mention this, but it relies on the model reacting to the error — worth
   watching in acceptance.
