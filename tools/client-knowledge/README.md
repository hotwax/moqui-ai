# Rails client knowledge POC

A local Moqui database provides product documentation and Rails project evidence to Codex through MCP. Codex is the answering model. The local browser is an evidence viewer with automatic question capture, not a separate generative chatbot.

## Open and use

- Viewer: http://127.0.0.1:8097/
- MCP: http://127.0.0.1:8097/mcp/json
- Codex server name: `rails-knowledge-local` in `~/.codex/config.toml`.
- Example: **Use rails-knowledge-local to explain Rails' warehouse-first routing. Cite the decision date, distinguish intended configuration from verified deployment, and record the question and draft answer locally.**
- The current Codex chat may need its MCP connections reloaded, or a new chat, before newly configured tools appear. Configuration and SDK transport verification are distinct from seeing a named tool in an existing chat.

Tools: `search_documentation`, `search_client_knowledge`, `get_client_evidence`, `record_client_question`, `list_client_questions`, `ingest_client_evidence`.

## Data and learning

Native entities are `moqui.ai.AiClientSpace`, `AiClientMember`, `AiClientEvidence`, and `AiClientQuestion`. Services live in `ai.ClientKnowledgeServices`; native `*.mcp.xml` declarations use the existing catalog, dispatcher, executor and `AiToolCall` audit. No parallel application database or vector service is used.

`GLOBAL` contains public product documentation. `rails` contains only Rails sources. Explicit user membership is required in every client service, including for administrators. Curators ingest/review; readers search and record questions. Shared product sources are included in a client's search; other clients' data is excluded. Source text is untrusted evidence and is never injected into a privileged system prompt.

A source version stores its URL, title, type, source date, ingestion date, body hash, current/superseded state and review provenance. Repeated content is a no-op. A changed source creates a new observation and supersedes the previous version. Delayed changed snapshots are rejected. Questions and generated draft answers are stored separately, so a model cannot teach itself that its own answer is established policy. An unanswered or uncertain question remains `open` or `needs_human`. Human confirmation uses the curator-only `review#Evidence` service, intentionally absent from MCP.

The POC imports:

- 24 selected published pages from the HotWax documentation index, split into bounded sections. For documentation, `sourceDate` is the snapshot retrieval time because GitBook's Markdown response does not establish the original modification date.
- The 100 most recently updated issues/PRs from `hotwax/rails`, with PR merge metadata and up to 100 issue comments each. The cursor avoids fetching unchanged discussions again. PR status is evidence about development, not proof of deployment.
- The local Rails routing decision record, explicitly dated August 13, 2026.
- One real Read AI kickoff summary and its key questions, dated July 16, 2026. This is a summary import, not a full transcript collection or continuous Read AI subscription.

`client_knowledge_refresh` is a native Moqui `ServiceJob` scheduled every 15 minutes while the POC server runs. It reads GitHub, the selected local document, published docs and the local JSON inbox. `ServiceJobRun` records completion/failure; no notification topic or recipients are configured. The server also requests an initial job run at launch. Other seeded jobs are paused in this isolated database.

Additional meeting summaries or client interactions can be supplied through `ingest_client_evidence` or inbox snapshots. There is no autonomous Slack/Chat/email subscription in this POC and no sending capability. The browser captures each question automatically. Codex should explicitly record its final draft and citations with `record_client_question`.

## Local runtime

Code branch: `codex/rails-client-knowledge-poc` in the existing `moqui-ai` checkout. Client bodies, meeting content, database files, logs and screenshots stay outside the repository under:

`/Users/adityapatel/Documents/GitHub/moqui/maarg-oms/client-knowledge-poc`

The runner loads the existing built Moqui framework and component jars, with a separate H2 database, transaction journal and configuration. It links only Moqui AI and its required OMS/Maarg components. The operational MySQL database is not used. No model-provider API keys are configured or needed.

`serve.groovy` is a development-only loopback HTTP adapter over the **real native Moqui MCP dispatcher**, with an allowlist of six knowledge tools. It maps local callers to the dedicated `knowledge-local` Moqui account without a stored password. Only `127.0.0.1:8097` is bound; Host and Origin are checked. Any local process can use this POC account. This is not production authentication or permission to expose the port. The standard deployed Moqui `/mcp/json` endpoint can discover the same declaration file under its normal authentication.

From this directory, run:

```sh
python3 start.py
```

It refuses to start if port 8097 is already occupied. `server.pid` identifies the process it started. To stop it, verify that PID still owns this POC command and port before sending SIGTERM. Wait for shutdown before running bootstrap, verification or another server: Moqui's transaction journal and H2 database cannot be shared by two processes.

For a fresh local setup (machine-specific paths in `setup.py`):

```sh
python3 setup.py
python3 run.py bootstrap
python3 run.py jobs
python3 collect.py local
python3 collect.py github
python3 collect.py docs
```

Set `scheduled_job_check_time` to `30` in the generated `runtime/conf/KnowledgeConf.xml` to enable the job checker (setup starts with it disabled). Then start the server. The `KNOWLEDGE_POC_HOME` and `KNOWLEDGE_JAVA` environment variables override the runner paths. The collector uses the existing native `gh` authentication; it never copies or prints its credentials.

## Verification

With the server stopped: `python3 run.py verify` exercises real services, database versioning, authorization, native MCP catalog/execution and audit. With the server running: `uv run --with mcp==2.3.0 python verify_mcp.py` exercises the official Python SDK's HTTP handshake, discovery, cited search and client isolation. The SDK verification is pinned to version 2.3.0.

The browser test searches payment mappings, displays Rails issue #331 and PR #332 with source dates and saves the question in the database. The scheduled collector must also have a successful `ServiceJobRun`; merely loading the catalog is not acceptance.

## POC boundaries

This proves local retrieval and evidence accumulation for an L1 assistant. It does not replace business approval, escalation ownership, production incident investigation or a client-facing account representative yet. Claims of completed UAT, deployments, contractual commitments and live operational status still need current authoritative evidence.

Search uses bounded SQL keyword matching (250 candidate rows, up to 20 results), not semantic retrieval. Source retractions/deletions and shorter replacement documents do not yet retire orphaned chunks. The GitHub history, comment and document corpus are intentionally bounded; absence from this corpus is not absence from the project. The obvious-credential check is not a comprehensive redaction system. Inherited Moqui AI entity permissions and generic operational tool surfaces need a separate deployment authorization review before multi-client hosting; this adapter exposes only its explicit knowledge allowlist.

The POC makes inbound reads from GitHub/docs and local writes only. It does not send messages, open PRs, push code, merge, deploy or modify any remote client system.
