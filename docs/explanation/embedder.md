# Embedder: Semantic Search for the OFBiz AI Framework

## The Problem

AI agents answer questions using knowledge — domain guides, business rules, product
data, order history. Injecting all of it into every prompt does not scale and adds
noise. What agents need is the right content for the current question, retrieved
automatically.

Semantic search solves this. The Embedder is how OFBiz does it.


## What Semantic Search Is

Traditional search finds documents that contain your words. Semantic search finds
documents that mean the same thing, even when the words differ. A query about
"jacket for cold weather" retrieves documents about "winter outerwear" and
"insulated coats" without those exact words appearing.

It works by converting text into a vector — a list of numbers representing meaning.
Texts with similar meaning produce vectors that are mathematically close. Two things
make this work: **embedding** (text → vector, done by the Embedder) and a
**vector index** (storing and searching those vectors).


## The Embedder Interface

One interface, one responsibility: convert text into vectors.

```
embed(texts, inputType) → embeddings
```

`inputType` is either `document` (content being indexed) or `query` (a question
being searched). Some models generate different vectors for each, optimized for
storage versus retrieval. The Embedder passes this hint to the provider; providers
that don't distinguish ignore it.


## Why the OpenAI-Compatible API

Rather than writing a separate adapter for every embedding vendor, OFBiz adopts
the OpenAI embeddings API format as its wire standard. Most modern providers speak
it natively. Switching vendors is a configuration change:

```
base_url  →  https://api.openai.com/v1      OpenAI (default)
             https://api.voyageai.com/v1    Voyage AI
             https://api.mistral.ai/v1      Mistral
model     →  text-embedding-3-small / voyage-3 / mistral-embed
```

The embedding configuration is independent of the LLM configuration — a deployment
can use one vendor for generating answers and a different vendor for embeddings.


## Where It Fits

OFBiz AI has two kinds of calls:

```
LLM integration  →  generates text (answers, summaries, decisions)
Embedder         →  generates vectors (for search and retrieval)
```

They never interact directly. The LLM sees plain text. The Embedder produces
vectors. They connect through content: the Embedder indexes it, the LLM reads it.


## Workflow 1 — Knowledge Base Retrieval

An operator asks: *"What is the process for handling a short-ship from a vendor?"*

```
Question → embed as query → search vector index → retrieve relevant documents
         → inject into agent context → LLM generates grounded answer
```

The knowledge base is indexed once when content is published. At query time only
the relevant documents are retrieved. The agent sees focused context, not the
entire library.


## Workflow 2 — Product and Order RAG

A customer asks: *"Do you have waterproof hiking boots under $150?"*

```
Question → embed as query → search vector index → retrieve matching product IDs
         → load product records from OFBiz entities → LLM generates response
```

Products are indexed when created or updated. The vector index is a search index,
not a system of record — the source of truth stays in OFBiz entities. Vectors
are always regenerable from the original data.


## Design Principles

- **One interface, many providers.** The Embedder contract is stable. Switching
  providers is configuration, not code.

- **Independent of the LLM.** Embedding vendor and LLM vendor are separate choices
  with separate configuration.

- **No lock-in.** The corpus lives in OFBiz. Re-indexing with a new provider takes
  minutes and costs cents. Nothing is lost if the vector index is rebuilt.
