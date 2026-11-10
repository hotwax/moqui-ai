# Vector Search Storage: Choosing a Pluggable Strategy

## The Question After Embeddings

The Embedder converts text into vectors. Those vectors need to go somewhere and be
searchable. That is a separate problem — and it has more than one answer.

We evaluated three options, found a familiar analogy, and landed on an interface-first
design that lets each deployment choose what fits.


## What We Evaluated

**JVector** — A pure Java library. The vector index runs inside the JVM process, no
external server required. Backed by DataStax, Apache 2.0 licensed. Uses the DiskANN
algorithm: disk-first, lower RAM footprint than HNSW at comparable recall.

**pgvector** — A PostgreSQL extension that adds vector column types and HNSW indexing.
If the deployment already runs PostgreSQL, this adds vector search to existing
infrastructure with no new servers.

**OpenSearch** — A distributed search engine with built-in vector search. If the
deployment already runs OpenSearch or Elasticsearch, vector search is another query
type on existing infrastructure.


## The Insight: The H2 Model

Every OFBiz developer knows H2. It ships with the framework, starts with the
application, requires zero configuration. Development and testing work immediately.
In production, teams switch to PostgreSQL or MySQL or Oracle. The switch is
configuration — the Entity Engine abstracts the database, so application code
never knows which one is running.

Vector search needs the same model.

| | Relational database | Vector search |
|---|---|---|
| Dev / zero config | H2 | JVector |
| Production (existing DB) | PostgreSQL | pgvector |
| Production (search infra) | — | OpenSearch |

JVector is the H2 of vector search. No installation, no process management, no DBA.
A developer clones the repository and semantic search works immediately. Production
deployments choose the option that matches their existing infrastructure.


## The Decision: A VectorStore Interface

Rather than writing application code against any specific vector store, we define
a `VectorStore` interface. Each implementation handles the differences in storage,
indexing, and query format. Application code calls the interface and never sees
which implementation is running.

```
VectorStore  (interface)
  store(id, vector, metadata)
  search(queryVector, topK)
  delete(id)

JVectorStore          ← embedded, zero config, dev and small deployments
PgVectorStore         ← PostgreSQL + pgvector extension
OpenSearchVectorStore ← OpenSearch / Elasticsearch
```

This mirrors the pattern already used elsewhere in OFBiz: the Entity Engine
abstracts the relational database, the AI framework abstracts the LLM provider.
The VectorStore does the same for vector search.


## When to Use Each

**JVector** — when you want to start without any infrastructure. Also appropriate
for small production deployments where a separate search server adds more operational
cost than value. The DiskANN algorithm keeps RAM usage manageable as the index grows.

**pgvector** — when the deployment already runs PostgreSQL. No new server, no new
backup strategy, no new monitoring. Vector columns live in the same database as
OFBiz entities.

**OpenSearch** — when the deployment already runs OpenSearch or Elasticsearch for
full-text search, logging, or analytics. Vector search becomes one more query type
on existing infrastructure.


## The Contract

The interface is intentionally narrow:

- Vectors are arrays of floats
- Documents are identified by a string id
- Metadata is a flat string map
- Search returns a ranked list of ids with scores

Advanced features of each platform — approximate vs. exact search, pre-filter by
metadata, multiple indices — are handled through implementation configuration, not
the interface. The interface covers what every implementation can promise.


## Design Principles

- **Zero-config default.** JVector means a fresh install works without any vector
  infrastructure. This lowers the barrier for contributors, evaluators, and
  development environments.

- **No lock-in.** Vectors are always regenerable from source data in OFBiz entities.
  Switching implementations means re-indexing, not migrating schema or reformatting
  records.

- **Match existing infrastructure.** The choice of implementation should follow
  what a deployment already operates — not create new dependencies.

- **Same abstraction pattern.** VectorStore follows the same interface-first design
  as the Embedder and the LLM provider. One pattern, three domains.
