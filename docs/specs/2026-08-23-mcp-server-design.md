# MCP Server Design

Date: 2026-08-23 (consolidated 2026-08-24 after implementation review)
Status: ALL steps implemented and live. OAuth 2.1 resource server (step 6) is built
on the moqui-sso component and verified end-to-end against a local OIDC issuer:
discovery -> JWKS -> RS256 validation -> audience check -> externalUserId mapping ->
scope enforcement -> audited call. Production rollout still needs: a real IdP
configured as an AuthFlow/OidcFlow row, audit retention, and an operator screen
Component: `moqui-ai`

## Purpose

Give Moqui the same thing for AI clients that `*.rest.xml` gives it for HTTP clients:
a declarative, per-component file that turns existing Moqui services into callable
tools, with no code per tool.

Today `moqui-ai` runs agents *inside* Moqui that call Moqui services as tools. This
adds the outward direction: an external MCP client (Claude Desktop, Claude Code,
Cursor, ChatGPT) connects to Moqui and uses those same services as tools.

MCP is the Model Context Protocol, a standard for how an AI client discovers and
calls tools on a server. This design targets specification revision **2026-07-28**.

## Decisions taken

| # | Decision | Choice |
|---|----------|--------|
| 1 | Direction | MCP **server**. Moqui exposed outward. Not the client direction. |
| 2 | Placement | Self-contained in `moqui-ai`. No framework changes. |
| 3 | Declaration | File only. `*.mcp.xml` per component, scanned at boot. No DB rows. |
| 4 | Primitives | **Tools** only. Resources and prompts are out of scope. |
| 5 | Auth | OAuth 2.1. Moqui is a **resource server** only. An external IdP issues tokens. |
| 6 | Mutation | Superseded by decisions 16 and 18. |
| 7 | Parameter default | No `<parameter>` children means all in-parameters are exposed. |
| 8 | Bad service | Skip the tool, log an error, keep booting. |
| 9 | `tools/list` filtering | Show everything. Rely on OAuth step-up for scope. |
| 10 | Identity | `UserAccount.externalUserId` only. No username fallback. |
| 11 | Rate limiting | Reuse Moqui's existing `ArtifactTarpit`. |
| 12 | Audit | Make `AiToolCall.toolCallId` the sole primary key, run reference optional. |
| 13 | Endpoint | A screen transition, like `/rpc/json`. Not a custom servlet. One small servlet only for `/.well-known`. |
| 14 | Schema generation | Replace `ToolSchemaBuilder` with the framework's `RestSchemaUtil`, for agents and MCP alike. |
| 15 | Dispatch | Declarative, by a literal allowlist map: MCP method name (as sent) to service name, defined once in the engine. The MCP request vocabulary is a closed set (ten names), so the map is the honest form. Tools remain the open, file-declared set. |
| 16 | Exposure | The scan never polices the service verb. Declaring a tool in `*.mcp.xml` IS the exposure decision and the author owns it ("if you define it, you asked for it"). Only `effect="read"` claims the readOnlyHint annotation; anything else claims nothing, and MCP client defaults treat the tool as potentially destructive. |
| 17 | Legacy tolerance | Per the spec's MAY clause: requests with no `MCP-Protocol-Version` header, or naming a known older revision (2024-11-05 … 2025-11-25), are served legacy-style — `initialize` answered, no header/`_meta` gates. Verified necessary: the claude CLI still leads with the legacy `initialize` handshake, then speaks the 2025-11-25 dialect (version header, no `Mcp-Method`), and treats HTTP 400 as fatal without the spec's fallback body inspection. Strict gates apply to 2026-07-28+ traffic only; unknown versions still get `-32022`. |
| 18 | Denylist | RESOLVED (guard step): the MCP scan checks `AiToolDenylist` as an operator floor. A DB pattern row vetoes a service regardless of what any `*.mcp.xml` declares — the file author proposes (decision 16), the deployment operator can block without touching a vendor component's files. Read at scan time; a denylist edit takes effect on the next catalog cache rebuild. |
| 19 | OAuth foundation | Built on `moqui/moqui-sso` 2.0.0 (dependency of this component): IdP config lives in its `AuthFlow`/`OidcFlow` entities (issuer + JWKS via OIDC discovery, cached in `ai.mcp.oauth.meta`), nimbus-jose-jwt comes from its lib. Our layer is the thin resource server: `McpBearerValidator` (pure: token + JWKSet + issuer + audience in, claims out), the engine's Bearer gate (401 + `WWW-Authenticate` `resource_metadata`, 403 unknown-subject, 403 `insufficient_scope` per tool `scope`), `McpMetadataServlet` for `/.well-known/oauth-protected-resource`, and `mcp_enabled` (default N — the endpoint ships dark). Moqui web auth (Basic / the fork's HMAC JWT) remains the fallback door; the fork's speculative Bearer-HMAC failure is cleared in the transition so it cannot veto RS256 validation. The endpoint therefore has three doors, and the internal-JWT one is verified live (2026-08-25): a valid HS512 token (key `runtime/conf/jwtKey.txt`, issuer `ofbiz.instance.name`, claim `userLoginId`) is logged in by `UserFacadeImpl` before dispatch, so the gate's `ec.user.userAccount == null` guard skips OAuth entirely — tool ran, audit row carried the mapped userId, and a forged-signature token got 401 from both validators. Internal tokens and Basic are first-party and are not scope-checked; per-tool `scope` gates IdP tokens only. |

## Scope

**In scope**

- `*.mcp.xml` discovery and parsing
- `server/discover`, `tools/list` and `tools/call` over Streamable HTTP
- Full protocol conformance for revision 2026-07-28 (see Protocol conformance)
- OAuth 2.1 resource-server token validation
- RFC 9728 Protected Resource Metadata
- Audit records for every call

**Out of scope for version one**

- MCP resources and prompts
- The MCP client direction (Moqui consuming external MCP servers)
- The 2025-06-18 stateful protocol and its deprecated HTTP+SSE transport
- Human approval holds via Multi Round-Trip Requests
- Building an OAuth authorization server

## Relationship to `moqui-mcp`

`hotwax/moqui-mcp` v1.1.0 exists and is a different thing. It exposes Moqui
**screens** as an accessibility tree (MARIA), through four hardcoded tools in a
Groovy map (`McpToolAdapter.groovy:28`). It is built against the older stateful
protocol revision, with a session adapter and SSE transport.

This design exposes **services and entities**, declared per component, against the
current stateless revision. The two can coexist on different paths. Merging them is
a later decision, not a prerequisite.

## What comes from the framework

Checked against `hotwax/moqui-framework`. Most of the plumbing already exists, so the
build is smaller than the design's length suggests.

**Use as-is:**

| Need | What exists |
|------|-------------|
| JSON Schema from a service | `RestSchemaUtil.getJsonSchemaMapIn(sd)` and `getJsonSchemaMapOut(sd)` (`framework/src/main/groovy/org/moqui/impl/util/RestSchemaUtil.groovy:448`). Handles nested objects, arrays with `items`, `format`, descriptions and defaults |
| Service dispatch with authz | `ec.service.sync()` pushes an `AT_SERVICE` artifact; `ArtifactAuthz` runs automatically |
| Rate limiting and `429` | Tarpit throws `ArtifactTarpitException`; `MoquiServlet` returns 429 with `Retry-After` (`MoquiServlet.groovy:130`) |
| HTTP error mapping | `MoquiServlet` maps 401, 403, 404, 415 and 500 from exceptions |
| Request body JSON parsing | `_requestBodyJsonList` and `_requestBodyJsonParseError` in request parameters |
| Response control | `ec.web.getResponse()` returns the raw `HttpServletResponse`, so a transition can set any status and header. `ec.web.sendJsonResponse(Object)` writes the body |
| Boot-time caching | `cacheFacade.getLocalCache()`, as `RestApi` uses for `service.rest.api` |
| Screen mounting from a component | `screen-facade` / `subscreens-item` in the component's own `MoquiConf.xml` |

**Exists but not enough:**

| Need | The gap |
|------|---------|
| Component file scan | `RestApi.loadRootResourceNode` (`RestApi.groovy:77`) does exactly this for `*.rest.xml`, but inline, with no reusable API. Around 30 lines to adapt |
| JSON-RPC envelope | `ServiceJsonRpcDispatcher` exists and is served at `/rpc/json`. It has the `-32700` to `-32603` constants and the message shape, but no tool catalog, no `resultType`, no `_meta`, no MCP error codes |
| Authentication from a request | `MoquiAuthFilter` and `UserFacadeImpl.initFromHttpRequest` handle Basic, api_key and login key. No OAuth resource-server semantics |
| Bearer JWT validation | `co.hotwax.auth.JWTManager` is HMAC only, with a shared key file and no audience check. OAuth needs RS256 and JWKS. The `com.auth0:java-jwt` dependency supports RSA, so it is a usable base |
| `Origin` validation | `MoquiServlet.handleCors` validates `Origin` but returns **401** on rejection (`MoquiServlet.groovy:230`). MCP requires **403**, so this must be handled in the transition before the CORS path decides |
| Audit | `ArtifactHit` exists, but `persist-hit="false"` for `AT_SERVICE` by default |

**Nothing exists** for the MCP protocol layer itself (`server/discover`, header-to-body
validation, `_meta` validation, `resultType`, the `-32020` to `-32022` codes, `ttlMs`
and `cacheScope`), for the OAuth resource-server parts (JWKS fetching, audience
validation, `WWW-Authenticate` with `resource_metadata`, the RFC 9728 document), or
for `*.mcp.xml` itself.

## Architecture

Eight pieces, all inside `moqui-ai`, all XML except the two OAuth-step items.

| Piece | Responsibility | Mirrors |
|-------|----------------|---------|
| `screen/Mcp.xml` | Transport only at `/mcp/json`: hand the request body to the dispatch service, write the returned Map as JSON. Zero logic. Mounted via `subscreens-item` on `webroot.xml` | `webroot/screen/webroot/rpc.xml` |
| `ai.mcp.McpServices.dispatch#Request` | The engine, one XML service, fixed size. Owns the JSON-RPC envelope, the literal method allowlist map, and (conformance step) header and `_meta` validation and the MCP error codes. A method service returns a plain result Map or an `error` Map; the engine envelopes either | `ServiceJsonRpcDispatcher` |
| `ai.mcp.McpMethodServices` | One ordinary Moqui service per MCP method: `discover#Server`, `list#Tools`, `call#Tools` | — |
| `ai.mcp.McpServices.get#ToolCatalog` | The fixed scan source plus cache: component service dirs, `*.mcp.xml`, no in-parameters | `RestApi.loadRootResourceNode` |
| `ai.mcp.McpServices.build#ToolCatalog` | Pure transformer holding all scan rules; required `locationList`, no cache — the unit the rule tests exercise | per-node construction in `RestApi` |
| `ai.mcp.McpServices.exec#Tool` | Run one tool call: real-user gate, argument filtering to the exposed schema, `fixed` injection (server wins), own transaction, failures as `isError` text | — |
| `McpAuthenticator` (OAuth step) + `McpMetadataServlet` (`/.well-known`, RFC 9728) | Validate the Bearer token against the IdP, map to `UserAccount`; serve Protected Resource Metadata | — |
| `xsd/mcp-api-1.xsd` | Schema for the new file type, shipped in the component | `framework/xsd/rest-api-3.xsd` |

The XSD lives in the component rather than the framework, to keep decision 2 intact.

### Why a transition and not a servlet

Moqui serves both `/rest` and `/rpc` as screen transitions, not servlets. `rpc.xml` is
nine lines: a transition calling `ec.web.handleJsonRpcServiceCall()` with
`<default-response type="none"/>`. Everything else comes from `MoquiServlet`.

Serving `/mcp` the same way inherits CORS handling, the exception-to-status mapping
including tarpit to 429, and the execution-context lifecycle. A custom servlet would
reimplement all of it. `ec.web.getResponse()` gives the raw response object, so the
transition still controls every status code and header that OAuth needs.

### Declarative dispatch

The guiding rule, taken from the framework itself: the engine is generic and never
changes; growth happens in declarations. `RestApi` has no branch per endpoint, it
walks `*.rest.xml`. An if-ladder on MCP method strings would put per-method
knowledge inside the engine, so every new method would mean editing the dispatcher.

The dispatch mechanism is a literal allowlist map in the engine: the MCP method
name, exactly as the client sends it, is the key; the implementing service name is
the value. A lookup, nothing else:

| Map key (MCP method) | Map value (service) |
|------------|-------------|
| `server/discover` | `ai.mcp.McpMethodServices.discover#Server` |
| `tools/list` (step 2) | `ai.mcp.McpMethodServices.list#Tools` |
| `tools/call` (step 3) | `ai.mcp.McpMethodServices.call#Tools` |
| anything else | absent key, so `-32601` |

A map rather than a naming convention because the MCP request vocabulary is a
CLOSED set: revision 2026-07-28 defines exactly ten server-side request methods,
fixed by the protocol. For a closed set the visible allowlist is the honest form,
and it handles every name shape (`resources/templates/list` included). The open
set — tools — is a different animal and stays declarative in per-component
`*.mcp.xml` files.

Consequences:

- The map keys are the entire reachable surface. Nothing outside
  `ai.mcp.McpMethodServices` is ever callable from the endpoint, the way
  `allow-remote` gates `/rpc/json`.
- Unknown-method handling is not code. It is the absence of a map entry.
- There is no exists-check on the mapped service. A map entry and its service
  land in the same change; a wrong entry should fail loud at call time, because
  answering `-32601` for a mapped-but-broken method would lie to the client.
- A method service returns a plain result Map. The engine wraps it in `jsonrpc`,
  `id`, `resultType` and `serverInfo`. Method services never see the envelope.
- Conformance work (headers, `_meta`, error codes) lands only in the engine.
  Capability work (a new method entry plus its service, a new tool) lands only as
  declarations. Neither ever touches the other, which is what makes
  one-step-at-a-time delivery safe.

Mounting uses the same mechanism the component already uses for AiOps, but on the
webroot screen rather than `apps.xml`:

```xml
<screen-facade>
    <screen location="component://webroot/screen/webroot.xml">
        <subscreens-item name="mcp" location="component://moqui-ai/screen/Mcp.xml"/>
    </screen>
</screen-facade>
```

The `tools` component already adds a subscreen to webroot this way
(`webroot.xml:76`), so the precedent exists.

### Why `.well-known` still needs a servlet

Screen and subscreen names are typed `name-plain` in the schema, restricted to
`[a-zA-Z][_a-zA-Z0-9]*` (`framework/xsd/common-types-3.xsd:20`). A name cannot start
with a dot or contain a hyphen, so `/.well-known/oauth-protected-resource` cannot be
a screen path.

`McpMetadataServlet` therefore serves that one path. It returns a static JSON document
built from configuration and does nothing else.

### Boot scan

The scan mirrors `RestApi.loadRootResourceNode`
(`framework/src/main/groovy/org/moqui/impl/service/RestApi.groovy:77`). For every
component base location it reads `<location>/service`, and parses each file ending
in `.mcp.xml`.

The scan is split the way `RestApi` splits (as built): `get#ToolCatalog` is the fixed
source plus cache — no in-parameters, so nothing a caller sends can point it at a
file — and `build#ToolCatalog` is the pure transformer holding all the rules, taking
a required `locationList`, which is what the rule tests exercise.

Per file: a file that fails to parse is logged and skipped as a whole. `RestApi`
fails the boot on a malformed `*.rest.xml` because its scan runs at boot, on the
developer; ours runs lazily on the first `tools/list`, so a throw would land on
clients as a dead endpoint instead.

Per declared tool, in order:

1. Duplicate tool name: reject the duplicate, first declaration wins, log both files.
2. Resolve the backing service. If it does not exist, skip the tool, log an error,
   continue. The scan never polices the service verb (decision 16).
3. Build `inputSchema` via `RestSchemaUtil.getJsonSchemaMapIn(sd)`, remove the
   implicit auth parameters (`authUsername`, `authPassword`, `authTenantId`), then
   apply `<parameter>` narrowing and hold `fixed` values for call-time injection.
4. Build `outputSchema` via `RestSchemaUtil.getJsonSchemaMapOut(sd)`.
5. Annotations: `effect="read"` yields `readOnlyHint: true`; anything else yields no
   annotations, and MCP client defaults treat the tool as potentially destructive.

The catalog is cached in `ai.mcp.tool.catalog`, declared in the component's
`MoquiConf.xml` the way the framework declares `service.rest.api`: no expiry,
rebuilt on any cache miss. Callers share the cached object graph, read-only.

### Call flow

```
Client  POST /mcp
        Mcp-Method: tools/call
        Mcp-Name: cancel_order
        Authorization: Bearer <token>
   |
   v
McpAuthenticator validate signature via IdP JWKS
                 check iss, exp/nbf, and aud == this server
                 map identity claim -> UserAccount.externalUserId -> username
                 ec.user.internalLoginUser(username)
                 on failure: 401 + WWW-Authenticate
   |
   v
Mcp.xml          transition, transport only, default-response type="none"
   |
   v
dispatch#Request the engine service
                 validate Origin (403 if invalid), headers vs body, _meta
                 parse JSON-RPC body from _requestBodyJsonList
                 resolve tools/call -> ai.mcp.McpMethodServices.call#Tools
                 (no service defined for the method -> -32601)
   |
   v
call#Tools       look up cancel_order in the tool catalog
                 check the tool's declared scope against the token
                 on missing scope: 403 insufficient_scope, before any dispatch
                 inject any fixed parameters
                 cancel_order -> co.hotwax.oms.OrderServices.cancel#Order
   |
   v
ec.service.sync().name(...).parameters(args).call()
                 <- Moqui ArtifactAuthz applies here, as the mapped user
                 <- ArtifactTarpit applies here, as AT_SERVICE
   |
   v
persist AiToolCall, return JSON-RPC result
```

`internalLoginUser` goes through Shiro
(`framework/src/main/groovy/org/moqui/impl/context/UserFacadeImpl.groovy:676`), so
whichever realm the deployment configures handles the lookup. In this deployment
that is `co.hotwax.auth.OfbizShiroRealm`, from the `maarg-util` component, which
authenticates against the OFBiz `UserLogin` model. The design does not depend on
that; it resolves a username and lets the realm decide.

## The `*.mcp.xml` format

### What is written versus derived

| MCP wire field | Source |
|----------------|--------|
| `name` | file |
| `title` | file, optional |
| `description` | file |
| `inputSchema` | derived from service in-parameters |
| `outputSchema` | derived from service out-parameters |
| `annotations` | derived from the declared effect |

The file carries only what code cannot know.

### Example

```xml
<?xml version="1.0" encoding="UTF-8"?>
<mcp xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
     xsi:noNamespaceSchemaLocation="component://moqui-ai/xsd/mcp-api-1.xsd"
     name="oms" display-name="HotWax OMS" version="1.0.0"
     description="Order management tools for external AI clients.">

    <!-- read-only claim by the author; all in-parameters exposed -->
    <tool name="list_orders" title="Find Orders"
          description="Find orders by status, customer or date range.">
        <service name="co.hotwax.oms.OrderServices.find#Orders"/>
    </tool>

    <!-- narrowed: only these parameters reach the model -->
    <tool name="get_order" title="Get Order"
          description="Read one order with its items and shipments."
          scope="orders:read">
        <service name="co.hotwax.oms.OrderServices.get#Order"/>
        <parameter name="orderId"/>
        <parameter name="includeItems"/>
        <parameter name="productStoreId" fixed="${mcp_default_store}"/>
    </tool>

    <!-- mutating: declaring it here IS the exposure decision (decision 16); with no
         effect="read" claim, MCP clients treat it as potentially destructive and prompt -->
    <tool name="cancel_order" title="Cancel Order" effect="mutating"
          description="Cancel an open order. Fails if it already shipped."
          scope="orders:write">
        <service name="co.hotwax.oms.OrderServices.cancel#Order"/>
        <parameter name="orderId"/>
        <parameter name="cancelReason"/>
    </tool>
</mcp>
```

### Rules

**Declaring a tool is the exposure decision (decision 16).** The scan never derives
or polices anything from the service verb; the author owns what the file exposes.
`effect="read"` is the author's claim of the readOnlyHint annotation; any other
value, or none, claims nothing, and MCP client defaults treat the tool as
potentially destructive, which is the safe direction.

**Parameters narrow when listed.** No `<parameter>` children exposes every
in-parameter, matching how REST behaves and keeping simple read tools to three
lines. Listing even one parameter exposes only the listed ones. This matters
because a service such as `find#Orders` may carry thirty in-parameters.

**`fixed` parameters never reach the model.** They are excluded from `inputSchema`
and injected at call time. Use them for values the caller must not control, such as
a store id. The value supports normal `${...}` config expansion.

A `fixed` parameter still counts as listed for the narrowing rule above. In the
`get_order` example the tool lists three parameters, so only those three are
considered, and of those only `orderId` and `includeItems` appear in `inputSchema`.
`productStoreId` is supplied by the server on every call.

**`scope` drives the OAuth response.** It is what the server puts in the
`WWW-Authenticate` header when returning `403 insufficient_scope`. A tool with no
declared scope requires only a valid token.

**Names share one flat namespace.** MCP tool names are unique per server. A
collision between two components is rejected at boot, naming both files.

Names should follow the existing `verb_noun` snake_case convention that
`store#AiTool` derives (`service/ai/ToolServices.xml:39`), for consistency with
agent-facing tools. The MCP spec allows a wider character set, so this is a
project convention, not a protocol requirement.

### Annotations

Confirmed against the published schema for revision 2026-07-28. `Tool.annotations`
is typed `ToolAnnotations`:

```typescript
export interface ToolAnnotations {
  title?: string;
  readOnlyHint?: boolean;      // Default: false
  destructiveHint?: boolean;   // Default: true   (meaningful only when readOnlyHint == false)
  idempotentHint?: boolean;    // Default: false  (meaningful only when readOnlyHint == false)
  openWorldHint?: boolean;     // Default: true
}
```

The defaults are safe. A tool carrying no annotations is already treated as
destructive. Getting this wrong therefore over-warns rather than under-warns.

The defaults are what makes decision 16 safe: hints are author-declared, never
derived. `effect="read"` emits `annotations: { readOnlyHint: true }`. Any other
effect value, or none, emits no annotations at all, and the client-side defaults
above already treat such a tool as potentially destructive. Getting a declaration
wrong therefore over-warns rather than under-warns. `destructiveHint` and
`idempotentHint` are not emitted in this version; they can become author-declared
attributes later if the distinction proves to matter.

`openWorldHint` is not emitted, so it takes its default of `true`. Whether a Moqui
service reaches an external system cannot be known from the verb. Some connector
services do call Shopify or NetSuite. An explicit attribute could be added later if
the difference proves to matter.

`ToolAnnotations.title` is not emitted either. The Tool display-name precedence is
`title`, then `annotations.title`, then `name`, and this design sets the top-level
`Tool.title` from the file. Setting both would be redundant.

## Protocol conformance

This section is the checklist a build must satisfy to be a conforming MCP server at
revision 2026-07-28. It is separate from the sections below, which describe what this
server chooses to do. Everything here is required of any server, and the
`dispatch#Request` engine owns all of it.

### Transport

The Streamable HTTP transport in this revision removed the GET stream endpoint and
protocol-level sessions.

| Requirement | Behaviour |
|-------------|-----------|
| One endpoint accepting POST | `/mcp` |
| Validate the `Origin` header | If present and invalid, return `403 Forbidden`. Guards against DNS rebinding |
| Respond to a request | Either `Content-Type: application/json` or `text/event-stream`. This server always answers `application/json`, which conforms |
| Respond to a notification | `202 Accepted` with no body (any id-less message; this revision defines no client-to-server notifications over HTTP, so accept-and-ignore) |
| GET or DELETE to `/mcp` | `405 Method Not Allowed` |
| `Mcp-Session-Id` header | Ignore. Never mint or echo a session id |
| `Last-Event-ID` header | Ignore. Streams are not resumable |

### Header validation

Three headers are required for compliance. The server must also check that each one
matches the request body. The reason is stated in the specification: an intermediary
may route on the header while the server executes on the body, and a disagreement
between the two is a security hole.

| Header | Source field | Required for |
|--------|--------------|--------------|
| `MCP-Protocol-Version` | `_meta` `io.modelcontextprotocol/protocolVersion` | every request |
| `Mcp-Method` | `method` | every request |
| `Mcp-Name` | `params.name` | `tools/call` |

Rules:

- A missing required header, or a header that does not match the body, gives HTTP
  `400` and JSON-RPC `-32020` `HeaderMismatch`.
- An `Mcp-Name` value arriving as `=?base64?{value}?=` must be Base64-decoded before
  comparison. The marker is case sensitive and lowercase.
- A protocol version this server does not implement gives HTTP `400` and `-32022`
  `UnsupportedProtocolVersion`, listing the versions it does support.
- A method this server does not implement gives HTTP `404 Not Found` and `-32601`.
  Note the status is 404, not 200. It is how a client tells a modern server from a
  legacy one.
- `Mcp-Param-*` headers are not produced by this server, since no tool parameter is
  annotated `x-mcp-header`. If a client sends one anyway, its value must still match
  the body or the request is rejected with `-32020`.

Header names compare case-insensitively. Header values compare case-sensitively.

### Base protocol

| Requirement | Behaviour |
|-------------|-----------|
| JSON-RPC 2.0 | Request `id` must be a string or number, never null |
| `resultType` on every result | `"complete"` for a normal result |
| `_meta` validation | `io.modelcontextprotocol/protocolVersion` and `io.modelcontextprotocol/clientCapabilities` are required on every request. Missing gives `-32602` and HTTP `400` |
| Client capabilities | Never rely on a capability the client did not declare. If one is needed, return `-32021` `MissingRequiredClientCapability` with `data.requiredCapabilities`, and HTTP `400` |
| Statelessness | Never infer context from an earlier request on the same connection |
| `serverInfo` | Include `io.modelcontextprotocol/serverInfo` in every result's `_meta`. A SHOULD, and cheap |
| JSON Schema | Support draft 2020-12. Never automatically dereference a `$ref` that resolves to a network URI |

Nothing in this server needs a client capability, so `-32021` should never fire. It
is implemented so that adding a feature later cannot silently skip the check.

### `server/discover`

The schema states this plainly: "Servers **MUST** implement `server/discover`."
Clients may call it but are not required to, because version negotiation can also
happen inline through `_meta`.

The result:

```json
{
  "resultType": "complete",
  "supportedVersions": ["2026-07-28"],
  "capabilities": { "tools": { "listChanged": false } },
  "instructions": "...",
  "ttlMs": 300000,
  "cacheScope": "public"
}
```

`listChanged` is `false` and honest. Tools come from files scanned at boot, so the
list cannot change while the server runs. Supporting `true` would require
`subscriptions/listen`, which is out of scope.

`instructions` is natural-language guidance a client may put in the model's system
prompt. It is built by joining the `description` attribute of each loaded
`*.mcp.xml` root element, so each component describes its own tool group in its own
file.

### Error code register

| Code | Name | Emitted when | HTTP status |
|------|------|--------------|-------------|
| `-32700` | Parse error | Body is not valid JSON | 400 |
| `-32600` | Invalid Request | Body is not a valid JSON-RPC message | 400 |
| `-32601` | Method not found | Unknown MCP method | **404** |
| `-32602` | Invalid params | Unknown tool name, or a required `_meta` field is missing | 400 for `_meta`, 200 for unknown tool |
| `-32020` | HeaderMismatch | Required header missing, or header disagrees with the body | 400 |
| `-32021` | MissingRequiredClientCapability | A needed client capability was not declared | 400 |
| `-32022` | UnsupportedProtocolVersion | Requested protocol version not implemented | 400 |

Codes that must never be emitted: `-32002` and `-32042`, both retired from earlier
revisions. No new code may be allocated in the `-32000` to `-32019` range.

### Cacheable results

`ttlMs` and `cacheScope` are required fields on `ListToolsResult` and
`DiscoverResult`, not optional hints. `ListToolsResult extends PaginatedResult,
CacheableResult`, and `CacheableResult` declares both without `?`.

`cacheScope` is `"public"` because decision 9 does not filter the tool list by the
caller's scopes. If that decision is ever reversed, this must become `"private"`,
since a scope-filtered list is authorization-specific and caches must not be shared
across authorization contexts.

### Not implemented, and why that is conforming

| Feature | Status |
|---------|--------|
| `initialize` / `initialized` | Removed from the schema in this revision. Served anyway as legacy compat (decision 17): `initialize#Server` echoes the requested protocolVersion; `notifications/initialized` is accepted as a notification (202). Sessions are not minted and GET returns 405 — both legal in the legacy revisions too |
| `ping` | No longer exists as a method in this revision |
| Sessions, `Mcp-Session-Id` | Removed from the transport |
| SSE responses | Optional. The server may always answer `application/json` |
| Resources, prompts, completions, logging | Optional capabilities, not declared |
| `subscriptions/listen` and change notifications | Optional. Not declared, and `listChanged` is `false` |
| Authorization | Marked OPTIONAL by the specification. This server implements it anyway, which is above the bar rather than at it |

## Wire behaviour

### Token validation

1. Read `Authorization: Bearer <token>`. Missing gives 401.
2. Fetch the IdP JWKS. Cache by `kid`, refresh on an unknown `kid`.
3. Verify the signature.
4. Check `iss` equals the configured issuer.
5. Check `exp` and `nbf`.
6. Check `aud` equals this server's canonical URI.
7. Map the identity claim to `UserAccount.externalUserId`, exact match only.
8. `ec.user.internalLoginUser(username)`.

Step 6 is the requirement the specification is most insistent about. A token issued
for another service must be rejected, not merely unrecognised.

The server never forwards this token. If Moqui calls an upstream API, that call uses
its own separate token. Passing the client's token through is explicitly forbidden.

### Identity mapping

The configured claim, `sub` by default, matches `UserAccount.externalUserId` exactly.
There is no fallback to `username`. A fallback would mean an IdP subject that happens
to equal a Moqui username silently receives that account.

Operational consequence: a user must have `externalUserId` populated before they can
use MCP at all.

### `tools/list`

```json
{
  "resultType": "complete",
  "tools": [
    { "name": "...", "title": "...", "description": "...",
      "inputSchema": {...}, "outputSchema": {...} }
  ],
  "ttlMs": 300000,
  "cacheScope": "public"
}
```

Order is deterministic, sorted by tool name. The specification asks for this so
clients can cache the list and so model prompt caches keep hitting.

The list is not filtered by the caller's scopes. Every tool is shown, and a call
without sufficient scope returns `403 insufficient_scope` so the client can run the
step-up authorization flow. This keeps `cacheScope` at `public`.

### `tools/call`

A Moqui service returns a Map, which maps directly onto the MCP result.

```json
{
  "resultType": "complete",
  "content": [
    { "type": "text", "text": "{\"orderId\":\"10023\",\"statusId\":\"ORDER_CANCELLED\"}" }
  ],
  "structuredContent": { "orderId": "10023", "statusId": "ORDER_CANCELLED" },
  "isError": false
}
```

`structuredContent` is the service result Map. The text block repeats it as JSON,
which the specification asks for as a compatibility measure. `content` is a required
field on `CallToolResult`, so it is sent even when `structuredContent` carries the
real payload.

A service that returns a list still produces an object. `find#Orders` declares an
out-parameter `orderList`, so `structuredContent` is `{ "orderList": [...] }` rather
than a bare array, and `outputSchema` describes an object with one array property.
This is correct. Note the asymmetry in the schema: `inputSchema` must be
`type: "object"`, while `outputSchema` has no such constraint and may describe any
shape.

### Error mapping

This table covers errors specific to this server. Protocol-level errors, meaning
header mismatch, version negotiation and unknown methods, are in the error code
register under Protocol conformance. The two do not overlap.

The distinction that matters: a client can fix an authorization problem by asking
for more scopes, and a model can fix a bad argument by retrying. Everything else is
neither.

| Situation | Response |
|-----------|----------|
| No token, bad signature, expired, wrong issuer | 401 with `WWW-Authenticate: Bearer resource_metadata="..."` |
| `aud` is not this server | 401. Never accepted, never forwarded |
| Valid token, claim maps to no `UserAccount` | 403 |
| Valid token, missing the tool's declared scope | 403 with `error="insufficient_scope", scope="..."` |
| Unknown tool name | JSON-RPC error `-32602` |
| Malformed request | JSON-RPC error `-32700` or `-32600` |
| Service returned validation or business errors | `isError: true`, messages as text, so the model can self-correct |
| Moqui `ArtifactAuthz` denied the user | `isError: true`, plain "not permitted" text. No re-authorization would help, so a 403 step-up would loop |
| Unexpected exception | `isError: true`, generic message only |

The last row is deliberate. Stack traces, SQL fragments and internal paths must not
reach an external AI client. Full detail goes to the log only.

## Security model

### Two authorization gates

Both exist, and neither replaces the other.

**At scan time**, two things and only two: the author's declaration (decision 16 —
declaring a tool in `*.mcp.xml` is the exposure decision) and the operator floor
(decision 18 — `AiToolDenylist` patterns veto a service regardless of the file; the
component seeds floors for deletes, framework internals, credentials, account and
authorization administration). The floor is read at scan time, so a denylist edit
takes effect on the next catalog cache rebuild.

**Interim, at call time**, `exec#Tool` refuses to dispatch unless a real user (one
with a `UserAccount`) is logged in via Moqui's existing HTTP authentication. This
guard exists because the dispatch chain is `anonymous-view` for discover/list, and
that anonymous grant satisfies `authenticate="true"` on backing services — verified
live: without the guard an unauthenticated caller got real data. The OAuth step
replaces this guard with proper 401s.

**At call time**, Moqui's normal `ArtifactAuthz` runs as the mapped user. This is
per person and per deployment.

### Rate limiting

Handled by Moqui's existing `ArtifactTarpit`. `AT_SERVICE` has `tarpit-enabled="true"`
by default (`framework/src/main/resources/MoquiDefaultConf.xml:290`), and the check
runs when an artifact is pushed in `ArtifactExecutionFacadeImpl`. Because MCP
dispatch goes through `ec.service.sync()`, the tarpit applies with no new code.

Two limits to understand:

- It is data-driven. The check reads `ArtifactTarpit` rows joining a `UserGroup` to
  an `ArtifactGroup`. With no rows there is no limit. `moqui-ai` must therefore ship
  an `ArtifactGroup` covering MCP-exposed services and a default `ArtifactTarpit`
  row. `ArtifactGroupMember` supports `nameIsPattern="Y"`.
- The counter key is `userId + '@' + AT_SERVICE + ':' + serviceName`
  (`ArtifactExecutionFacadeImpl.groovy:490`), so the cap is per user per service. A
  caller hitting fifty different tools gets fifty separate counters. This caps abuse
  of any one tool but does not cap total throughput through `/mcp`.

The per-service-counter gap is closed by seeding the tarpit on the ONE service every
MCP request passes through: `data/McpSecurityData.xml` puts
`ai.mcp.McpServices.dispatch#Request` in an `ArtifactGroup` with an `ArtifactTarpit`
row for `ALL_USERS` (120 hits per 60s per caller, 60s lockout, 429 with
`Retry-After` via `MoquiServlet`). Deployments override by loading different numbers.

Known limitation, verified: `MoquiDevConf.xml` deliberately sets
`tarpit-enabled="false"` for `AT_SERVICE` in dev mode, so the tarpit protects only
conf profiles that leave it enabled (the production default). The suite's tarpit
test restores the production setting for its own duration to avoid being vacuous.

### Off by default

`mcp_enabled` defaults to `N`. Opening an external door should be a deliberate act.

## Data model change (implemented, step 7)

`AiToolCall` records exactly the right fields for an audit trail, but its primary
key ties it to an agent run:

```xml
<field name="agentRunId" type="id" is-pk="true"/>
<field name="stepSeqId"  type="id" is-pk="true"/>
<field name="toolCallId" type="id" is-pk="true"/>
```

An MCP call has no run and no step.

**Change**: `toolCallId` becomes the sole primary key, sequenced globally.
`agentRunId` and `stepSeqId` become ordinary nullable fields.

**Why**: a tool call is one concept, whether an agent made it or an external client
did. One table answers "who called what, when, with what arguments, and what
happened". The alternative, a near-identical second table, would force a union onto
every question about tool activity.

**Affected code**:

- `AgentRunner` writes `AiToolCall` rows and must supply a sequenced `toolCallId`
- The AiOps RunDetail screen queries by `agentRunId`, which stays valid as a
  non-key field but needs an index
- A migration for existing rows

New non-key fields for the MCP case: `sourceEnumId` (agent versus MCP), and the
calling `userId`.

## Code removal (implemented, step 7 — the replacement is `org.moqui.ai.ServiceSchemas`)

Decision 14 deletes `src/main/groovy/org/moqui/ai/ToolSchemaBuilder.groovy`. It is a
weaker reimplementation of framework code: it maps every parameter to a scalar type
and handles no nested objects, no array `items`, no `format`, and no defaults.
`RestSchemaUtil` handles all of them.

The only behaviour worth keeping is that it skips `authUsername`, `authPassword` and
`authTenantId`. That becomes a small filter applied to the `RestSchemaUtil` output.

**Affected code**:

- `DefinitionLoader.groovy:23` builds a schema per tool for an agent run
- `ComposerServices.xml:80` builds `inputSchema` for the Composer preview
- `ToolServices.xml:30` calls it only to validate that a service resolves, so this
  caller wants the resolution, not the schema
- `ToolSchemaBuilderTests` is replaced by tests over the new wrapper

Agent tool schemas change shape as a result. They gain nested-object and array
detail they did not have. This is an improvement, but it changes what the model sees,
so the agent test suite must be run and read, not just watched for green.

## Configuration

```
mcp_enabled                Y / N, default N
mcp_canonical_uri          https://oms.example.com/mcp
mcp_authorization_servers  comma-separated IdP issuer URLs
mcp_audience               expected aud, normally the canonical URI
mcp_user_claim             default: sub
mcp_scopes_supported       advertised in the metadata document
mcp_tools_ttl_ms           default: 300000
```

Servlet and filter are declared in the component's own `MoquiConf.xml` under
`webapp-list`, the same mechanism `moqui-mcp` uses, so no webroot file is touched.

## Testing

Spock specs following the existing pattern: one shared `ec`, registered in
`MoquiSuite`.

As built (steps 1-3):

| Class | What it proves |
|-------|----------------|
| `McpDispatchTests` | The envelope: discover wrapped correctly, unknown method `-32601` with id echoed, missing method `-32600`, a method name with service-path characters cannot escape the allowlist. |
| `McpToolsTests` | `tools/list` shape, sorted, `ttlMs`/`cacheScope`; schemas and the read-only annotation on the wire, never `serviceName`; empty-properties case; narrowing and `fixed` exclusion; unknown-service skip; write-verb tool loads with no annotations claim; duplicate rejected first-wins; malformed file skipped, later files still load. Rule cases run against `build#ToolCatalog` with fixture locations under `src/test/resources/mcp/`, so nothing test-only enters the production catalog. |
| `McpCallTests` | `tools/call` returns `structuredContent` plus text content; unknown tool `-32602`; `fixed` injected server-side and wins over a client override; out-of-schema arguments dropped; a service error becomes `isError: true` with the message text and a clean message facade; an unauthenticated call is refused with `isError` even though dispatch itself is anonymous. |

Planned (conformance and OAuth steps):

| Class | What it proves |
|-------|----------------|
| `McpAuthTests` | Missing token gives 401 with the header. Bad signature gives 401. A token whose `aud` is another service gives 401. A subject matching no `UserAccount` gives 403. A missing scope gives 403 with `error="insufficient_scope"` and the correct `scope`. |
| `McpConformanceTests` | One case per row of the Protocol conformance section. An invalid `Origin` gives 403. GET and DELETE give 405. A missing or mismatched `MCP-Protocol-Version`, `Mcp-Method` or `Mcp-Name` gives 400 with `-32020`. A Base64-encoded `Mcp-Name` is decoded before comparison. An unknown version gives 400 with `-32022` listing supported versions. An unknown method gives **404** with `-32601`. A request missing `_meta` `protocolVersion` or `clientCapabilities` gives 400 with `-32602`. Every result carries `resultType` and `serverInfo`. |

Two seams make this testable without an IdP:

- **Key resolution is an interface.** Tests inject a static public key rather than
  fetching JWKS over HTTP. The same seam later supports more than one IdP.
- **Test tools come from fixture files** under `src/test/resources/mcp/`, outside any
  scanned directory, passed to `build#ToolCatalog` explicitly. They declare tools
  over the existing `service/moqui/ai/test/TestServices.xml`. No production service
  is involved in tests.

Every negative test asserts the exact status code and the exact header content.
Asserting only that an error occurred would pass even when the server returns the
wrong code.

## Open items to verify before implementing

1. **Moqui service result noise.** A Moqui service result Map may carry more than the
   declared out-parameters. Confirm what a result carries beyond them, so nothing
   internal leaks into `structuredContent`.
3. **Transition error paths.** `ec.web.getResponse()` gives full control of status and
   headers, confirmed. Still to check: that an exception escaping the transition does
   not let `MoquiServlet` overwrite a status the dispatcher already set, particularly
   a `401` carrying `WWW-Authenticate`.
4. **CORS ordering.** `MoquiServlet.handleCors` runs before the screen render and
   returns `401` for a disallowed `Origin`, where MCP wants `403`. Confirm whether the
   dispatcher can return `403` first, or whether `allow-origin` configuration must be
   set so the CORS path never rejects an MCP caller.

## References

The TypeScript schema is the source of truth for all message shapes. Every claim in
this document about a field name, its type, or whether it is optional was checked
against it, not against memory.

- Schema, revision 2026-07-28 (source of truth):
  https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/schema/2026-07-28/schema.ts
- Base protocol, revision 2026-07-28:
  https://modelcontextprotocol.io/specification/2026-07-28/basic/index
- Streamable HTTP transport, revision 2026-07-28:
  https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http
- MCP authorization, revision 2026-07-28:
  https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization
- MCP tools, revision 2026-07-28:
  https://modelcontextprotocol.io/specification/2026-07-28/server/tools
- The 2026-07-28 specification release notes:
  https://blog.modelcontextprotocol.io/posts/2026-07-28/
- RFC 9728 OAuth 2.0 Protected Resource Metadata
- RFC 8707 Resource Indicators for OAuth 2.0
- RFC 6750 OAuth 2.0 Bearer Token Usage
