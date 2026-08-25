/* Implementation of ai.mcp.McpServices.dispatch#Request — the MCP engine
 * (spec: docs/specs/2026-08-23-mcp-server-design.md, "Declarative dispatch" and
 * "Protocol conformance"). In/out contract lives in service/ai/mcp/McpServices.xml.
 * Runs the conformance gates, resolves the method through the literal allowlist map,
 * calls the method service, wraps the outcome in the JSON-RPC envelope. */

// request visibility: which protocol revision and headers real clients actually send
ec.logger.info("MCP request method=${method} id=${id} paramsKeys=${params?.keySet()} protoHeader=${protocolVersionHeader} mcpMethodHeader=${mcpMethodHeader}")

List SUPPORTED = ['2026-07-28']
httpStatus = 200
Map err = null       // JSON-RPC error map when a gate fails

// 1. body parse error (transport rule: 400, -32700)
if (parseError) { httpStatus = 400; err = [code: -32700, message: 'Parse error: ' + parseError] }

// 2. no id = notification: accept with 202 and no body (this revision defines no
//    client-to-server notifications over HTTP, so accept-and-ignore is conforming)
boolean isNotification = (err == null && id == null)

// Legacy tolerance (spec MAY clause, design decision 17): a client is legacy-era when it
// sends no MCP-Protocol-Version header (pre-2025-06-18, e.g. the claude CLI's initialize
// handshake) OR names a known older revision (2025-11-25 clients send the version header
// but Mcp-Method did not exist yet). Strict header/_meta gates apply only to 2026-07-28+
// traffic; a version that is neither implemented nor known-legacy still gets -32022 below.
List LEGACY_VERSIONS = ['2024-11-05', '2025-03-26', '2025-06-18', '2025-11-25']
boolean legacyClient = (protocolVersionHeader == null || LEGACY_VERSIONS.contains(protocolVersionHeader))

// 3. JSON-RPC 2.0 only
if (!isNotification && err == null && jsonrpc != '2.0') {
    httpStatus = 400; err = [code: -32600, message: 'Invalid Request: jsonrpc must be "2.0"'] }

// 4-5. required headers, and header-vs-body agreement (an intermediary routing on the
//      header while the body says otherwise is the attack this closes)
if (!isNotification && err == null && !legacyClient && mcpMethodHeader == null) {
    httpStatus = 400; err = [code: -32020, message: 'HeaderMismatch: the Mcp-Method header is required'] }
if (!isNotification && err == null && !legacyClient && (method == null || mcpMethodHeader != method)) {
    httpStatus = 400; err = [code: -32020, message: "HeaderMismatch: Mcp-Method header '${mcpMethodHeader}' does not match body method '${method}'".toString()] }

// 6. required _meta fields — presence, not truthiness: clientCapabilities may be {}
Map metaMap = params instanceof Map ? (Map) params.get('_meta') : null
if (!isNotification && err == null && !legacyClient && (!(metaMap instanceof Map)
        || !metaMap.containsKey('io.modelcontextprotocol/protocolVersion')
        || !metaMap.containsKey('io.modelcontextprotocol/clientCapabilities'))) {
    httpStatus = 400; err = [code: -32602, message: 'Invalid params: _meta must carry io.modelcontextprotocol/protocolVersion and io.modelcontextprotocol/clientCapabilities'] }

// 7-8. version: header matches body, and the version is one we implement
if (!isNotification && err == null && !legacyClient) {
    String metaVersion = metaMap.get('io.modelcontextprotocol/protocolVersion')
    if (protocolVersionHeader != metaVersion) {
        httpStatus = 400; err = [code: -32020, message: "HeaderMismatch: MCP-Protocol-Version header '${protocolVersionHeader}' does not match _meta protocolVersion '${metaVersion}'".toString()]
    } else if (!SUPPORTED.contains(metaVersion)) {
        httpStatus = 400; err = [code: -32022, message: "Unsupported protocol version: ${metaVersion}".toString(), data: [supportedVersions: SUPPORTED]]
    }
}

// 9. tools/call: Mcp-Name required and must equal params.name after sentinel decode
if (!isNotification && err == null && !legacyClient && method == 'tools/call') {
    String headerName = (String) mcpNameHeader
    if (headerName != null && headerName.startsWith('=?base64?') && headerName.endsWith('?='))
        headerName = new String(headerName.substring(9, headerName.length() - 2).decodeBase64(), 'UTF-8')
    Object bodyName = params instanceof Map ? params.get('name') : null
    if (mcpNameHeader == null || headerName != bodyName) {
        httpStatus = 400; err = [code: -32020, message: "HeaderMismatch: Mcp-Name '${headerName}' does not match body tool name '${bodyName}'".toString()] }
}

// 10. the method allowlist: method name (as sent) -> implementing service. The MCP request
//     vocabulary is CLOSED (ten names in revision 2026-07-28), so a literal map is the
//     honest form; an absent key IS the -32601 answer, and per the transport an
//     unimplemented method is HTTP 404. The map keys are the entire reachable surface;
//     nothing outside ai.mcp.McpMethodServices is ever callable from the endpoint.
String serviceName = null
if (!isNotification && err == null) {
    serviceName = [
            'server/discover': 'ai.mcp.McpMethodServices.discover#Server',
            'initialize':      'ai.mcp.McpMethodServices.initialize#Server',   // legacy compat
            'tools/list':      'ai.mcp.McpMethodServices.list#Tools',
            'tools/call':      'ai.mcp.McpMethodServices.call#Tools',
    ].get(method)
    if (serviceName == null) {
        httpStatus = 404; err = [code: -32601, message: 'Method not found: ' + method] }
}

if (isNotification) {
    httpStatus = 202; response = null
} else if (err != null) {
    response = [jsonrpc: '2.0', id: id, error: err]
} else {
    // 11. call the method service; it returns a plain result Map or an error Map
    Map methodOut = ec.service.sync().name(serviceName).parameters([params: params]).call()
    if (methodOut.error != null) {
        response = [jsonrpc: '2.0', id: id, error: methodOut.error]   // e.g. unknown tool: JSON-RPC error, HTTP 200
    } else {
        // envelope belongs to the engine: resultType default (a method service may
        // override) and serverInfo in every result's _meta
        String compVersion = 'unknown'
        try { compVersion = ec.factory.componentInfoMap?.get('moqui-ai')?.version ?: 'unknown' } catch (Throwable t) {}
        Map result = [resultType: 'complete'] + (Map) methodOut.result
        result.put('_meta', ['io.modelcontextprotocol/serverInfo': [name: 'moqui-ai', version: compVersion]])
        response = [jsonrpc: '2.0', id: id, result: result]
    }
}

// ScriptServiceRunner uses the script's RETURN VALUE as the whole service result when it is
// a Map (ScriptServiceRunner.java:60); return null so out-parameters are read from context.
return null
