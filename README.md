# moqui-ai

LLM integration for [Moqui Framework](https://github.com/moqui/moqui-framework) via a first-class `ec.ai` facade.

Adds AI capability to Moqui following the same pattern as the built-in `ec.elastic` facade — so any Groovy service, screen action, or scheduled job can call an LLM with a single line:

```groovy
ec.ai.getDefault().chat(messages)
```

---

## Design principles

- **Follows Moqui conventions** — same registration pattern as `ElasticFacadeImpl`, same config pattern as `entity-facade` with named, switchable connections
- **Provider-agnostic** — configure OpenAI, Anthropic, Ollama, or any OpenAI-compatible endpoint without changing code
- **Multiple named configs** — define many `<model-config>` entries, pick a default, reference others by name at runtime
- **No code change to switch providers** — change `MoquiConf.xml`, restart, done
- **Secrets handled correctly** — `api-key` uses Moqui's `is-secret="true"` mechanism, never logged

---

## Architecture

`ec.ai` is registered as a singleton facade on `ExecutionContextFactoryImpl` (ECFI), exactly like `ec.elastic`:

```
ExecutionContextFactoryImpl  (singleton)
  └── aiFacade: AiFacadeImpl

ExecutionContextImpl  (per thread)
  └── aiFacade  ←  ecfi.aiFacade

ExecutionContext  (public interface)
  └── getAi()  →  ec.ai
```

---

## Configuration

In `MoquiDefaultConf.xml`:

```xml
<!-- AI Facade settings -->
<default-property name="ai_provider"  value=""/>
<default-property name="ai_model"     value=""/>
<default-property name="ai_base_url"  value=""/>
<default-property name="ai_api_key"   value="" is-secret="true"/>
<default-property name="ai_timeout"   value="60"/>
<default-property name="ai_pool_max"  value="10"/>

<ai-facade default-config="default">
    <model-config name="default"
                  provider="${ai_provider}"
                  model="${ai_model}"
                  base-url="${ai_base_url}"
                  api-key="${ai_api_key}"
                  timeout="${ai_timeout}"
                  pool-max="${ai_pool_max}"/>
</ai-facade>
```

In your `MoquiConf.xml` at runtime:

```xml
<default-property name="ai_provider"  value="openai"/>
<default-property name="ai_model"     value="gpt-4o-mini"/>
<default-property name="ai_base_url"  value="https://api.openai.com/v1"/>
<default-property name="ai_api_key"   value="sk-..."/>
```

Or set environment variables — Moqui picks them up automatically:

```bash
export ai_provider=anthropic
export ai_model=claude-sonnet-4-6
export ai_base_url=https://api.anthropic.com
export ai_api_key=sk-ant-...
```

### Multiple providers

```xml
<ai-facade default-config="openai">
    <model-config name="openai"
                  provider="openai"
                  model="gpt-4o"
                  base-url="https://api.openai.com/v1"
                  api-key="${openai_api_key}"/>

    <model-config name="anthropic"
                  provider="anthropic"
                  model="claude-sonnet-4-6"
                  base-url="https://api.anthropic.com"
                  api-key="${anthropic_api_key}"/>

    <model-config name="local"
                  provider="openai-compatible"
                  model="llama-3-70b"
                  base-url="http://localhost:11434/v1"
                  api-key=""/>
</ai-facade>
```

---

## Usage

```groovy
// Use the default configured provider
def response = ec.ai.getDefault().chat([
    [role: "user", content: "Summarize this order in one sentence."]
])

// Use a specific named config
def response = ec.ai.getConfig("anthropic").chat(messages)
```

---

## Development plan

- [x] Step 1 — Config: `default-property` entries and `<ai-facade>` block in `MoquiDefaultConf.xml`
- [ ] Step 2 — Interface: `AiFacade.java` in `org.moqui.context`
- [ ] Step 3 — Implementation: `AiFacadeImpl.groovy` in `org.moqui.impl.context`
- [ ] Step 4 — Wiring: `getAi()` on `ExecutionContext.java`, field on `ExecutionContextImpl.java`, init/destroy in `ExecutionContextFactoryImpl.groovy`
- [ ] Step 5 — Smoke test: minimal Groovy service calling `ec.ai.getDefault().chat(...)`
- [ ] Step 6 — Usage examples in screen actions and services

---

## Key design decisions

**Why `<model-config>` and not `<provider>`?**
In Moqui, "model" in everyday developer conversation refers to the AI model being used (GPT-4o, Claude, Llama). Using `<model-config>` as the element name follows that convention. `provider` is kept as an attribute pointing to the LLM company (openai, anthropic), parallel to how `database-conf-name` points to the DB vendor in `<datasource>`.

**Why follow ElasticFacade and not a component ToolFactory?**
`ec.elastic` is a first-class facade — available everywhere in Moqui with no imports, no service calls, no component dependency. AI capability deserves the same status. A ToolFactory would make it optional and harder to reference.

**Why ECFI singleton and not per-thread?**
HTTP clients and connection pools are expensive to create. One instance per JVM, shared across threads, is the right model — same as `ElasticFacadeImpl`.

---

## Related

- [Moqui Framework](https://github.com/moqui/moqui-framework)
- [ElasticFacade.java](https://github.com/moqui/moqui-framework/blob/master/framework/src/main/java/org/moqui/context/ElasticFacade.java) — the pattern this follows
- [Mastra](https://mastra.ai) — TypeScript AI framework, useful reference for provider/model terminology
