# Composer Assistant — Moqui Realization (Level 2)

> The engineering vision for how the Composer Assistant (see the tech-agnostic overview in
> `docs/product/composer-assistant-overview.md`) is built in Moqui, on top of the registry keystone
> (`docs/specs/2026-06-05-agent-tool-registry-design.md`).

- **Date:** 2026-06-05
- **Status:** Shipped (2026-06-06)
- **Component:** `moqui-ai` (branch `feature/ai-agent-framework`)
- **Platform:** HotWax fork of Moqui, **JDK 11**
- **Builds on:** the Agent & Tool Registry keystone (stable ids, editable names, DB-as-source,
  `store#AiTool` / `store#AiAgent`, the safety gate, on-demand schema)

---

## 1. Principle

**The Composer Assistant is itself an `AiAgent` — one whose granted tools are the registry's
authoring and introspection services.** It is not a new subsystem; it is the framework pointed at
itself. It runs through the same `AgentRunner`, conversation threading, approval gate, and cost
tracking as any other agent. Building it well *is* the proof that the framework is good.

Two agents are in play and must not be confused:
- the **Composer Assistant** — the seeded builder agent the user talks to;
- the **draft agent** — the new agent being composed, persisted as a `draft`-status `AiAgent` that
  fills in over the conversation.

## 2. The four Level-1 steps, in Moqui

| Level-1 step | Moqui realization |
|---|---|
| **Describe it** | A normal conversation with the Composer Assistant — `run#Agent` over an `AiConversation`. |
| **Answer a few questions** | The agent loop asks clarifying questions across turns (conversation continuity we already ship). As intent firms up, it writes a **draft agent**. |
| **Try it live** | **Preview** — run the draft agent against the user's test input in a sandbox where mutating tools are held by the approval gate (nothing irreversible fires). Show the step/tool trace. |
| **Put it to work** | **Commit** — request activation; a human approves (required, §7); on approval the draft flips to `active` and is runnable by the team. |

## 3. The Composer Assistant's tools

Its granted capabilities are ordinary `AiTool` catalog entries backed by Moqui services — the same
shape as any business tool. (So the builder is configured *through the registry it manages* — the
deepest dogfood.)

| Tool (`verb_noun`) | Backing service | Responsibility |
|---|---|---|
| `find_capability` | `find#Capability` | search the catalog (`exposable=Y`, `active`) by intent/keyword/noun — "what can an agent here actually do?" |
| `describe_capability` | `describe#Capability` | a tool's purpose + inputs (for the assistant to reason about fit) |
| `list_domain_terms` | `list#DomainTerm` | the business vocabulary/nouns the assistant can ground in (from the ontology / knowledgebase) |
| `propose_naming` | `propose#Naming` | suggest agent/tool name + description, grounded in the glossary (thin in v1; deepens with the KB spec) |
| `draft_agent` | `store#AiAgent` (status `draft`) | create/update the draft (name, description, system prompt, model) |
| `grant_capability` | grant `AiAgentTool` | add a catalog tool to the draft (only `exposable=Y` tools resolve) |
| `set_guardrail` | `AiAgentTool.requiresApprovalOverride` | mark which of the draft's tools need human approval |
| `preview_agent` | `preview#Agent` | sandbox-run the draft on a test input (§6) |
| `activate_agent` | `activate#Agent` | draft → active (the commit) |
| `request_capability` | `request#Capability` | when no tool exists for the intent, record a gap for the Curator instead of inventing one (§5, §7) |

The Composer Assistant ships as **seed data**: one `AiAgent` (`composer-assistant`) with grants to
exactly these tools and a strong system prompt. Present out of the box.

## 4. Draft lifecycle (Option A — decided)

The in-progress agent is a **real `AiAgent` row marked `draft`** (plus its draft `AiAgentTool`
grants), saved from the moment the assistant starts building and updated as the user refines —
chosen over holding it in conversation state because preview is then free (the draft *is* a runnable
agent), the build is resumable, and the approval step (§7) has a concrete record to review.

- **Status flow:** `AI_AGENT_DRAFT` → *(activation requested → human approval, §7)* →
  `AI_AGENT_ACTIVE` → `AI_AGENT_DISABLED`. (`AI_AGENT_DRAFT` is added to the keystone's `AiAgent`
  status set; `activate#Agent` re-checks the grants are still `exposable` before going live.)
- **Isolation:** drafts are excluded from every active-agent list and never auto-run; only the
  Composer screen and the approval review surface them.
- **Cleanup:** a "discard draft" action, plus optional auto-expiry of stale drafts (TTL — a minor
  planning detail).
- The `AiConversation` links the builder session to the draft it's shaping, so the thread survives
  across turns (reuses conversation continuity).

## 5. Grounding — "knows your business"

For v1 the assistant grounds in what the keystone already exposes:
- the **capability catalog** (`AiTool`) — what agents can do here;
- the **domain ontology** — the nouns/terms it can reason about (`list_domain_terms`).

The richer "learns your vocabulary, gets better over time" behavior is the **Builder Knowledgebase**
spec (separate). This design only wires the seams: `propose_naming` and `list_domain_terms` read from
whatever grounding source exists, so the KB drops in later without reshaping the assistant.

**Gap handling.** The assistant cannot create capabilities (that's the Curator's gated job). When the
intent needs a tool that doesn't exist, it calls `request_capability` to record the gap for the
Curator and tells the user honestly — never fabricates a capability.

## 6. Preview ("try it live")

`preview#Agent` runs the draft through the normal `AgentRunner` against a user-supplied test message,
with one safety override: **every mutating tool is treated as `requiresApproval` for the duration of
the preview.** So the run *suspends* at each would-be write and shows the intended call (e.g. "would
call `cancel_order(123)`") via the existing approval/suspend machinery — the user sees exactly what
the agent would do, with nothing irreversible executed. Read-only tools run normally **on real data**
(decided — preview shows true results). The result + trace render in the Composer screen.

## 7. Safety & authorization

- The Composer Assistant **runs as the signed-in user** (Composer role). Its authoring tools
  (`draft_agent`, `grant_capability`, `activate_agent`) are `ArtifactAuthz`-gated to that role.
- It can only grant tools where `exposable=Y` (the keystone gate) — so **it cannot expose a dangerous
  service even if asked**. The hard denylist floor still applies.
- The approval gate applies twice over: to the **agents it builds** (their mutating tools), and to
  the **commit** itself — `activate#Agent` **requires human approval** (decided): a person reviews
  the draft (and can preview it) before it goes live.
- Everything is a normal run: logged in `AiAgentRun` / `AiToolCall`, costed via `CostCalc`.

## 8. Interaction surface

A **Composer** screen in the AI Ops console, three regions:
- **Chat** with the Composer Assistant (the describe/clarify loop).
- A live **draft panel** that fills in as the conversation progresses — name, description, system
  prompt, selected capabilities, guardrails — editable directly.
- A **preview pane** — test input → result + trace, with the held mutating calls shown.

(Visual detail — layout, the draft-panel interaction — is a design-review topic; mockups can come
when we build it.)

## 9. Reuse vs. build-new

- **Reuse:** `AgentRunner`, conversation/threading, the approval/suspend gate, `CostCalc`, the
  keystone registry + `store#AiTool`/`store#AiAgent`, `ToolSchemaBuilder`, the providers, the Ops
  console shell.
- **Build new:** the meta-tools' services (§3), the `composer-assistant` agent seed + system prompt,
  the `AI_AGENT_DRAFT` status, the preview override (§6), and the Composer screen (§8).

## 10. Error handling

- Authoring stays **fail-loud**: `store#AiTool`/`store#AiAgent` reject unknown services, denied
  exposure, and name collisions (keystone §6).
- Preview failures surface in the trace, never as a silent empty answer (existing provider
  fail-loud).
- Gaps are surfaced, not hidden (§5). Persistence hiccups during a build log as warnings and don't
  abort the session (framework rule).

## 11. Testing

- **Unit:** each meta-tool service (`find#Capability` filters to exposable/active; `grant_capability`
  refuses non-exposable; `activate#Agent` re-checks grants; `request#Capability` records a gap).
- **Integration:** a full compose→preview→activate run — describe intent → assistant drafts an agent
  with the right catalog tools → preview holds a mutating call → activate flips to active → the built
  agent runs.
- **Acceptance (live, NotNaked):** talk to the Composer Assistant, build a small order-summary agent
  from the seeded `list_orders` capability, preview it on real orders, activate it, run it.

> **Shipped 2026-06-06:** the 10 meta-tools (`find#`/`describe#Capability`, `list#DomainTerm`,
> `propose#Naming`, `set#Guardrail`, `request#Capability`, `preview#Agent`, `activate#Agent`,
> plus the `draft_agent`/`grant_capability` aliases pointing at `ai.AgentServices.store#AiAgent` /
> `ai.AgentServices.store#AiAgentTool`); `AiCapabilityRequest` + `AiCapReqStatus`; the `AgentRunner.runPreview`
> force-gate-on-mutating override; the seeded `composer-assistant` agent + meta-tool catalog + grants
> (`data/AiComposerData.xml`, ext-seed); the Composer screen (chat + live draft panel + preview pane,
> `screen/AiOps/Composer.xml`); the unit + compose→preview→gated-activate e2e (MockProvider). Suite green
> on MySQL `hcsd_notnaked` (111 tests, 0 failed, 8 skipped live-provider).
> **Deferred:** KB-grounded `list#DomainTerm`/`propose#Naming` (v1 = catalog nouns + heuristic); the Curator
> assistant + `request_capability`/`AiCapabilityRequest` UI; stale-draft TTL; the Composer-role `ArtifactAuthz`
> (v1 is `authenticate="true"` under the AiOps `ALL_USERS` grant); a direct "Activate" button on the screen
> (activation rides the in-conversation approval gate + Approvals tab); excluding abandoned preview runs from
> the operator Approvals queue.

## 12. Boundaries & resolved decisions

- **Out of scope (own specs):** the Builder Knowledgebase / domain glossary (#4); a Curator assistant
  (v1 is Composer-only); deep multi-tenant catalog scoping.
- **Resolved:**
  1. **Draft state** → a real `draft`-status `AiAgent` row (Option A). Cleanup via a discard action +
     optional TTL (planning detail).
  2. **Commit approval** → `activate#Agent` **requires human approval**.
  3. **Naming in v1** → best-guess (LLM + catalog heuristic) now; KB-grounded later.
  4. **Preview on real data** → read-only tools run on real data; mutating tools held by the gate.
