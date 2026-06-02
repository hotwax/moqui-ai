package org.moqui.ai

import org.moqui.context.ExecutionContextFactory
import org.moqui.util.MNode

/** Scans every component's ai/ directory for *.tools.xml, validates each tool's service
 *  reference (fail-loud), and builds an immutable catalog Map keyed by service name. Each
 *  entry is a toolDef Map: [toolName, serviceName, description, requiresApproval, schema]
 *  (see the plan's "Canonical Map shapes"). Reload returns a NEW map; callers swap
 *  atomically and keep the last-good map on failure. */
class DefinitionLoader {
    /** Build the catalog by scanning all components' ai/ dirs. Throws on any bad reference. */
    static Map<String, Map> loadCatalog(ExecutionContextFactory ecf) {
        Map<String, Map> catalog = [:]
        for (String componentName in ecf.getComponentBaseLocations().keySet()) {
            String base = ecf.getComponentBaseLocations().get(componentName)
            org.moqui.resource.ResourceReference aiDir = ecf.resource.getLocationReference(base + "/ai")
            if (aiDir == null || !aiDir.getExists() || !aiDir.isDirectory()) continue
            for (org.moqui.resource.ResourceReference rr in aiDir.getDirectoryEntries()) {
                if (!rr.fileName.endsWith(".tools.xml")) continue
                parseToolsNode(ecf, MNode.parse(rr), catalog)
            }
        }
        return catalog
    }

    /** Parse one <tools> node into the catalog, validating each service ref (fail-loud). */
    static void parseToolsNode(ExecutionContextFactory ecf, MNode toolsNode, Map<String, Map> catalog) {
        for (MNode toolNode in toolsNode.children("tool")) {
            String serviceName = toolNode.attribute("service")
            // Fail-loud: ToolSchemaBuilder.build throws IllegalArgumentException on unknown service
            Map schema = ToolSchemaBuilder.build(ecf, serviceName)
            catalog.put(serviceName, [toolName: serviceName, serviceName: serviceName,
                    description: toolNode.attribute("description"),
                    requiresApproval: toolNode.attribute("requires-approval") == "true",
                    schema: schema])
        }
    }
}
