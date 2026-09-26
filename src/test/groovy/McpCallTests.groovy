import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui

/** MCP step 3: tools/call (spec: docs/specs/2026-08-23-mcp-server-design.md).
 *  call#Tools resolves the tool in the catalog (unknown name -> protocol error -32602) and
 *  exec#Tool runs it: arguments are filtered to the exposed inputSchema, fixed parameters are
 *  injected server-side and always win, the backing service runs in its own transaction, and
 *  failures become isError:true text (message facade left clean) — never an exception or a
 *  stack trace on the wire. Fixture-based cases go through exec#Tool with a toolDef built by
 *  build#ToolCatalog, so nothing test-only enters the production catalog. */
class McpCallTests extends Specification {
    @Shared ExecutionContext ec
    static final String FIX = "component://moqui-ai/src/test/resources/mcp/aitest.mcp.xml"

    def setupSpec() {
        System.setProperty('mcp_enabled', 'Y')   // the endpoint ships dark (default N)
        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
        ec.transaction.runRequireNew(30, "mcp call test setup", {
            ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
        })
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.artifactExecution.enableAuthz()
    }
    def cleanupSpec() { if (ec != null) ec.destroy() }
    def setup() {
        ec.artifactExecution.disableAuthz()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
    }
    def cleanup() { ec.artifactExecution.enableAuthz() }

    private Map dispatch(String method, Map params) {
        return (Map) ec.service.sync().name("ai.mcp.McpServices.dispatch#Request")
                .parameters([jsonrpc: '2.0', method: method, id: 1,
                             params: [_meta: ['io.modelcontextprotocol/protocolVersion': '2026-07-28', 'io.modelcontextprotocol/clientCapabilities': [:]]] + params,
                             protocolVersionHeader: '2026-07-28', mcpMethodHeader: method,
                             mcpNameHeader: params?.name]).call().response
    }
    private Map exec(Map toolDef, Map arguments) {
        return (Map) ec.service.sync().name("ai.mcp.McpServices.exec#Tool")
                .parameters([toolDef: toolDef, arguments: arguments]).call().result
    }
    private Map fixtureTool(String name) {
        List<Map> tools = (List<Map>) ec.service.sync().name("ai.mcp.McpServices.build#ToolCatalog")
                .parameters([locationList: [FIX]]).call().toolList
        return (Map) tools.find { it.name == name }
    }

    def "tools/call runs a catalog tool and returns structured and text content"() {
        when:
        Map resp = dispatch('tools/call', [name: 'get_ai_spend', arguments: [groupBy: 'none']])
        Map result = (Map) resp.result

        then:
        resp.error == null
        result.resultType == 'complete'
        result.isError == false
        ((Map) result.structuredContent).containsKey('totalCost')
        ((Map) ((List) result.content)[0]).type == 'text'
        ((String) ((Map) ((List) result.content)[0]).text).contains('totalCost')
    }

    def "an unknown tool name is a protocol error -32602"() {
        when:
        Map resp = dispatch('tools/call', [name: 'no_such_tool', arguments: [:]])

        then:
        resp.result == null
        ((Map) resp.error).code == -32602
        ((String) ((Map) resp.error).message).contains('no_such_tool')
    }

    def "fixed parameters are injected server-side and win over client arguments"() {
        when: "echo_fixed fixes repeat=2; the client tries to override with 5"
        Map result = exec(fixtureTool('echo_fixed'), [text: 'ab', repeat: 5])

        then:
        result.isError == false
        ((Map) result.structuredContent).echoed == 'abab'
    }

    def "arguments outside the exposed schema are dropped, never passed through"() {
        when: "echo_text exposes only text; repeat must not reach the service"
        Map result = exec(fixtureTool('echo_text'), [text: 'a', repeat: 5])

        then:
        result.isError == false
        ((Map) result.structuredContent).echoed == 'a'
    }

    def "an unauthenticated call is refused with isError, even though dispatch itself is anonymous"() {
        given: "no real user: the anonymous-view chain alone must not be enough to run a tool"
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).logoutUser()

        when:
        Map resp = dispatch('tools/call', [name: 'get_ai_spend', arguments: [:]])
        Map result = (Map) resp.result

        then:
        result.isError == true
        ((String) ((Map) ((List) result.content)[0]).text).toLowerCase().contains('authentication required')

        cleanup:
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
    }

    def "a tool call writes an AiToolCall audit row: source, user, outcome"() {
        when:
        dispatch('tools/call', [name: 'get_ai_spend', arguments: [:]])
        def row = ec.entity.find("moqui.ai.AiToolCall")
                .condition("toolName", "get_ai_spend").condition("sourceEnumId", "AI_TCS_MCP")
                .orderBy("-toolCallId").list()[0]

        then:
        row != null
        row.userId == "AiTestUser"
        row.success == "Y"
        row.serviceName == "ai.CostServices.get#AiSpend"
        row.agentRunId == null
        row.durationMs != null
    }

    def "a failed tool call is audited with the error text"() {
        when:
        exec(fixtureTool('echo_full'), [text: 'x', repeat: -1])
        def row = ec.entity.find("moqui.ai.AiToolCall")
                .condition("toolName", "echo_full").condition("sourceEnumId", "AI_TCS_MCP")
                .orderBy("-toolCallId").list()[0]

        then:
        row != null
        row.success == "N"
        ((String) row.errorText).contains('repeat must be')
    }

    def "a service error returns isError true with the message text, and leaves no residue"() {
        when: "echo_full exposes repeat; -1 makes the service return an error"
        Map result = exec(fixtureTool('echo_full'), [text: 'x', repeat: -1])

        then:
        result.isError == true
        ((String) ((Map) ((List) result.content)[0]).text).contains('repeat must be')
        result.structuredContent == null
        !ec.message.hasError()
    }
}
