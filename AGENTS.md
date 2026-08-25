# Agent guide: moqui-ai

Instructions for AI coding agents (Claude Code, Codex, Gemini CLI, others) working in or
with this component. In a Moqui deployment this file lives at
`runtime/component/moqui-ai/AGENTS.md` — read it whenever you are asked to create, change,
or debug MCP tools.

The full design record with all decisions is `docs/specs/2026-08-23-mcp-server-design.md`.

## What the MCP server is

This component serves the Model Context Protocol at `/mcp/json`. External AI clients
discover tools and run them as ordinary Moqui service calls under the caller's own Moqui
authorization. Tools are declared in `*.mcp.xml` files — one file per component, no code
per tool. This is the MCP analogue of Moqui's `*.rest.xml`.

## Defining a tool: the recipe

You will usually be asked something like "expose order lookup as an MCP tool". Follow
these steps in order.

### 1. Pick or write the backing service

The single most important choice. Rules:

- READ-ONLY service for anything a model will call freely.
- Small and tidy beats big and complete: an LLM reads the whole result. A service
  returning 6 fields per row beats one returning 60. If no tidy service exists, write one
  (plain XML actions, `entity-find` + a small projection loop) rather than exposing a
  heavy internal service.
- Test the service FIRST, directly (`ec.service.sync()` in a test, or via curl if REST-
  exposed). A broken backing service produces `isError` results, not catalog errors —
  you will waste time debugging the wrong layer.
- The tool schema is derived from the service's in/out parameter definitions, and the
  model reads the `description` elements. Write real descriptions on the parameters.

### 2. Declare the tool

Create or extend `<yourcomponent>/service/<yourcomponent>.mcp.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<mcp xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
     xsi:noNamespaceSchemaLocation="component://moqui-ai/xsd/mcp-api-1.xsd"
     name="yourcomponent" version="1.0.0" description="One line on this tool group.">

    <tool name="get_sales_orders" title="Get Sales Orders" effect="read" scope="mcp:orders"
          description="Recent sales orders, newest first: orderId, status, date, total.
              Optionally filter by statusId (e.g. ORDER_APPROVED); cap with maxOrders (default 20).">
        <service name="yourcomponent.OrderServices.get#OrderSummaryList"/>
    </tool>
</mcp>
```

The rules that govern this file:

| Rule | Meaning |
|------|---------|
| Declaring a tool IS the exposure decision | Whatever you put here becomes callable. You own it. There is no second gate in the file. |
| `effect="read"` | The only way to claim the read-only hint clients use to skip confirmation prompts. Omit it (or anything else) and clients treat the tool as potentially destructive — the safe default. Never claim `read` on a service that writes. |
| No `<parameter>` children | Every in-parameter of the service is exposed. |
| Listed `<parameter name="..."/>` children | ONLY the listed ones are exposed. Use this to narrow a wide service. |
| `<parameter name="x" fixed="value"/>` | Injected server-side on every call; never visible to and never overridable by the caller. Use for tenant/store ids. Supports `${property}` expansion. |
| `scope="..."` | The OAuth scope a Bearer token must carry to call this tool. Callers using Moqui web auth (no token) are not scope-checked. |
| Tool names | `verb_noun` snake_case, unique across the WHOLE server, not just your file. A duplicate is rejected at scan with a log line naming both files. |
| `AiToolDenylist` | The operator's veto. Patterns (seeded: all `delete#` services, `org.moqui.impl.*`, anything with "password", `UserAccount`, `ArtifactAuthz`) drop your tool at scan time regardless of the file. |
| description | Written for the model, not for people browsing code. Say what it returns, what filters mean, and when to use it. |

### 3. Restart and verify — always all three checks

The catalog is scanned once and cached; a `*.mcp.xml` change needs a server restart.
Scan problems NEVER break the server — a bad tool is skipped with an ERROR log line
starting `MCP scan:`. If your tool is missing from the list, grep the log for that.

Check 1 — the tool is in the catalog with the right schema:

```bash
curl -s -X POST http://localhost:8080/mcp/json \
  -H "Content-Type: application/json" -H "MCP-Protocol-Version: 2026-07-28" -H "Mcp-Method: tools/list" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
```

Check 2 — the tool runs and returns real data (dev mode: HTTP Basic with a Moqui account):

```bash
curl -s -u 'user:password' -X POST http://localhost:8080/mcp/json \
  -H "Content-Type: application/json" -H "MCP-Protocol-Version: 2026-07-28" \
  -H "Mcp-Method: tools/call" -H "Mcp-Name: get_sales_orders" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}},"name":"get_sales_orders","arguments":{"maxOrders":3}}}'
```

Look at `result.isError`. `true` means the BACKING SERVICE failed (its message is in
`content[0].text`) — fix or swap the service, the MCP layer is fine. A JSON-RPC `error`
with code `-32602` means the tool name is not in the catalog — back to the scan log.

Check 3 — the audit row landed (entity `moqui.ai.AiToolCall`): find the newest row,
confirm `sourceEnumId=AI_TCS_MCP`, the calling `userId`, and `success`.

### 4. Do not claim done without check 2 passing on real data

A tool that lists but fails on call is not done. The most common failure is a heavy
backing service that breaks on the deployment's dataset — this is why step 1 says test
the service first.

## Environment facts your agent session needs

- The endpoint ships DARK. Dev server needs `-Dmcp_enabled=Y`. Without OAuth config
  (`mcp_auth_flow_id` empty) tool calls authenticate with Moqui web auth (HTTP Basic).
- `tools/list` and `server/discover` are public by design; `tools/call` requires a real
  user. The anonymous login is never enough to run a tool.
- The test suite and the dev server CANNOT run at the same time (shared transaction
  journal): stop the server, `./gradlew :runtime:component:moqui-ai:test`, restart.
- If you change anything under this component's `src/main/`, rebuild the jar before
  restarting the server: `./gradlew :runtime:component:moqui-ai:jar`.
- Rate limiting (429) is active only under production conf; MoquiDevConf disables the
  service tarpit.

## Component conventions (for changes to moqui-ai itself)

- Service logic: pure XML actions when it fits; a service that is mostly Groovy points at
  a file under `script/` via `type="script"` (end such scripts with `return null` —
  ScriptServiceRunner treats a returned Map as the whole service result).
- TDD is the norm here: every MCP behavior rule has a test that was written first.
  New engine rules go in `McpConformanceTests`, scan rules in `McpToolsTests` (fixtures
  under `src/test/resources/mcp/`, passed to `build#ToolCatalog` explicitly), call
  behavior in `McpCallTests`, OAuth in `McpOAuthTests`.
- In Spock assertions use `.get('properties')` on Maps — Groovy 5 resolves `.properties`
  to the bean getter and Spock 2.4 mis-rewrites the subscript form.
