/* Implementation of ai.mcp.McpServices.build#ToolCatalog — the pure per-file transformer
 * holding all the scan rules (spec: "Boot scan"). No cache, no default source; the unit the
 * rule tests exercise. Contract in service/ai/mcp/McpServices.xml.
 *
 * Rules, in order per tool: duplicate name rejected (first declaration wins, both files
 * logged); unknown backing service skipped. The scan never polices the service verb:
 * declaring a tool in a *.mcp.xml IS the exposure decision and the author owns it (design
 * decision 16); only effect="read" claims the readOnlyHint annotation. A file that fails to
 * parse is logged and skipped as a WHOLE file: RestApi fails the boot on a malformed
 * *.rest.xml because its scan runs at boot, on the developer; ours runs lazily on the first
 * tools/list, so a throw here would land on clients as a dead endpoint instead. A failed
 * rule is a log line, never a dead endpoint. */

import org.moqui.util.MNode
import org.moqui.ai.ServiceSchemas

// The operator floor (design decision 18): AiToolDenylist patterns veto services no matter
// what a *.mcp.xml declares — the file author proposes, the deployment operator can block
// without touching a vendor component's files. Read once per build; the catalog cache means
// a denylist edit takes effect on the next cache rebuild (restart or cache clear).
List<String> denyPatterns = ec.entity.find("moqui.ai.AiToolDenylist").useCache(true).disableAuthz().list()
        .collect { it.getNoCheckSimple("servicePattern") as String }
Map<String, Map> byName = new LinkedHashMap<>()
Map<String, String> nameOwner = [:]

for (String loc in locationList) {
    MNode root = null
    try { root = MNode.parse(ec.resource.getLocationReference(loc)) }
    catch (Throwable t) { ec.logger.error("MCP scan: cannot parse ${loc}, skipping the file: ${t.message}") }
    if (root == null) continue

    for (MNode toolNode in root.children("tool")) {
        String toolName = toolNode.attribute("name")
        String serviceName = toolNode.first("service")?.attribute("name")
        if (byName.containsKey(toolName)) {
            ec.logger.error("MCP scan: duplicate tool '${toolName}' in ${loc} (first declared in ${nameOwner.get(toolName)}); rejecting the duplicate")
            continue
        }
        if (serviceName == null || !ec.service.isServiceDefined(serviceName)) {
            ec.logger.error("MCP scan: tool '${toolName}' in ${loc} names unknown service '${serviceName}'; skipping")
            continue
        }
        String denied = denyPatterns.find { serviceName ==~ it }
        if (denied != null) {
            ec.logger.error("MCP scan: tool '${toolName}' in ${loc} backs onto denylisted service '${serviceName}' (pattern '${denied}'); dropping — the operator floor beats the file")
            continue
        }
        // schemas from the live ServiceDefinition via ServiceSchemas (implicit auth
        // parameters already removed — the same generator agents use, decision 14)
        Map inputSchema = ServiceSchemas.inSchema(ec.factory, serviceName)
        Map outputSchema = ServiceSchemas.outSchema(ec.factory, serviceName)
        Map props = (Map) inputSchema.get("properties")
        List required = (List) inputSchema.get("required")

        // declared <parameter> children narrow the schema; fixed="..." values are held for
        // call-time injection and never exposed
        Map<String, String> fixedParameters = [:]
        List<MNode> parmNodes = toolNode.children("parameter")
        if (parmNodes) {
            Set exposed = [] as Set
            for (MNode pn in parmNodes) {
                String fixedVal = pn.attribute("fixed")
                if (fixedVal != null) fixedParameters.put(pn.attribute("name"), ec.resource.expand(fixedVal, null))
                else exposed.add(pn.attribute("name"))
            }
            props.keySet().retainAll(exposed)
            if (required != null) { required.retainAll(exposed); if (required.isEmpty()) inputSchema.remove("required") }
        }

        // annotations come from the author's effect declaration only: effect="read" claims
        // readOnlyHint; anything else claims nothing and MCP client defaults treat the tool
        // as potentially destructive (the safe direction)
        Map annotations = toolNode.attribute("effect") == 'read' ? [readOnlyHint: true] : null
        byName.put(toolName, [name: toolName, title: toolNode.attribute("title"),
                description: toolNode.attribute("description"), serviceName: serviceName,
                inputSchema: inputSchema, outputSchema: outputSchema,
                fixedParameters: fixedParameters, annotations: annotations])
        nameOwner.put(toolName, loc)
    }
}
toolList = byName.values().toList().sort { it.name }

// ScriptServiceRunner uses the script's RETURN VALUE as the whole service result when it is
// a Map (ScriptServiceRunner.java:60); return null so out-parameters are read from context.
return null
