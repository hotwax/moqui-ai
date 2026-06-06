package org.moqui.ai

import org.moqui.context.ExecutionContextFactory
import org.moqui.entity.EntityValue

/** Builds the in-memory tool catalog from AiTool rows (DB is the source of truth — spec D3).
 *  Only ACTIVE + exposable tools are grant-eligible. The JSON schema for each tool is generated
 *  on demand from the LIVE service via ToolSchemaBuilder (never stored — spec §6). Returns a NEW
 *  map keyed by toolId; the factory swaps it atomically and also indexes it by toolName. */
class DefinitionLoader {
    static Map<String, Map> loadCatalog(ExecutionContextFactory ecf) {
        Map<String, Map> catalog = [:]
        // disableAuthz() returns whether authz was ALREADY disabled; only re-enable if WE disabled it
        // (matches the framework idiom). The previous `if (disabled) enableAuthz()` inverted this and
        // re-enabled authz for callers that had already disabled it (e.g. tests, data loads).
        boolean alreadyDisabled = ecf.getExecutionContext().artifactExecution.disableAuthz()
        try {
            for (EntityValue t in ecf.getExecutionContext().entity.find("moqui.ai.AiTool")
                    .condition("statusId", "AI_TOOL_ACTIVE").condition("exposable", "Y").list()) {
                String serviceName = t.serviceName as String
                Map schema
                try {
                    schema = ToolSchemaBuilder.build(ecf, serviceName)   // fail-loud per tool
                } catch (Exception e) {
                    // a seeded tool whose service was removed should not break the whole catalog at boot;
                    // log and skip (the authoring gate validates at write time — this is a defensive read).
                    org.slf4j.LoggerFactory.getLogger(DefinitionLoader).warn(
                        "AiTool ${t.toolId} (${t.toolName}) references missing service ${serviceName}; skipping")
                    continue
                }
                catalog.put(t.toolId as String, [toolId: t.toolId, toolName: t.toolName,
                    serviceName: serviceName, description: t.description,
                    requiresApproval: (t.requiresApproval == "Y"), effectEnumId: t.effectEnumId,
                    schema: schema])
            }
        } finally {
            if (!alreadyDisabled) ecf.getExecutionContext().artifactExecution.enableAuthz()
        }
        return catalog
    }
}
