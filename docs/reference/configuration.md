# Configuration, Secrets & Deploy Hygiene

> Reference for the **moqui-ai** component. The **code is canonical** — every value
> below is verified against `MoquiConf.xml`, `build.gradle`, `.gitignore`, `data/*.xml`,
> and `src/main/groovy/org/moqui/ai/AiToolFactory.groovy`. No secret values appear in
> this document — only property and variable **names**.

This page covers four things:

1. The `MoquiConf.xml` default properties (provider config, defaults for new agents) and the
   component's two registrations (the tool factory, the AI Ops screen mount).
2. How secrets/keys are resolved and how the component `dev.env` is sourced before launch.
3. Deploy hygiene — the `lib/*.jar` classpath, the `refreshLibJar` build step, and the
   stale-jar pitfall.
4. Data files and reader types — which `data/*.xml` load when, the glossary cron `ServiceJob`,
   and the load-once model.

---

## 1. MoquiConf default properties

The component ships its own `MoquiConf.xml` (component root). It declares default properties,
registers the AI tool factory, and mounts the AI Ops console — all from the component's own
config, so no webroot or framework file is touched.

### Provider / runtime properties

| Property | Default value | `is-secret` | Purpose |
|---|---|---|---|
| `ai_openai_key` | *(empty)* | `true` | OpenAI API key. Empty by default — the OpenAI provider registers **only when this is set** (see §2). |
| `ai_openai_base_url` | `https://api.openai.com/v1` | — | OpenAI API base URL (override for a proxy/compatible endpoint). |
| `ai_anthropic_key` | *(empty)* | `true` | Anthropic API key. Empty by default — the Anthropic provider registers **only when this is set**. |
| `ai_anthropic_base_url` | `https://api.anthropic.com` | — | Anthropic API base URL. |
| `ai_anthropic_version` | `2023-06-01` | — | Anthropic `anthropic-version` header value. |
| `ai_timeout_seconds` | `60` | — | HTTP timeout (seconds) applied to both real providers. |
| `ai_default_provider` | `openai` | — | Provider stamped on a **newly drafted** agent when none is supplied. |
| `ai_default_model` | `gpt-4o-mini` | — | Model stamped on a **newly drafted** agent when none is supplied. |

**`ai_default_provider` / `ai_default_model`** are the defaults the Composer's `draft_agent`
path (`ai.AgentServices.store#AiAgent` on create) applies: the user describes *what* an agent
should do and the system picks the model. They are deployment-tunable — override per deployment.
(`store#AiAgent` falls back to `openai` / `gpt-4o-mini` in code if these are unset, so a runnable
agent is always created.)

The two key properties carry `is-secret="true"`. Their default `value` is the empty string,
which is what makes provider registration conditional: a provider is only wired up when its key
resolves to a non-empty value.

### Tool-factory registration

```xml
<tools>
    <tool-factory class="org.moqui.ai.AiToolFactory" init-priority="30" disabled="false"/>
</tools>
```

This registers `AiToolFactory` as `ec.factory.getTool("AI", …)`. At `init` it registers
providers and prepares the tool catalog (the catalog is lazy-loaded from `AiTool` rows on first
access, because no `ExecutionContext` exists yet at `ToolFactory.init`).

### AI Ops screen mount

```xml
<screen-facade>
    <screen location="component://webroot/screen/webroot/apps.xml">
        <subscreens-item name="AiOps" menu-title="AI Ops" menu-index="97"
                         location="component://moqui-ai/screen/AiOps.xml"/>
    </screen>
</screen-facade>
```

The AI Ops console mounts as a top-level app under the standard `apps` webapp at **`/apps/AiOps`**
(menu title "AI Ops", index `97`). This is the same `subscreens-item`-on-`apps.xml` mechanism the
`tools`/`oms`/`poorti` components use, declared from this component's own `MoquiConf` so no webroot
file is modified.

---

## 2. Secrets & key resolution

### Resolution order

Keys and provider config are read by `AiToolFactory.prop()`:

```groovy
private static String prop(String name) {
    String v = System.getProperty(name)
    if (v == null || v.isEmpty()) v = System.getenv(name)
    return (v == null || v.isEmpty()) ? null : v
}
```

Effective precedence: **system property → environment variable → `MoquiConf` default value.**

`AiToolFactory` reads only the first two because it runs at `ToolFactory.init`, before any
`ExecutionContext` exists (so `ec.resource.expand` is unavailable there). The framework reconciles
this with the `MoquiConf` defaults at startup: for each `default-property`, Moqui keeps an existing
**system property** if present, otherwise copies a matching **environment variable** into a system
property, otherwise applies the declared `value`. Net effect for `AiToolFactory`: a key set in the
environment is visible as a system property by the time tools run, and a key never set anywhere
resolves to `null`.

A `null` key means the corresponding provider is **skipped** (not registered). `MockProvider` is
always registered — it needs no configuration — so the framework boots and tests run with no keys
present. Provider construction is also wrapped in try/catch so a bad key never breaks boot.

### Where keys live: the component `dev.env`

The repository's API keys are **not** committed. A component-level `dev.env` file exists at the
component root and holds the keys as shell `export` statements — for example
`export ai_openai_key=…` and `export ai_anthropic_key=…` (values are intentionally not reproduced
here). Treat it as a secrets file.

Nothing auto-loads `dev.env`. It must be **sourced into the shell before launching the server**, so
the keys become environment variables that `prop()` (via the framework) can read:

```bash
source runtime/component/moqui-ai/dev.env
./gradlew --no-daemon run
```

Two operational notes:

- **`dev.env` is git-ignored** (see `.gitignore`) so secrets never get committed.
- Use **`--no-daemon`**. A long-lived Gradle daemon captures the environment of whatever shell
  first started it; if you source `dev.env` in a new shell but a stale daemon is reused, the daemon
  can run with the *old* (key-less) environment and silently skip provider registration.

---

## 3. Deploy hygiene — the `lib/` jar

The Moqui runtime loads this component's compiled classes from its **`lib/*.jar`**, but the Gradle
`jar` task only writes to `build/libs/`. To keep the two in step, `build.gradle` mirrors the freshly
built jar into `lib/` on every build:

```groovy
task refreshLibJar(type: Copy) {
    from jar
    into "${projectDir}/lib"
}
jar.finalizedBy refreshLibJar
```

Because `jar.finalizedBy refreshLibJar`, **every** build that produces a jar also refreshes
`lib/moqui-ai.jar`. That artifact is **git-ignored** (`.gitignore` lists `lib/moqui-ai.jar`) — it is
a build product regenerated locally, not source.

**Pitfall (documented in `build.gradle`):** if `lib/` holds a *stale* `moqui-ai.jar`, the runtime
loads the old classes and shadows the current ones. This actually happened — a stale `lib/moqui-ai.jar`
once shadowed the current classes and produced an **`Unknown agent`** failure. `refreshLibJar` exists
precisely to prevent that: never let the dev server run a stale component jar. If you ever see
class-level behavior that doesn't match the source, rebuild so `refreshLibJar` overwrites `lib/`.

---

## 4. Data files & reader types

All seed/reference data lives under `data/`. The `type` on each file's `<entity-facade-xml>` root
selects which load command includes it (see the load-once model below). The actual types in the
shipped component are:

| File | `type` | Contents |
|---|---|---|
| `AiStatusData.xml` | `ext-seed` | Core status types/items + flows: `AiAgentRunStatus`, `AiAgentStatus`, `AiToolCallRequestStatus`, `AiToolStatus`, `AiCapReqStatus`; the `AiToolEffect` enumeration; and the non-overridable **`AiToolDenylist`** safety-floor rows. |
| `AiConversationStatusData.xml` | `ext-seed` | `AiConversationStatus` status type/items + flow (`AI_CONV_ACTIVE` ⇄ `AI_CONV_CLOSED`). |
| `AiGlossaryData.xml` | `ext-seed` | Glossary reference data: `AiDomainTermStatus` flow + the fixed `AiTermKind` / `AiTermSource` / `AiSignalType` enumerations. (Status/enum reference only — no runnable job, so the glossary test suite can load it in isolation.) |
| `AiComposerData.xml` | `ext-seed` | The out-of-the-box **Composer Assistant** agent (`AICMP_AGENT`) and its meta-tool catalog rows (`find_capability`, `draft_agent`, `grant_capability`, `activate_agent`, …) + grants. |
| `AiTestToolData.xml` | `ext-seed` | Test-only tools (`get_echo`, `get_gated_echo`); loaded explicitly by tests. |
| `AiSecurityData.xml` | `install` | The AI Ops authorization grants — `AI_OPS_SCREENS` (+ members) and the separate VIEW-only `AI_OPS_DATA_READ`. |
| `AiGlossaryJobData.xml` | `ext` | The glossary auto-seed cron `ServiceJob` (see below). |

> **Note on type vs. category:** Task 4 / the drift report grouped the status data loosely under
> "status data." In the code the status/enum files (`AiStatusData.xml`,
> `AiConversationStatusData.xml`, `AiGlossaryData.xml`) are themselves **`ext-seed`** — there is no
> separate "status" reader type. Likewise the glossary job file is **`ext`**, not `install`. The
> reader types above are read directly from each file's root element.

### The glossary auto-seed `ServiceJob`

`AiGlossaryJobData.xml` ships a single cron job:

```xml
<moqui.service.job.ServiceJob jobName="ai_seed_DomainGlossary"
        description="Seed/refresh the AI domain glossary (idempotent)"
        serviceName="ai.GlossaryServices.seed#DomainGlossary"
        cronExpression="0 0 * * *" paused="N"/>
```

- It runs `ai.GlossaryServices.seed#DomainGlossary` **daily** (`0 0 * * *`).
- It ships **`paused="N"`** so it runs without operator action — unlike the external-integration poll
  jobs (which ship `paused="Y"` because they need credentials first). This is internal reference data
  that needs no setup. The seed derives nouns from the deployed entity model + curated UDM concepts
  and verbs from known service names, so it reflects *this* deployment and cannot ship as static rows.
- It is **idempotent** (existing terms are skipped): the run seeds a fresh install on first fire, then
  no-ops, and keeps the glossary in step as the model evolves. A fresh install therefore isn't left
  with an empty glossary (which would silently degrade `list#DomainTerm` / `propose#Naming` to bare
  catalog nouns). Operators can also trigger an immediate re-seed from **AI Ops → Glossary**.
- It lives in its own file (not `AiGlossaryData.xml`) so the glossary test suite — which loads only
  the status/enum reference data — never picks up a runnable job.

### The load-once model — changing already-deployed environments

Moqui loads `data/*.xml` according to the requested reader types, and **all `data/` readers load
once, at initial setup** — none re-run on upgrade. Two phases:

| Phase | What loads | Cadence |
|---|---|---|
| **Initial setup** | every `data/*.xml` whose `type` is in the requested set | once |
| **Upgrade** | `upgrade/<release#>/UpgradeData.xml` only | once per release |

What gets loaded at setup depends on the command:

| Command (from the project's moqui-framework root) | Reader set | Brings in moqui-ai's… |
|---|---|---|
| `./gradlew load` | `types=all` (empty filter → everything) | all of the above (`ext-seed`, `ext`, `install`) |
| `./gradlew loadProduction` | `seed,seed-initial,install` | only the `install` file (`AiSecurityData.xml`) — **omits** the `ext-seed`/`ext` files |
| `./gradlew load -Ptypes=ext-seed` | `ext-seed` | only the `ext-seed` files (Composer agent, status/enum, glossary reference) |

A fresh empty DB auto-loads only `seed,seed-initial,install`; the full `load` (`types=all`) adds the
`ext-*` readers. So the **net of initial setup is: every reader type loads once.** Note this means a
production base install via `loadProduction` gets the security grants but **not** the seeded Composer
agent or the glossary/status reference data — those arrive with a full `load` (`types=all`).

**Consequence for deployed environments:** editing a `data/*.xml` file does **not** reach an
environment that has already been set up — that file already loaded once and is never re-applied. To
change data in a deployed environment, add a versioned **`upgrade/<next-release#>/UpgradeData.xml`**
step; that is the only path that runs on upgrade, executed by the `maarg-util` ComponentUpgrade
service (not by the `load` tasks above). A record that must reach both fresh and existing installs
typically lives in both `data/` (fresh installs) and the release's `UpgradeData.xml` (existing
installs).

> This component currently ships **no `upgrade/` directory** — all of its data is initial-setup
> `data/`. When a change must reach already-deployed installs, introduce
> `upgrade/<release#>/UpgradeData.xml` at that point. Full mechanism, the upgrade service, and reader
> categories: `/Users/anilpatel/maarg-sd/docs/data-load-types.md`.
