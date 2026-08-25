import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui

/** MCP endpoint (spec: docs/specs/2026-08-23-mcp-server-design.md, "Declarative dispatch").
 *  The engine ai.mcp.McpServices.dispatch#Request resolves MCP method names through a literal
 *  allowlist map (the MCP request vocabulary is a closed set) and wraps the result in the
 *  JSON-RPC envelope. An absent map key IS the -32601 answer. */
class McpDispatchTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() { System.setProperty('mcp_enabled', 'Y'); ec = Moqui.getExecutionContext() }
    def cleanupSpec() { if (ec != null) ec.destroy() }
    def setup() { ec.message.clearErrors() }

    private Map headered(String method, Map extraParams = [:], Object id = 1) {
        return [jsonrpc: '2.0', method: method, id: id,
                params: [_meta: ['io.modelcontextprotocol/protocolVersion': '2026-07-28', 'io.modelcontextprotocol/clientCapabilities': [:]]] + extraParams,
                protocolVersionHeader: '2026-07-28', mcpMethodHeader: method]
    }

    def "with the endpoint disabled every request is a dark 404"() {
        given:
        System.setProperty('mcp_enabled', 'N')

        when:
        Map out = ec.service.sync().name("ai.mcp.McpServices.dispatch#Request")
                .parameters(headered('server/discover', [:], 'dark-1')).call()

        then:
        out.httpStatus == 404
        out.response == null

        cleanup:
        System.setProperty('mcp_enabled', 'Y')
    }

    def "server/discover returns the discovery result in a JSON-RPC envelope"() {
        when:
        Map out = ec.service.sync().name("ai.mcp.McpServices.dispatch#Request")
                .parameters(headered('server/discover', [:], 'req-1')).call()
        Map resp = (Map) out.response

        then:
        resp.jsonrpc == '2.0'
        resp.id == 'req-1'
        resp.error == null
        Map result = (Map) resp.result
        result.resultType == 'complete'
        result.supportedVersions == ['2026-07-28']
        ((Map) ((Map) result.capabilities).tools).listChanged == false
        result.ttlMs == 300000
        result.cacheScope == 'public'
    }

    def "unknown method returns -32601 and echoes the numeric id unchanged"() {
        when:
        Map out = ec.service.sync().name("ai.mcp.McpServices.dispatch#Request")
                .parameters(headered('foo/bar', [:], 7)).call()
        Map resp = (Map) out.response

        then:
        resp.id == 7
        resp.result == null
        ((Map) resp.error).code == -32601
        ((String) ((Map) resp.error).message).contains('foo/bar')
    }

    def "missing method (and so a missing Mcp-Method header) is a HeaderMismatch"() {
        when:
        Map out = ec.service.sync().name("ai.mcp.McpServices.dispatch#Request")
                .parameters([jsonrpc: '2.0', id: 3, params: [_meta: ['io.modelcontextprotocol/protocolVersion': '2026-07-28', 'io.modelcontextprotocol/clientCapabilities': [:]]],
                             protocolVersionHeader: '2026-07-28']).call()
        Map resp = (Map) out.response

        then:
        ((Map) resp.error).code == -32020
    }

    def "a method name with service-path characters cannot escape the allowlist"() {
        when:
        Map out = ec.service.sync().name("ai.mcp.McpServices.dispatch#Request")
                .parameters(headered('org.moqui.impl.UserServices/create', [:], 4)).call()
        Map resp = (Map) out.response

        then:
        ((Map) resp.error).code == -32601
    }
}
