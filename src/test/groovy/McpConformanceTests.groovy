import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui

/** MCP step 4: protocol conformance (spec: docs/specs/2026-08-23-mcp-server-design.md,
 *  "Protocol conformance"). All of it lives in the engine; the screen only extracts headers
 *  and passes them as plain parameters, which is what keeps every rule testable here.
 *  Status codes are asserted via the engine's httpStatus out-parameter (applied to the real
 *  response when a web context exists; verified over HTTP in the live checks). */
class McpConformanceTests extends Specification {
    @Shared ExecutionContext ec
    static final String VER = '2026-07-28'

    def setupSpec() { System.setProperty('mcp_enabled', 'Y'); ec = Moqui.getExecutionContext() }
    def cleanupSpec() { if (ec != null) ec.destroy() }
    def setup() { ec.message.clearErrors() }

    private static Map meta() {
        // clientCapabilities is REQUIRED but may be an empty object — presence, not truthiness
        return ['io.modelcontextprotocol/protocolVersion': VER,
                'io.modelcontextprotocol/clientCapabilities': [:]]
    }
    /** Valid baseline request; overrides replace, removeKeys delete (to simulate absence). */
    private Map call(Map overrides = [:], List<String> removeKeys = []) {
        Map p = [jsonrpc: '2.0', method: 'server/discover', id: 1,
                 params: [_meta: meta()],
                 protocolVersionHeader: VER, mcpMethodHeader: 'server/discover']
        p.putAll(overrides)
        for (String k in removeKeys) p.remove(k)
        return (Map) ec.service.sync().name("ai.mcp.McpServices.dispatch#Request").parameters(p).call()
    }

    def "a valid request passes every gate and the result carries serverInfo"() {
        when:
        Map out = call()
        Map result = (Map) ((Map) out.response).result

        then:
        out.httpStatus == 200
        result.resultType == 'complete'
        ((Map) ((Map) result.get('_meta')).get('io.modelcontextprotocol/serverInfo')).name == 'moqui-ai'
    }

    def "a modern request (version header present) missing Mcp-Method is a 400 HeaderMismatch"() {
        when:
        Map out = call([:], ['mcpMethodHeader'])

        then:
        out.httpStatus == 400
        ((Map) ((Map) out.response).error).code == -32020
    }

    def "an Mcp-Method header that disagrees with the body is a 400 HeaderMismatch"() {
        when:
        Map out = call([mcpMethodHeader: 'tools/list'])

        then:
        out.httpStatus == 400
        ((Map) ((Map) out.response).error).code == -32020
    }

    def "an unknown protocol version is a 400 UnsupportedProtocolVersion listing supported"() {
        when: "a version that is neither implemented nor a known legacy revision"
        Map out = call([protocolVersionHeader: '2099-01-01',
                        params: [_meta: ['io.modelcontextprotocol/protocolVersion': '2099-01-01',
                                         'io.modelcontextprotocol/clientCapabilities': [:]]]])

        then:
        out.httpStatus == 400
        ((Map) ((Map) out.response).error).code == -32022
        ((Map) ((Map) ((Map) out.response).error).data).supportedVersions == [VER]
    }

    def "a header that disagrees with the _meta protocolVersion is a 400 HeaderMismatch"() {
        when:
        Map out = call([params: [_meta: ['io.modelcontextprotocol/protocolVersion': '2025-06-18',
                                         'io.modelcontextprotocol/clientCapabilities': [:]]]])

        then:
        out.httpStatus == 400
        ((Map) ((Map) out.response).error).code == -32020
    }

    def "_meta missing clientCapabilities is a 400 invalid params"() {
        when:
        Map out = call([params: [_meta: ['io.modelcontextprotocol/protocolVersion': VER]]])

        then:
        out.httpStatus == 400
        ((Map) ((Map) out.response).error).code == -32602
    }

    def "tools/call without an Mcp-Name header is a 400 HeaderMismatch"() {
        when:
        Map out = call([method: 'tools/call', mcpMethodHeader: 'tools/call',
                        params: [_meta: meta(), name: 'no_such', arguments: [:]]])

        then:
        out.httpStatus == 400
        ((Map) ((Map) out.response).error).code == -32020
    }

    def "a Base64-sentinel Mcp-Name is decoded before comparison"() {
        when: "=?base64?bm9fc3VjaA==?= decodes to no_such; passing the gate reaches the catalog"
        Map out = call([method: 'tools/call', mcpMethodHeader: 'tools/call',
                        mcpNameHeader: '=?base64?bm9fc3VjaA==?=',
                        params: [_meta: meta(), name: 'no_such', arguments: [:]]])

        then:
        out.httpStatus == 200   // unknown TOOL is a JSON-RPC-level error, not an HTTP error
        ((Map) ((Map) out.response).error).code == -32602
        ((String) ((Map) ((Map) out.response).error).message).contains('no_such')
    }

    def "an Mcp-Name that disagrees with params.name is a 400 HeaderMismatch"() {
        when:
        Map out = call([method: 'tools/call', mcpMethodHeader: 'tools/call',
                        mcpNameHeader: 'other_tool',
                        params: [_meta: meta(), name: 'no_such', arguments: [:]]])

        then:
        out.httpStatus == 400
        ((Map) ((Map) out.response).error).code == -32020
    }

    def "an unknown method is 404 with -32601"() {
        when:
        Map out = call([method: 'foo/bar', mcpMethodHeader: 'foo/bar'])

        then:
        out.httpStatus == 404
        ((Map) ((Map) out.response).error).code == -32601
    }

    def "a body that is not JSON-RPC 2.0 is a 400 invalid request"() {
        when:
        Map out = call([jsonrpc: '1.0'])

        then:
        out.httpStatus == 400
        ((Map) ((Map) out.response).error).code == -32600
    }

    def "an id-less message is a notification: 202 accepted, no response body"() {
        when:
        Map out = call([:], ['id'])

        then:
        out.httpStatus == 202
        out.response == null
    }

    def "a headerless legacy initialize gets a proper legacy response"() {
        when: "what the claude CLI actually sends today: initialize, no headers, no _meta"
        Map out = ec.service.sync().name("ai.mcp.McpServices.dispatch#Request")
                .parameters([jsonrpc: '2.0', method: 'initialize', id: 0,
                             params: [protocolVersion: '2025-06-18', capabilities: [:],
                                      clientInfo: [name: 'claude-code', version: 'x']]]).call()
        Map result = (Map) ((Map) out.response).result

        then:
        out.httpStatus == 200
        result.protocolVersion == '2025-06-18'
        ((Map) ((Map) result.capabilities).tools).listChanged == false
        ((Map) result.serverInfo).name == 'moqui-ai'
    }

    def "a headerless legacy tools/list works"() {
        when:
        Map out = ec.service.sync().name("ai.mcp.McpServices.dispatch#Request")
                .parameters([jsonrpc: '2.0', method: 'tools/list', id: 1, params: [:]]).call()
        Map result = (Map) ((Map) out.response).result

        then:
        out.httpStatus == 200
        ((List) result.tools).size() >= 2
    }

    def "a headerless legacy notification (initialized) is accepted with 202"() {
        when:
        Map out = ec.service.sync().name("ai.mcp.McpServices.dispatch#Request")
                .parameters([jsonrpc: '2.0', method: 'notifications/initialized', params: [:]]).call()

        then:
        out.httpStatus == 202
        out.response == null
    }

    def "a 2025-11-25 client (version header, no Mcp-Method) is served as legacy"() {
        when: "what the claude CLI sends after its initialize handshake"
        Map out = ec.service.sync().name("ai.mcp.McpServices.dispatch#Request")
                .parameters([jsonrpc: '2.0', method: 'tools/list', id: 1,
                             protocolVersionHeader: '2025-11-25']).call()
        Map result = (Map) ((Map) out.response).result

        then:
        out.httpStatus == 200
        ((List) result.tools).size() >= 2
    }

    def "a body parse error is a 400 with -32700"() {
        when:
        Map out = call([parseError: 'unexpected token'])

        then:
        out.httpStatus == 400
        ((Map) ((Map) out.response).error).code == -32700
    }
}
