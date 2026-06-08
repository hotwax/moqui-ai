# Cost Awareness Implementation Plan (reconciled)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the token counts every run already captures into money: a configurable per-model price, an estimated cost stamped on each `AiAgentRun`, and a queryable spend service (per agent / user / time window).

**Architecture:** One effective-dated price entity (`AiModelPrice`), a pure-Groovy `CostCalc` helper, a cost stamp in `AgentRunner.finish` (priced off the **served** provider/model the run actually used), and two services: `store#AiModelPrice` (idempotent price upsert) and `get#AiSpend` (filter + aggregate in Groovy over `AiAgentRun`). No change to the agentic loop. All data Map-based.

**Tech Stack:** Groovy 3 / JDK 11, Moqui (HotWax fork), Spock + JUnit Platform Suite, H2 dev DB.

---

## Implements
GitHub issue **#10** (Phase 3: Cost awareness). **Supersedes** the stale `docs/plans/2026-06-02-phase3-cost-awareness.md` (written against unverified Phase-1 assumptions; reconciled here to the current post-fallback code).

## Locked scope (2026-06-03)
- **In:** `AiModelPrice` + cost stamping per run + `get#AiSpend` aggregation.
- **Out (explicit):** `maxCost` enforcement (the field stays unused for now — a later policy task), multi-currency conversion (one `currencyUomId` per deployment), back-filling cost for runs created before a price existed (they stay 0), the cost-review UI (Phase 5).

## Key reconciliations vs. the old draft (read before starting)
- **Cost basis = the served model.** The fallback chain put `servedProviderName` + `servedByModelId` on the run result (the candidate that actually answered). Price off those, not the configured `agent.providerName`/`modelName`.
- **`get#AiSpend` aggregates in Groovy** over an `entity-find` on `AiAgentRun` (filter by agent/user/date, sum + optional group in code). No `AiSpendSummary` view-entity (avoids Moqui group-by/HAVING fragility; fine for an internal spend query at this scale).
- **`finish()` signature is unchanged** — it reads `result.servedProviderName`/`servedByModelId`/`tokensIn`/`tokensOut`, all already on the result Map.

## Conventions (confirmed; same as prior work)
- Tests: Spock `*Tests.groovy` in `src/test/groovy/`, registered in `MoquiSuite.groovy` `@SelectClasses`. This plan adds a **new** test class (`AiCostTests`) → it MUST be added to the suite.
- Run suite from moqui root `/Users/anilpatel/maarg-sd/moqui`:
  ```bash
  source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
  ```
  Live tests are key-gated; both accounts funded — they run for real, don't skip.
- New entity / nullable fields just need the boot the test triggers (Moqui auto-creates tables/columns).
- Services are `authenticate="true"` (consistent with `run#Agent`); tests log in a test user via `internalLoginUser` under `disableAuthz` (the established harness pattern).
- Money: `currency-precise` (DECIMAL(18,3)) for per-million-token prices; `AiAgentRun.estimatedCost` stays `number-decimal`. Prices effective-dated (`fromDate`/`thruDate`) so historical runs keep the price current at run time.
- Commit **inside the component repo** (`runtime/component/moqui-ai`, branch `feature/ai-agent-framework`) with component-relative paths.

## Canonical Map shapes (unchanged; cost rides existing fields)
- runResult already carries `tokensIn`, `tokensOut`, `servedProviderName`, `servedByModelId`, `estimatedCost` (the last currently always null — this plan populates it).

---

## File Structure

| File | Change |
|---|---|
| `entity/AiPriceEntities.xml` | New — `AiModelPrice` (effective-dated per-model price) |
| `src/main/groovy/org/moqui/ai/CostCalc.groovy` | New — pure cost math (tokens × per-million price) |
| `src/main/groovy/org/moqui/ai/AgentRunner.groovy` | `estimateCost` helper; stamp `estimatedCost` in `finish` from the served model |
| `service/ai/CostServices.xml` | New — `store#AiModelPrice`, `get#AiSpend` |
| `src/test/groovy/AiCostTests.groovy` | New — cost calc + stamp + spend rollup |
| `src/test/groovy/MoquiSuite.groovy` | Register `AiCostTests` |

---

## Task 1: `AiModelPrice` entity

**Files:**
- Create: `entity/AiPriceEntities.xml`
- Test: `src/test/groovy/AiEntitiesTests.groovy`

- [ ] **Step 1: Write a failing entity round-trip test**

In `src/test/groovy/AiEntitiesTests.groovy`, add (match the file's `disableAuthz`/create/read pattern):

```groovy
    def "AiModelPrice stores an effective-dated per-model price"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeValue("moqui.ai.AiModelPrice").setAll([providerName: "openai", modelName: "gpt-4o-mini",
            fromDate: ec.user.nowTimestamp, inputPricePerMillion: 0.150G, outputPricePerMillion: 0.600G,
            currencyUomId: "USD"]).create()
        when:
        def p = ec.entity.find("moqui.ai.AiModelPrice").condition("providerName", "openai")
            .condition("modelName", "gpt-4o-mini").list().getFirst()
        then:
        (p.inputPricePerMillion as BigDecimal) == 0.150G
        (p.outputPricePerMillion as BigDecimal) == 0.600G
        p.currencyUomId == "USD"
        cleanup:
        ec.entity.find("moqui.ai.AiModelPrice").condition("providerName", "openai").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```

- [ ] **Step 2: Run to confirm it fails**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — entity `moqui.ai.AiModelPrice` not defined.

- [ ] **Step 3: Create the entity file**

`entity/AiPriceEntities.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<entities xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/entity-definition-3.xsd">

    <!-- Effective-dated price per (provider, model). inputPrice/outputPrice are per 1,000,000 tokens.
         A model's price changes over time; old runs keep the price current at run time. -->
    <entity entity-name="AiModelPrice" package="moqui.ai">
        <field name="providerName" type="text-short" is-pk="true"/>
        <field name="modelName" type="text-medium" is-pk="true"/>
        <field name="fromDate" type="date-time" is-pk="true"/>
        <field name="thruDate" type="date-time"/>
        <field name="inputPricePerMillion" type="currency-precise"/>
        <field name="outputPricePerMillion" type="currency-precise"/>
        <field name="currencyUomId" type="id"/>
    </entity>
</entities>
```

- [ ] **Step 4: Run — passes**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add entity/AiPriceEntities.xml src/test/groovy/AiEntitiesTests.groovy && \
git commit -m "feat(ai): AiModelPrice effective-dated per-model price entity (#10)"
```

---

## Task 2: `CostCalc` helper + stamp `estimatedCost` on the served model

**Files:**
- Create: `src/main/groovy/org/moqui/ai/CostCalc.groovy`
- Modify: `src/main/groovy/org/moqui/ai/AgentRunner.groovy`
- Test: `src/test/groovy/AiCostTests.groovy` (created in Task 4; the CostCalc unit case can live there)

- [ ] **Step 1: Write the failing pure cost-calc test**

Create `src/test/groovy/AiCostTests.groovy` with just the pure case for now (the full file/suite registration is Task 4; this gives Task 2 a red test):

```groovy
import spock.lang.*

class AiCostTests extends Specification {
    def "computes cost from per-million prices"() {
        expect:
        // 1000 in @ $3/M + 500 out @ $15/M = 0.003 + 0.0075 = 0.0105
        org.moqui.ai.CostCalc.cost(1000L, 500L, 3.0G, 15.0G) == 0.010500G
        org.moqui.ai.CostCalc.cost(0L, 0L, 3.0G, 15.0G) == 0.000000G
    }
}
```

Add `AiCostTests.class` to `src/test/groovy/MoquiSuite.groovy`'s `@SelectClasses` list now (so this test runs):

```groovy
        AiConversationTests.class, OpenAiProviderTests.class, AiCostTests.class ])
```
(append `AiCostTests.class` to the existing list — match the file's current trailing entry/bracket.)

- [ ] **Step 2: Run to confirm it fails**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — `org.moqui.ai.CostCalc` does not exist.

- [ ] **Step 3: Implement `CostCalc`**

`src/main/groovy/org/moqui/ai/CostCalc.groovy`:
```groovy
package org.moqui.ai

import java.math.BigDecimal
import java.math.RoundingMode

/** Pure cost math. Prices are per 1,000,000 tokens. Returns a BigDecimal cost scaled to 6 dp. */
class CostCalc {
    static BigDecimal cost(long tokensIn, long tokensOut, BigDecimal inPricePerM, BigDecimal outPricePerM) {
        BigDecimal million = 1000000G
        BigDecimal inCost  = (inPricePerM ?: 0G)  * (tokensIn as BigDecimal)  / million
        BigDecimal outCost = (outPricePerM ?: 0G) * (tokensOut as BigDecimal) / million
        return (inCost + outCost).setScale(6, RoundingMode.HALF_UP)
    }
}
```

- [ ] **Step 4: Run — the pure test passes**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS (the CostCalc unit test; rest of suite still green).

- [ ] **Step 5: Stamp `estimatedCost` in `AgentRunner.finish` from the served model**

In `AgentRunner.groovy`, add an `estimateCost` helper (next to `loadModelCandidates`):
```groovy
    /** Effective price for (provider, model) at now → estimated cost from token counts, or 0 when
     *  no price is configured (cost stays 0; never blocks a run). */
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

In `finish(...)`, BEFORE the `update#moqui.ai.AiAgentRun` persist, compute and set the cost from the **served** model on the result Map:
```groovy
        result.estimatedCost = estimateCost(result.servedProviderName as String, result.servedByModelId as String,
            result.tokensIn as long, result.tokensOut as long)
```

Add `estimatedCost: result.estimatedCost` to the `update#moqui.ai.AiAgentRun` params map (alongside the existing `tokensIn`/`tokensOut`/`servedByModelId`/`providerName`/`providerRunId`):
```groovy
            providerName: result.servedProviderName, servedByModelId: result.servedByModelId,
            providerRunId: result.providerRunId, estimatedCost: result.estimatedCost])
```

Also seed `estimatedCost: 0G` into the `result = [...]` literal (so the key always exists on the returned Map; add it next to `providerRunId: null`):
```groovy
                      servedProviderName: primary.providerName as String, providerRunId: null, estimatedCost: 0G]
```

> `import java.math.BigDecimal` — confirm it's available (Groovy imports `java.math.*` by default, so `BigDecimal`/`0G` literals work without an explicit import; `EntityValue` is already imported).
>
> Cost basis note: a run that failed over uses the **served (final) model's** price on the run's **total** tokens — exact for the common single-model run, an approximation for the rare mixed-model run. Acceptable for an estimate; documented as such.

- [ ] **Step 6: Run — all green**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS. Existing runs with no price configured get `estimatedCost = 0` (no behavior change beyond the now-populated 0).

- [ ] **Step 7: Commit**

```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add src/main/groovy/org/moqui/ai/CostCalc.groovy \
        src/main/groovy/org/moqui/ai/AgentRunner.groovy \
        src/test/groovy/AiCostTests.groovy \
        src/test/groovy/MoquiSuite.groovy && \
git commit -m "feat(ai): estimate + stamp run cost from AiModelPrice (served model) (#10)"
```

---

## Task 3: `store#AiModelPrice` + `get#AiSpend` services

**Files:**
- Create: `service/ai/CostServices.xml`

- [ ] **Step 1: Create the services**

`service/ai/CostServices.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<services xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/service-definition-3.xsd">

    <!-- Idempotent price upsert (store# = create-or-update). Defaults fromDate to now, currency to USD. -->
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

    <!-- Queryable estimated spend. All filters optional. Aggregates in Groovy over AiAgentRun;
         groupBy = none | agent | user returns optional per-group rows. -->
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
            <parameter name="rows" type="List"><description>when grouped: [[key, totalCost, totalTokensIn, totalTokensOut, runCount], ...]</description></parameter>
        </out-parameters>
        <actions>
            <entity-find entity-name="moqui.ai.AiAgentRun" list="runs">
                <econdition field-name="agentName" ignore-if-empty="true"/>
                <econdition field-name="userId" ignore-if-empty="true"/>
                <econdition field-name="fromDate" operator="greater-equals" from="fromDate" ignore-if-empty="true"/>
                <econdition field-name="fromDate" operator="less" from="thruDate" ignore-if-empty="true"/>
            </entity-find>
            <script><![CDATA[
                totalCost = (runs.sum { (it.estimatedCost ?: 0G) as BigDecimal } ?: 0G) as BigDecimal
                totalTokensIn = (runs.sum { (it.tokensIn ?: 0L) as long } ?: 0L) as Long
                totalTokensOut = (runs.sum { (it.tokensOut ?: 0L) as long } ?: 0L) as Long
                runCount = runs.size() as Long
                rows = []
                if (groupBy == 'agent' || groupBy == 'user') {
                    String keyField = groupBy == 'agent' ? 'agentName' : 'userId'
                    runs.groupBy { it.get(keyField) }.each { k, groupRuns ->
                        rows.add([key: k,
                            totalCost: (groupRuns.sum { (it.estimatedCost ?: 0G) as BigDecimal } ?: 0G) as BigDecimal,
                            totalTokensIn: (groupRuns.sum { (it.tokensIn ?: 0L) as long } ?: 0L) as Long,
                            totalTokensOut: (groupRuns.sum { (it.tokensOut ?: 0L) as long } ?: 0L) as Long,
                            runCount: groupRuns.size() as Long])
                    }
                }
            ]]></script>
        </actions>
    </service>
</services>
```

> The econditions on `fromDate` filter the run's own `fromDate` (start time) — a run started within `[fromDate, thruDate)`. `ignore-if-empty` means an omitted filter matches all. Groovy `sum` returns `null` for an empty list, hence the `?: 0G`/`?: 0L` guards.

- [ ] **Step 2: Verify it compiles (service is exercised by Task 4 tests)**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS (no new test yet; this confirms the service XML loads without error).

- [ ] **Step 3: Commit**

```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add service/ai/CostServices.xml && \
git commit -m "feat(ai): store#AiModelPrice + get#AiSpend aggregation services (#10)"
```

---

## Task 4: End-to-end cost tests

**Files:**
- Modify: `src/test/groovy/AiCostTests.groovy` (add the integration cases to the file created in Task 2)

- [ ] **Step 1: Add the integration tests**

Replace `src/test/groovy/AiCostTests.groovy` with the full class (keeps the pure case, adds setup + integration). It follows the established harness pattern: `@Shared ec`, a test user + `internalLoginUser` for the authenticate=true services, MockProvider for deterministic tokens:

```groovy
import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.entity.EntityValue
import org.moqui.ai.provider.MockProvider

class AiCostTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        ec.entity.makeValue("moqui.security.UserAccount")
            .setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "CostAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.service.sync().name("ai.CostServices.store#AiModelPrice").parameters([providerName: "mock",
            modelName: "mock-1", inputPricePerMillion: 3.0G, outputPricePerMillion: 15.0G]).call()
    }
    def cleanupSpec() {
        if (ec == null) return
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiAgentRun").condition("agentName", "CostAgent").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "CostAgent").deleteAll()
        ec.entity.find("moqui.ai.AiModelPrice").condition("providerName", "mock").deleteAll()
        ec.artifactExecution.enableAuthz()
        ec.destroy()
    }
    def cleanup() { MockProvider.reset() }

    def "computes cost from per-million prices"() {
        expect:
        org.moqui.ai.CostCalc.cost(1000L, 500L, 3.0G, 15.0G) == 0.010500G
        org.moqui.ai.CostCalc.cost(0L, 0L, 3.0G, 15.0G) == 0.000000G
    }

    def "a run stamps estimatedCost and get#AiSpend sums it"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.message.clearErrors()
        MockProvider.reset()
        MockProvider.enqueue([assistantText: "ok", finishReason: "stop", toolCalls: [], tokensIn: 1000L, tokensOut: 500L])
        when:
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "CostAgent", userMessage: "hi"]).call()
        EntityValue run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()
        then:
        out.statusId == "AI_RUN_COMPLETED"
        (run.estimatedCost as BigDecimal) == 0.010500G       // 1000@$3/M + 500@$15/M
        when:
        Map spend = ec.service.sync().name("ai.CostServices.get#AiSpend").parameters([agentName: "CostAgent"]).call()
        then:
        (spend.totalCost as BigDecimal) == 0.010500G
        (spend.totalTokensIn as Long) == 1000L
        (spend.runCount as Long) == 1L
        cleanup:
        ec.artifactExecution.enableAuthz()
    }

    def "get#AiSpend groups by agent"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.message.clearErrors()
        MockProvider.reset()
        MockProvider.enqueue([assistantText: "ok", finishReason: "stop", toolCalls: [], tokensIn: 200L, tokensOut: 100L])
        ec.service.sync().name("ai.AgentServices.run#Agent").parameters([agentName: "CostAgent", userMessage: "again"]).call()
        when:
        Map spend = ec.service.sync().name("ai.CostServices.get#AiSpend").parameters([groupBy: "agent"]).call()
        then:
        spend.rows.any { it.key == "CostAgent" && (it.runCount as Long) >= 1L }
        cleanup:
        ec.artifactExecution.enableAuthz()
    }
}
```

> Note: this run executes under `internalLoginUser("AiTestUser")` from `setupSpec`, and `run#Agent` / cost services are `authenticate="true"`. Keep `disableAuthz` active across each `when:` (the test user has no granted permissions; login supplies authentication, disableAuthz supplies authorization — the established split). If `AiEntitiesTests` or another class already left a price/agent named the same, the unique `CostAgent`/`mock-1` names here avoid collisions.

- [ ] **Step 2: Run — all green**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS — cost calc, the stamped `estimatedCost`, and both `get#AiSpend` shapes (total + grouped). Live tests still green.

- [ ] **Step 3: Commit**

```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add src/test/groovy/AiCostTests.groovy && \
git commit -m "test(ai): cost calc, run cost stamp, get#AiSpend rollup (#10)"
```

---

## Task 5: Decision-record doc note

**Files:**
- Modify: `docs/specs/2026-06-03-enterprise-decisions-gap-report.md`

- [ ] **Step 1: Record cost stamping/query shipped**

The enterprise gap report frames cost under "Cost awareness" (the principle behind `estimatedCost`/`maxCost`). Add a short note where cost is discussed (and/or near Decision 10's correlation area): estimated cost is now stamped per run from an effective-dated `AiModelPrice` (priced off the served model); `get#AiSpend` aggregates spend by agent/user/time window. Note explicitly that **`maxCost` enforcement remains deferred** (a later policy task) — the field is intentionally still unused. Keep edits surgical.

- [ ] **Step 2: Commit**

```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add docs/specs/2026-06-03-enterprise-decisions-gap-report.md && \
git commit -m "docs(ai): record cost stamping + get#AiSpend shipped; maxCost enforcement still deferred (#10)"
```

---

## Self-Review

**Spec coverage** (vs. issue #10 + locked scope):
- `AiModelPrice` (effective-dated, per-million prices) → Task 1. ✅
- Cost stamping on each run → Task 2 (`CostCalc` + `estimateCost` in `finish`, priced off served model). ✅
- `get#AiSpend` (per agent/user/time window) → Task 3 + Task 4 tests. ✅
- `store#AiModelPrice` upsert → Task 3. ✅
- `maxCost` enforcement explicitly OUT → not built; documented in Task 5. ✅
- Multi-currency / back-fill / UI OUT → not built. ✅

**Placeholder scan:** every code step has complete code; every run step has the exact command + expected outcome. No TBD. ✅

**Type/name consistency:**
- `CostCalc.cost(long, long, BigDecimal, BigDecimal) → BigDecimal` scaled 6dp; called in `estimateCost` (Task 2) and the pure test (Task 2/4) with matching arg types. ✅
- `estimateCost(String, String, long, long) → BigDecimal`, fed `result.servedProviderName`/`servedByModelId`/`tokensIn`/`tokensOut` — all present on the result Map (servedProviderName/servedByModelId added by the fallback chain; tokens by Phase 1). ✅
- `result.estimatedCost` seeded `0G`, set in `finish`, persisted to `AiAgentRun.estimatedCost` (number-decimal — BigDecimal stores fine). ✅
- `AiModelPrice` PK (providerName, modelName, fromDate); `store#AiModelPrice` defaults fromDate→now; `estimateCost` reads via `conditionDate`. ✅
- `get#AiSpend` out-params (`totalCost` BigDecimal, `totalTokensIn`/`totalTokensOut`/`runCount` Long, `rows` List) match what the script sets; `rows[].key` matches the test assertion. ✅
- `AiCostTests` added to `MoquiSuite` (Task 2 Step 1). ✅

**Ordering / independent deployability:** Task 1 (entity) → Task 2 (calc + stamp, needs entity) → Task 3 (services, needs entity) → Task 4 (integration, needs all) → Task 5 (docs). Each ends green and committed.
