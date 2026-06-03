# Phase 1 (Slice 1): Working Agentic Loop — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the `moqui-ai` component so an agent defined in DB + a tool defined in an `ai/*.tools.xml` file can be invoked via `ai.AgentServices.run#Agent`, run a provider-agnostic agentic loop (tool calls dispatched through `ec.service.sync()`), persist full observability, and return the answer — proven end-to-end with a deterministic `MockProvider`, plus a real Anthropic adapter.

**Architecture:** Pure component, zero framework-core changes. Registered via Moqui's `ToolFactory` SPI (`AiToolFactory`, name `"AI"`, mirrors `moqui-kie`). File-defined tool catalog held in an immutable in-memory map on the singleton (rebuilt on reload); DB-backed agents read via the entity cache per run. The loop holds **no enclosing transaction** — LLM HTTP calls run outside any tx, each tool call and each observability write runs in its own short tx. A per-run ceiling (tokens / cost / tool-calls-per-turn) guards runaway spend.

**Tech Stack:** Groovy 3, Moqui (hotwax/main, JDK 11), Moqui `RestClient` for transport (no provider SDKs), Spock 2.1 for tests, `groovy.json` for (de)serialization.

**Source of truth:** the design spec at `docs/specs/2026-06-02-ai-agent-framework-design.md` (§19 records the eng-review decisions this plan implements).

**Out of this slice (later plans):** OpenAI + Google adapters; structured output; attached knowledge; developer console screens; cost-aggregation service; human-approval gate; RAG. This slice deliberately ships only the working loop + observability + Mock + Anthropic.

---

## Framework API notes

**Verified against hotwax/main source** (use as-is):
- `ToolFactory<V>` SPI: `getName()`, `init(ExecutionContextFactory)`, `getInstance(Object...)`, `destroy()` — `framework/.../context/ToolFactory.java`.
- `ServiceFacadeImpl.getServiceDefinition(name)` → `ServiceDefinition`; `sd.getInParameterNames()` (ArrayList<String>), `sd.getInParameter(name)` (MNode) — `framework/.../service/ServiceDefinition.java`, `ParameterInfo.java`.
- `RestClient`: `.uri().method('POST').contentType().addHeader().timeout().text().call()`; response `.getStatusCode()`, `.text()`, `.jsonObject()`; non-2xx throws — `framework/.../util/RestClient.java`.
- Spock bootstrap: `ec = Moqui.getExecutionContext()` in `setupSpec`, `ec.destroy()` in `cleanupSpec` — `framework/src/test/groovy/*Tests.groovy`.

**Confirm on first compile** (standard Moqui APIs used by this plan; if a signature differs, adjust the one call site — the design does not change):
- `ecf.getComponentBaseLocations()` → `Map<String,String>` of componentName → base location (used in `DefinitionLoader.loadCatalog`). If absent, iterate `ecf.resource` component locations the equivalent way.
- `MNode.parse(ResourceReference)` and parsing a `<tools>` snippet from text (used in `DefinitionLoader` / `loadToolsFromText`). Use whichever `MNode` factory the framework exposes for text (`MNode.parse(location, text)` or equivalent).
- `ec.message.hasError()` / `getErrorsString()` / `clearErrors()` (MessageFacade) and `ec.entity.sequencedIdPrimary(seqName, null, null)` (EntityFacade).

These are the only uncertain points; everything else is verified. Resolve them once in Task 1/6 against the framework and reuse.

---

## File Structure

```
runtime/component/moqui-ai/
├── MoquiConf.xml                                   ← registers AiToolFactory (Task 1)
├── component.xml                                   ← component metadata (Task 1)
├── build.gradle                                    ← test source set + deps (Task 1)
├── entity/AiEntities.xml                           ← 6 entities (Task 2)
├── data/AiStatusData.xml                           ← StatusItem/Flow/Transition install data (Task 2)
├── service/ai/AgentServices.xml                    ← run#Agent (Task 8)
├── service/moqui/ai/test/TestServices.xml          ← echo tool for tests (Task 7)
├── ai/test.tools.xml                               ← test tool catalog file (Task 6)
├── src/main/groovy/org/moqui/ai/
│   ├── AiToolFactory.groovy                        ← SPI singleton + registry (Tasks 1,6,7)
│   ├── LlmProvider.groovy                          ← interface, Map-based (Task 3)
│   ├── ToolSchemaBuilder.groovy                    ← service in-params → JSON schema Map (Task 5)
│   ├── DefinitionLoader.groovy                     ← scan ai/ dirs, validate, load (Task 6)
│   ├── AgentRunner.groovy                          ← the agentic loop (Task 7)
│   └── provider/
│       ├── MockProvider.groovy                     ← scripted, for tests (Task 4)
│       ├── AbstractLlmProvider.groovy              ← RestClient transport base (Task 9)
│       └── AnthropicProvider.groovy                ← Anthropic adapter (Task 9)
└── src/test/groovy/
    ├── MoquiSuite.groovy                            ← @Suite @SelectClasses runner (Task 1; append each Tests class)
    ├── AiToolFactoryBootTests.groovy                ← Task 1
    ├── AiEntitiesTests.groovy                       ← Task 2
    ├── MockProviderTests.groovy                     ← Task 4
    ├── ToolSchemaBuilderTests.groovy               ← Task 5
    ├── DefinitionLoaderTests.groovy                 ← Task 6
    ├── AgentRunnerTests.groovy                      ← Task 7
    ├── RunAgentServiceTests.groovy                  ← Task 8
    └── AnthropicProviderTests.groovy               ← Task 9
```

Tests follow the framework convention exactly (`framework/src/test/groovy/`): classes named
`*Tests` extending `spock.lang.Specification`, aggregated by a single `MoquiSuite`
(`@Suite @SelectClasses([...])`, JUnit Platform) that owns factory teardown. The Gradle
`test` task runs only `**/*MoquiSuite.class` — so there is no per-class run flag; you run the
whole suite and **append each new `*Tests` class to `MoquiSuite`'s `@SelectClasses` as you add
it**. Per spec each test gets its EC via `Moqui.getExecutionContext()` in `setupSpec` and
`ec.destroy()` in `cleanupSpec`; the suite destroys the factory once at the end.

---

## Task 1: Component scaffold + ToolFactory boot

**Files:**
- Create: `runtime/component/moqui-ai/component.xml`
- Create: `runtime/component/moqui-ai/MoquiConf.xml`
- Create: `runtime/component/moqui-ai/build.gradle`
- Create: `runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/AiToolFactory.groovy`
- Test: `runtime/component/moqui-ai/src/test/groovy/AiToolFactoryBootTests.groovy`

- [ ] **Step 1: Create the component descriptor**

`runtime/component/moqui-ai/component.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<component name="moqui-ai" version="0.1.0"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/component-3.xsd"/>
```

- [ ] **Step 2: Register the ToolFactory in component MoquiConf**

`runtime/component/moqui-ai/MoquiConf.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<moqui-conf xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/moqui-conf-3.xsd">
    <tools>
        <tool-factory class="org.moqui.ai.AiToolFactory" init-priority="30" disabled="false"/>
    </tools>
</moqui-conf>
```

- [ ] **Step 3: Create the component build.gradle (mirrors framework/build.gradle's test block)**

Having a `build.gradle` is what makes settings.gradle auto-include this component as the
`:runtime:component:moqui-ai` Gradle subproject. The `test {}` block is copied from
`framework/build.gradle` (same `include '**/*MoquiSuite.class'`, same `moqui.*` system
properties) so tests run exactly like the framework's.

`runtime/component/moqui-ai/build.gradle`:
```groovy
apply plugin: 'groovy'

sourceCompatibility = '11'
targetCompatibility = '11'

// projectDir = runtime/component/moqui-ai ; runtime dir is two levels up
def runtimeDir = file("${projectDir}/../..")

repositories {
    // framework declares some deps (e.g. btm) from framework/lib via flatDir; dependents must too
    flatDir name: 'frameworkLib', dirs: file("${projectDir}/../../../framework/lib").absolutePath
    mavenCentral()
}

dependencies {
    implementation project(':framework')
    // JUnit Platform: launcher + suite (@Suite/@SelectClasses) + jupiter (@AfterAll) — mirrors framework
    testImplementation 'org.junit.platform:junit-platform-launcher:1.12.1'
    testImplementation 'org.junit.platform:junit-platform-suite:1.12.1'
    testImplementation 'org.junit.jupiter:junit-jupiter-api:5.12.1'
    testImplementation platform("org.spockframework:spock-bom:2.1-groovy-3.0")
    testImplementation 'org.spockframework:spock-core:2.1-groovy-3.0'
    testImplementation 'org.spockframework:spock-junit4:2.1-groovy-3.0'
}

test {
    useJUnitPlatform()
    testLogging { events "passed", "skipped", "failed" }
    testLogging.showStandardStreams = true; testLogging.showExceptions = true
    maxParallelForks 1
    // run only the suite, exactly like framework/build.gradle
    include '**/*MoquiSuite.class'
    systemProperty 'moqui.runtime', runtimeDir.absolutePath
    systemProperty 'moqui.conf', 'conf/MoquiDevConf.xml'
    systemProperty 'moqui.init.static', 'true'
    classpath += files(sourceSets.main.output.classesDirs)
    classpath += files(projectDir.absolutePath)
    classpath = classpath.filter { it.exists() }
}
```

Note: Moqui auto-discovers components in `runtime/component/` and auto-loads each component's `MoquiConf.xml`, `entity/`, `service/`, and `src/main/groovy`. This `build.gradle` only adds the component to the Gradle build so its Spock tests compile and run against the framework, the same way the framework's own tests run.

- [ ] **Step 4: Create the test suite (the only class Gradle runs directly)**

`runtime/component/moqui-ai/src/test/groovy/MoquiSuite.groovy` — identical pattern to
`framework/src/test/groovy/MoquiSuite.groovy`. **Append each task's `*Tests` class to
`@SelectClasses` as you create it.**
```groovy
import org.junit.jupiter.api.AfterAll
import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite
import org.moqui.Moqui

@Suite
@SelectClasses([ AiToolFactoryBootTests.class ])   // add AiEntitiesTests.class, MockProviderTests.class, ... per task
class MoquiSuite {
    @AfterAll
    static void destroyMoqui() { Moqui.destroyActiveExecutionContextFactory() }
}
```

- [ ] **Step 5: Write the failing boot test**

`runtime/component/moqui-ai/src/test/groovy/AiToolFactoryBootTests.groovy`:
```groovy
import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.AiToolFactory

class AiToolFactoryBootTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() { ec = Moqui.getExecutionContext() }
    def cleanupSpec() { if (ec != null) ec.destroy() }

    def "AI tool factory is registered and returns a singleton"() {
        when:
        AiToolFactory ai = ec.factory.getTool("AI", AiToolFactory.class)
        then:
        ai != null
        ec.factory.getTool("AI", AiToolFactory.class).is(ai)   // same singleton instance
    }
}
```

- [ ] **Step 6: Write the minimal ToolFactory**

`runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/AiToolFactory.groovy`:
```groovy
package org.moqui.ai

import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ToolFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Registers `ec.factory.getTool("AI", AiToolFactory.class)`. Singleton holds the
 *  provider registry and the in-memory tool catalog. Mirrors moqui-kie's KieToolFactory. */
class AiToolFactory implements ToolFactory<AiToolFactory> {
    protected final static Logger logger = LoggerFactory.getLogger(AiToolFactory.class)

    protected ExecutionContextFactory ecf = null

    /** No-arg constructor required by the ToolFactory SPI. */
    AiToolFactory() { }

    @Override String getName() { return "AI" }

    @Override void init(ExecutionContextFactory ecf) {
        this.ecf = ecf
        logger.info("AiToolFactory initialized")
    }

    @Override AiToolFactory getInstance(Object... parameters) {
        if (ecf == null) throw new IllegalStateException("AiToolFactory not initialized")
        return this
    }

    @Override void destroy() {
        logger.info("AiToolFactory destroyed")
    }
}
```

- [ ] **Step 7: Run the suite**

From the framework root (settings.gradle auto-includes the component once `build.gradle` exists):
```bash
./gradlew :runtime:component:moqui-ai:test
```
Expected: PASS — Gradle runs `MoquiSuite`, which runs `AiToolFactoryBootTests`. This is the test command for every later task (each task first appends its new `*Tests` class to `MoquiSuite`'s `@SelectClasses`). Booting `Moqui.getExecutionContext()` uses Moqui's default H2 datasource — no external service needed.

- [ ] **Step 8: Commit**

```bash
git add runtime/component/moqui-ai/component.xml runtime/component/moqui-ai/MoquiConf.xml \
        runtime/component/moqui-ai/build.gradle \
        runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/AiToolFactory.groovy \
        runtime/component/moqui-ai/src/test/groovy/MoquiSuite.groovy \
        runtime/component/moqui-ai/src/test/groovy/AiToolFactoryBootTests.groovy
git commit -m "feat(moqui-ai): scaffold component + AiToolFactory boot"
```

---

## Task 2: Observability + definition entities

**Files:**
- Create: `runtime/component/moqui-ai/entity/AiEntities.xml`
- Test: `runtime/component/moqui-ai/src/test/groovy/AiEntitiesTests.groovy`

- [ ] **Step 1: Write the failing entity test**

`runtime/component/moqui-ai/src/test/groovy/AiEntitiesTests.groovy`:
```groovy
import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.entity.EntityValue

class AiEntitiesTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() { ec = Moqui.getExecutionContext() }
    def cleanupSpec() { if (ec != null) ec.destroy() }

    def "can create and read an AiAgent with a tool grant"() {
        when:
        ec.entity.makeValue("moqui.ai.AiAgent")
            .setAll([agentName: "T2Agent", providerName: "mock", modelName: "mock-1",
                     systemPrompt: "test", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiTool")
            .setAll([toolName: "get#Echo", serviceName: "moqui.ai.test.TestServices.get#Echo",
                     description: "echo", requiresApproval: "N"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgentTool")
            .setAll([agentName: "T2Agent", toolName: "get#Echo"]).createOrUpdate()
        then:
        EntityValue a = ec.entity.find("moqui.ai.AiAgent").condition("agentName", "T2Agent").one()
        a != null
        a.providerName == "mock"
        ec.entity.find("moqui.ai.AiAgentTool")
            .condition("agentName", "T2Agent").list().size() == 1

        cleanup:
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentName", "T2Agent").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "T2Agent").deleteAll()
        ec.entity.find("moqui.ai.AiTool").condition("toolName", "get#Echo").deleteAll()
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :runtime:component:moqui-ai:test`
Expected: FAIL — entity `moqui.ai.AiAgent` not found.

- [ ] **Step 3: Define the entities**

`runtime/component/moqui-ai/entity/AiEntities.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<entities xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/entity-definition-3.xsd">

    <!-- ===== Definitions ===== -->
    <entity entity-name="AiAgent" package="moqui.ai">
        <field name="agentName" type="id" is-pk="true"/>
        <field name="providerName" type="text-short"/>
        <field name="modelName" type="text-medium"/>
        <field name="systemPrompt" type="text-very-long"/>
        <field name="maxIterations" type="number-integer"/>
        <field name="maxTokens" type="number-integer"/>
        <field name="maxCost" type="number-decimal"/>
        <field name="maxToolCallsPerTurn" type="number-integer"/>
        <field name="statusId" type="id"><description>StatusItem statusTypeId=AiAgentStatus: AI_AGENT_ACTIVE | AI_AGENT_DISABLED</description></field>
        <relationship type="one" related="moqui.basic.StatusItem" short-alias="status"/>
    </entity>

    <entity entity-name="AiTool" package="moqui.ai">
        <field name="toolName" type="id" is-pk="true"/>
        <field name="serviceName" type="text-medium"/>
        <field name="description" type="text-long"/>
        <field name="requiresApproval" type="text-indicator"><description>Y/N (reserved; not enforced this slice)</description></field>
        <field name="sourceComponent" type="text-medium"/>
    </entity>

    <entity entity-name="AiAgentTool" package="moqui.ai">
        <field name="agentName" type="id" is-pk="true"/>
        <field name="toolName" type="id" is-pk="true"/>
        <relationship type="one" related="moqui.ai.AiAgent" short-alias="agent"/>
        <relationship type="one" related="moqui.ai.AiTool" short-alias="tool"/>
    </entity>

    <!-- ===== Observability ===== -->
    <entity entity-name="AiAgentRun" package="moqui.ai">
        <field name="agentRunId" type="id" is-pk="true"/>
        <field name="agentName" type="id"/>
        <field name="userId" type="id"/>
        <field name="fromDate" type="date-time"/>
        <field name="thruDate" type="date-time"/>
        <field name="statusId" type="id"><description>StatusItem statusTypeId=AiAgentRunStatus: AI_RUN_RUNNING|AI_RUN_COMPLETED|AI_RUN_FAILED|AI_RUN_TRUNCATED|AI_RUN_ABORTED</description></field>
        <field name="providerName" type="text-short"/>
        <field name="modelName" type="text-medium"/>
        <field name="userMessage" type="text-very-long"/>
        <field name="assistantMessage" type="text-very-long"/>
        <field name="iterations" type="number-integer"/>
        <field name="tokensIn" type="number-integer"/>
        <field name="tokensOut" type="number-integer"/>
        <field name="estimatedCost" type="number-decimal"/>
        <field name="errorText" type="text-very-long"/>
        <relationship type="one" related="moqui.ai.AiAgent" short-alias="agent"/>
        <relationship type="one" related="moqui.basic.StatusItem" short-alias="status"/>
    </entity>

    <entity entity-name="AiAgentRunStep" package="moqui.ai">
        <field name="agentRunId" type="id" is-pk="true"/>
        <field name="stepSeqId" type="id" is-pk="true"/>
        <field name="stepType" type="text-short"><description>llm_call | tool_call</description></field>
        <field name="fromDate" type="date-time"/>
        <field name="thruDate" type="date-time"/>
        <field name="tokensIn" type="number-integer"/>
        <field name="tokensOut" type="number-integer"/>
        <field name="finishReason" type="text-short"/>
        <relationship type="one" related="moqui.ai.AiAgentRun" short-alias="run"/>
    </entity>

    <entity entity-name="AiToolCall" package="moqui.ai">
        <field name="agentRunId" type="id" is-pk="true"/>
        <field name="stepSeqId" type="id" is-pk="true"/>
        <field name="toolCallId" type="id" is-pk="true"/>
        <field name="toolName" type="text-medium"/>
        <field name="serviceName" type="text-medium"/>
        <field name="arguments" type="text-very-long"/>
        <field name="result" type="text-very-long"/>
        <field name="success" type="text-indicator"/>
        <field name="errorText" type="text-very-long"/>
        <field name="durationMs" type="number-integer"/>
        <field name="approvalStatus" type="text-short"><description>reserved for human-approval phase</description></field>
    </entity>
</entities>
```

- [ ] **Step 4: Add status seed data (StatusItem + StatusFlow + StatusFlowTransition)**

Per the practices guide §1.5 + §3.7, statuses are framework `StatusItem` records governed by
`StatusFlow`/`StatusFlowTransition`, loaded as **`install`** data (so they reach existing
environments on upgrade, not just fresh installs). Field names verified against
`framework/entity/BasicEntities.xml`.

`runtime/component/moqui-ai/data/AiStatusData.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<entity-facade-xml type="install">
    <moqui.basic.StatusType statusTypeId="AiAgentRunStatus" description="AI Agent Run Status"/>
    <moqui.basic.StatusType statusTypeId="AiAgentStatus" description="AI Agent Status"/>

    <moqui.basic.StatusItem statusId="AI_RUN_RUNNING"   statusTypeId="AiAgentRunStatus" sequenceNum="1" description="Running"/>
    <moqui.basic.StatusItem statusId="AI_RUN_COMPLETED" statusTypeId="AiAgentRunStatus" sequenceNum="2" description="Completed"/>
    <moqui.basic.StatusItem statusId="AI_RUN_FAILED"    statusTypeId="AiAgentRunStatus" sequenceNum="3" description="Failed"/>
    <moqui.basic.StatusItem statusId="AI_RUN_TRUNCATED" statusTypeId="AiAgentRunStatus" sequenceNum="4" description="Truncated"/>
    <moqui.basic.StatusItem statusId="AI_RUN_ABORTED"   statusTypeId="AiAgentRunStatus" sequenceNum="5" description="Aborted"/>

    <moqui.basic.StatusItem statusId="AI_AGENT_ACTIVE"   statusTypeId="AiAgentStatus" sequenceNum="1" description="Active"/>
    <moqui.basic.StatusItem statusId="AI_AGENT_DISABLED" statusTypeId="AiAgentStatus" sequenceNum="2" description="Disabled"/>

    <moqui.basic.StatusFlow statusFlowId="AiAgentRunFlow" statusTypeId="AiAgentRunStatus" description="AI agent run lifecycle"/>
    <moqui.basic.StatusFlow statusFlowId="AiAgentFlow"    statusTypeId="AiAgentStatus"    description="AI agent enable/disable"/>

    <!-- run: RUNNING -> terminal -->
    <moqui.basic.StatusFlowTransition statusFlowId="AiAgentRunFlow" statusId="AI_RUN_RUNNING" toStatusId="AI_RUN_COMPLETED" transitionSequence="1" transitionName="Complete"/>
    <moqui.basic.StatusFlowTransition statusFlowId="AiAgentRunFlow" statusId="AI_RUN_RUNNING" toStatusId="AI_RUN_FAILED"    transitionSequence="2" transitionName="Fail"/>
    <moqui.basic.StatusFlowTransition statusFlowId="AiAgentRunFlow" statusId="AI_RUN_RUNNING" toStatusId="AI_RUN_TRUNCATED" transitionSequence="3" transitionName="Truncate"/>
    <moqui.basic.StatusFlowTransition statusFlowId="AiAgentRunFlow" statusId="AI_RUN_RUNNING" toStatusId="AI_RUN_ABORTED"   transitionSequence="4" transitionName="Abort"/>

    <!-- agent: ACTIVE <-> DISABLED -->
    <moqui.basic.StatusFlowTransition statusFlowId="AiAgentFlow" statusId="AI_AGENT_ACTIVE"   toStatusId="AI_AGENT_DISABLED" transitionSequence="1" transitionName="Disable"/>
    <moqui.basic.StatusFlowTransition statusFlowId="AiAgentFlow" statusId="AI_AGENT_DISABLED" toStatusId="AI_AGENT_ACTIVE"   transitionSequence="2" transitionName="Enable"/>
</entity-facade-xml>
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :runtime:component:moqui-ai:test`
Expected: PASS (Moqui auto-creates tables for the H2 datasource on first access; install data loads the status records).

- [ ] **Step 6: Commit**

```bash
git add runtime/component/moqui-ai/entity/AiEntities.xml \
        runtime/component/moqui-ai/data/AiStatusData.xml \
        runtime/component/moqui-ai/src/test/groovy/AiEntitiesTests.groovy
git commit -m "feat(moqui-ai): definition + observability entities, status data"
```

---

## Task 3: Provider interface + normalized Map shapes

Moqui passes structured data as **Maps** (`Map` / `List<Map>`), exactly like `ElasticFacade`
(`Map get(...)`, `Map search(...)`, `void index(String, String, Map document)`) and like every
service (`ServiceCallSync.call()` returns `Map<String,Object>`). **No data-holder classes.**
The only thing we define here is the one interface providers implement; everything flowing
through it is a Groovy map literal `[key: value]`.

**Canonical Map shapes (the contract — documented, not classes):**
```
message     : [role: "system"|"user"|"assistant"|"tool", content: String,
               toolCalls: List<Map> (assistant turns), toolCallId: String (tool results)]
toolCall    : [id: String, name: String, arguments: Map]
toolSchema  : [name: String, description: String, parameters: Map]   // parameters = JSON Schema map
request     : [model: String, systemContext: String, messages: List<Map>, tools: List<Map>]
response    : [assistantText: String (null if only tool calls), toolCalls: List<Map>,
               tokensIn: long, tokensOut: long, finishReason: String]
toolDef     : [toolName: String, serviceName: String, description: String,
               requiresApproval: boolean, schema: Map]               // a catalog entry
runResult   : [assistantMessage: String, agentRunId: String, tokensIn: long, tokensOut: long,
               iterations: int, truncated: boolean, statusId: String]
```

**Files:**
- Create: `runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/LlmProvider.groovy`

No dedicated test (the shapes are exercised by Task 4's `MockProviderTests`).

- [ ] **Step 1: Define the provider interface (Map in, Map out)**

`runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/LlmProvider.groovy`:
```groovy
package org.moqui.ai

/** The only contract a new provider implements. Map-based, like ElasticFacade.
 *  The agentic loop talks ONLY to this. See the plan's "Canonical Map shapes" for the
 *  request/response Map contract. */
interface LlmProvider {
    /** Registry key: "mock" | "anthropic" | "openai" | "google". Matches AiAgent.providerName. */
    String getName()
    /** request Map in (model, systemContext, messages, tools), response Map out
     *  (assistantText, toolCalls, tokensIn, tokensOut, finishReason). Impl makes the HTTP call. */
    Map chat(Map request)
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :runtime:component:moqui-ai:compileGroovy`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/LlmProvider.groovy
git commit -m "feat(moqui-ai): LlmProvider interface (Map-based, no data classes)"
```

---

## Task 4: MockProvider (scripted, for deterministic tests)

**Files:**
- Create: `runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/provider/MockProvider.groovy`
- Test: `runtime/component/moqui-ai/src/test/groovy/MockProviderTests.groovy`

- [ ] **Step 1: Write the failing test**

`runtime/component/moqui-ai/src/test/groovy/MockProviderTests.groovy`:
```groovy
import spock.lang.*
import org.moqui.ai.provider.MockProvider

class MockProviderTests extends Specification {
    def cleanup() { MockProvider.reset() }

    def "returns scripted response Maps in order then defaults to a stop"() {
        given:
        def r1 = [toolCalls: [[id: "c1", name: "get#Echo", arguments: [text: "hi"]]],
                  finishReason: "tool_use", tokensIn: 10L, tokensOut: 5L]
        def r2 = [assistantText: "done", finishReason: "stop", tokensIn: 8L, tokensOut: 3L]
        MockProvider.enqueue(r1); MockProvider.enqueue(r2)
        def provider = new MockProvider()

        expect:
        provider.name == "mock"
        provider.chat([model: "mock-1"]).is(r1)
        provider.chat([model: "mock-1"]).is(r2)
        // queue empty -> a default stop response Map, never null
        provider.chat([model: "mock-1"]).finishReason == "stop"
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :runtime:component:moqui-ai:test`
Expected: FAIL — `MockProvider` does not exist.

- [ ] **Step 3: Implement MockProvider**

`runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/provider/MockProvider.groovy`:
```groovy
package org.moqui.ai.provider

import org.moqui.ai.LlmProvider
import java.util.concurrent.ConcurrentLinkedQueue

/** Deterministic provider for tests. A test enqueues the response Maps the loop should
 *  receive, in order. When the queue is empty, returns a benign "stop" so the loop
 *  always terminates. Registered under name "mock" (always available, no config). */
class MockProvider implements LlmProvider {
    private static final ConcurrentLinkedQueue<Map> SCRIPT = new ConcurrentLinkedQueue<>()

    static void enqueue(Map r) { SCRIPT.add(r) }
    static void reset() { SCRIPT.clear() }

    @Override String getName() { return "mock" }

    @Override Map chat(Map request) {
        Map r = SCRIPT.poll()
        if (r != null) return r
        return [assistantText: "", finishReason: "stop", toolCalls: [], tokensIn: 0L, tokensOut: 0L]
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :runtime:component:moqui-ai:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/provider/MockProvider.groovy \
        runtime/component/moqui-ai/src/test/groovy/MockProviderTests.groovy
git commit -m "feat(moqui-ai): scripted MockProvider for deterministic loop tests"
```

---

## Task 5: Tool JSON-schema generation from service in-parameters

**Files:**
- Create: `runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/ToolSchemaBuilder.groovy`
- Test: `runtime/component/moqui-ai/src/test/groovy/ToolSchemaBuilderTests.groovy`

- [ ] **Step 1: Write the failing test (against a real framework service)**

Uses the always-present framework service `org.moqui.impl.EntityServices.get#DataDocuments`? To avoid coupling to a specific framework service, the test defines its own tiny service file first.

Create `runtime/component/moqui-ai/service/moqui/ai/test/TestServices.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<services xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/service-definition-3.xsd">
    <service verb="get" noun="Echo">
        <in-parameters>
            <parameter name="text" type="String" required="true"><description>Text to echo back.</description></parameter>
            <parameter name="repeat" type="Integer"/>
        </in-parameters>
        <out-parameters><parameter name="echoed" type="String"/></out-parameters>
        <actions>
            <set field="echoed" value="${text}"/>
            <if condition="repeat"><set field="echoed" value="${echoed * repeat}"/></if>
        </actions>
    </service>
</services>
```

`runtime/component/moqui-ai/src/test/groovy/ToolSchemaBuilderTests.groovy`:
```groovy
import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.ToolSchemaBuilder

class ToolSchemaBuilderTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() { ec = Moqui.getExecutionContext() }
    def cleanupSpec() { if (ec != null) ec.destroy() }

    def "builds a JSON schema from a service's in-parameters"() {
        when:
        Map schema = ToolSchemaBuilder.build(ec.factory, "moqui.ai.test.TestServices.get#Echo")
        then:
        schema.type == "object"
        schema.properties.text.type == "string"
        schema.properties.repeat.type == "integer"
        schema.required == ["text"]
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :runtime:component:moqui-ai:test`
Expected: FAIL — `ToolSchemaBuilder` does not exist.

- [ ] **Step 3: Implement ToolSchemaBuilder**

`runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/ToolSchemaBuilder.groovy`:
```groovy
package org.moqui.ai

import org.moqui.context.ExecutionContextFactory
import org.moqui.impl.service.ServiceFacadeImpl
import org.moqui.impl.service.ServiceDefinition
import org.moqui.util.MNode

/** Generates an OpenAPI/JSON-Schema-style object schema for a Moqui service's in-parameters.
 *  Maps Moqui parameter types to JSON Schema types. Used to tell the LLM how to call a tool. */
class ToolSchemaBuilder {
    static Map<String, Object> build(ExecutionContextFactory ecf, String serviceName) {
        ServiceDefinition sd = ((ServiceFacadeImpl) ecf.service).getServiceDefinition(serviceName)
        if (sd == null) throw new IllegalArgumentException("Unknown service for tool: ${serviceName}")

        Map<String, Object> properties = [:]
        List<String> required = []
        for (String pName in sd.getInParameterNames()) {
            // Skip Moqui's implicit auth/system parameters
            if (pName in ['authUsername', 'authPassword', 'authTenantId']) continue
            MNode p = sd.getInParameter(pName)
            Map<String, Object> prop = [type: jsonType(p.attribute("type"))]
            String desc = p.first("description")?.text
            if (desc) prop.description = desc.trim()
            properties.put(pName, prop)
            if (p.attribute("required") == "true") required.add(pName)
        }
        Map<String, Object> schema = [type: "object", properties: properties]
        if (required) schema.required = required
        return schema
    }

    /** Moqui parameter type -> JSON Schema type. Defaults to string. */
    static String jsonType(String moquiType) {
        if (moquiType == null) return "string"
        switch (moquiType) {
            case "Integer": case "java.lang.Integer":
            case "Long":    case "java.lang.Long":
            case "BigInteger": case "java.math.BigInteger":
                return "integer"
            case "Float":  case "java.lang.Float":
            case "Double": case "java.lang.Double":
            case "BigDecimal": case "java.math.BigDecimal":
                return "number"
            case "Boolean": case "java.lang.Boolean":
                return "boolean"
            case "List": case "java.util.List": case "Collection": case "Set":
                return "array"
            case "Map": case "java.util.Map":
                return "object"
            default:
                return "string"   // String, Date, Time, Timestamp -> string
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :runtime:component:moqui-ai:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add runtime/component/moqui-ai/service/moqui/ai/test/TestServices.xml \
        runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/ToolSchemaBuilder.groovy \
        runtime/component/moqui-ai/src/test/groovy/ToolSchemaBuilderTests.groovy
git commit -m "feat(moqui-ai): generate tool JSON schema from service in-parameters"
```

---

## Task 6: DefinitionLoader + provider registry on the factory

This task adds the tool-catalog loader (file scan → in-memory immutable map) and the
provider registry to `AiToolFactory`. Agents stay in the entity layer (read per run in Task 7).

**Files:**
- Create: `runtime/component/moqui-ai/ai/test.tools.xml`
- Create: `runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/DefinitionLoader.groovy`
- Modify: `runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/AiToolFactory.groovy`
- Test: `runtime/component/moqui-ai/src/test/groovy/DefinitionLoaderTests.groovy`

- [ ] **Step 1: Create a tool-catalog file the loader will read**

`runtime/component/moqui-ai/ai/test.tools.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<tools>
    <tool service="moqui.ai.test.TestServices.get#Echo"
          description="Echoes the given text back, optionally repeated."/>
</tools>
```

- [ ] **Step 2: Write the failing loader test**

`runtime/component/moqui-ai/src/test/groovy/DefinitionLoaderTests.groovy`:
```groovy
import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.AiToolFactory

class DefinitionLoaderTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() { ec = Moqui.getExecutionContext() }
    def cleanupSpec() { if (ec != null) ec.destroy() }

    def "tool catalog is loaded from ai/*.tools.xml at boot, with a generated schema"() {
        when:
        AiToolFactory ai = ec.factory.getTool("AI", AiToolFactory.class)
        def tool = ai.getTool("moqui.ai.test.TestServices.get#Echo")
        then:
        tool != null
        tool.toolName == "moqui.ai.test.TestServices.get#Echo"
        tool.description.contains("Echoes")
        tool.schema.properties.text.type == "string"
    }

    def "unknown service reference in a tools file fails loud"() {
        when:
        ec.factory.getTool("AI", AiToolFactory.class)
            .loadToolsFromText('<tools><tool service="no.such.Service.get#Nope" description="x"/></tools>')
        then:
        thrown(IllegalArgumentException)
    }
}
```

- [ ] **Step 3: Implement DefinitionLoader**

`runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/DefinitionLoader.groovy`:
```groovy
package org.moqui.ai

import org.moqui.context.ExecutionContextFactory
import org.moqui.util.MNode

/** Scans every component's ai/ directory for *.tools.xml, validates each tool's service
 *  reference (fail-loud), and builds an immutable catalog Map keyed by service name. Each
 *  entry is a toolDef Map: [toolName, serviceName, description, requiresApproval, schema]
 *  (see the plan's "Canonical Map shapes"). Reload returns a NEW map; callers swap
 *  atomically and keep the last-good map on failure. */
class DefinitionLoader {
    /** Build the catalog by scanning all components' ai/ dirs. Throws on any bad reference. */
    static Map<String, Map> loadCatalog(ExecutionContextFactory ecf) {
        Map<String, Map> catalog = [:]
        for (String componentName in ecf.getComponentBaseLocations().keySet()) {
            String base = ecf.getComponentBaseLocations().get(componentName)
            org.moqui.resource.ResourceReference aiDir = ecf.resource.getLocationReference(base + "/ai")
            if (aiDir == null || !aiDir.getExists() || !aiDir.isDirectory()) continue
            for (org.moqui.resource.ResourceReference rr in aiDir.getDirectoryEntries()) {
                if (!rr.fileName.endsWith(".tools.xml")) continue
                parseToolsNode(ecf, MNode.parse(rr), catalog)
            }
        }
        return catalog
    }

    /** Parse one <tools> node into the catalog, validating each service ref (fail-loud). */
    static void parseToolsNode(ExecutionContextFactory ecf, MNode toolsNode, Map<String, Map> catalog) {
        for (MNode toolNode in toolsNode.children("tool")) {
            String serviceName = toolNode.attribute("service")
            // Fail-loud: ToolSchemaBuilder.build throws IllegalArgumentException on unknown service
            Map schema = ToolSchemaBuilder.build(ecf, serviceName)
            catalog.put(serviceName, [toolName: serviceName, serviceName: serviceName,
                    description: toolNode.attribute("description"),
                    requiresApproval: toolNode.attribute("requires-approval") == "true",
                    schema: schema])
        }
    }
}
```

- [ ] **Step 4: Wire the loader + provider registry into AiToolFactory**

Replace `runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/AiToolFactory.groovy` with:
```groovy
package org.moqui.ai

import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ToolFactory
import org.moqui.ai.provider.MockProvider
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Registers `ec.factory.getTool("AI", AiToolFactory.class)`. Holds the provider registry
 *  and the immutable, file-defined tool catalog (rebuilt on reload). Agents/knowledge are
 *  NOT held here — they are read from entities per run (see AgentRunner). */
class AiToolFactory implements ToolFactory<AiToolFactory> {
    protected final static Logger logger = LoggerFactory.getLogger(AiToolFactory.class)

    protected ExecutionContextFactory ecf = null
    private volatile Map<String, Map> toolCatalog = [:]
    private final Map<String, LlmProvider> providers = [:]

    AiToolFactory() { }

    @Override String getName() { return "AI" }

    @Override void init(ExecutionContextFactory ecf) {
        this.ecf = ecf
        // Register providers. Mock is always available (no config). Real providers added in Task 9.
        registerProvider(new MockProvider())
        // Fail-loud at boot: a bad service ref in any ai/*.tools.xml stops startup.
        this.toolCatalog = DefinitionLoader.loadCatalog(ecf)
        logger.info("AiToolFactory initialized: ${toolCatalog.size()} tools, ${providers.size()} providers")
    }

    @Override AiToolFactory getInstance(Object... parameters) {
        if (ecf == null) throw new IllegalStateException("AiToolFactory not initialized")
        return this
    }

    @Override void destroy() { logger.info("AiToolFactory destroyed") }

    // ---- provider registry ----
    void registerProvider(LlmProvider p) { providers.put(p.name, p) }
    LlmProvider getProvider(String name) {
        LlmProvider p = providers.get(name)
        if (p == null) throw new IllegalArgumentException("No AI provider registered: ${name}")
        return p
    }

    // ---- tool catalog (each entry is a toolDef Map) ----
    Map getTool(String serviceName) { return toolCatalog.get(serviceName) }
    Map<String, Map> getToolCatalog() { return toolCatalog }

    /** Reload the catalog from disk. On any validation error, keep the last-good catalog and rethrow. */
    void reloadCatalog() { this.toolCatalog = DefinitionLoader.loadCatalog(ecf) }

    /** Test/util helper: validate + merge a <tools> snippet (used by tests; fail-loud). */
    void loadToolsFromText(String toolsXml) {
        Map<String, Map> merged = new LinkedHashMap<>(toolCatalog)
        DefinitionLoader.parseToolsNode(ecf, MNode.parseText("tools.xml", toolsXml), merged)
        this.toolCatalog = merged
    }

    ExecutionContextFactory getEcf() { return ecf }
}
```

- [ ] **Step 5: Run the loader tests**

Run: `./gradlew :runtime:component:moqui-ai:test`
Expected: PASS (both the catalog-load case and the fail-loud case).

- [ ] **Step 6: Commit**

```bash
git add runtime/component/moqui-ai/ai/test.tools.xml \
        runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/DefinitionLoader.groovy \
        runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/AiToolFactory.groovy \
        runtime/component/moqui-ai/src/test/groovy/DefinitionLoaderTests.groovy
git commit -m "feat(moqui-ai): tool-catalog loader (fail-loud) + provider registry"
```

---

## Task 7: AgentRunner — the agentic loop

The heart. No enclosing transaction; tool calls via `ec.service.sync()` (own tx each);
observability writes via entity-auto create services (own tx each), guarded so a write
failure never aborts the run. Per-run ceiling enforced.

**Files:**
- Create: `runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/AgentRunner.groovy`
- Test: `runtime/component/moqui-ai/src/test/groovy/AgentRunnerTests.groovy`

- [ ] **Step 1: Write the failing loop tests (Mock-driven)**

`runtime/component/moqui-ai/src/test/groovy/AgentRunnerTests.groovy`:
```groovy
import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.*
import org.moqui.ai.provider.MockProvider

class AgentRunnerTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "EchoAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "You echo.", maxIterations: 5,
            maxToolCallsPerTurn: 10, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgentTool")
            .setAll([agentName: "EchoAgent", toolName: "moqui.ai.test.TestServices.get#Echo"]).createOrUpdate()
    }
    def cleanupSpec() {
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentName", "EchoAgent").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "EchoAgent").deleteAll()
        ec.destroy()
    }
    def cleanup() { MockProvider.reset() }

    private AgentRunner runner() { new AgentRunner(ec, ec.factory.getTool("AI", AiToolFactory.class)) }

    def "text-only response returns the assistant message"() {
        given: MockProvider.enqueue([assistantText: "hello", finishReason: "stop", tokensIn: 4L, tokensOut: 2L])
        when: def out = runner().run("EchoAgent", "hi")
        then:
        out.assistantMessage == "hello"
        out.truncated == false
        out.agentRunId != null
        ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one().statusId == "AI_RUN_COMPLETED"
    }

    def "tool call is dispatched and the result is fed back"() {
        given:
        MockProvider.enqueue([toolCalls: [[id: "c1",
            name: "moqui.ai.test.TestServices.get#Echo", arguments: [text: "boom"]]], finishReason: "tool_use"])
        MockProvider.enqueue([assistantText: "echoed boom", finishReason: "stop"])
        when: def out = runner().run("EchoAgent", "echo boom")
        then:
        out.assistantMessage == "echoed boom"
        def call = ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", out.agentRunId).list()[0]
        call.toolName == "moqui.ai.test.TestServices.get#Echo"
        call.success == "Y"
        call.result.contains("boom")
    }

    def "hitting max-iterations returns truncated"() {
        given: 6.times { MockProvider.enqueue([toolCalls: [[id: "c$it",
            name: "moqui.ai.test.TestServices.get#Echo", arguments: [text: "x"]]], finishReason: "tool_use"]) }
        when: def out = runner().run("EchoAgent", "loop")
        then:
        out.truncated == true
        ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one().statusId == "AI_RUN_TRUNCATED"
    }

    def "a throwing tool feeds the error back instead of aborting the run"() {
        given:
        MockProvider.enqueue([toolCalls: [[id: "c1",
            name: "moqui.ai.test.TestServices.get#Echo", arguments: [repeat: -1]]], finishReason: "tool_use"])
        MockProvider.enqueue([assistantText: "recovered", finishReason: "stop"])
        when: def out = runner().run("EchoAgent", "bad")
        then:
        out.assistantMessage == "recovered"   // loop continued after the tool error
    }
}
```

(Note: the `repeat: -1` case relies on `get#Echo` throwing on a negative repeat. Add that guard to `TestServices.xml`'s `get#Echo` actions: `<if condition="repeat != null &amp;&amp; repeat &lt; 0"><return error="true" message="repeat must be >= 0"/></if>` before the echo `<set>`.)

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :runtime:component:moqui-ai:test`
Expected: FAIL — `AgentRunner` does not exist.

- [ ] **Step 3: Implement AgentRunner**

`runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/AgentRunner.groovy`:
```groovy
package org.moqui.ai

import groovy.json.JsonOutput
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** The provider-agnostic agentic loop. All data is Map-based (Moqui idiom, like ElasticFacade).
 *  Holds NO enclosing transaction: LLM calls happen outside any tx; each tool call runs in its
 *  own tx (ec.service.sync default); each observability write runs in its own tx via persist(),
 *  guarded so failures only warn. run() returns a runResult Map (see "Canonical Map shapes"). */
class AgentRunner {
    protected final static Logger logger = LoggerFactory.getLogger(AgentRunner.class)

    private final ExecutionContext ec
    private final AiToolFactory ai

    AgentRunner(ExecutionContext ec, AiToolFactory ai) { this.ec = ec; this.ai = ai }

    /** @return runResult Map: [assistantMessage, agentRunId, tokensIn, tokensOut, iterations, truncated, statusId] */
    Map run(String agentName, String userMessage) {
        EntityValue agent = ec.entity.find("moqui.ai.AiAgent")
            .condition("agentName", agentName).useCache(true).one()
        if (agent == null) throw new IllegalArgumentException("Unknown agent: ${agentName}")

        int maxIter = (agent.maxIterations ?: 8) as int
        long maxTokens = (agent.maxTokens ?: 0L) as long          // 0 = no limit
        int maxToolCalls = (agent.maxToolCallsPerTurn ?: 20) as int

        LlmProvider provider = ai.getProvider(agent.providerName as String)
        List<Map> toolSchemas = loadToolSchemas(agentName)

        String runId = ec.entity.sequencedIdPrimary("moqui.ai.AiAgentRun", null, null)
        Map result = [agentRunId: runId, assistantMessage: null, tokensIn: 0L, tokensOut: 0L,
                      iterations: 0, truncated: false, statusId: "AI_RUN_RUNNING"]
        persist("create#moqui.ai.AiAgentRun", [agentRunId: runId, agentName: agentName,
            userId: ec.user.userId, fromDate: ec.user.nowTimestamp, statusId: "AI_RUN_RUNNING",
            providerName: agent.providerName, modelName: agent.modelName, userMessage: userMessage])

        List<Map> messages = [[role: "user", content: userMessage]]
        int stepSeq = 0
        try {
            for (int i = 0; i < maxIter; i++) {
                result.iterations = i + 1
                // request Map in, response Map out -- external HTTP, no tx held
                Map resp = provider.chat([model: agent.modelName, systemContext: agent.systemPrompt,
                        messages: messages, tools: toolSchemas])
                long inTok = (resp.tokensIn ?: 0L) as long
                long outTok = (resp.tokensOut ?: 0L) as long
                result.tokensIn += inTok; result.tokensOut += outTok
                stepSeq++
                persist("create#moqui.ai.AiAgentRunStep", [agentRunId: runId, stepSeqId: stepSeq as String,
                    stepType: "llm_call", tokensIn: inTok, tokensOut: outTok, finishReason: resp.finishReason])

                if (maxTokens > 0 && ((result.tokensIn as long) + (result.tokensOut as long)) > maxTokens)
                    return finish(result, runId, "AI_RUN_ABORTED", "Per-run token ceiling exceeded")

                List toolCalls = (resp.toolCalls ?: []) as List
                if (!toolCalls) {
                    result.assistantMessage = resp.assistantText ?: ""
                    return finish(result, runId, "AI_RUN_COMPLETED", null)
                }
                if (toolCalls.size() > maxToolCalls)
                    return finish(result, runId, "AI_RUN_ABORTED", "Tool-calls-per-turn ceiling exceeded")

                // record the assistant turn that requested tools, then dispatch each
                messages.add([role: "assistant", toolCalls: toolCalls])
                for (Map tc in toolCalls) {
                    String resultJson = dispatchTool(runId, stepSeq, tc)
                    messages.add([role: "tool", toolCallId: tc.id, content: resultJson])
                }
            }
            return finish(result, runId, "AI_RUN_TRUNCATED", null)   // ran out of iterations
        } catch (Throwable t) {
            logger.error("Agent run ${runId} failed", t)
            return finish(result, runId, "AI_RUN_FAILED", t.message)
        }
    }

    /** Dispatch one tool-call Map via ec.service.sync (its own tx; Moqui authz applies). Tool
     *  errors are caught and returned as a JSON error so the loop can recover. */
    private String dispatchTool(String runId, int stepSeq, Map tc) {
        Map td = ai.getTool(tc.name as String)
        long start = System.currentTimeMillis()
        String resultJson; boolean success; String errorText = null
        if (td == null) {
            success = false; errorText = "Tool not in catalog: ${tc.name}"
            resultJson = JsonOutput.toJson([error: errorText])
        } else {
            try {
                Map out = ec.service.sync().name(td.serviceName as String)
                        .parameters((tc.arguments ?: [:]) as Map).call()
                if (ec.message.hasError()) {
                    success = false; errorText = ec.message.errorsString; ec.message.clearErrors()
                    resultJson = JsonOutput.toJson([error: errorText])
                } else {
                    success = true; resultJson = JsonOutput.toJson(out ?: [:])
                }
            } catch (Throwable t) {
                success = false; errorText = t.message; resultJson = JsonOutput.toJson([error: t.message])
            }
        }
        persist("create#moqui.ai.AiToolCall", [agentRunId: runId, stepSeqId: stepSeq as String,
            toolCallId: tc.id, toolName: tc.name, serviceName: td?.serviceName,
            arguments: JsonOutput.toJson(tc.arguments ?: [:]), result: resultJson,
            success: success ? "Y" : "N", errorText: errorText,
            durationMs: (System.currentTimeMillis() - start) as int])
        return resultJson
    }

    /** Build the agent's granted tools as a List of toolSchema Maps. */
    private List<Map> loadToolSchemas(String agentName) {
        List<Map> schemas = []
        for (EntityValue grant in ec.entity.find("moqui.ai.AiAgentTool")
                .condition("agentName", agentName).useCache(true).list()) {
            Map td = ai.getTool(grant.toolName as String)
            if (td == null) {
                logger.warn("Agent ${agentName} grants unknown tool ${grant.toolName}; skipping")
                continue
            }
            schemas.add([name: td.toolName, description: td.description, parameters: td.schema])
        }
        return schemas
    }

    /** Finalize: set status + truncated on the result Map, persist the run update, return it. */
    private Map finish(Map result, String runId, String statusId, String errorText) {
        result.statusId = statusId
        result.truncated = (statusId == "AI_RUN_TRUNCATED")
        persist("update#moqui.ai.AiAgentRun", [agentRunId: runId, thruDate: ec.user.nowTimestamp,
            statusId: statusId, assistantMessage: result.assistantMessage, iterations: result.iterations,
            tokensIn: result.tokensIn, tokensOut: result.tokensOut, errorText: errorText])
        return result
    }

    /** Persistence never aborts the run: each write is its own service call (own tx); on
     *  failure we log a warning and continue. */
    private void persist(String serviceName, Map params) {
        try { ec.service.sync().name(serviceName).parameters(params).call() }
        catch (Throwable t) { logger.warn("Observability write ${serviceName} failed (continuing): ${t.message}") }
    }
}
```

- [ ] **Step 4: Run the loop tests to verify they pass**

Run: `./gradlew :runtime:component:moqui-ai:test`
Expected: PASS (all four cases).

- [ ] **Step 5: Commit**

```bash
git add runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/AgentRunner.groovy \
        runtime/component/moqui-ai/service/moqui/ai/test/TestServices.xml \
        runtime/component/moqui-ai/src/test/groovy/AgentRunnerTests.groovy
git commit -m "feat(moqui-ai): agentic loop with tool dispatch, ceilings, observability"
```

---

## Task 8: `run#Agent` service

Expose the runner as the Moqui-native entry point, with **no enclosing transaction**.

**Files:**
- Create: `runtime/component/moqui-ai/service/ai/AgentServices.xml`
- Test: `runtime/component/moqui-ai/src/test/groovy/RunAgentServiceTests.groovy`

- [ ] **Step 1: Write the failing service test**

`runtime/component/moqui-ai/src/test/groovy/RunAgentServiceTests.groovy`:
```groovy
import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.provider.MockProvider

class RunAgentServiceTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "SvcAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
    }
    def cleanupSpec() {
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "SvcAgent").deleteAll()
        ec.destroy()
    }
    def cleanup() { MockProvider.reset() }

    def "run#Agent returns the assistant message and run id"() {
        given: MockProvider.enqueue([assistantText: "service ok", finishReason: "stop", tokensOut: 3L])
        when:
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "SvcAgent", userMessage: "ping"]).call()
        then:
        out.assistantMessage == "service ok"
        out.agentRunId != null
        out.truncated == false
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :runtime:component:moqui-ai:test`
Expected: FAIL — service `ai.AgentServices.run#Agent` not found.

- [ ] **Step 3: Implement the service**

`runtime/component/moqui-ai/service/ai/AgentServices.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<services xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/service-definition-3.xsd">

    <!-- transaction="ignore": the loop holds NO enclosing tx; tool calls + observability
         writes each manage their own tx (see AgentRunner). -->
    <service verb="run" noun="Agent" transaction="ignore" authenticate="true">
        <in-parameters>
            <parameter name="agentName" required="true"/>
            <parameter name="userMessage" required="true"/>
        </in-parameters>
        <out-parameters>
            <parameter name="assistantMessage"/>
            <parameter name="agentRunId"/>
            <parameter name="tokensIn" type="Long"/>
            <parameter name="tokensOut" type="Long"/>
            <parameter name="iterations" type="Integer"/>
            <parameter name="truncated" type="Boolean"/>
            <parameter name="statusId"/>
        </out-parameters>
        <actions>
            <script><![CDATA[
                def ai = ec.factory.getTool("AI", org.moqui.ai.AiToolFactory.class)
                def runner = new org.moqui.ai.AgentRunner(ec, ai)
                def r = runner.run(agentName, userMessage)
                assistantMessage = r.assistantMessage
                agentRunId = r.agentRunId
                tokensIn = r.tokensIn
                tokensOut = r.tokensOut
                iterations = r.iterations
                truncated = r.truncated
                statusId = r.statusId
            ]]></script>
        </actions>
    </service>
</services>
```

- [ ] **Step 4: Run the service test to verify it passes**

Run: `./gradlew :runtime:component:moqui-ai:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add runtime/component/moqui-ai/service/ai/AgentServices.xml \
        runtime/component/moqui-ai/src/test/groovy/RunAgentServiceTests.groovy
git commit -m "feat(moqui-ai): ai.AgentServices.run#Agent entry point (no enclosing tx)"
```

---

## Task 9: AbstractLlmProvider base + AnthropicProvider

Shared transport/error/token logic in a base; the Anthropic adapter implements only
encode/decode. Network test uses a stubbed `RestClient` request factory; an opt-in live
smoke test runs only when `ai_anthropic_key` is set.

**Files:**
- Create: `runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/provider/AbstractLlmProvider.groovy`
- Create: `runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/provider/AnthropicProvider.groovy`
- Modify: `runtime/component/moqui-ai/MoquiConf.xml` (provider key default-properties)
- Modify: `runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/AiToolFactory.groovy` (register Anthropic when keyed)
- Test: `runtime/component/moqui-ai/src/test/groovy/AnthropicProviderTests.groovy`

- [ ] **Step 1: Add provider key default-properties to MoquiConf**

Edit `runtime/component/moqui-ai/MoquiConf.xml` — add inside `<moqui-conf>`, before `<tools>`:
```xml
    <default-property name="ai_anthropic_key" value="" is-secret="true"/>
    <default-property name="ai_anthropic_base_url" value="https://api.anthropic.com"/>
    <default-property name="ai_anthropic_version" value="2023-06-01"/>
    <default-property name="ai_timeout_seconds" value="60"/>
```

- [ ] **Step 2: Write the failing encode/decode test (no network)**

`runtime/component/moqui-ai/src/test/groovy/AnthropicProviderTests.groovy`:
```groovy
import spock.lang.*
import org.moqui.ai.provider.AnthropicProvider
import groovy.json.JsonSlurper

class AnthropicProviderTests extends Specification {

    def "encodes a request body with system, messages, and tools"() {
        given:
        def p = new AnthropicProvider("k", "https://api.anthropic.com", "2023-06-01", 60)
        Map req = [model: "claude-sonnet-4-6", systemContext: "be terse",
            messages: [[role: "user", content: "hi"]],
            tools: [[name: "get#Echo", description: "echo",
                parameters: [type: "object", properties: [text: [type: "string"]], required: ["text"]]]]]
        when:
        Map body = new JsonSlurper().parseText(p.encodeRequest(req)) as Map
        then:
        body.model == "claude-sonnet-4-6"
        body.system == "be terse"
        body.messages[0].role == "user"
        body.tools[0].name == "get#Echo"
        body.tools[0].input_schema.properties.text.type == "string"
    }

    def "decodes a tool_use response into tool-call Maps"() {
        given:
        def p = new AnthropicProvider("k", "https://api.anthropic.com", "2023-06-01", 60)
        String raw = '''{"stop_reason":"tool_use","usage":{"input_tokens":12,"output_tokens":7},
            "content":[{"type":"text","text":"calling"},
            {"type":"tool_use","id":"tu_1","name":"get#Echo","input":{"text":"hi"}}]}'''
        when:
        Map r = p.decodeResponse(raw)
        then:
        r.finishReason == "tool_use"
        r.tokensIn == 12L && r.tokensOut == 7L
        r.toolCalls.size() == 1
        r.toolCalls[0].name == "get#Echo"
        r.toolCalls[0].arguments.text == "hi"
    }

    def "decodes a plain text response"() {
        given: def p = new AnthropicProvider("k", "u", "v", 60)
        when:
        Map r = p.decodeResponse('{"stop_reason":"end_turn","usage":{"input_tokens":3,"output_tokens":2},"content":[{"type":"text","text":"hello"}]}')
        then:
        r.assistantText == "hello"
        (r.toolCalls ?: []).isEmpty()
        r.finishReason == "end_turn"
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew :runtime:component:moqui-ai:test`
Expected: FAIL — `AnthropicProvider` does not exist.

- [ ] **Step 4: Implement the abstract base**

`runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/provider/AbstractLlmProvider.groovy`:
```groovy
package org.moqui.ai.provider

import org.moqui.ai.LlmProvider
import org.moqui.util.RestClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Shared transport for HTTP providers: RestClient POST of an encoded body, error mapping,
 *  and the encode/decode template. All data is Map-based. Concrete adapters implement only
 *  the wire format (encodeRequest Map->String, decodeResponse String->Map). */
abstract class AbstractLlmProvider implements LlmProvider {
    protected final static Logger logger = LoggerFactory.getLogger(AbstractLlmProvider.class)

    protected final String apiKey
    protected final String baseUrl
    protected final int timeoutSeconds

    AbstractLlmProvider(String apiKey, String baseUrl, int timeoutSeconds) {
        this.apiKey = apiKey; this.baseUrl = baseUrl; this.timeoutSeconds = timeoutSeconds
    }

    /** The endpoint path appended to baseUrl, e.g. "/v1/messages". */
    protected abstract String endpointPath()
    /** Provider-specific auth/version headers. */
    protected abstract Map<String, String> authHeaders()
    /** Encode the normalized request Map into the provider's JSON body. */
    abstract String encodeRequest(Map request)
    /** Decode the provider's JSON response text into a normalized response Map. */
    abstract Map decodeResponse(String responseText)

    @Override
    Map chat(Map request) {
        String body = encodeRequest(request)
        RestClient rc = new RestClient().uri(baseUrl + endpointPath())
            .method('POST').contentType("application/json").timeout(timeoutSeconds).text(body)
        authHeaders().each { k, v -> rc.addHeader(k, v) }
        def resp
        try {
            resp = rc.call()   // RestClient throws on non-2xx (and on network errors)
        } catch (Exception e) {
            throw new RuntimeException("LLM provider ${name} HTTP error: ${e.message}", e)
        }
        return decodeResponse(resp.text())
    }
}
```

- [ ] **Step 5: Implement AnthropicProvider**

`runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/provider/AnthropicProvider.groovy`:
```groovy
package org.moqui.ai.provider

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/** Anthropic Messages API adapter. Maps the normalized request/response Maps to/from
 *  Anthropic's tool_use/tool_result content blocks. */
class AnthropicProvider extends AbstractLlmProvider {
    private final String anthropicVersion

    AnthropicProvider(String apiKey, String baseUrl, String anthropicVersion, int timeoutSeconds) {
        super(apiKey, baseUrl, timeoutSeconds); this.anthropicVersion = anthropicVersion
    }

    @Override String getName() { return "anthropic" }
    @Override protected String endpointPath() { return "/v1/messages" }
    @Override protected Map<String, String> authHeaders() {
        return ["x-api-key": apiKey, "anthropic-version": anthropicVersion]
    }

    @Override
    String encodeRequest(Map request) {
        List<Map> apiMessages = []
        for (Map m in (request.messages ?: []) as List<Map>) {
            if (m.role == "tool") {
                apiMessages.add([role: "user", content: [[type: "tool_result",
                    tool_use_id: m.toolCallId, content: m.content]]])
            } else if (m.role == "assistant" && m.toolCalls) {
                apiMessages.add([role: "assistant", content: (m.toolCalls as List<Map>).collect { tc ->
                    [type: "tool_use", id: tc.id, name: tc.name, input: tc.arguments] }])
            } else {
                apiMessages.add([role: m.role, content: m.content])
            }
        }
        Map body = [model: request.model, max_tokens: 4096, messages: apiMessages]
        if (request.systemContext) body.system = request.systemContext
        if (request.tools) body.tools = (request.tools as List<Map>).collect { t ->
            [name: t.name, description: t.description, input_schema: t.parameters] }
        return JsonOutput.toJson(body)
    }

    @Override
    Map decodeResponse(String responseText) {
        Map json = new JsonSlurper().parseText(responseText) as Map
        Map usage = (json.usage ?: [:]) as Map
        List<Map> toolCalls = []
        StringBuilder text = new StringBuilder()
        for (Map block in (json.content ?: []) as List<Map>) {
            if (block.type == "text") text.append(block.text as String)
            else if (block.type == "tool_use") toolCalls.add(
                [id: block.id, name: block.name, arguments: (block.input ?: [:])])
        }
        return [finishReason: json.stop_reason,
                tokensIn: (usage.input_tokens ?: 0L) as long,
                tokensOut: (usage.output_tokens ?: 0L) as long,
                toolCalls: toolCalls,
                assistantText: text.length() > 0 ? text.toString() : null]
    }
}
```

- [ ] **Step 6: Run the encode/decode tests to verify they pass**

Run: `./gradlew :runtime:component:moqui-ai:test`
Expected: PASS (all three cases — no network used).

- [ ] **Step 7: Register Anthropic in the factory when a key is configured**

In `AiToolFactory.init(...)`, after `registerProvider(new MockProvider())`, add:
```groovy
        String anthKey = ecf.resource.expand('${ai_anthropic_key}', null)
        if (anthKey) {
            registerProvider(new org.moqui.ai.provider.AnthropicProvider(anthKey,
                ecf.resource.expand('${ai_anthropic_base_url}', null),
                ecf.resource.expand('${ai_anthropic_version}', null),
                (ecf.resource.expand('${ai_timeout_seconds}', null) ?: "60") as int))
            logger.info("AI: registered Anthropic provider")
        }
```
(Provider-init failure isolation, per spec §21: register a provider only when its key is present; a missing key for an unused provider does not block boot. An agent referencing an unconfigured provider fails at run time via `getProvider`'s clear error.)

- [ ] **Step 8: Add an opt-in live smoke test**

Append to `AnthropicProviderTests.groovy`:
```groovy
    @Requires({ System.getenv("ai_anthropic_key") })
    def "live: a real Anthropic call returns text"() {
        given:
        def key = System.getenv("ai_anthropic_key")
        def p = new AnthropicProvider(key, "https://api.anthropic.com", "2023-06-01", 60)
        when:
        Map r = p.chat([model: "claude-sonnet-4-6",
            messages: [[role: "user", content: "Reply with the single word: pong"]]])
        then:
        (r.assistantText as String)?.toLowerCase()?.contains("pong")
    }
```

- [ ] **Step 9: Run the full suite**

Run: `./gradlew :runtime:component:moqui-ai:test`
Expected: PASS for all specs. The live smoke test is skipped unless `ai_anthropic_key` is exported.

- [ ] **Step 10: Commit**

```bash
git add runtime/component/moqui-ai/MoquiConf.xml \
        runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/provider/AbstractLlmProvider.groovy \
        runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/provider/AnthropicProvider.groovy \
        runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/AiToolFactory.groovy \
        runtime/component/moqui-ai/src/test/groovy/AnthropicProviderTests.groovy
git commit -m "feat(moqui-ai): AbstractLlmProvider base + Anthropic adapter (key-gated)"
```

---

## Slice Done — Definition of Done

- `./gradlew :runtime:component:moqui-ai:test` is green (live smoke skipped without a key).
- An agent (DB record) + a tool (`ai/*.tools.xml`) can be invoked via `ai.AgentServices.run#Agent` and returns an answer, dispatching tools through `ec.service.sync()`.
- Every run/step/tool-call is persisted; a persistence failure logs a warning and does not abort the run.
- Bad tool service reference fails the boot loudly; per-run ceilings stop runaway loops.
- Loop holds no enclosing transaction; Anthropic adapter works against the real API when keyed.

## NOT in this slice (next plans)
- OpenAI + Google adapters (mirror Task 9 on the shared base).
- Structured output (agent-declared output schema + validation + one re-ask).
- Attached knowledge (`AiAgentKnowledge` entity + injection into `systemContext`).
- Developer console screens (Agents, Tools, Playground, Runs).
- `reload#Definitions`, `create#Agent`, `update#Agent` admin services + restricted-user permission E2E.
- Cost-aggregation service, human-approval gate, RAG.
