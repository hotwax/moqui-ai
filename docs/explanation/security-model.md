# Security Model

How the moqui-ai component controls **who can reach the AI Ops console** and **what an
agent is allowed to do** once it runs.

This is an *explanation* doc — it describes the as-built v1 posture and the reasoning
behind it. The canonical source of truth is the code: `data/AiSecurityData.xml`, the
`require-authentication` attributes on the screens under `screen/AiOps/`, and the gate
logic in `src/main/groovy/org/moqui/ai/AgentRunner.groovy`,
`service/ai/ComposerServices.xml`, and `service/ai/ToolCallRequestServices.xml`.

There are three layers, and they are independent:

1. **Console access** — who can open the AI Ops screens (authentication).
2. **Artifact authorization** — which entities and services those screens (and the agent
   loop) are permitted to touch (group membership).
3. **Action gates** — which agent actions require a human decision before they execute
   (approval / preview / activation).

---

## 1. v1 posture: authentication only

Every AI Ops screen is mounted with `require-authentication="true"`. Any logged-in user
can open the console; there is **no dedicated operator role yet**.

The mount and all nine subscreens (`screen/AiOps.xml` + `screen/AiOps/*.xml`) carry
`require-authentication="true"`. Most files (8 of the 10) also carry a committed comment
recording the deliberate v1 choice — `Agents.xml` and `Playground.xml` are the exceptions:

> v1 security: require-authentication only (any logged-in user). The AI_OPERATOR group +
> ArtifactAuthz gating is deferred to a follow-up so the demo isn't gated.

| Screen file | `require-authentication` |
|---|---|
| `screen/AiOps.xml` (the mount) | `true` |
| `screen/AiOps/Playground.xml` | `true` |
| `screen/AiOps/Composer.xml` | `true` |
| `screen/AiOps/Agents.xml` | `true` |
| `screen/AiOps/Runs.xml` | `true` |
| `screen/AiOps/RunDetail.xml` | `true` |
| `screen/AiOps/Approvals.xml` | `true` |
| `screen/AiOps/Cost.xml` | `true` |
| `screen/AiOps/Conversations.xml` | `true` |
| `screen/AiOps/Glossary.xml` | `true` |

> **Note on the run-trace include.** `screen/includes/AiRunTrace.xml` is
> `require-authentication="false"`. It is **not** an independently mountable screen — it
> is a shared include (steps + tool calls for one run) rendered *inside* the already
> authenticated `Runs` / `RunDetail` / `Playground` screens, so it inherits the parent's
> auth context. It is not a public surface.

**What is deferred:** a dedicated `AI_OPERATOR` user group with per-screen / per-service
`ArtifactAuthz` gating, and per-approver permissions on the approval queue (the
`Approvals` and `get#PendingToolCallRequest` comments flag this as Phase 5 work). Until then, any
authenticated user can read the pending approval queue including tool arguments. This is
intentional for the demo; do not lock it down piecemeal.

---

## 2. The membership mechanism (load-bearing)

This is the part that is easy to get wrong, and the part that was previously undocumented.

### Why there is an explicit allow-list at all

In **this framework version**, `moqui.security.ArtifactAuthz` has **no `inheritAuthz`
column**. That means an authorization grant does **not** automatically cascade to nested
artifacts. Granting access to a screen does *not* implicitly grant access to the entities
that screen reads or the services it calls.

The committed comment in `data/AiSecurityData.xml` states this directly:

> in this framework version ArtifactAuthz has no inheritAuthz column, so authz does NOT
> auto-cascade to nested artifacts — each entity/service the screens touch must be listed
> as a group member above.

The consequence: **every entity and every service the screens (or the agent loop) touch
must be enumerated as an explicit `ArtifactGroupMember`.** Miss one, and a non-admin
operator hits a "not authorized" error on that artifact even though the screen itself
opened fine.

### `AI_OPS_SCREENS` — the console grant

`AiSecurityData.xml` defines an `ArtifactGroup` `AI_OPS_SCREENS` and grants it to the
built-in `ALL_USERS` group with `AUTHZT_ALLOW` / `AUTHZA_ALL` (all actions). It mirrors
the framework's built-in `ALL_USERS → ALL_SCREENS` grant, scoped to moqui-ai.

Because nothing cascades, the group lists **every artifact the console and the agent loop
reach**:

| Member (`artifactName` pattern) | Artifact type | Why it is listed |
|---|---|---|
| `component://moqui-ai/screen/.*` | `AT_XML_SCREEN` | the AI Ops screens themselves |
| `component://moqui-ai/screen/.*` | `AT_XML_SCREEN_TRANS` | the screen transitions (form POSTs / actions) |
| `moqui\.ai\..*` | `AT_ENTITY` | all moqui-ai entity reads/writes by the screens **and the agent loop** (`AiAgent`, `AiAgentRun`, `AiToolCallRequest`, …) |
| `moqui\.basic\.Status.*` | `AT_ENTITY` | status-lifecycle reads: an entity-auto `update#` with a `statusId` change validates the transition against `StatusFlowTransition` / `StatusItem` (`EntityAutoServiceRunner.checkStatus`); without read access the transition check is denied |
| `moqui\.screen\.form\..*` | `AT_ENTITY` | form-list rendering reads per-user column config (`FormConfigUser`, `FormConfig`, …); **without this, every form-list on the AI Ops screens — including the Composer conversation — fails to render** for a non-admin operator |
| `ai\..*` | `AT_SERVICE` | the AI services invoked from the screens (`run#Agent`, the Composer/Approval/Cost/Glossary services) |
| `notnaked\.OmsAiServices\..*` | `AT_SERVICE` | the NotNaked OMS tool + runner the Playground and seeded agents call |

The two `moqui.basic.Status.*` and `moqui.screen.form.*` members are the non-obvious
ones — they are required not because a screen reads them directly, but because the
framework's own status-validation and form-rendering machinery does, and that machinery
runs under the operator's authorization.

### `AI_OPS_DATA_READ` — a *separate*, VIEW-only grant for business data

The agent's tools read real business data — for the demo, the NotNaked order tool
(`notnaked.OmsAiServices.get#OrderSummaryList`) reads `org.apache.ofbiz.order.*`.

Crucially, this access is **not** folded into `AI_OPS_SCREENS`. It is a separate group,
`AI_OPS_DATA_READ`, granted **VIEW only** (`AUTHZA_VIEW`, not `AUTHZA_ALL`):

| Group | Member | Authz action |
|---|---|---|
| `AI_OPS_DATA_READ` | `org\.apache\.ofbiz\.order\..*` (`AT_ENTITY`) | `AUTHZA_VIEW` |

It is kept separate on purpose: the console grant is all-actions, but read access to
order data must **not** inherit that all-actions grant. An operator (and therefore an
agent running as that operator — see §3) can *read* orders, never mutate them through
this grant.

---

## 3. An agent runs as the signed-in user

The most important behavioral property of the security model:

**An agent executes with the data access of the operator who ran it. It cannot read what
that user cannot read.**

This falls directly out of how the loop dispatches tools. In `AgentRunner.run(...)` the
run is stamped with `userId: ec.user.userId`, and every tool call is dispatched through
the normal Moqui service layer:

```groovy
// AgentRunner.dispatchTool(...)
Map out = ec.service.sync().name(td.serviceName as String)
        .parameters((tc.arguments ?: [:]) as Map).call()
```

The dispatch comment is explicit: *"Dispatch one tool-call Map via ec.service.sync (its
own tx; Moqui authz applies)."* There is no privilege elevation, no service-as-system
wrapper. The LLM only chooses *which* registered tool to call and with *what* arguments;
the call itself runs under the operator's identity and is subject to ordinary Moqui
authorization.

This is why the `AI_OPS_DATA_READ` grant in §2 exists, and it was demonstrated live: the
NotNaked order tool was **denied** until the order-read grant was added, because the
signed-in user had no view access to `org.apache.ofbiz.order.*`. Once the operator could
see orders, so could the agent.

**This is a feature, not a bug.** It means an AI agent is bounded by exactly the same data
permissions as the human who launched it — it can never become a back-door to data the
operator isn't entitled to. Tightening the operator's permissions automatically tightens
the agent's.

---

## 4. Action gates: requiring a human decision

Authentication and authorization control *reach*. A separate set of gates controls
whether a permitted *mutating* action runs immediately or pauses for a human. All three
gates live in the agent loop and the Composer services.

### 4.1 Approval gate (`requiresApproval` / `requiresApprovalOverride`)

A tool can be marked `requiresApproval` on its catalog row (`AiTool`). When an agent's
turn proposes any such tool call, the **whole turn suspends**: the loop writes a
`PENDING` `AiToolCallRequest` row per gated call and sets the run to `AI_RUN_SUSPENDED`
(`AgentRunner` approval-gate block). Nothing executes until a human decides.

Per-agent strictness is set via the Composer's `set#Guardrail`, which writes
`AiAgentTool.requiresApprovalOverride`. The rule (enforced by intent in the service
comment) is **stricter, never looser**: an agent grant can escalate a tool to require
approval, but cannot un-gate a tool the catalog requires approval for. A read-only tool
stays runnable; only mutating tools get gated.

Decisions flow through `ToolCallRequestServices`: `approve#ToolCallRequest` / `reject#ToolCallRequest` both
delegate to `decide#ToolCallRequest`, which records the decision and, once **no** `PENDING`
approvals remain for the run, calls `AgentRunner.resume(...)`. All three services are
`transaction="ignore"` — resume drives LLM calls that must hold no enclosing transaction.

`resume()` is **fail-closed**: before executing the suspended turn it re-checks every
gated call and, if **any** is still undecided (PENDING or its approval row is missing), it
leaves the run suspended and returns — a premature or double-fired resume is a safe no-op,
never a consume-and-execute. A `requiresApproval` tool can therefore never run without an
explicit decision.

### 4.2 Preview force-gate (every mutating tool held)

`preview#Agent` (Composer) lets an operator sandbox-run a draft against a test message on
**real data** without risking an irreversible write. It calls `AgentRunner.runPreview`,
which sets `forceApprovalOnMutating = true`. With that flag, the approval gate treats
**every** `AI_TOOL_MUTATING` tool as if it required approval — regardless of the tool's
own `requiresApproval` setting:

```groovy
// AgentRunner approval-gate predicate
if (td.requiresApproval) return true
// preview: force-gate any MUTATING tool so a draft never executes a write on real data
return forceApprovalOnMutating && (td.effectEnumId == "AI_TOOL_MUTATING")
```

So in preview, read-only tools run for real and return live results, while every mutating
call is suspended and surfaced to the screen as a "would call X(...)" held call. The run
is marked `isPreview=Y`. These preview approvals are **not** real decisions: they are
excluded from the operator queue (`get#PendingToolCallRequest` filters out `isPreview=Y` runs)
and are deleted outright when the draft is dropped (`discard#Draft`).

### 4.3 Activation requires human approval

Promoting a draft agent to active (`activate#Agent`) is itself gated. Human approval is
enforced **upstream**: `activate_agent` is registered as a `requiresApproval` `AiTool`, so
when the Composer Assistant proposes activating an agent, its own run suspends via the
§4.1 gate. The `activate#Agent` service runs only after a human approves that proposal (it
is also directly callable by an operator via the screen's Activate button). On execution
it re-validates that every granted tool is still exposable + active and fails loudly
otherwise.

---

## 5. Known v1 gaps

These are deliberate demo-stage shortcuts, recorded in the committed comments. Production
hardening should address each:

| Gap | Current state | Production target |
|---|---|---|
| Console access | `require-authentication` only — **any** logged-in user | dedicated `AI_OPERATOR` group + `ArtifactAuthz` gating |
| **Order-data read grant** | `AI_OPS_DATA_READ` is granted to **`ALL_USERS`** for the demo | scope to an operator group that legitimately sees order data |
| Approval-queue access | any authenticated user can read the pending queue (incl. tool arguments) | per-approver permissions (Phase 5) |

The order-data gap is the one to watch: because an agent runs as the signed-in user
(§3), an `ALL_USERS` VIEW grant on `org.apache.ofbiz.order.*` means **every**
authenticated user — and any agent they run — can read all order data. Scoping
`AI_OPS_DATA_READ` to a real operator group is the single highest-value production change.
