package org.moqui.ai

import org.moqui.context.ExecutionContextFactory
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl
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
        ServiceDefinition sd = definition(ecf, serviceName)
        if (sd == null) return entitySchema(ecf, serviceName, false)
        Map<String, Object> schema = RestSchemaUtil.getJsonSchemaMapIn(sd)
        Map props = (Map) schema.get("properties")
        for (String p in AUTH_PARAMS) props.remove(p)
        List required = (List) schema.get("required")
        if (required != null) { required.removeAll(AUTH_PARAMS); if (required.isEmpty()) schema.remove("required") }
        return schema
    }

    /** Schema for the out-parameters; fail-loud on an unknown service. */
    static Map<String, Object> outSchema(ExecutionContextFactory ecf, String serviceName) {
        ServiceDefinition sd = definition(ecf, serviceName)
        if (sd == null) return entitySchema(ecf, serviceName, true)
        return RestSchemaUtil.getJsonSchemaMapOut(sd)
    }

    /** Null means "entity-auto service": no definition exists, but the name matches the
     *  implicit create#/update#/delete#/store# pattern on some entity. Anything else unknown
     *  fails loud. */
    private static ServiceDefinition definition(ExecutionContextFactory ecf, String serviceName) {
        ServiceFacadeImpl sfi = (ServiceFacadeImpl) ecf.service
        ServiceDefinition sd = sfi.getServiceDefinition(serviceName)
        if (sd == null && !sfi.isEntityAutoPattern(serviceName))
            throw new IllegalArgumentException("Unknown service for tool: ${serviceName}")
        return sd
    }

    /** Entity-auto schemas from the entity definition, via the same RestSchemaUtil type
     *  mapping services get. In: all fields, pk required for update/delete (create/store may
     *  sequence the pk). Out: create/store return the generated pk; update/delete return
     *  nothing. The REST-only '_entity' discriminator and title are stripped. */
    private static Map<String, Object> entitySchema(ExecutionContextFactory ecf, String serviceName, boolean out) {
        String verb = ServiceDefinition.getVerbFromName(serviceName)
        String noun = ServiceDefinition.getNounFromName(serviceName)
        EntityDefinition ed = ((EntityFacadeImpl) ecf.entity).getEntityDefinition(noun)
        if (ed == null) throw new IllegalArgumentException("Unknown entity for entity-auto tool: ${serviceName}")
        boolean returnsPk = verb in ['create', 'store']
        if (out && !returnsPk) return [type: 'object', properties: [:]] as Map<String, Object>
        Map<String, Object> schema = (Map<String, Object>) RestSchemaUtil.getJsonSchema(
                ed, out, false, null, null, null, null, false, null, null)
        ((Map) schema.get('properties')).remove('_entity')
        schema.remove('title')
        schema.put('type', 'object')
        if (!out && verb in ['update', 'delete']) schema.put('required', new ArrayList<String>(ed.getPkFieldNames()))
        return schema
    }
}
