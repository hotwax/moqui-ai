import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.ServiceSchemas

/** The schema wrapper that replaced ToolSchemaBuilder (design decision 14): schemas come from
 *  the framework's RestSchemaUtil (nested objects, array items, formats, descriptions,
 *  defaults) with the implicit auth parameters removed — one generator for agents and MCP. */
class ServiceSchemasTests extends Specification {
    @Shared ExecutionContext ec
    def setupSpec() { ec = Moqui.getExecutionContext() }
    def cleanupSpec() { if (ec != null) ec.destroy() }

    def "builds a JSON schema from a service's in-parameters, auth parameters removed"() {
        when:
        Map schema = ServiceSchemas.inSchema(ec.factory, "moqui.ai.test.TestServices.get#Echo")

        then:
        schema.get('type') == 'object'
        ((Map) ((Map) schema.get('properties')).get('text')).get('type') == 'string'
        ((Map) ((Map) schema.get('properties')).get('repeat')).get('type') == 'integer'
        schema.get('required') == ['text']
        !((Map) schema.get('properties')).containsKey('authUsername')
        !((Map) schema.get('properties')).containsKey('authPassword')
    }

    def "carries the richer RestSchemaUtil detail ToolSchemaBuilder flattened away"() {
        when:
        Map schema = ServiceSchemas.inSchema(ec.factory, "ai.CostServices.get#AiSpend")
        Map props = (Map) schema.get('properties')

        then: "Timestamp parameters get the date-time format; descriptions survive"
        ((Map) props.get('fromDate')).get('format') == 'date-time'
        ((String) ((Map) props.get('groupBy')).get('description')).contains('agent')
    }

    def "an unknown service fails loud"() {
        when:
        ServiceSchemas.inSchema(ec.factory, "no.such.Services.get#Nope")

        then:
        thrown(IllegalArgumentException)
    }

    // Entity-auto services (implicit create#/update#/delete#/store# on an entity) have no
    // ServiceDefinition; their schema comes from the entity definition instead. Ported from
    // PR #61's ToolSchemaBuilder change after that class was replaced by this one.

    def "entity-auto create schema comes from the entity fields, nothing required"() {
        when:
        Map schema = ServiceSchemas.inSchema(ec.factory, "create#moqui.basic.Enumeration")
        Map props = (Map) schema.get('properties')

        then:
        schema.get('type') == 'object'
        ((Map) props.get('enumId')).get('type') == 'string'
        ((Map) props.get('sequenceNum')).get('type') == 'number'
        ((Map) props.get('sequenceNum')).get('format') == 'int64'
        !props.containsKey('_entity')
        schema.get('required') == null
    }

    def "entity-auto update requires the primary key"() {
        when:
        Map schema = ServiceSchemas.inSchema(ec.factory, "update#moqui.basic.Enumeration")

        then:
        ((List) schema.get('required')).contains('enumId')
    }

    def "entity-auto create out-schema is the primary key"() {
        when:
        Map schema = ServiceSchemas.outSchema(ec.factory, "create#moqui.basic.Enumeration")
        Map props = (Map) schema.get('properties')

        then:
        props.keySet() == ['enumId'] as Set
    }

    def "an entity-auto name for an unknown entity fails loud"() {
        when:
        ServiceSchemas.inSchema(ec.factory, "create#no.such.Entity")

        then:
        thrown(IllegalArgumentException)
    }
}
