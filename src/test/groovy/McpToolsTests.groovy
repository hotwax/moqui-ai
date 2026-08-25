import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui

/** MCP step 2: tools/list (spec: docs/specs/2026-08-23-mcp-server-design.md).
 *  The tool catalog comes from *.mcp.xml files in component service directories; each tool's
 *  schemas are derived from the live ServiceDefinition via RestSchemaUtil, with the implicit
 *  auth parameters removed. The scan never polices the service verb: declaring a tool in the
 *  file IS the exposure decision, and only effect="read" claims the readOnlyHint annotation.
 *  Scan-rule cases use explicit fixture locations under src/test/resources so the production
 *  scan never sees them. */
class McpToolsTests extends Specification {
    @Shared ExecutionContext ec
    static final String FIX = "component://moqui-ai/src/test/resources/mcp/aitest.mcp.xml"
    static final String FIX_DUP = "component://moqui-ai/src/test/resources/mcp/aitest-dup.mcp.xml"

    def setupSpec() {
        System.setProperty('mcp_enabled', 'Y')   // the endpoint ships dark (default N)
        ec = Moqui.getExecutionContext()
        // the denylist rows the operator-floor test relies on (idempotent; other specs load it too)
        ec.artifactExecution.disableAuthz()
        ec.transaction.runRequireNew(30, "mcp tools test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        })
        ec.artifactExecution.enableAuthz()
    }
    def cleanupSpec() { if (ec != null) ec.destroy() }
    def setup() { ec.message.clearErrors() }

    private Map dispatch(String method, Map params = [:]) {
        Map out = ec.service.sync().name("ai.mcp.McpServices.dispatch#Request")
                .parameters([jsonrpc: '2.0', method: method, id: 1,
                             params: [_meta: ['io.modelcontextprotocol/protocolVersion': '2026-07-28', 'io.modelcontextprotocol/clientCapabilities': [:]]] + params,
                             protocolVersionHeader: '2026-07-28', mcpMethodHeader: method]).call()
        return (Map) out.response
    }
    private Map listTools() { return (Map) dispatch('tools/list').result }
    private List<Map> scan(List<String> locations) {
        Map out = ec.service.sync().name("ai.mcp.McpServices.build#ToolCatalog")
                .parameters([locationList: locations]).call()
        return (List<Map>) out.toolList
    }

    def "tools/list returns the shipped tools sorted, with the required list fields"() {
        when:
        Map resp = dispatch('tools/list')
        Map result = (Map) resp.result
        List<Map> tools = (List<Map>) result.tools

        then:
        resp.error == null
        result.resultType == 'complete'
        result.ttlMs == 300000
        result.cacheScope == 'public'
        tools.collect { it.name } == tools.collect { it.name }.sort()
        tools.find { it.name == 'get_ai_spend' } != null
        tools.find { it.name == 'list_ai_models' } != null
    }

    def "a listed tool carries schemas and the read-only annotation, but never its service name"() {
        when:
        Map tool = ((List<Map>) listTools().tools).find { it.name == 'get_ai_spend' }

        then:
        tool.title == 'Get AI Spend'
        tool.description
        ((Map) tool.inputSchema).get('type') == 'object'
        ((Map) tool.inputSchema).get('properties').keySet() == ['agentName', 'userId', 'fromDate', 'thruDate', 'groupBy'] as Set
        ((Map) tool.outputSchema).get('properties').containsKey('totalCost')
        ((Map) tool.annotations).readOnlyHint == true
        tool.serviceName == null
    }

    def "a no-parameter service yields an object inputSchema with empty properties"() {
        when:
        Map tool = ((List<Map>) listTools().tools).find { it.name == 'list_ai_models' }

        then:
        ((Map) tool.inputSchema).get('type') == 'object'
        ((Map) tool.inputSchema).get('properties').isEmpty()
    }

    def "declared parameters narrow the schema and fixed parameters never reach it"() {
        when:
        List<Map> tools = scan([FIX])
        Map narrowed = tools.find { it.name == 'echo_text' }
        Map fixed = tools.find { it.name == 'echo_fixed' }

        then:
        ((Map) narrowed.inputSchema).get('properties').keySet() == ['text'] as Set
        ((Map) narrowed.inputSchema).get('required') == ['text']
        ((Map) fixed.inputSchema).get('properties').keySet() == ['text'] as Set
        fixed.fixedParameters == [repeat: '2']
    }

    def "an unknown-service tool is skipped; a write-verb tool loads — the author owns the exposure"() {
        when:
        List<Map> tools = scan([FIX])

        then:
        tools.find { it.name == 'echo_missing' } == null
        tools.find { it.name == 'echo_text' } != null
        Map mutating = tools.find { it.name == 'echo_mutating' }
        mutating != null
        mutating.annotations == null   // nothing declared, nothing claimed: clients assume destructive
    }

    def "a denylisted service is dropped: the operator floor beats the file"() {
        when: "the fixture declares a tool over a service the seeded AiToolDenylist blocks"
        List<Map> tools = scan([FIX])

        then:
        tools.find { it.name == 'change_password' } == null
        tools.find { it.name == 'echo_text' } != null
    }

    def "a duplicate tool name across files is rejected, first declaration wins"() {
        when:
        List<Map> tools = scan([FIX, FIX_DUP])

        then:
        tools.count { it.name == 'echo_text' } == 1
    }

    def "a malformed file is logged and skipped; later files still load"() {
        when: "the malformed file comes FIRST, so surviving it is what loads the second"
        List<Map> tools = scan(["component://moqui-ai/src/test/resources/mcp/aitest-malformed.mcp.xml", FIX])

        then:
        tools.find { it.name == 'never_loads' } == null
        tools.find { it.name == 'echo_text' } != null
    }
}
