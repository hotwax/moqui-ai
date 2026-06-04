# Phase 3: Cost Awareness — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the raw token counts Phase 1 already captures into money: compute an estimated
cost per run from a configurable per-model price, and expose a queryable spend service (per
agent, per user, per time window).

**Architecture:** Adds one effective-dated price entity (`AiModelPrice`), a small pure-Groovy
cost helper, a step in `AgentRunner.finish` to stamp `AiAgentRun.estimatedCost`, a view entity
that sums the run table, and a `get#AiSpend` query service. No change to the agentic loop itself.
All data Map-based.

**Tech Stack:** same as Phase 1 (Groovy 3, Moqui hotwax/main JDK 11, Spock 2.1).

**Depends on:** Phase 1 — `AiAgentRun` (has `tokensIn`, `tokensOut`, `estimatedCost`, `agentName`,
`userId`, `fromDate`), `AgentRunner.finish`, `MoquiSuite`. **Inherits Phase 1's unverified API
assumptions; reconcile against the proven Phase 1 build.**

**Conventions (binding):** UDM Domain Object Practices Guide
(`/Users/anilpatel/maarg-sd/docs/udm-domain-object-practices.md`): Maps not data classes;
effective dating (`fromDate`/`thruDate`, §1.4); `currency-precise` for sub-cent money;
view entities with aggregate functions for rollups; `store#`/`get#` verb conventions;
`*Tests.groovy` + `MoquiSuite`. **No name conflicts with Java/Moqui fundamentals.**

**Out of this phase:** budgets/alerts/enforcement (the per-run ceiling already exists in Phase 1;
spend *limits* per user/window are a later policy feature), multi-currency conversion (assume one
`currencyUom` per deployment), and the cost-review UI (Phase 5).

---

## File Structure (added/changed)

```
runtime/component/moqui-ai/
├── entity/AiPriceEntities.xml                      ← AiModelPrice + AiSpendSummary view entity (Task 1)
├── src/main/groovy/org/moqui/ai/CostCalc.groovy    ← pure cost helper (Task 2)
├── src/main/groovy/org/moqui/ai/AgentRunner.groovy ← MODIFY: stamp estimatedCost in finish (Task 2)
├── service/ai/CostServices.xml                     ← get#AiSpend, store#AiModelPrice (Task 3)
└── src/test/groovy/
    ├── MoquiSuite.groovy                           ← MODIFY: add AiCostTests
    └── AiCostTests.groovy                          ← Task 4
```

---

## Task 1: Price entity + spend summary view

**Files:**
- Create: `runtime/component/moqui-ai/entity/AiPriceEntities.xml`

- [ ] **Step 1: Define the price entity and the summary view entity**

Prices are per (provider, model), effective-dated (a model's price changes over time; old runs
keep the price that was current then). `currency-precise` = DECIMAL(18,3) for fractional-cent
per-million-token rates.

`runtime/component/moqui-ai/entity/AiPriceEntities.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<entities xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/entity-definition-3.xsd">

    <!-- Effective-dated price per model. inputPrice/outputPrice are per 1,000,000 tokens. -->
    <entity entity-name="AiModelPrice" package="moqui.ai">
        <field name="providerName" type="text-short" is-pk="true"/>
        <field name="modelName" type="text-medium" is-pk="true"/>
        <field name="fromDate" type="date-time" is-pk="true"/>
        <field name="thruDate" type="date-time"/>
        <field name="inputPricePerMillion" type="currency-precise"/>
        <field name="outputPricePerMillion" type="currency-precise"/>
        <field name="currencyUomId" type="id"/>
    </entity>

    <!-- Rollup of run spend. The service adds conditions (agent/user/date) + optional group-by. -->
    <view-entity entity-name="AiSpendSummary" package="moqui.ai">
        <member-entity entity-alias="RUN" entity-name="moqui.ai.AiAgentRun"/>
        <alias entity-alias="RUN" name="agentName" group-by="true"/>
        <alias entity-alias="RUN" name="userId" group-by="true"/>
        <alias entity-alias="RUN" name="runCount" field="agentRunId" function="count"/>
        <alias entity-alias="RUN" name="totalTokensIn" field="tokensIn" function="sum"/>
        <alias entity-alias="RUN" name="totalTokensOut" field="tokensOut" function="sum"/>
        <alias entity-alias="RUN" name="totalCost" field="estimatedCost" function="sum"/>
        <alias entity-alias="RUN" name="fromDate"/>
    </view-entity>
</entities>
```
Note: `group-by="true"` on `agentName`/`userId` lets the same view return per-agent or per-user
rows; the service narrows with conditions and selects only the aliases it needs. Confirm Moqui's
exact view-entity aggregate syntax (`function="sum"`/`"count"` + `group-by`) on first compile —
it's standard but verify against an existing view entity in the runtime.

- [ ] **Step 2: Commit**

```bash
git add runtime/component/moqui-ai/entity/AiPriceEntities.xml
git commit -m "feat(moqui-ai): AiModelPrice entity + AiSpendSummary view"
```

---

## Task 2: Cost calculation + stamp on run finish

**Files:**
- Create: `runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/CostCalc.groovy`
- Modify: `runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/AgentRunner.groovy`
- Test: `AiCostTests` (Task 4)

- [ ] **Step 1: Write the failing cost-calc test (pure unit, no Moqui)**

Add to `AiCostTests` (full file in Task 4); this case needs no `ec`:
```groovy
    def "computes cost from per-million prices"() {
        expect:
        // 1000 in @ $3/M + 500 out @ $15/M = 0.003 + 0.0075 = 0.0105
        org.moqui.ai.CostCalc.cost(1000L, 500L, 3.0, 15.0) == 0.0105G
        org.moqui.ai.CostCalc.cost(0L, 0L, 3.0, 15.0) == 0.0G
    }
```

- [ ] **Step 2: Implement CostCalc**

`runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/CostCalc.groovy`:
```groovy
package org.moqui.ai

import java.math.BigDecimal
import java.math.RoundingMode

/** Pure cost math. Prices are per 1,000,000 tokens. Returns a BigDecimal cost. */
class CostCalc {
    static BigDecimal cost(long tokensIn, long tokensOut, BigDecimal inPricePerM, BigDecimal outPricePerM) {
        BigDecimal million = 1000000G
        BigDecimal inCost  = (inPricePerM ?: 0G)  * (tokensIn as BigDecimal)  / million
        BigDecimal outCost = (outPricePerM ?: 0G) * (tokensOut as BigDecimal) / million
        return (inCost + outCost).setScale(6, RoundingMode.HALF_UP)
    }
}
```

- [ ] **Step 3: Stamp estimatedCost when a run finishes**

In `AgentRunner`, add a price lookup + cost stamp. Add a helper and call it inside `finish`
(before the `update#moqui.ai.AiAgentRun` persist) so the stored run carries its cost:
```groovy
    /** Look up the effective price for (provider, model) at now, return estimated cost or 0. */
    private BigDecimal estimateCost(String providerName, String modelName, long tokensIn, long tokensOut) {
        EntityValue price = ec.entity.find("moqui.ai.AiModelPrice")
            .condition("providerName", providerName).condition("modelName", modelName)
            .conditionDate("fromDate", "thruDate", ec.user.nowTimestamp)
            .orderBy("-fromDate").useCache(true).list().getFirst()
        if (price == null) return 0G
        return CostCalc.cost(tokensIn, tokensOut,
            price.inputPricePerMillion as BigDecimal, price.outputPricePerMillion as BigDecimal)
    }
```
Then in `finish(...)`, compute the cost from the run's agent provider/model and put it in the
update map. The runner already holds `agent.providerName`/`agent.modelName` in `run`; pass them
into `finish` (extend its signature) or re-read the run. Minimal change: extend `finish` to take
`providerName`/`modelName`, compute `estimatedCost`, and add it to the persisted update:
```groovy
    // in finish(...), add:
    BigDecimal estCost = estimateCost(providerName, modelName,
        result.tokensIn as long, result.tokensOut as long)
    result.estimatedCost = estCost
    // ...add estimatedCost: estCost to the update#moqui.ai.AiAgentRun in-map
```
Update each `finish(...)` call site in `run` to pass `agent.providerName as String, agent.modelName as String`.
(If you prefer a smaller diff, read the agent fields off a captured local rather than widening the
signature — either way keep one source of the provider/model values.)

- [ ] **Step 4: Run tests; commit**

Run: `./gradlew :runtime:component:moqui-ai:test`
```bash
git add runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/CostCalc.groovy \
        runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/AgentRunner.groovy
git commit -m "feat(moqui-ai): estimate + stamp run cost from AiModelPrice"
```

---

## Task 3: Spend query + price upsert services

**Files:**
- Create: `runtime/component/moqui-ai/service/ai/CostServices.xml`

- [ ] **Step 1: Define get#AiSpend and store#AiModelPrice**

`runtime/component/moqui-ai/service/ai/CostServices.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<services xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/service-definition-3.xsd">

    <!-- Queryable estimated spend. All filters optional; returns totals (+ rows when grouped). -->
    <service verb="get" noun="AiSpend" authenticate="true">
        <in-parameters>
            <parameter name="agentName"/>
            <parameter name="userId"/>
            <parameter name="fromDate" type="Timestamp"/>
            <parameter name="thruDate" type="Timestamp"/>
            <parameter name="groupBy"><description>none | agent | user</description><default-value>none</default-value></parameter>
        </in-parameters>
        <out-parameters>
            <parameter name="totalCost" type="BigDecimal"/>
            <parameter name="totalTokensIn" type="Long"/>
            <parameter name="totalTokensOut" type="Long"/>
            <parameter name="runCount" type="Long"/>
            <parameter name="rows" type="List"><description>when grouped: [[agentName/userId, totalCost, ...], ...]</description></parameter>
        </out-parameters>
        <actions>
            <entity-find entity-name="moqui.ai.AiSpendSummary" list="rows">
                <econdition field-name="agentName" ignore-if-empty="true"/>
                <econdition field-name="userId" ignore-if-empty="true"/>
                <econdition field-name="fromDate" operator="greater-equals" from="fromDate" ignore-if-empty="true"/>
                <econdition field-name="fromDate" operator="less" from="thruDate" ignore-if-empty="true"/>
                <select-field field-name="runCount"/><select-field field-name="totalTokensIn"/>
                <select-field field-name="totalTokensOut"/><select-field field-name="totalCost"/>
                <select-field field-name="agentName"/><select-field field-name="userId"/>
            </entity-find>
            <!-- totals across the returned rows -->
            <set field="totalCost" from="rows.sum { it.totalCost ?: 0G } ?: 0G"/>
            <set field="totalTokensIn" from="(rows.sum { (it.totalTokensIn ?: 0L) as long } ?: 0L) as Long"/>
            <set field="totalTokensOut" from="(rows.sum { (it.totalTokensOut ?: 0L) as long } ?: 0L) as Long"/>
            <set field="runCount" from="(rows.sum { (it.runCount ?: 0L) as long } ?: 0L) as Long"/>
            <if condition="groupBy == 'none'"><set field="rows" from="[]"/></if>
        </actions>
    </service>

    <!-- Idempotent price upsert (store# per the verb convention: create-or-update). -->
    <service verb="store" noun="AiModelPrice" authenticate="true">
        <in-parameters>
            <parameter name="providerName" required="true"/>
            <parameter name="modelName" required="true"/>
            <parameter name="fromDate" type="Timestamp"/>
            <parameter name="inputPricePerMillion" type="BigDecimal" required="true"/>
            <parameter name="outputPricePerMillion" type="BigDecimal" required="true"/>
            <parameter name="currencyUomId"><default-value>USD</default-value></parameter>
        </in-parameters>
        <out-parameters><parameter name="fromDate" type="Timestamp"/></out-parameters>
        <actions>
            <if condition="!fromDate"><set field="fromDate" from="ec.user.nowTimestamp"/></if>
            <service-call name="store#moqui.ai.AiModelPrice" in-map="context"/>
        </actions>
    </service>
</services>
```
Note: confirm the `AiSpendSummary` view returns per-group rows as expected; if Moqui requires the
group-by aliases to be explicitly selected to trigger grouping, the `select-field` set above
handles it. The "totals = sum of rows" approach works whether grouped or not (when `groupBy=none`
we still sum all rows, then blank the `rows` list).

- [ ] **Step 2: Commit**

```bash
git add runtime/component/moqui-ai/service/ai/CostServices.xml
git commit -m "feat(moqui-ai): get#AiSpend query + store#AiModelPrice"
```

---

## Task 4: Cost tests

**Files:**
- Create: `runtime/component/moqui-ai/src/test/groovy/AiCostTests.groovy`
- Modify: `runtime/component/moqui-ai/src/test/groovy/MoquiSuite.groovy` (add `AiCostTests.class`)

- [ ] **Step 1: Write the tests (add AiCostTests to the suite first)**

`runtime/component/moqui-ai/src/test/groovy/AiCostTests.groovy`:
```groovy
import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.provider.MockProvider

class AiCostTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "CostAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ec.service.sync().name("ai.CostServices.store#AiModelPrice").parameters([providerName: "mock",
            modelName: "mock-1", inputPricePerMillion: 3.0G, outputPricePerMillion: 15.0G]).call()
    }
    def cleanupSpec() {
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "CostAgent").deleteAll()
        ec.entity.find("moqui.ai.AiModelPrice").condition("providerName", "mock").deleteAll()
        ec.entity.find("moqui.ai.AiAgentRun").condition("agentName", "CostAgent").deleteAll()
        ec.destroy()
    }
    def cleanup() { MockProvider.reset() }

    def "computes cost from per-million prices"() {
        expect:
        org.moqui.ai.CostCalc.cost(1000L, 500L, 3.0G, 15.0G) == 0.0105G
        org.moqui.ai.CostCalc.cost(0L, 0L, 3.0G, 15.0G) == 0.0G
    }

    def "a run stamps estimatedCost and get#AiSpend sums it"() {
        given: MockProvider.enqueue([assistantText: "ok", finishReason: "stop", tokensIn: 1000L, tokensOut: 500L])
        when:
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "CostAgent", userMessage: "hi"]).call()
        then:
        def run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()
        (run.estimatedCost as BigDecimal) == 0.0105G

        when:
        Map spend = ec.service.sync().name("ai.CostServices.get#AiSpend")
            .parameters([agentName: "CostAgent"]).call()
        then:
        (spend.totalCost as BigDecimal) == 0.0105G
        (spend.totalTokensIn as Long) == 1000L
        (spend.runCount as Long) == 1L
    }
}
```

- [ ] **Step 2: Run the suite; iterate to green; commit**

Run: `./gradlew :runtime:component:moqui-ai:test`
```bash
git add runtime/component/moqui-ai/src/test/groovy/AiCostTests.groovy \
        runtime/component/moqui-ai/src/test/groovy/MoquiSuite.groovy
git commit -m "test(moqui-ai): cost calc, run cost stamp, get#AiSpend rollup"
```

---

## Phase Done — Definition of Done
- `store#AiModelPrice` sets a model's effective price; runs stamp `AiAgentRun.estimatedCost`.
- `get#AiSpend` returns total cost / tokens / run count, filterable by agent, user, and date
  window, with optional per-agent / per-user `rows`.
- Sub-cent precision (`currency-precise`); prices are effective-dated so historical runs keep
  the price that was current at run time.
- Suite green.

## NOT in this phase
- **Spend limits / alerts / enforcement** (block or warn when a user/window exceeds a budget) —
  policy feature; the per-run ceiling from Phase 1 is the only hard stop for now.
- **Multi-currency conversion** — one `currencyUomId` per deployment assumed; mixing currencies
  in one `get#AiSpend` total is out of scope.
- **Cost-review UI** (Phase 5).
- Back-filling `estimatedCost` for runs created before a price existed (they stay 0; document it).
