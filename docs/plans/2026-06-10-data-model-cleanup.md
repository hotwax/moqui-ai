# Data-Model Cleanup (PR-A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the moqui-ai timestamp fields honest and normalized — rename creation stamps to `createdDate`, rename the run interval to `startedDate`/`endedDate`, drop dead/derivable fields — with **zero behavior change**.

**Architecture:** Pure rename/move refactor on the consolidated `entity/AiEntities.xml` plus its writers/readers (services, `AgentRunner`, screens, tests, docs). `AiConversation.lastActivityDate` is dropped and re-derived as `MAX(AiConversationMessage.createdDate)` via a new view-entity. Moqui loads entity definitions regardless of file/order, so this is behavior-neutral and the **existing test suite is the gate**.

**Tech Stack:** Moqui XML entities/services/screens, Groovy, Spock. Run from the project root `/Users/anilpatel/maarg-sd/asbeauty`.

**The decisions this implements (locked in brainstorming):**
- → `createdDate`: `AiConversationMessage.fromDate`, `AiConversation.fromDate`, `AiNamingSignal.fromDate`
- → `startedDate`/`endedDate`: `AiAgentRun.fromDate`/`thruDate` (keep both; **no** duration field — derive on read)
- **DROP**: `AiConversation.lastActivityDate` (derive via view-entity) and `AiAgentRunStep.fromDate`/`thruDate` (dead — never read; `thruDate` never set)
- **KEEP UNCHANGED** (genuine effective-dating — renaming these would be the bug): `AiModelPrice.fromDate`/`thruDate`, `AiKnowledgeTopic.fromDate`/`thruDate`

**Rule of thumb for every edit:** only `AiAgentRun`, `AiAgentRunStep`, `AiConversation`, `AiConversationMessage`, `AiNamingSignal` change. If a `fromDate`/`thruDate` belongs to `AiModelPrice` or `AiKnowledgeTopic`, **leave it**.

---

## Task 0: Branch + baseline

**Files:** none (setup)

- [ ] **Step 1: Sync main and branch**

```bash
cd /Users/anilpatel/maarg-sd/asbeauty/runtime/component/moqui-ai
git checkout main && git pull --ff-only origin main
git checkout -b chore/data-model-cleanup
```

- [ ] **Step 2: Establish the baseline (this is the gate to match at the end)**

Run: `cd /Users/anilpatel/maarg-sd/asbeauty && ./gradlew :runtime:component:moqui-ai:test --no-daemon`
Expected: **136 passed / 7 failed / 8 skipped**. The 7 failures are pre-existing, out-of-scope seed-data tests (`AiComposerTests` ×6, `NotNakedSeedTests` ×1 — they need NotNaked/Composer seed data absent in this environment). **PR-A must end with the same tally — no new failures.**
If the run dies at boot with a `bitronix … IOException`, clear the stale tx log and re-run: `rm -f /Users/anilpatel/maarg-sd/asbeauty/runtime/txlog/btm*.tlog`.

---

## Task 1: Entity definitions (`entity/AiEntities.xml`)

**Files:** Modify `entity/AiEntities.xml`

- [ ] **Step 1: `AiAgentRun` — rename the interval**

In the `AiAgentRun` entity (section 2), replace:
```xml
        <field name="fromDate" type="date-time"/>
        <field name="thruDate" type="date-time"/>
```
with:
```xml
        <field name="startedDate" type="date-time"><description>When the run started.</description></field>
        <field name="endedDate" type="date-time"><description>When the run reached a terminal status; null while RUNNING/SUSPENDED. Duration = endedDate - startedDate (derived, not stored).</description></field>
```
> There are three `fromDate`/`thruDate` pairs in this file. This is the one inside `<entity entity-name="AiAgentRun">`. The other two (`AiModelPrice`, `AiKnowledgeTopic`) must NOT change.

- [ ] **Step 2: `AiAgentRunStep` — drop the dead interval fields**

In the `AiAgentRunStep` entity, delete these two lines entirely:
```xml
        <field name="fromDate" type="date-time"/>
        <field name="thruDate" type="date-time"/>
```

- [ ] **Step 3: `AiConversation` — rename start, drop lastActivityDate**

In `AiConversation`, replace:
```xml
        <field name="fromDate" type="date-time"/>
        <field name="lastActivityDate" type="date-time"/>
```
with:
```xml
        <field name="createdDate" type="date-time"><description>When the conversation was created.</description></field>
```
> `lastActivityDate` is removed — it is re-derived in Task 1 Step 6 (view-entity).

- [ ] **Step 4: `AiConversationMessage` — rename creation stamp**

In `AiConversationMessage`, replace `<field name="fromDate" type="date-time"/>` with:
```xml
        <field name="createdDate" type="date-time"><description>When this message-part was recorded.</description></field>
```

- [ ] **Step 5: `AiNamingSignal` — rename creation stamp**

In `AiNamingSignal`, replace `<field name="fromDate" type="date-time"/>` with:
```xml
        <field name="createdDate" type="date-time"><description>When the signal was captured.</description></field>
```

- [ ] **Step 6: Add the `AiConversationActivity` view-entity (derives lastActivityDate)**

Immediately AFTER the `</entity>` that closes `AiConversationFact` (still inside section 4 "Conversation & context"), add:
```xml
    <!-- Derived conversation list: lastActivityDate = MAX(message createdDate). Replaces the former
         denormalized AiConversation.lastActivityDate column (derive-don't-denormalize). join-optional
         so a conversation with no messages still appears (lastActivityDate = null). -->
    <view-entity entity-name="AiConversationActivity" package="moqui.ai">
        <member-entity entity-alias="CONV" entity-name="moqui.ai.AiConversation"/>
        <member-entity entity-alias="MSG" entity-name="moqui.ai.AiConversationMessage"
                join-from-alias="CONV" join-optional="true">
            <key-map field-name="conversationId"/>
        </member-entity>
        <alias-all entity-alias="CONV"/>
        <alias name="lastActivityDate" entity-alias="MSG" field="createdDate" function="max"/>
    </view-entity>
```

- [ ] **Step 7: Confirm the keepers were NOT touched**

Run: `grep -nA2 'entity-name="AiModelPrice"\|entity-name="AiKnowledgeTopic"' entity/AiEntities.xml | grep -i fromdate`
Expected: still shows `fromDate`/`thruDate` on both (unchanged).
Run: `grep -c 'name="fromDate"\|name="thruDate"' entity/AiEntities.xml`
Expected: **4** (only `AiModelPrice` ×2 + `AiKnowledgeTopic` ×2 remain).

- [ ] **Step 8: Commit**

```bash
git add entity/AiEntities.xml
git commit -m "refactor(entity): rename timestamp fields, drop dead/derivable ones, add AiConversationActivity view"
```

---

## Task 2: `AgentRunner.groovy` writers

**Files:** Modify `src/main/groovy/org/moqui/ai/AgentRunner.groovy`

- [ ] **Step 1: Run create — `fromDate` → `startedDate`**

Find (the `create#AiAgentRun` params, ~line 76):
```groovy
            userId: ec.user.userId, fromDate: ec.user.nowTimestamp, statusId: "AI_RUN_RUNNING",
```
Replace with:
```groovy
            userId: ec.user.userId, startedDate: ec.user.nowTimestamp, statusId: "AI_RUN_RUNNING",
```

- [ ] **Step 2: Knowledge-cap step — drop the `fromDate` it set**

In `loadAgentKnowledge`, the `create#moqui.ai.AiAgentRunStep` call sets a `fromDate`. Delete this single line from that params map:
```groovy
                        fromDate   : ec.user.nowTimestamp,
```
> `AiAgentRunStep` no longer has that field. The other `create#…AiAgentRunStep` calls never set it, so no other edit is needed here.

- [ ] **Step 3: `finish()` — `thruDate` → `endedDate`, drop the lastActivityDate touch, fix the comment**

Replace this block:
```groovy
    /** Finalize: set status + truncated on the result Map, persist the run update, bump the
     *  conversation's lastActivityDate when present, return it. */
```
with:
```groovy
    /** Finalize: set status + truncated on the result Map, persist the run update, return it.
     *  (lastActivityDate is derived via the AiConversationActivity view-entity, not stored.) */
```
Then replace:
```groovy
        persist("update#moqui.ai.AiAgentRun", [agentRunId: runId, thruDate: ec.user.nowTimestamp,
```
with:
```groovy
        persist("update#moqui.ai.AiAgentRun", [agentRunId: runId, endedDate: ec.user.nowTimestamp,
```
Then delete these two lines entirely:
```groovy
        if (conversationId) persist("update#moqui.ai.AiConversation",
            [conversationId: conversationId, lastActivityDate: ec.user.nowTimestamp])
```

- [ ] **Step 4: `persistConversationMessage` — `fromDate` → `createdDate`**

Find (~line 584):
```groovy
                agentRunId: runId, fromDate: ec.user.nowTimestamp])
```
Replace with:
```groovy
                agentRunId: runId, createdDate: ec.user.nowTimestamp])
```

- [ ] **Step 5: Confirm the AiModelPrice price lookup was NOT touched**

Run: `grep -n 'conditionDate("fromDate", "thruDate"' src/main/groovy/org/moqui/ai/AgentRunner.groovy`
Expected: still present (the `estimateCost` effective-dated `AiModelPrice` query — must stay).

- [ ] **Step 6: Commit**

```bash
git add src/main/groovy/org/moqui/ai/AgentRunner.groovy
git commit -m "refactor(runner): write startedDate/endedDate/createdDate; drop lastActivityDate + dead step fields"
```

---

## Task 3: Services

**Files:** Modify `service/ai/AgentServices.xml`, `service/ai/GlossaryServices.xml`, `service/ai/CostServices.xml`

- [ ] **Step 1: `create#Conversation` — `fromDate` → `createdDate`**

In `service/ai/AgentServices.xml`, find:
```xml
                fromDate: ec.user.nowTimestamp, statusId: 'AI_CONV_ACTIVE']"/>
```
Replace with:
```xml
                createdDate: ec.user.nowTimestamp, statusId: 'AI_CONV_ACTIVE']"/>
```

- [ ] **Step 2: `capture#NamingSignal` — `fromDate` → `createdDate`**

In `service/ai/GlossaryServices.xml`, find:
```xml
                wasOverridden: overridden, userId: ec.user.userId, fromDate: ec.user.nowTimestamp]"/>
```
Replace with:
```xml
                wasOverridden: overridden, userId: ec.user.userId, createdDate: ec.user.nowTimestamp]"/>
```

- [ ] **Step 3: `get#AiSpend` — filter `AiAgentRun` on `startedDate` (NOT the param names)**

In `service/ai/CostServices.xml`, find the two `econdition`s that filter the run by date:
```xml
                <econdition field-name="fromDate" operator="greater-equals" from="fromDate" ignore-if-empty="true"/>
                <econdition field-name="fromDate" operator="less" from="thruDate" ignore-if-empty="true"/>
```
Replace with (only `field-name` changes — the `from=` service-param names stay):
```xml
                <econdition field-name="startedDate" operator="greater-equals" from="fromDate" ignore-if-empty="true"/>
                <econdition field-name="startedDate" operator="less" from="thruDate" ignore-if-empty="true"/>
```
> Do **NOT** change `store#AiModelPrice` in this file — its `fromDate` parameter/field is effective-dating and stays.

- [ ] **Step 4: Commit**

```bash
git add service/ai/AgentServices.xml service/ai/GlossaryServices.xml service/ai/CostServices.xml
git commit -m "refactor(service): createdDate on conversation/naming-signal; get#AiSpend filters startedDate"
```

---

## Task 4: Screens

**Files:** Modify `screen/AiOps/Conversations.xml`, `screen/AiOps/RunDetail.xml`, `screen/AiOps/Runs.xml`
**Do NOT touch:** `Knowledge.xml`, `KnowledgeTopic.xml` (AiKnowledgeTopic — keepers), `Cost.xml` (get#AiSpend param names — keepers).

- [ ] **Step 1: `Conversations.xml` — read the derived view; rename the message column**

Replace the list query:
```xml
        <entity-find entity-name="moqui.ai.AiConversation" list="convList">
            <order-by field-name="-lastActivityDate"/>
        </entity-find>
```
with:
```xml
        <entity-find entity-name="moqui.ai.AiConversationActivity" list="convList">
            <order-by field-name="-lastActivityDate"/>
        </entity-find>
```
Replace the detail load (so `conv` carries the derived `lastActivityDate` and all conversation fields):
```xml
            <entity-find-one entity-name="moqui.ai.AiConversation" value-field="conv"/>
```
with:
```xml
            <entity-find-one entity-name="moqui.ai.AiConversationActivity" value-field="conv"/>
```
Rename the message-list timestamp column:
```xml
                            <field name="fromDate"><default-field title="At"><display/></default-field></field>
```
to:
```xml
                            <field name="createdDate"><default-field title="At"><display/></default-field></field>
```
> The `lastActivityDate` list column (line ~31) and detail label (line ~39) need no change — the view-entity supplies `lastActivityDate`.

- [ ] **Step 2: `RunDetail.xml` — started/ended**

Find:
```xml
                <label text="Started: ${run.fromDate} · ended: ${run.thruDate}"/>
```
Replace with:
```xml
                <label text="Started: ${run.startedDate} · ended: ${run.endedDate}"/>
```

- [ ] **Step 3: `Runs.xml` — order + column**

Find `<search-form-inputs default-order-by="-fromDate"/>` → replace with `<search-form-inputs default-order-by="-startedDate"/>`.
Find `<field name="fromDate">` → replace with `<field name="startedDate">`. (If the field has a title/sub-elements, keep them; only the `name` attribute changes.)

- [ ] **Step 4: Commit**

```bash
git add screen/AiOps/Conversations.xml screen/AiOps/RunDetail.xml screen/AiOps/Runs.xml
git commit -m "refactor(screen): derive lastActivity via view-entity; started/ended + createdDate columns"
```

---

## Task 5: Tests

**Files:** Modify `src/test/groovy/AiContextTests.groovy`, `AiGlossaryTests.groovy`, `AiEntitiesTests.groovy`
**Do NOT touch:** `AiKnowledgeTests.groovy` (its `fromDate`/`thruDate` are `AiKnowledgeTopic` effective dates — keepers).

- [ ] **Step 1: `AiContextTests.groovy` — all `fromDate` are conversation/message → `createdDate`**

Every `fromDate` in this file is on `AiConversation` or `AiConversationMessage`. Replace all occurrences of `fromDate: ec.user.nowTimestamp` with `createdDate: ec.user.nowTimestamp`.
Run to confirm none remain: `grep -c "fromDate" src/test/groovy/AiContextTests.groovy` → expected **0**.

- [ ] **Step 2: `AiGlossaryTests.groovy` — `AiNamingSignal.fromDate` → `createdDate`**

Replace all 3 occurrences of `fromDate: ec.user.nowTimestamp` with `createdDate: ec.user.nowTimestamp` (lines ~73, ~228, ~232 — all `AiNamingSignal` creates).
Run: `grep -c "fromDate" src/test/groovy/AiGlossaryTests.groovy` → expected **0**.

- [ ] **Step 3: `AiEntitiesTests.groovy` — surgical (one stays!)**

This file has three `fromDate` sets with different verdicts:
- The `AiModelPrice` create (`inputPricePerMillion`/`outputPricePerMillion` nearby) — **LEAVE `fromDate` unchanged** (effective-dating).
- The `AiConversation` create (`statusId: "AI_CONV_ACTIVE"` nearby) — change `fromDate:` → `createdDate:`.
- The `AiAgentRun` create (`statusId: "AI_RUN_SUSPENDED"`, `pendingState` nearby) — change `fromDate:` → `startedDate:`.

Then re-grep the file for any remaining references to renamed/dropped fields that need updating (e.g. assertions reading `.fromDate`/`.thruDate`/`.lastActivityDate` on these entities):
```bash
grep -n "fromDate\|thruDate\|lastActivityDate" src/test/groovy/AiEntitiesTests.groovy
```
Expected after edits: only the **one** `AiModelPrice` `fromDate` line remains. Update any assertion that reads a renamed field on `AiAgentRun`/`AiConversation`/`AiConversationMessage`/`AiNamingSignal` (e.g. `.fromDate` → `.startedDate`/`.createdDate`). If the test asserts `AiAgentRunStep.fromDate`/`.thruDate`, remove that assertion (field dropped).

- [ ] **Step 4: Commit**

```bash
git add src/test/groovy/AiContextTests.groovy src/test/groovy/AiGlossaryTests.groovy src/test/groovy/AiEntitiesTests.groovy
git commit -m "test: use createdDate/startedDate; AiModelPrice fromDate unchanged"
```

---

## Task 6: Docs

**Files:** Modify `docs/reference/entities.md`, `docs/reference/screens.md`; sweep the rest.

- [ ] **Step 1: `reference/entities.md` field tables**

Update the field rows:
- `AiAgentRun`: `fromDate`→`startedDate` (run start), `thruDate`→`endedDate` (run end; null while RUNNING/SUSPENDED; note "duration = endedDate − startedDate, derived").
- `AiAgentRunStep`: remove the `fromDate`/`thruDate` rows.
- `AiConversation`: `fromDate`→`createdDate`; remove `lastActivityDate` and add a note: "last activity is derived via the `AiConversationActivity` view-entity (`MAX(AiConversationMessage.createdDate)`), not stored — see the data-modeling rule *derive, don't denormalize*."
- `AiConversationMessage`: `fromDate`→`createdDate`.
- `AiNamingSignal`: `fromDate`→`createdDate`.
- Add a short `AiConversationActivity` view-entity entry (members `AiConversation` + optional `AiConversationMessage`; derived alias `lastActivityDate = MAX(createdDate)`).
- Leave `AiModelPrice` and `AiKnowledgeTopic` rows unchanged (effective-dating).

- [ ] **Step 2: `reference/screens.md`**

Note the Conversations screen now reads `AiConversationActivity` (derived last activity); Runs/RunDetail show `startedDate`/`endedDate`.

- [ ] **Step 3: Sweep the other docs for stale field names**

Run:
```bash
grep -rn "lastActivityDate\|\.fromDate\|\.thruDate" docs/ | grep -viE "ModelPrice|KnowledgeTopic|effective"
```
Update any prose that named the old run/conversation/message/signal fields (e.g. `architecture.md`, `capabilities.md`). Leave references describing `AiModelPrice`/`AiKnowledgeTopic` effective dating.

- [ ] **Step 4: Commit**

```bash
git add docs/
git commit -m "docs: reflect createdDate/startedDate/endedDate + derived lastActivity"
```

---

## Task 7: Verify, push, PR

- [ ] **Step 1: Full suite — must match the Task 0 baseline**

Run: `cd /Users/anilpatel/maarg-sd/asbeauty && ./gradlew :runtime:component:moqui-ai:test --no-daemon`
Expected: **136 passed / 7 failed / 8 skipped** — the same 7 pre-existing seed-data failures, **no new failures**. If a renamed field was missed, you'll see an entity error like `The field XYZ.fromDate is not valid` — grep that field name across `service/ src/ screen/ data/ entity/` and fix the straggler. (If boot dies on bitronix, `rm -f runtime/txlog/btm*.tlog` and re-run.)

- [ ] **Step 2: Confirm the view-entity loads + Conversations renders (manual sanity, optional)**

The suite booting green already proves the view-entity parses and loads. (Optional: open `/apps/AiOps` → Conversations and confirm the list sorts by recency.)

- [ ] **Step 3: Push and open the PR**

```bash
cd /Users/anilpatel/maarg-sd/asbeauty/runtime/component/moqui-ai
git push -u origin chore/data-model-cleanup
gh pr create --repo hotwax/moqui-ai --base main --head chore/data-model-cleanup \
  --title "refactor(data-model): honest timestamp names + derive lastActivity" \
  --body "PR-A of the data-model cleanup. Behavior-neutral.

- createdDate: AiConversationMessage / AiConversation / AiNamingSignal (.fromDate)
- startedDate/endedDate: AiAgentRun (.fromDate/.thruDate); duration derived, no stored field
- DROP lastActivityDate (derived via new AiConversationActivity view-entity) + dead AiAgentRunStep.fromDate/thruDate
- KEEP AiModelPrice / AiKnowledgeTopic fromDate/thruDate (genuine effective-dating)

Test plan: :runtime:component:moqui-ai:test → 136 passed / 7 failed / 8 skipped (same 7 pre-existing out-of-scope seed-data failures; no new failures)."
```

---

## Task 8: `AiAgentRunStep` — stepType = kind only, add `success` outcome

**Files:** Modify `entity/AiEntities.xml`, `src/main/groovy/org/moqui/ai/AgentRunner.groovy`, `screen/includes/AiRunTrace.xml`, `src/test/groovy/AgentRunnerTests.groovy`, `docs/reference/entities.md`

**Rationale:** `llm_call_failed` conflated *kind* (an llm call) with *outcome* (it failed); `tool_call` was never written. `stepType` becomes the intrinsic kind; a `success` indicator (mirroring `AiToolCall.success`) records the llm_call outcome. A failed failover attempt = `stepType=llm_call, success=N, finishReason=provider_error:…`.

- [ ] **Step 1: Entity — add `success`, fix `stepType` description**

In `AiAgentRunStep`, change:
```xml
        <field name="stepType" type="text-short"><description>llm_call | tool_call | llm_call_failed | context_trim | compaction</description></field>
```
to:
```xml
        <field name="stepType" type="text-short"><description>The kind of step: llm_call | context_trim | compaction. (Outcome lives in success; a failed llm call is stepType=llm_call, success=N.)</description></field>
```
And add a `success` field after `finishReason`:
```xml
        <field name="success" type="text-indicator"><description>Y/N outcome of an llm_call step (a failed failover attempt = N). Null/Y for non-call steps.</description></field>
```

- [ ] **Step 2: Runner — winning llm_call sets `success: "Y"`**

Find:
```groovy
                    stepType: "llm_call", tokensIn: inTok, tokensOut: outTok, finishReason: resp.finishReason])
```
Replace with:
```groovy
                    stepType: "llm_call", success: "Y", tokensIn: inTok, tokensOut: outTok, finishReason: resp.finishReason])
```

- [ ] **Step 3: Runner — failed attempt → `llm_call` + `success: "N"`**

Find:
```groovy
                        stepType: "llm_call_failed", finishReason: "provider_error:${fa.providerName}:${fa.modelName}" as String])
```
Replace with:
```groovy
                        stepType: "llm_call", success: "N", finishReason: "provider_error:${fa.providerName}:${fa.modelName}" as String])
```

- [ ] **Step 4: `AiRunTrace.xml` — add an "OK" column**

In the `Steps` form-list, after the `finishReason` field, add:
```xml
            <field name="success"><default-field title="OK"><display/></default-field></field>
```

- [ ] **Step 5: Tests — `AgentRunnerTests` failover asserts**

Both occurrences of:
```groovy
            .condition("stepType", "llm_call_failed").list().size() == 1
```
become:
```groovy
            .condition("stepType", "llm_call").condition("success", "N").list().size() == 1
```

- [ ] **Step 6: Docs — `entities.md`**

Update the `AiAgentRunStep.stepType` row to `llm_call | context_trim | compaction`; add a `success` (`Y/N`) row.

- [ ] **Step 7: Commit**

```bash
git add entity/AiEntities.xml src/main/groovy/org/moqui/ai/AgentRunner.groovy screen/includes/AiRunTrace.xml src/test/groovy/AgentRunnerTests.groovy docs/reference/entities.md
git commit -m "refactor(step): stepType=kind only (drop llm_call_failed/tool_call); add success outcome"
```

---

## Self-review checklist (done while writing)

- **Spec coverage:** every brainstorming decision maps to a task — createdDate renames (T1.3-5, T2.4, T3.1-2, T5), startedDate/endedDate (T1.1, T2.1/3, T3.3, T4.2-3, T5.3), drop lastActivityDate + view-entity (T1.3/6, T2.3, T4.1), drop step fields (T1.2, T2.2), keepers verified untouched (T1.7, T2.5, and explicit "do not touch" notes in T3/T4/T5). ✓
- **Keepers protected:** `AiModelPrice` + `AiKnowledgeTopic` `fromDate/thruDate` have explicit "leave unchanged" guards in Tasks 1, 3, 4, 5, plus the `grep -c …=4` check (T1.7). ✓
- **No placeholders:** every code step shows exact old→new text. ✓
- **Name consistency:** `startedDate`/`endedDate`/`createdDate`/`AiConversationActivity`/`lastActivityDate` used identically across entity, runner, service, screen, test, doc tasks. ✓
