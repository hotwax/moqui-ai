# Phase 6: Knowledge Retrieval (RAG) — Implementation Plan

> **Status: NOT IMPLEMENTED (deferred).** Semantic / RAG retrieval (embeddings, vector index, kNN, chunking, `search#Knowledge`, auto-retrieve) described below was never built — none of it exists in the code. The shipped 'knowledge' capability is instead the lexical **domain Glossary**; see docs/specs/2026-06-05-builder-knowledgebase-design.md and docs/plans/2026-06-05-builder-knowledgebase.md.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an agent draw on a large knowledge base beyond what fits in the always-injected
attached knowledge (Phase 1): chunk + embed `AiAgentKnowledge` into a vector index, and retrieve
the most relevant chunks at run time — either via a built-in `search#Knowledge` tool the agent
calls, or auto-injected into the system context.

**Architecture:** Embeddings via an OpenAI-compatible embeddings endpoint (`EmbeddingClient`,
RestClient-based, configured independently of the chat provider). Vectors stored/searched in
`ec.elastic` (`createIndex` with a `dense_vector` mapping; kNN `search`). A chunker, an
`index#AgentKnowledge` service (with hash-based change detection per practices §3.6), a built-in
`search#Knowledge` tool, and optional auto-retrieval in `AgentRunner`. All data Map-based.

**Tech Stack:** Phase 1 stack + `ec.elastic` (already in Moqui) + an OpenAI-compatible embeddings
HTTP endpoint via `RestClient`. No new libraries.

**Depends on:** Phase 1 (`AgentRunner`, `AiAgentKnowledge`, `AiTool` catalog, `RestClient` usage
in providers), Phase 5 optional (a knowledge screen). Requires a running ElasticSearch/OpenSearch
(the runtime's `runtime/elasticsearch|opensearch`). **Inherits earlier phases' unverified
assumptions, AND adds the two least-verifiable surfaces in the whole plan set: the exact
`ec.elastic` dense_vector mapping + kNN query shape (ES vs OpenSearch differ by version), and the
embeddings endpoint payload. Verify both against the live cluster before trusting the code.**

**Conventions (binding):** UDM Domain Object Practices Guide — Maps not data classes; hash-based
change detection (§3.6); `index#`/`search#`/`store#` style verbs; effective/active filtering;
`*Tests.groovy` + `MoquiSuite`; no Java/Moqui name conflicts. Provider config in `MoquiConf.xml`.

---

## Design decisions (documented with recommended defaults — change if you disagree)

1. **Embedding provider — separate from chat.** Not all chat providers offer embeddings
   (Anthropic doesn't). Recommended: a **dedicated embedding config** (`ai_embedding_*`) pointing
   at an OpenAI-compatible `/embeddings` endpoint (default model `text-embedding-3-small`, 1536
   dims). Default: configure independently; an agent's chat provider and its embedding provider
   are unrelated.
2. **Vector store — `ec.elastic`.** Per the spec. One index `ai_knowledge` with a `dense_vector`
   field + keyword metadata (`agentName`, `knowledgeId`, `sourceTitle`) + the chunk `text`. Default:
   single shared index, filtered by `agentName` at query time.
3. **Chunking — fixed-size with overlap, v1.** Recommended: ~3500 chars per chunk, ~400-char
   overlap (roughly ~800/100 tokens). Simple, deterministic, good enough. Semantic/markdown-aware
   chunking is a later refinement. Default: fixed-size+overlap.
4. **Retrieval mode — tool by default, auto-inject opt-in.** Recommended: ship `search#Knowledge`
   as a **built-in tool** any agent can be granted (the model decides when to look things up —
   cheaper, controllable). Also support **auto-retrieval**: if `AiAgent.autoRetrieveTopK > 0`, the
   runner retrieves on the user message and prepends results to the system context every turn.
   Default: both available; tool is the primary path, auto-inject is the opt-in flag.
5. **Indexing trigger — on knowledge change + manual.** Recommended: a SECA on
   `create#/update#AiAgentKnowledge` (post-commit, `ignore-error="true"`) calls
   `index#AgentKnowledge`; also exposed as a manual service. Hash-based skip avoids re-embedding
   unchanged knowledge (cost control). Default: SECA + manual, hash-gated.

---

## File Structure (added/changed)

```
runtime/component/moqui-ai/
├── MoquiConf.xml                                   ← MODIFY: ai_embedding_* props (Task 1)
├── entity/AiKnowledgeEntities.xml                  ← AiKnowledgeIndex + extend AiAgent.autoRetrieveTopK (Task 2)
├── src/main/groovy/org/moqui/ai/EmbeddingClient.groovy ← RestClient embeddings (Task 1)
├── src/main/groovy/org/moqui/ai/TextChunker.groovy ← pure chunker (Task 2)
├── src/main/groovy/org/moqui/ai/AgentRunner.groovy ← MODIFY: optional auto-retrieve (Task 3)
├── service/ai/KnowledgeServices.xml                ← index#AgentKnowledge, search#Knowledge (Task 2,3)
├── ai/knowledge.tools.xml                          ← declares search#Knowledge as a built-in tool (Task 3)
├── secas/AiKnowledgeSecas.xml                      ← reindex on knowledge change (Task 2)
└── src/test/groovy/
    ├── MoquiSuite.groovy                           ← MODIFY: add AiKnowledgeTests
    └── AiKnowledgeTests.groovy                     ← Task 4 (chunker unit + guarded integration)
```

---

## Task 1: Embedding config + client

**Files:**
- Modify: `runtime/component/moqui-ai/MoquiConf.xml`
- Create: `runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/EmbeddingClient.groovy`

- [ ] **Step 1: Add embedding config**

Add inside `<moqui-conf>` in `MoquiConf.xml`:
```xml
    <default-property name="ai_embedding_provider" value="openai"/>
    <default-property name="ai_embedding_model" value="text-embedding-3-small"/>
    <default-property name="ai_embedding_base_url" value="https://api.openai.com/v1"/>
    <default-property name="ai_embedding_key" value="" is-secret="true"/>
    <default-property name="ai_embedding_dims" value="1536"/>
    <default-property name="ai_knowledge_index" value="ai_knowledge"/>
```

- [ ] **Step 2: Implement EmbeddingClient (OpenAI-compatible /embeddings)**

`runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/EmbeddingClient.groovy`:
```groovy
package org.moqui.ai

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.moqui.context.ExecutionContext
import org.moqui.util.RestClient

/** Calls an OpenAI-compatible POST {base}/embeddings. Returns List of vectors (List&lt;Double&gt;),
 *  one per input string, in order. Map-based config read from MoquiConf properties. */
class EmbeddingClient {
    static List<List<Double>> embed(ExecutionContext ec, List<String> texts) {
        if (!texts) return []
        String base = ec.resource.expand('${ai_embedding_base_url}', null)
        String model = ec.resource.expand('${ai_embedding_model}', null)
        String key = ec.resource.expand('${ai_embedding_key}', null)
        String body = JsonOutput.toJson([model: model, input: texts])
        RestClient rc = new RestClient().uri(base + "/embeddings").method('POST')
            .contentType("application/json").addHeader("Authorization", "Bearer ${key}").timeout(60).text(body)
        def resp
        try { resp = rc.call() } catch (Exception e) { throw new RuntimeException("Embedding HTTP error: ${e.message}", e) }
        Map json = new JsonSlurper().parseText(resp.text()) as Map
        // OpenAI shape: {data: [{embedding: [...], index: 0}, ...]}
        return ((json.data ?: []) as List<Map>).sort { (it.index ?: 0) as int }
            .collect { (it.embedding as List).collect { (it as Number).doubleValue() } }
    }
}
```
Verify the response shape against your actual embeddings endpoint (OpenAI vs Azure vs local
gateway may differ slightly). This is one of the flagged uncertainties.

- [ ] **Step 3: Commit**

```bash
git add runtime/component/moqui-ai/MoquiConf.xml runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/EmbeddingClient.groovy
git commit -m "feat(moqui-ai): embedding config + OpenAI-compatible EmbeddingClient"
```

---

## Task 2: Chunker + index entity + index#AgentKnowledge + reindex SECA

**Files:**
- Create: `runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/TextChunker.groovy`
- Create: `runtime/component/moqui-ai/entity/AiKnowledgeEntities.xml`
- Create: `runtime/component/moqui-ai/service/ai/KnowledgeServices.xml`
- Create: `runtime/component/moqui-ai/secas/AiKnowledgeSecas.xml`

- [ ] **Step 1: Pure chunker (unit-testable)**

`runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/TextChunker.groovy`:
```groovy
package org.moqui.ai

/** Fixed-size character chunking with overlap. Deterministic, dependency-free. */
class TextChunker {
    static List<String> chunk(String text, int maxChars = 3500, int overlap = 400) {
        if (!text) return []
        if (text.length() <= maxChars) return [text]
        List<String> out = []
        int start = 0
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length())
            out.add(text.substring(start, end))
            if (end >= text.length()) break
            start = end - overlap                  // step forward with overlap
        }
        return out
    }
}
```

- [ ] **Step 2: Index-tracking entity + autoRetrieve flag**

`runtime/component/moqui-ai/entity/AiKnowledgeEntities.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<entities xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/entity-definition-3.xsd">

    <!-- hash-based change detection (§3.6): skip re-embedding unchanged knowledge -->
    <entity entity-name="AiKnowledgeIndex" package="moqui.ai">
        <field name="agentName" type="id" is-pk="true"/>
        <field name="knowledgeId" type="id" is-pk="true"/>
        <field name="contentHash" type="text-medium"/>
        <field name="chunkCount" type="number-integer"/>
        <field name="lastIndexedDate" type="date-time"/>
    </entity>

    <extend-entity entity-name="AiAgent" package="moqui.ai">
        <field name="autoRetrieveTopK" type="number-integer"><description>0/null = off; N = auto-retrieve top-N chunks into system context each turn</description></field>
    </extend-entity>
</entities>
```
(Assumes Phase 1's `AiAgentKnowledge` has a `knowledgeId` PK + `content` + `agentName` + active
status. If the Phase 1 entity differs, reconcile.)

- [ ] **Step 3: index#AgentKnowledge service**

`runtime/component/moqui-ai/service/ai/KnowledgeServices.xml` (index service; search added Task 3):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<services xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/service-definition-3.xsd">

    <service verb="index" noun="AgentKnowledge" authenticate="true">
        <in-parameters><parameter name="agentName" required="true"/></in-parameters>
        <out-parameters><parameter name="chunksIndexed" type="Integer"/></out-parameters>
        <actions>
            <script><![CDATA[
                import org.moqui.ai.*
                String index = ec.resource.expand('${ai_knowledge_index}', null)
                int dims = (ec.resource.expand('${ai_knowledge_dims}', null) ?: ec.resource.expand('${ai_embedding_dims}', null) ?: "1536") as int
                // ensure index exists with a dense_vector mapping (idempotent)
                if (!ec.elastic.default.indexExists(index)) {
                    ec.elastic.default.createIndex(index, [properties: [
                        agentName:[type:'keyword'], knowledgeId:[type:'keyword'], sourceTitle:[type:'text'],
                        text:[type:'text'], vector:[type:'dense_vector', dims:dims]]], null)
                }
                int total = 0
                def knowList = ec.entity.find("moqui.ai.AiAgentKnowledge").condition("agentName", agentName).list()
                for (def k in knowList) {
                    String content = k.content as String
                    String hash = org.moqui.util.StringUtilities.sha256Hex ? org.moqui.util.StringUtilities.sha256Hex(content) : content.md5()  // verify hash util
                    def idx = ec.entity.find("moqui.ai.AiKnowledgeIndex").condition("agentName", agentName).condition("knowledgeId", k.knowledgeId).one()
                    if (idx != null && idx.contentHash == hash) continue   // unchanged -> skip (cost control)
                    List<String> chunks = TextChunker.chunk(content)
                    List<List<Double>> vectors = EmbeddingClient.embed(ec, chunks)
                    List<Map> docs = []
                    chunks.eachWithIndex { String ch, int i ->
                        docs.add([_id: "${agentName}_${k.knowledgeId}_${i}", agentName: agentName, knowledgeId: k.knowledgeId,
                                  sourceTitle: k.title, text: ch, vector: vectors[i]])
                    }
                    // (optional) delete prior chunks for this knowledgeId before re-indexing
                    ec.elastic.default.bulkIndex(index, "_id", docs)
                    ec.service.sync().name("store#moqui.ai.AiKnowledgeIndex").parameters([agentName: agentName,
                        knowledgeId: k.knowledgeId, contentHash: hash, chunkCount: chunks.size(),
                        lastIndexedDate: ec.user.nowTimestamp]).call()
                    total += chunks.size()
                }
                chunksIndexed = total
            ]]></script>
        </actions>
    </service>
</services>
```
Flagged uncertainties to verify on first run: the `ec.elastic.default` accessor + `createIndex`/
`indexExists`/`bulkIndex` signatures (confirmed shapes exist on `ElasticFacade`, but confirm
`bulkIndex(index, idField, docList)` + the `_id` handling), the `dense_vector` mapping syntax for
your ES/OpenSearch version, and the SHA-256 helper (`StringUtilities` — confirm the actual method;
fall back to `java.security.MessageDigest`).

- [ ] **Step 4: Reindex SECA on knowledge change**

`runtime/component/moqui-ai/secas/AiKnowledgeSecas.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<secas xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/service-eca-3.xsd">
    <seca service="create#moqui.ai.AiAgentKnowledge" when="post-commit">
        <actions><service-call name="ai.KnowledgeServices.index#AgentKnowledge" in-map="[agentName:agentName]" ignore-error="true"/></actions>
    </seca>
    <seca service="update#moqui.ai.AiAgentKnowledge" when="post-commit">
        <actions><service-call name="ai.KnowledgeServices.index#AgentKnowledge" in-map="[agentName:agentName]" ignore-error="true"/></actions>
    </seca>
</secas>
```
Per practices §3.3: `post-commit` (side effect after commit), `ignore-error="true"` so an indexing
failure never rolls back the knowledge write. Confirm the SECA file is auto-discovered (component
`secas/` dir) or register it in `MoquiConf.xml`.

- [ ] **Step 5: Commit**

```bash
git add runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/TextChunker.groovy \
        runtime/component/moqui-ai/entity/AiKnowledgeEntities.xml \
        runtime/component/moqui-ai/service/ai/KnowledgeServices.xml \
        runtime/component/moqui-ai/secas/AiKnowledgeSecas.xml
git commit -m "feat(moqui-ai): chunk+embed+index knowledge to ec.elastic (hash-gated) + reindex SECA"
```

---

## Task 3: search#Knowledge tool + auto-retrieval

**Files:**
- Modify: `runtime/component/moqui-ai/service/ai/KnowledgeServices.xml`
- Create: `runtime/component/moqui-ai/ai/knowledge.tools.xml`
- Modify: `runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/AgentRunner.groovy`

- [ ] **Step 1: search#Knowledge service (kNN over ec.elastic)**

Add to `KnowledgeServices.xml`:
```xml
    <service verb="search" noun="Knowledge" authenticate="true">
        <in-parameters>
            <parameter name="agentName" required="true"/>
            <parameter name="query" required="true"><description>What to look up in the agent's knowledge base.</description></parameter>
            <parameter name="topK" type="Integer"><default-value>5</default-value></parameter>
        </in-parameters>
        <out-parameters><parameter name="chunks" type="List"><description>[{text, score, sourceTitle}]</description></parameter></out-parameters>
        <actions>
            <script><![CDATA[
                import org.moqui.ai.EmbeddingClient
                String index = ec.resource.expand('${ai_knowledge_index}', null)
                List<Double> qv = EmbeddingClient.embed(ec, [query])[0]
                // kNN search filtered by agentName. Verify exact knn syntax for your ES/OpenSearch version.
                Map searchMap = [size: topK, knn: [field: "vector", query_vector: qv, k: topK, num_candidates: (topK*10),
                    filter: [term: [agentName: agentName]]], _source: ["text","sourceTitle"]]
                List<Map> hits = ec.elastic.default.searchHits(index, searchMap)
                chunks = hits.collect { [text: it._source?.text, sourceTitle: it._source?.sourceTitle, score: it._score] }
            ]]></script>
        </actions>
    </service>
```
The kNN query shape (`knn` top-level vs inside `query`, `num_candidates`, filter placement) varies
by ElasticSearch/OpenSearch version — **verify against the running cluster**. This is the single
most version-sensitive piece in the plan set.

- [ ] **Step 2: Declare search#Knowledge as a built-in tool**

`runtime/component/moqui-ai/ai/knowledge.tools.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<tools>
    <tool service="ai.KnowledgeServices.search#Knowledge"
          description="Search the agent's knowledge base for passages relevant to a query. Call this when you need company-specific facts you don't already have."/>
</tools>
```
Because it's a normal `ai/*.tools.xml`, the Phase 1 loader picks it up into the catalog; grant it
to any agent via `AiAgentTool` (or auto-grant — decision below). The LLM then calls it like any
tool. Note: `search#Knowledge` takes `agentName` — the runner should inject the current agentName
rather than trust the model; have `dispatchTool` add `agentName` for this built-in, OR make
`agentName` default from context. Recommended: the runner sets `agentName` on the tool args for
this built-in so the model only supplies `query`/`topK`. Flag this as an implementation detail to
wire in `AgentRunner.dispatchTool` (a small allowlist of "framework-injected params").

- [ ] **Step 3: Optional auto-retrieval in AgentRunner**

In `AgentRunner.run` (and `continueAgent` start), before the loop, if the agent opts in:
```groovy
        // auto-retrieval: prepend top-K knowledge chunks to the system context
        String systemContext = agent.systemPrompt as String
        int topK = (agent.autoRetrieveTopK ?: 0) as int
        if (topK > 0) {
            Map kn = ec.service.sync().name("ai.KnowledgeServices.search#Knowledge")
                .parameters([agentName: agentName, query: userMessage, topK: topK]).call()
            List<Map> chunks = (kn.chunks ?: []) as List<Map>
            if (chunks) systemContext = systemContext + "\n\n## Retrieved knowledge\n" +
                chunks.collect { "- ${it.text}" }.join("\n")
        }
        // ...use systemContext (instead of agent.systemPrompt) when building the request Map
```
Make the loop use this computed `systemContext`. Keep it cheap: auto-retrieval runs once per run
on the user message, not per turn (document the choice; per-turn retrieval is a refinement).

- [ ] **Step 4: Commit**

```bash
git add runtime/component/moqui-ai/service/ai/KnowledgeServices.xml runtime/component/moqui-ai/ai/knowledge.tools.xml \
        runtime/component/moqui-ai/src/main/groovy/org/moqui/ai/AgentRunner.groovy
git commit -m "feat(moqui-ai): search#Knowledge tool + optional auto-retrieval"
```

---

## Task 4: Tests (chunker unit + guarded integration)

RAG can't be fully unit-tested without a live embeddings endpoint + ES. So: a **pure** chunker
test always runs; an **integration** test runs only when `ai_embedding_key` is set and ES is up
(guarded like the Phase 1 live-Anthropic smoke).

**Files:**
- Create: `runtime/component/moqui-ai/src/test/groovy/AiKnowledgeTests.groovy`
- Modify: `MoquiSuite.groovy` (add `AiKnowledgeTests.class`)

- [ ] **Step 1: Chunker unit + guarded RAG roundtrip**

`runtime/component/moqui-ai/src/test/groovy/AiKnowledgeTests.groovy`:
```groovy
import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.TextChunker

class AiKnowledgeTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() { ec = Moqui.getExecutionContext() }
    def cleanupSpec() { if (ec != null) ec.destroy() }

    def "chunker splits with overlap and preserves coverage"() {
        given: String text = ("abcdefghij" * 800)   // 8000 chars
        when: List<String> chunks = TextChunker.chunk(text, 3500, 400)
        then:
        chunks.size() >= 3
        chunks.first().length() == 3500
        chunks.every { it.length() <= 3500 }
        // overlap: end of chunk0 reappears at start of chunk1
        chunks[1].startsWith(chunks[0].substring(3500 - 400))
    }

    def "short text is a single chunk"() {
        expect: TextChunker.chunk("hello", 3500, 400) == ["hello"]
    }

    @Requires({ System.getenv("ai_embedding_key") })
    def "live: index then search returns the relevant chunk"() {
        given:
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "KnowAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "x", statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        ec.entity.makeValue("moqui.ai.AiAgentKnowledge").setAll([agentName: "KnowAgent", knowledgeId: "K1",
            title: "Returns policy", content: "Our return window is 45 days for unworn items with a receipt.",
            statusId: "AI_KNOW_ACTIVE"]).createOrUpdate()
        when:
        ec.service.sync().name("ai.KnowledgeServices.index#AgentKnowledge").parameters([agentName: "KnowAgent"]).call()
        Map out = ec.service.sync().name("ai.KnowledgeServices.search#Knowledge")
            .parameters([agentName: "KnowAgent", query: "how many days to return?", topK: 3]).call()
        then:
        (out.chunks as List).any { (it.text as String)?.contains("45 days") }
        cleanup:
        ec.entity.find("moqui.ai.AiAgentKnowledge").condition("agentName", "KnowAgent").deleteAll()
        ec.entity.find("moqui.ai.AiKnowledgeIndex").condition("agentName", "KnowAgent").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "KnowAgent").deleteAll()
    }
}
```
(The integration test assumes `AiAgentKnowledge` has `knowledgeId`/`title`/`content`/`statusId`
with an `AI_KNOW_ACTIVE` status from Phase 1's knowledge slice — reconcile field/status names.)

- [ ] **Step 2: Run the suite; commit**

Run: `./gradlew :runtime:component:moqui-ai:test` (chunker tests run; live RAG test skipped without keys/ES)
```bash
git add runtime/component/moqui-ai/src/test/groovy/AiKnowledgeTests.groovy runtime/component/moqui-ai/src/test/groovy/MoquiSuite.groovy
git commit -m "test(moqui-ai): chunker unit + guarded RAG roundtrip"
```

---

## Phase Done — Definition of Done
- An agent's `AiAgentKnowledge` is chunked, embedded, and indexed into `ec.elastic` (hash-gated to
  skip unchanged knowledge); reindex fires automatically on knowledge change.
- `search#Knowledge` is a built-in tool any agent can be granted; the model can look things up.
- `AiAgent.autoRetrieveTopK > 0` auto-injects top-K chunks into the system context per run.
- Chunker is unit-tested; the embed→index→search roundtrip is covered by a guarded integration test.

## NOT in this phase
- **Re-ranking / hybrid (keyword+vector) search** — pure kNN for v1.
- **Per-turn auto-retrieval** (we retrieve once on the user message) — refinement.
- **Chunk deletion on knowledge delete** (orphan cleanup) — add a delete SECA; noted as follow-up.
- **Semantic/markdown-aware chunking** — fixed-size+overlap for now.
- **Non-OpenAI embedding providers** (local/Ollama embeddings) — config points at an
  OpenAI-compatible endpoint; a true provider abstraction for embeddings is a follow-up.
- **The two flagged version-sensitive surfaces** (dense_vector mapping + kNN query shape; embeddings
  payload) MUST be verified against the live cluster/endpoint — they are the highest-risk unknowns
  in the entire plan set.
