/* Implementation of ai.mcp.McpServices.get#ToolCatalog — the FIXED scan source plus cache,
 * the RestApi.loadRootResourceNode analog (spec: "Boot scan"). Takes no inputs, so nothing
 * an endpoint caller sends can ever point it at a file; the rules live in
 * BuildToolCatalog.groovy. Contract in service/ai/mcp/McpServices.xml. */

def cache = ec.cache.getCache("ai.mcp.tool.catalog")
List cached = (List) cache.get("catalog")
if (cached != null) { toolList = cached; return }

List locationList = []
for (String location in ec.factory.getComponentBaseLocations().values()) {
    def serviceDirRr = ec.resource.getLocationReference(location + "/service")
    if (!serviceDirRr.supportsAll() || !serviceDirRr.isDirectory()) continue
    for (def rr in serviceDirRr.directoryEntries)
        if (rr.fileName.endsWith(".mcp.xml")) locationList.add(rr.location as String)
}
Map buildOut = ec.service.sync().name("ai.mcp.McpServices.build#ToolCatalog")
        .parameters([locationList: locationList]).call()
toolList = buildOut.toolList
cache.put("catalog", toolList)

// ScriptServiceRunner uses the script's RETURN VALUE as the whole service result when it is
// a Map (ScriptServiceRunner.java:60); return null so out-parameters are read from context.
return null
