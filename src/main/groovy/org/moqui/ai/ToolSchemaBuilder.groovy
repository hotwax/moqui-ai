package org.moqui.ai

import org.moqui.context.ExecutionContextFactory
import org.moqui.impl.service.ServiceFacadeImpl
import org.moqui.impl.service.ServiceDefinition
import org.moqui.util.MNode

/** Generates an OpenAPI/JSON-Schema-style object schema for a Moqui service's in-parameters.
 *  Maps Moqui parameter types to JSON Schema types. Used to tell the LLM how to call a tool. */
class ToolSchemaBuilder {
    static Map<String, Object> build(ExecutionContextFactory ecf, String serviceName) {
        ServiceDefinition sd = ((ServiceFacadeImpl) ecf.service).getServiceDefinition(serviceName)
        if (sd == null) throw new IllegalArgumentException("Unknown service for tool: ${serviceName}")

        Map<String, Object> properties = [:]
        List<String> required = []
        for (String pName in sd.getInParameterNames()) {
            // Skip Moqui's implicit auth/system parameters
            if (pName in ['authUsername', 'authPassword', 'authTenantId']) continue
            MNode p = sd.getInParameter(pName)
            Map<String, Object> prop = [type: jsonType(p.attribute("type"))]
            String desc = p.first("description")?.text
            if (desc) prop.description = desc.trim()
            properties.put(pName, prop)
            if (p.attribute("required") == "true") required.add(pName)
        }
        Map<String, Object> schema = [type: "object", properties: properties]
        if (required) schema.required = required
        return schema
    }

    /** Moqui parameter type -> JSON Schema type. Defaults to string. */
    static String jsonType(String moquiType) {
        if (moquiType == null) return "string"
        switch (moquiType) {
            case "Integer": case "java.lang.Integer":
            case "Long":    case "java.lang.Long":
            case "BigInteger": case "java.math.BigInteger":
                return "integer"
            case "Float":  case "java.lang.Float":
            case "Double": case "java.lang.Double":
            case "BigDecimal": case "java.math.BigDecimal":
                return "number"
            case "Boolean": case "java.lang.Boolean":
                return "boolean"
            case "List": case "java.util.List": case "Collection": case "Set":
                return "array"
            case "Map": case "java.util.Map":
                return "object"
            default:
                return "string"   // String, Date, Time, Timestamp -> string
        }
    }
}
