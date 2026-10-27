# Restricted document MCP acceptance client

`document_mcp_bridge.py` is a standard-library stdio MCP bridge to the fixed isolated
endpoint `http://127.0.0.1:8093/mcp/json`. It supports initialize, ping, document tool
calls, and authenticated document resource list/read/template methods. It does not
start a server, edit source, use SQL, or expose generic entity/service tools.

Pass an existing private dotenv file with `OMS_TEST_URL`, `OMS_TEST_USER` and
`OMS_TEST_PASSWORD`. The source URL must be HTTP loopback; the bridge always uses
port 8093 and `/mcp/json`. It parses this file as data, keeps Basic authentication
in memory, disables proxies and redirects, and never prints credentials or exports
them to the actor environment.

The trusted attachment manifest is private operator input, outside this checkout:

```json
{
  "attachments": [
    {
      "id": "supplied-font",
      "kind": "font",
      "displayName": "Supplied regular font face",
      "localPath": "/absolute/private/attachments/regular.ttf",
      "filename": "regular.ttf",
      "mimeType": "font/ttf"
    }
  ]
}
```

Kinds are `font` (TTF, 8 MiB maximum) and `image` (PNG/JPEG, 5 MiB maximum). At most
16 supplied attachments are accepted. The actor sees IDs/descriptions/kinds/MIME
only. `forward_document_attachment(attachmentId)` computes SHA-256 and transfers the
provided bytes using `begin_document_upload`, bounded `append_document_upload`
chunks, and `finish_document_upload`, then returns the finalized upload handle.
Raw upload tools are hidden and rejected when called by the actor. No actor path,
URL, filename, or model-generated base64 is accepted by this helper. Font/image
format, ownership and embedding validation remain the upstream catalog's job.

```sh
python3 tools/document_mcp_bridge.py \
  --login-env /private/local-login.env \
  --attachment-manifest /private/client-attachments.json \
  --audit-log /private/run/bridge-audit.jsonl
```

The audit log contains method/tool/outcome/chunk byte counts only. It never stores
arguments, source, binaries, font/alias/license profiles, customer payloads or paths.

`run_document_mcp_acceptance.py` prepares a reviewable fresh Codex run by default:

```sh
python3 tools/run_document_mcp_acceptance.py \
  --login-env /private/local-login.env \
  --attachment-manifest /private/client-attachments.json \
  --request-file /private/business-request.txt \
  --output-dir /private/prepared-run
```

After the operator confirms that port 8093 is ready, repeat with `--run` and a new
private output directory. Optional `--reference-image` supplies an approved non-PII
PNG to the initial prompt; optional `--model` preserves an explicit model choice.
The harness reuses existing `CODEX_HOME` authentication without copying it. It uses
`--ignore-user-config`, `--ephemeral`, JSON events, a fresh temporary working
directory, read-only sandbox, and explicit disabled shell/execution, browser,
computer, web, app/plugin, memory, image/file-view, and multi-agent features.
Only the restricted bridge is configured. The prepared command has a transient
working directory; use the harness to create a fresh directory for the actual run.

The stable `code_mode_host` stays enabled because current models dispatch MCP
through its isolated JavaScript orchestration host. The optional `code_mode`
feature stays off. A read-only live probe reported `require`, `process`, `fetch`,
`Deno`, `Bun`, `WebSocket`, and `fs` as undefined; no Node or direct filesystem/
network API is supplied by this configuration. CLI JSON events expose nested MCP
calls rather than an outer JavaScript execution item, so the audit still accepts
only the configured document MCP calls and does not broadly allow arbitrary exec.
This probe is a capability check, not an exhaustive sandbox security assessment.

Client result representations vary: use `structuredContent`, client-normalized
`structured_content`, or parse the JSON text in `content[0].text`. A missing field
must not be interpreted as empty business data. Native document DTOs are unwrapped;
template rows use `templates[].documentTemplateId`.

Private output includes the prompt, launch configuration, JSON transcript, stderr,
bridge metadata audit, and a transcript tool check. The check rejects command/file/
web/foreign-MCP calls and unknown tool event types. Configuration and pure tests do
not prove real client acceptance: inspect the actual transcript, active client
version and visual PDF/PNG results after running against the real backend.

Pure checks (no sockets, Moqui bootstrap, real credentials or model execution):

```sh
python3 -m unittest discover -s tools -p 'test_document_mcp_bridge.py' -v
```

The harness flags were checked against local Codex CLI help/features and the
[official configuration reference](https://learn.chatgpt.com/docs/config-file/config-reference),
[non-interactive mode](https://learn.chatgpt.com/docs/non-interactive-mode), and
[MCP configuration](https://learn.chatgpt.com/docs/extend/mcp?surface=cli).
