# Local validation record

Verified against the isolated runtime under `maarg-oms/client-knowledge-poc`.

- 18 assertions passed using the real Moqui services and H2 database (`verify-final.log`), including reader/curator authorization, client isolation, shared-document restrictions, idempotency, delayed-event rejection, genuine later reversion, supersession, uncited/stale citation refusal, question capture, MCP execution and audit.
- Official MCP Python SDK 2.3.0 initialized the Moqui MCP dispatcher over HTTP, discovered six allowed tools, queried real Rails evidence and shared documentation, and observed cross-client refusal (`mcp-sdk-verification.log`).
- HTTP origin and Host checks rejected foreign origins/hosts. Calling the generic operational `execute_query` tool was refused by the local adapter.
- Native Moqui `client_knowledge_refresh` completed without error. The latest verified run started at `2026-10-08T13:43:12.662Z` and ended at `2026-10-08T13:43:25.426Z`. It collected GitHub/local/docs sources and ingested the inbox. The job is configured for 15-minute refresh while the server runs, with an initial run at startup. No topic/recipient is configured.
- Readback after refresh: 109 current Rails source sections and 31 current documentation sections; two locally captured questions, including one cited draft with `needs_human` for unproven deployment.
- Browser validation displayed issue #331 and PR #332 for the payment-mapping question and then showed the captured question. The refreshed home page showed the completed job and counts.
- All three new XML files parsed. The focused Moqui audit had only informational missing-short-alias notes, consistent with neighboring AI entities. No trailing whitespace in the 23 checked source files.
- Codex native CLI readback confirms `rails-knowledge-local` is enabled at the local HTTP URL. The existing chat's named-tool reload is not independently proven; restart/reload connections or begin a new chat if it is not listed.

The existing framework/component bootstrap emits unrelated missing ElasticSearch and optional entity-reference warnings; the listed knowledge services and MCP requests were exercised successfully despite those warnings. This record does not certify other OMS tools or the full Moqui AI test suite.

Everything remains local. No GitHub push, PR, merge, deployment, email, Chat or Slack message was performed.
