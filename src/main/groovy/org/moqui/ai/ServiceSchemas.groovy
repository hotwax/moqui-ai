package org.moqui.ai

import org.moqui.context.ExecutionContextFactory
import org.moqui.impl.service.ServiceFacadeImpl
import org.moqui.impl.service.ServiceDefinition
import org.moqui.impl.util.RestSchemaUtil

/** JSON Schemas for a Moqui service's contract, used to tell an LLM (agent tools) or an MCP
 *  client how to call it. Replaced ToolSchemaBuilder (design decision 14): the schemas come
 *  from the framework's RestSchemaUtil — nested objects, array items, formats, descriptions,
 *  defaults — with the implicit auth parameters removed. One generator for agents and MCP. */
class ServiceSchemas {
    private static final List<String> AUTH_PARAMS = ['authUsername', 'authPassword', 'authTenantId']

    /** Schema for the in-parameters; fail-loud on an unknown service. */
    static Map<String, Object> inSchema(ExecutionContextFactory ecf, String serviceName) {
        Map<String, Object> schema = RestSchemaUtil.getJsonSchemaMapIn(definition(ecf, serviceName))
        Map props = (Map) schema.get("properties")
        for (String p in AUTH_PARAMS) props.remove(p)
        List required = (List) schema.get("required")
        if (required != null) { required.removeAll(AUTH_PARAMS); if (required.isEmpty()) schema.remove("required") }
        return schema
    }

    /** Schema for the out-parameters; fail-loud on an unknown service. */
    static Map<String, Object> outSchema(ExecutionContextFactory ecf, String serviceName) {
        return RestSchemaUtil.getJsonSchemaMapOut(definition(ecf, serviceName))
    }

    private static ServiceDefinition definition(ExecutionContextFactory ecf, String serviceName) {
        ServiceDefinition sd = ((ServiceFacadeImpl) ecf.service).getServiceDefinition(serviceName)
        if (sd == null) throw new IllegalArgumentException("Unknown service for tool: ${serviceName}")
        return sd
    }
}
