# Multi-Provider Fallback Chain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give an agent an ordered list of provider/model candidates so a provider outage/rate-limit fails over to the next candidate instead of killing the run, and record which model actually answered.

**Architecture:** New child entity `AiAgentModel` holds priority-ordered `(providerName, modelName)` candidates per agent; when none exist, the agent's existing single `providerName`/`modelName` is the sole candidate (backward-compatible). `AgentRunner` resolves the candidate list once, then each LLM call uses **sticky failover**: try the current candidate, and on a `provider.chat()` failure advance to the next and stay there for the rest of the run. The run's `servedByModelId` + `providerName` reflect the candidate that answered.

**Tech Stack:** Groovy 3 / JDK 11, Moqui (HotWax fork), Spock + JUnit Platform Suite, H2 dev DB.

---

## Implements
GitHub issue **#20** (feat: Multi-provider fallback chain) · Decision 7 of `docs/specs/2026-06-03-enterprise-decisions-gap-report.md`.

## Locked design decisions (2026-06-03)
- **Config shape:** new child entity `AiAgentModel`; single `AiAgent.providerName`/`modelName` are the implicit default when no chain rows exist (backward-compatible).
- **Failover:** sticky — advance on a provider-call failure, stay on the working candidate for the rest of the run.
- **Trigger:** provider call failures only — `provider.chat()` throwing (HTTP non-2xx incl. 429, timeout, transport). A successful response is always accepted.

## Conventions (confirmed; same as prior tasks)
- Tests: Spock `*Tests.groovy` in `src/test/groovy/`, registered in `MoquiSuite.groovy`. This plan adds methods to **existing** test classes — no suite-registration change.
- Run suite from moqui root `/Users/anilpatel/maarg-sd/moqui`:
  ```bash
  source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
  ```
  Live tests are key-gated; both accounts are funded — they run for real, don't skip.
- Entities are ours — edit directly, NOT `extend-entity`. Adding a nullable field / new entity only needs the boot that the test triggers (Moqui auto-creates the table/columns). Drop the dev DB only on FK/type *changes*, not additions.
- Commit **inside the component repo** (`runtime/component/moqui-ai`, branch `feature/ai-agent-framework`) with component-relative paths.
- Maps only for LLM request/response data.

## Canonical Map shapes (updated by this plan)
- **Candidate Map:** `[providerName, modelName]`.
- **`callWithFailover` return:** `[resp, idx, providerName, modelName, failedAttempts]` where `failedAttempts` is a `List<Map>` of `[providerName, modelName, error]`.
- **runResult Map** gains `servedProviderName` (the provider that answered; seeded from the primary candidate).

---

## File Structure

| File | Change |
|---|---|
| `entity/AiEntities.xml` | New `AiAgentModel` entity (priority-ordered candidates) |
| `src/main/groovy/org/moqui/ai/AgentRunner.groovy` | `loadModelCandidates` helper; candidate-based resolution; `callWithFailover` (sticky); track + persist served provider/model; record failed attempts as steps |
| `src/main/groovy/org/moqui/ai/provider/MockProvider.groovy` | Honor an `__error` key in an enqueued response by throwing — lets tests induce a provider failure deterministically |
| `service/ai/AgentServices.xml` | Add `providerName` out-parameter to `run#Agent` (served provider) |
| `docs/specs/2026-06-03-enterprise-decisions-gap-report.md` | Mark Decision 7 shipped |
| `src/test/groovy/AgentRunnerTests.groovy` | Resolution + sticky-failover + exhausted + failed-step tests |
| `src/test/groovy/AiEntitiesTests.groovy` | `AiAgentModel` round-trip assertion |

---

## Task 1: `AiAgentModel` entity + candidate-based resolution (behavior-preserving)

Introduce the candidate abstraction without failover yet: resolve a candidate list (chain rows by priority, else the single fields) and have the loop use the **primary** candidate. With no chain rows this is byte-for-byte today's behavior.

**Files:**
- Modify: `entity/AiEntities.xml`
- Modify: `src/main/groovy/org/moqui/ai/AgentRunner.groovy`
- Test: `src/test/groovy/AiEntitiesTests.groovy`, `src/test/groovy/AgentRunnerTests.groovy`

- [ ] **Step 1: Write a failing entity round-trip test**

In `src/test/groovy/AiEntitiesTests.groovy`, add (match the file's existing `disableAuthz`/`enableAuthz` + create/read pattern):

```groovy
    def "AiAgentModel stores priority-ordered provider/model candidates"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeValue("moqui.ai.AiAgentModel")
            .setAll([agentName: "EntAgent", priority: 0, providerName: "openai", modelName: "gpt-4o-mini"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgentModel")
            .setAll([agentName: "EntAgent", priority: 1, providerName: "anthropic", modelName: "claude-sonnet-4-6"]).createOrUpdate()
        when:
        List rows = ec.entity.find("moqui.ai.AiAgentModel").condition("agentName", "EntAgent").orderBy("priority").list()
        then:
        rows.size() == 2
        rows[0].providerName == "openai"
        rows[1].modelName == "claude-sonnet-4-6"
        cleanup:
        ec.entity.find("moqui.ai.AiAgentModel").condition("agentName", "EntAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```

- [ ] **Step 2: Run to confirm it fails**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — entity `moqui.ai.AiAgentModel` not defined.

- [ ] **Step 3: Add the entity**

In `entity/AiEntities.xml`, add after the `AiAgentTool` entity (keep it grouped with the other definition entities):

```xml
    <entity entity-name="AiAgentModel" package="moqui.ai">
        <field name="agentName" type="id" is-pk="true"/>
        <field name="priority" type="number-integer" is-pk="true"><description>Lower is tried first (0,1,2,...). Sticky failover advances on provider-call failure.</description></field>
        <field name="providerName" type="text-short"/>
        <field name="modelName" type="text-medium"/>
        <!-- one-nofk: config child; keep agent delete/test-cleanup unencumbered (mirrors AiAgentRun) -->
        <relationship type="one-nofk" related="moqui.ai.AiAgent" short-alias="agent"/>
    </entity>
```

- [ ] **Step 4: Write a failing resolution test (chain row wins over single fields)**

In `src/test/groovy/AgentRunnerTests.groovy`, add (use the file's existing `runner()` helper + `@Shared ec`; MockProvider returns the enqueued map):

```groovy
    def "uses the AiAgentModel chain (primary candidate) when present"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "ok", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "ChainAgent", providerName: "mock",
            modelName: "legacy-single", systemPrompt: "x", maxIterations: 3, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgentModel")
            .setAll([agentName: "ChainAgent", priority: 0, providerName: "mock", modelName: "primary-from-chain"]).createOrUpdate()
        when:
        Map out = runner().run("ChainAgent", "hi", null)
        EntityValue run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()
        then:
        out.statusId == "AI_RUN_COMPLETED"
        run.servedByModelId == "primary-from-chain"   // chain primary, not the legacy single field
        cleanup:
        ec.entity.find("moqui.ai.AiAgentModel").condition("agentName", "ChainAgent").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "ChainAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```

- [ ] **Step 5: Run to confirm it fails**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — `run.servedByModelId` is `legacy-single` (resolution still reads the single field).

- [ ] **Step 6: Add `loadModelCandidates` and switch resolution to the primary candidate**

In `AgentRunner.groovy`, add this helper (next to `loadToolSchemas`):

```groovy
    /** Ordered provider/model candidates for failover: AiAgentModel rows by priority, or the agent's
     *  own providerName/modelName when no chain is defined (backward-compatible). */
    private List<Map> loadModelCandidates(String agentName, EntityValue agent) {
        List<Map> candidates = []
        for (EntityValue m in ec.entity.find("moqui.ai.AiAgentModel")
                .condition("agentName", agentName).orderBy("priority").useCache(true).list())
            candidates.add([providerName: m.providerName, modelName: m.modelName])
        if (candidates.isEmpty())
            candidates.add([providerName: agent.providerName, modelName: agent.modelName])
        return candidates
    }
```

In `run(...)`, REPLACE the single-provider resolution line:

```groovy
        LlmProvider provider = ai.getProvider(agent.providerName as String)
```

with the candidate list + primary:

```groovy
        List<Map> candidates = loadModelCandidates(agentName, agent)
        Map primary = candidates[0]
        LlmProvider provider = ai.getProvider(primary.providerName as String)
```

Change the `result` literal's `servedByModelId` seed from `agent.modelName as String` to `primary.modelName as String`, and add the served-provider key:

```groovy
                      structuredResult: null, servedByModelId: primary.modelName as String,
                      servedProviderName: primary.providerName as String, providerRunId: null]
```

Change the `create#moqui.ai.AiAgentRun` params `providerName`/`modelName` to use the primary:

```groovy
            providerName: primary.providerName, modelName: primary.modelName, userMessage: userMessage])
```

Change the `provider.chat([...])` call to use the primary model (still single-candidate — failover comes in Task 2):

```groovy
                Map resp = provider.chat([model: primary.modelName, systemContext: agent.systemPrompt,
                        messages: messages, tools: toolSchemas, responseSchema: responseSchema])
```

- [ ] **Step 7: Run — both new tests pass, all prior green**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS. No-chain agents (every existing test) use `primary = [agent.providerName, agent.modelName]` — identical behavior. Live tests unaffected.

- [ ] **Step 8: Commit**

```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add entity/AiEntities.xml \
        src/main/groovy/org/moqui/ai/AgentRunner.groovy \
        src/test/groovy/AiEntitiesTests.groovy \
        src/test/groovy/AgentRunnerTests.groovy && \
git commit -m "feat(ai): AiAgentModel candidate chain + candidate-based resolution (#20)"
```

---

## Task 2: Sticky failover + served provider/model + failed-attempt observability

**Files:**
- Modify: `src/main/groovy/org/moqui/ai/provider/MockProvider.groovy`
- Modify: `src/main/groovy/org/moqui/ai/AgentRunner.groovy`
- Test: `src/test/groovy/AgentRunnerTests.groovy`

- [ ] **Step 1: Write failing failover tests (induced provider failure via Mock)**

In `src/test/groovy/AgentRunnerTests.groovy`, add three tests. They rely on `MockProvider` throwing when an enqueued response carries `__error` (added in Step 3). Both candidates use provider `mock` (same instance, shared queue), so candidate 0 polls the failure and candidate 1 polls the success — exercising failover deterministically:

```groovy
    def "fails over to the next candidate when the primary provider call throws"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.enqueue([__error: "primary down (503)"])
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "ok", finishReason: "stop", toolCalls: [], tokensIn: 2L, tokensOut: 1L])
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "FailoverAgent", providerName: "mock",
            modelName: "ignored", systemPrompt: "x", maxIterations: 3, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgentModel").setAll([agentName: "FailoverAgent", priority: 0, providerName: "mock", modelName: "primary"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgentModel").setAll([agentName: "FailoverAgent", priority: 1, providerName: "mock", modelName: "backup"]).createOrUpdate()
        when:
        Map out = runner().run("FailoverAgent", "hi", null)
        EntityValue run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()
        then:
        out.statusId == "AI_RUN_COMPLETED"
        run.servedByModelId == "backup"        // primary failed, backup answered
        run.providerName == "mock"
        // a failed-attempt step was recorded for the primary
        ec.entity.find("moqui.ai.AiAgentRunStep").condition("agentRunId", out.agentRunId)
            .condition("stepType", "llm_call_failed").list().size() == 1
        cleanup:
        ec.entity.find("moqui.ai.AiAgentModel").condition("agentName", "FailoverAgent").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "FailoverAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "fails the run when all candidates are exhausted"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.enqueue([__error: "down-1"])
        org.moqui.ai.provider.MockProvider.enqueue([__error: "down-2"])
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "AllDownAgent", providerName: "mock",
            modelName: "ignored", systemPrompt: "x", maxIterations: 3, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgentModel").setAll([agentName: "AllDownAgent", priority: 0, providerName: "mock", modelName: "m1"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgentModel").setAll([agentName: "AllDownAgent", priority: 1, providerName: "mock", modelName: "m2"]).createOrUpdate()
        when:
        Map out = runner().run("AllDownAgent", "hi", null)
        EntityValue run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()
        then:
        out.statusId == "AI_RUN_FAILED"
        (run.errorText as String)?.contains("down-2")    // last error surfaced
        cleanup:
        ec.entity.find("moqui.ai.AiAgentModel").condition("agentName", "AllDownAgent").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "AllDownAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "failover is sticky: once advanced, stays on the working candidate"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        // iteration 1: primary fails, backup succeeds but asks for a (nonexistent) tool to force a 2nd iteration
        org.moqui.ai.provider.MockProvider.enqueue([__error: "primary down"])
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "c1", name: "no.such.Tool", arguments: [:]]], tokensIn: 1L, tokensOut: 1L])
        // iteration 2: if sticky, only ONE call happens (backup) and it completes; primary is NOT retried
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "done", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "StickyAgent", providerName: "mock",
            modelName: "ignored", systemPrompt: "x", maxIterations: 4, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgentModel").setAll([agentName: "StickyAgent", priority: 0, providerName: "mock", modelName: "primary"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgentModel").setAll([agentName: "StickyAgent", priority: 1, providerName: "mock", modelName: "backup"]).createOrUpdate()
        when:
        Map out = runner().run("StickyAgent", "hi", null)
        EntityValue run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()
        then:
        out.statusId == "AI_RUN_COMPLETED"
        run.servedByModelId == "backup"
        // exactly one failed-attempt step total (iteration 1 only) — iteration 2 did not retry the primary
        ec.entity.find("moqui.ai.AiAgentRunStep").condition("agentRunId", out.agentRunId)
            .condition("stepType", "llm_call_failed").list().size() == 1
        cleanup:
        ec.entity.find("moqui.ai.AiAgentModel").condition("agentName", "StickyAgent").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "StickyAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```

- [ ] **Step 2: Run to confirm they fail**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — MockProvider doesn't throw on `__error` yet (it returns the map verbatim), and there's no failover.

- [ ] **Step 3: Make MockProvider able to induce a failure**

In `src/main/groovy/org/moqui/ai/provider/MockProvider.groovy`, in `chat(Map request)`, throw when the polled response carries `__error`:

```groovy
    @Override Map chat(Map request) {
        Map r = SCRIPT.poll()
        if (r != null) {
            if (r.containsKey("__error")) throw new RuntimeException(r.__error as String)
            return r
        }
        return [assistantText: "", finishReason: "stop", toolCalls: [], tokensIn: 0L, tokensOut: 0L]
    }
```

- [ ] **Step 4: Add `callWithFailover` and wire sticky failover into the loop**

In `AgentRunner.groovy`, add the helper (next to `loadModelCandidates`):

```groovy
    /** Sticky failover: try candidates from startIdx in order; the first whose provider.chat()
     *  succeeds wins. Returns [resp, idx, providerName, modelName, failedAttempts]; throws the last
     *  error if every remaining candidate fails. Pure (no persistence) — the caller records steps. */
    private Map callWithFailover(List<Map> candidates, int startIdx, Map baseRequest, String runId) {
        RuntimeException last = null
        List<Map> failed = []
        for (int i = startIdx; i < candidates.size(); i++) {
            Map c = candidates[i]
            try {
                LlmProvider p = ai.getProvider(c.providerName as String)
                Map resp = p.chat(baseRequest + [model: c.modelName])
                return [resp: resp, idx: i, providerName: c.providerName, modelName: c.modelName, failedAttempts: failed]
            } catch (RuntimeException e) {
                last = e
                logger.warn("Agent run ${runId} candidate ${c.providerName}:${c.modelName} failed, trying next: ${e.message}")
                failed.add([providerName: c.providerName, modelName: c.modelName, error: e.message])
            }
        }
        throw (last ?: new RuntimeException("No model candidates configured for agent run ${runId}"))
    }
```

Now change `run(...)`. Remove the primary-only provider line added in Task 1:

```groovy
        Map primary = candidates[0]
        LlmProvider provider = ai.getProvider(primary.providerName as String)
```

becomes just:

```groovy
        Map primary = candidates[0]
```

Add a sticky index before the loop (next to `int stepSeq = 0`):

```groovy
        int candIdx = 0
```

Replace the `Map resp = provider.chat([...])` call and the token-accounting block with the failover call + failed-attempt step recording. Specifically, replace:

```groovy
                Map resp = provider.chat([model: primary.modelName, systemContext: agent.systemPrompt,
                        messages: messages, tools: toolSchemas, responseSchema: responseSchema])
                long inTok = (resp.tokensIn ?: 0L) as long
```

with:

```groovy
                Map call = callWithFailover(candidates, candIdx,
                        [systemContext: agent.systemPrompt, messages: messages, tools: toolSchemas, responseSchema: responseSchema], runId)
                candIdx = call.idx as int                     // sticky: stay on the working candidate
                result.servedProviderName = call.providerName
                result.servedByModelId = call.modelName
                for (Map fa in (call.failedAttempts as List<Map>)) {   // observability for each skipped candidate
                    stepSeq++
                    persist("create#moqui.ai.AiAgentRunStep", [agentRunId: runId, stepSeqId: stepSeq as String,
                        stepType: "llm_call_failed", finishReason: "provider_error:${fa.providerName}:${fa.modelName}" as String])
                }
                Map resp = call.resp as Map
                long inTok = (resp.tokensIn ?: 0L) as long
```

In `finish(...)`, persist the served provider on the run by adding `providerName: result.servedProviderName` to the `update#moqui.ai.AiAgentRun` params:

```groovy
        persist("update#moqui.ai.AiAgentRun", [agentRunId: runId, thruDate: ec.user.nowTimestamp,
            statusId: statusId, assistantMessage: result.assistantMessage, iterations: result.iterations,
            tokensIn: result.tokensIn, tokensOut: result.tokensOut, errorText: errorText,
            providerName: result.servedProviderName, servedByModelId: result.servedByModelId, providerRunId: result.providerRunId])
```

> The `callWithFailover` throw on all-exhausted propagates to the existing `catch (Throwable t)` in `run()`, which routes to `finish(... "AI_RUN_FAILED", t.message)` — so the "all exhausted" test gets `AI_RUN_FAILED` with the last error in `errorText`. No new catch needed.

- [ ] **Step 5: Run — failover tests pass, all prior green**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS. The three failover tests pass; no-chain and single-candidate agents are unaffected (a 1-element candidate list with no failure behaves exactly as before, and `servedProviderName` = the only candidate). Live tests still green.

- [ ] **Step 6: Commit**

```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add src/main/groovy/org/moqui/ai/provider/MockProvider.groovy \
        src/main/groovy/org/moqui/ai/AgentRunner.groovy \
        src/test/groovy/AgentRunnerTests.groovy && \
git commit -m "feat(ai): sticky multi-provider failover with served-model + failed-attempt steps (#20)"
```

---

## Task 3: Expose served provider on the service + decision-record doc

**Files:**
- Modify: `service/ai/AgentServices.xml`
- Modify: `docs/specs/2026-06-03-enterprise-decisions-gap-report.md`
- Test: `src/test/groovy/RunAgentServiceTests.groovy`

- [ ] **Step 1: Write a failing service out-param test**

In `src/test/groovy/RunAgentServiceTests.groovy`, add (match the file's login/setup pattern):

```groovy
    def "run#Agent surfaces the served providerName"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "ok", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "SvcProvAgent", providerName: "mock",
            modelName: "m1", systemPrompt: "x", maxIterations: 2, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserAccount")
            .setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        when:
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "SvcProvAgent", userMessage: "q"]).call()
        then:
        out.statusId == "AI_RUN_COMPLETED"
        out.providerName == "mock"
        out.servedByModelId == "m1"
        cleanup:
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "SvcProvAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
```

> If the file already logs in a shared test user in setup, drop the UserAccount/login lines and match the file's pattern; the key assertions are the three `then:` lines.

- [ ] **Step 2: Run to confirm it fails**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: FAIL — `out.providerName` is null (not an out-param).

- [ ] **Step 3: Add the `providerName` out-parameter**

In `service/ai/AgentServices.xml`, in `run#Agent` `<out-parameters>`, add (next to `servedByModelId`):

```xml
            <parameter name="providerName"/>
```

and in the `<script>` actions, after `servedByModelId = r.servedByModelId`:

```groovy
                providerName = r.servedProviderName
```

> The runner result key is `servedProviderName`; the service exposes it as `providerName` (the provider that served the run). `servedByModelId` already round-trips.

- [ ] **Step 4: Run — passes**

```bash
source runtime/component/moqui-ai/dev.env && ./gradlew --no-daemon :runtime:component:moqui-ai:test
```
Expected: PASS.

- [ ] **Step 5: Mark Decision 7 shipped in the gap report**

In `docs/specs/2026-06-03-enterprise-decisions-gap-report.md`, update Decision 7's row (gap-report table) and Decision-Record item 7 from "defer" to shipped: `AiAgentModel` priority chain with sticky failover on provider-call failures; the run records the served provider/model; failed attempts logged as `llm_call_failed` steps. Update the punchline table and the Tally line accordingly. Keep edits surgical.

- [ ] **Step 6: Commit**

```bash
cd /Users/anilpatel/maarg-sd/moqui/runtime/component/moqui-ai && \
git add service/ai/AgentServices.xml \
        src/test/groovy/RunAgentServiceTests.groovy \
        docs/specs/2026-06-03-enterprise-decisions-gap-report.md && \
git commit -m "feat(ai): expose served providerName on run#Agent; record fallback chain shipped (#20)"
```

---

## Self-Review

**Spec coverage** (vs. issue #20 + Decision 7):
- `AiAgentModel` priority-ordered candidates → Task 1. ✅
- Chain-when-present, single-fields-otherwise (backward-compatible) → Task 1 `loadModelCandidates` + Task 1 Step 4/7. ✅
- Sticky failover on provider-call failure → Task 2 `callWithFailover` + `candIdx` stickiness + sticky test. ✅
- All-exhausted → `AI_RUN_FAILED` → Task 2 (propagates to existing catch) + test. ✅
- Run records served provider/model → Task 2 (`finish` persists `providerName` + `servedByModelId`) + Task 3 (service out-param). ✅
- Failed attempts logged → Task 2 (`llm_call_failed` steps + warn log) + test. ✅
- Trigger is provider-call failures only → `callWithFailover` catches only the `provider.chat()` call; tool/loop logic unchanged. ✅
- Out of scope (retry/backoff, load balancing, fail-on-unhelpful) → not built. ✅

**Placeholder scan:** every code step has complete code; every run step has the exact command + expected outcome. No TBD. ✅

**Type/name consistency:**
- `loadModelCandidates(String, EntityValue)` → returns `List<Map>` of `[providerName, modelName]`; consumed in `run()` (`primary`, `candidates`) and `callWithFailover`. ✅
- `callWithFailover(List<Map>, int, Map, String)` → returns `[resp, idx, providerName, modelName, failedAttempts]`; `candIdx = call.idx`, `result.servedProviderName`/`servedByModelId` set from it; `failedAttempts` iterated for steps. ✅
- `result.servedProviderName` (new) seeded from `primary.providerName` (Task 1), updated in loop (Task 2), persisted as run `providerName` in `finish` (Task 2), surfaced as service `providerName` (Task 3). ✅
- `servedByModelId` seeded from `primary.modelName` (Task 1), updated to served (Task 2) — consistent with the field shipped in #19. ✅
- MockProvider `__error` key — produced by tests (Task 2 Step 1), consumed by `chat()` (Task 2 Step 3). ✅
- `stepType` `"llm_call_failed"` — written in Task 2 Step 4, asserted in Task 2 Step 1 tests. (Existing values `llm_call`/`tool_call` unchanged.) ✅
- `AiAgentModel` PK `(agentName, priority)`; `priority` is `number-integer`; ordered via `orderBy("priority")`. ✅

**Ordering / independent deployability:** Task 1 (entity + resolution, behavior-preserving) → Task 2 (failover, the core) → Task 3 (service + docs). Each ends green and committed. Task 1's primary-only `provider.chat` line is the only code superseded (3 lines) — `loadModelCandidates`, the entity, and the candidate list all persist into Task 2.
