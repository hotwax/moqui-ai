import groovy.json.JsonSlurper
import org.moqui.ai.DocumentMcpSupport
import spock.lang.Specification

import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

/** Pure transport tests. These execute the real adapters/scripts and use a service-boundary
 * fixture solely to provide the documented Poorti DTOs; they never initialize Moqui or a DB. */
class McpDocumentTransportTests extends Specification {
    static final String NS = 'co.hotwax.poorti.document.DocumentTemplateServices.'
    static final String URI = 'hotwax-document://preview/test-preview/pdf'
    static final String PNG_URI = 'hotwax-document://preview/test-preview/page/1'
    static final byte[] PNG = Base64.decoder.decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Zl1sAAAAASUVORK5CYII=')
    static final byte[] PDF = '%PDF-1.7\n%%EOF\n'.getBytes('UTF-8')

    def "catalog accepts only the paired trusted document adapter and metadata profile"() {
        expect:
        DocumentMcpSupport.catalogOptions(NS + 'get#DocumentTemplate', 'document', 'document-metadata') ==
                [resultAdapter: 'document', auditProfile: 'document-metadata']
        DocumentMcpSupport.catalogOptions(NS + 'get#DocumentPreview', 'document-preview', 'document-metadata') != null
        DocumentMcpSupport.catalogOptions('other.Services.get#Anything', null, null) == [:]

        and:
        DocumentMcpSupport.catalogOptions(NS + 'get#DocumentTemplate', 'passthrough', 'document-metadata') == null
        DocumentMcpSupport.catalogOptions(NS + 'get#DocumentTemplate', 'document', null) == null
        DocumentMcpSupport.catalogOptions(NS + 'read#DocumentArtifact', 'document', 'document-metadata') == null
        DocumentMcpSupport.catalogOptions('other.Services.get#Anything', 'document', 'document-metadata') == null
        DocumentMcpSupport.catalogOptions(NS + 'get#DocumentTemplate', null, 'document-metadata') == null
        DocumentMcpSupport.catalogOptions(NS + 'get#DocumentTemplate', null, null) == null
        DocumentMcpSupport.catalogOptions(NS + 'save#DocumentRevision', 'document-preview', 'document-metadata') == null
    }

    def "the catalog XSD permits known adapter values and rejects unknown ones"() {
        given:
        def schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(new File('xsd/mcp-api-1.xsd'))
        String prefix = '<mcp name="docs"><tool name="get_doc" description="Doc" '
        String suffix = '><service name="' + NS + 'get#DocumentTemplate"/></tool></mcp>'

        when:
        schema.newValidator().validate(new StreamSource(new StringReader(prefix +
                'result-adapter="document-preview" audit-profile="document-metadata"' + suffix)))

        then:
        noExceptionThrown()

        when:
        schema.newValidator().validate(new StreamSource(new StringReader(prefix + 'result-adapter="passthrough"' + suffix)))

        then:
        thrown(org.xml.sax.SAXException)
    }

    def "a document domain failure is unwrapped and audited without its reason or source"() {
        given:
        def calls = new BoundaryCalls([(NS + 'save#DocumentRevision'): [documentResult:
                [ok: false, code: 'PUBLICATION_CONFLICT', message: 'Publication changed.',
                 details: [expectedPublicationSequence: 2], source: 'private source']]])
        Map args = [documentTemplateId: 'T1', source: 'private source', reason: 'customer address', sourceIds: ['S1', 'S2']]

        when:
        Map result = runExec(calls, tool('save#DocumentRevision', 'document'), args)
        Map audit = calls.audit

        then:
        result.isError == true
        result.structuredContent == [ok: false, code: 'PUBLICATION_CONFLICT', message: 'Publication changed.',
                                     details: [expectedPublicationSequence: 2]]
        result.ttlMs == 0
        result.cacheScope == 'private'
        audit.success == 'N'
        audit.errorText == 'PUBLICATION_CONFLICT'
        !audit.toString().contains('private source')
        !audit.toString().contains('customer address')
        !audit.toString().contains('S1')
        new JsonSlurper().parseText(audit.arguments).sourceCount == 2
    }

    def "default tool behavior still returns the complete service result"() {
        given:
        def calls = new BoundaryCalls(['other.Services.get#Data': [value: 7]])
        Map definition = [name: 'get_data', serviceName: 'other.Services.get#Data',
                          inputSchema: [type: 'object', properties: [:]], fixedParameters: [:]]

        expect:
        runExec(calls, definition, [:]).structuredContent == [value: 7]
        new JsonSlurper().parseText(calls.audit.result) == [value: 7]
    }

    def "preview adds only approved resource links and one authorized bounded PNG"() {
        given:
        def calls = new BoundaryCalls([(NS + 'get#DocumentPreview'): [documentResult:
                [ok: true, previewId: 'test-preview', status: 'ready',
                 content: [[type: 'image', data: 'untrusted']], artifacts: [
                     [uri: URI, name: 'Preview PDF', mimeType: 'application/pdf', size: PDF.length, artifactKey: 'pdf'],
                     [uri: PNG_URI, name: 'Page 1', mimeType: 'image/png', size: PNG.length, artifactKey: 'page-1', pageNumber: 1,
                      widthPoints: 612, heightPoints: 792]]]],
                (NS + 'read#DocumentArtifact'): [artifactResult: [ok: true, uri: PNG_URI, mimeType: 'image/png', blob: PNG.encodeBase64().toString()]]])

        when:
        Map result = runExec(calls, tool('get#DocumentPreview', 'document-preview'), [previewId: 'test-preview'])

        then:
        result.isError == false
        result.content.findAll { it.type == 'resource_link' }*.uri == [URI, PNG_URI]
        result.content.findAll { it.type == 'image' }*.data == [PNG.encodeBase64().toString()]
        result.structuredContent.artifacts[1].subMap(['artifactKey', 'pageNumber', 'widthPoints', 'heightPoints']) ==
                [artifactKey: 'page-1', pageNumber: 1, widthPoints: 612, heightPoints: 792]
        !result.content.find { it.type == 'resource_link' && it.uri == PNG_URI }.containsKey('artifactKey')
        calls.requests.findAll { it.name == NS + 'read#DocumentArtifact' }*.arguments == [[uri: PNG_URI]]
        !result.structuredContent.containsKey('content')
        !calls.audit.toString().contains(PNG.encodeBase64().toString())
        !calls.audit.toString().contains('untrusted')
    }

    def "oversized PNG stays a resource link and never triggers a binary read"() {
        given:
        def calls = new BoundaryCalls([(NS + 'get#DocumentPreview'): [documentResult: [ok: true, artifacts: [
                [uri: PNG_URI, name: 'Page 1', mimeType: 'image/png', size: 1048577, artifactKey: 'page-1', pageNumber: 1]]]]])

        when:
        Map result = runExec(calls, tool('get#DocumentPreview', 'document-preview'), [:])

        then:
        result.content*.type == ['text', 'resource_link']
        calls.requests.every { it.name != NS + 'read#DocumentArtifact' }
    }

    def "an inline artifact domain denial keeps its safe document code: #code"() {
        given:
        def calls = new BoundaryCalls([(NS + 'get#DocumentPreview'): [documentResult: [ok: true, artifacts: [
                [uri: PNG_URI, name: 'Page 1', mimeType: 'image/png', size: PNG.length, artifactKey: 'page-1', pageNumber: 1]]]],
                (NS + 'read#DocumentArtifact'): [artifactResult: [ok: false, code: code, message: 'Preview is unavailable.',
                        details: [retryable: false]]]])

        when:
        Map result = runExec(calls, tool('get#DocumentPreview', 'document-preview'), [:])

        then:
        result.isError == true
        result.structuredContent == [ok: false, code: code, message: 'Preview is unavailable.', details: [retryable: false]]
        result.content*.type == ['text']
        calls.audit.success == 'N'
        calls.audit.errorText == code

        where:
        code << ['PREVIEW_EXPIRED', 'PREVIEW_NOT_READY', 'SOURCE_ACCESS_DENIED']
    }

    def "resource methods reject anonymous callers before touching Poorti"() {
        given:
        def calls = new BoundaryCalls([:])

        when:
        Map out = runResource(operation, calls, [uri: URI], false)

        then:
        out.error.code == -32001
        calls.requests.every { it.name == 'create#moqui.ai.AiToolCall' }

        where:
        operation << ['read', 'list', 'templates']
    }

    def "resource templates describe only private preview URIs"() {
        given:
        def calls = new BoundaryCalls([:])

        when:
        Map out = runResource('templates', calls, [:])

        then:
        out.result.ttlMs == 0
        out.result.cacheScope == 'private'
        out.result.resourceTemplates*.uriTemplate == ['hotwax-document://preview/{previewId}/pdf',
                'hotwax-document://preview/{previewId}/page/{pageNumber}']
        calls.requests.every { it.name == 'create#moqui.ai.AiToolCall' }
    }

    def "unexpected document service errors have correlation IDs and metadata-only audit"() {
        given:
        def calls = new BoundaryCalls([(NS + 'get#DocumentTemplate'): [documentResult: [source: 'private payload']]])

        when:
        Map result = runExec(calls, tool('get#DocumentTemplate', 'document'), [:])

        then:
        result.isError == true
        result.structuredContent.details.correlationId ==~ /[a-f0-9-]{36}/
        !result.toString().contains('private payload')
        !calls.audit.toString().contains('private payload')
    }

    def "resource reads use a literal trusted service and return a private binary envelope"() {
        given:
        def calls = new BoundaryCalls([(NS + 'read#DocumentArtifact'):
                [artifactResult: [ok: true, uri: URI, mimeType: 'application/pdf', blob: PDF.encodeBase64().toString()]]])

        when:
        Map out = runResource('read', calls, [uri: URI, serviceName: 'evil.service'])

        then:
        out.result == [contents: [[uri: URI, mimeType: 'application/pdf', blob: PDF.encodeBase64().toString()]], ttlMs: 0, cacheScope: 'private']
        calls.requests.find { it.name == NS + 'read#DocumentArtifact' }.arguments == [uri: URI]
        new JsonSlurper().parseText(calls.audit.arguments) == [previewId: 'test-preview']
        !calls.audit.toString().contains(PDF.encodeBase64().toString())
    }

    def "resource artifact domain denials are safe resource errors: #code"() {
        given:
        def calls = new BoundaryCalls([(NS + 'read#DocumentArtifact'): [artifactResult:
                [ok: false, code: code, message: 'Preview is unavailable.', details: [source: 'private payload']]]])

        when:
        Map out = runResource('read', calls, [uri: URI])

        then:
        out.error == [code: -32002, message: 'Document resource is unavailable.', data: [code: code]]
        out.result == null
        calls.audit.success == 'N'
        calls.audit.errorText == code
        !out.toString().contains('private payload')
        !calls.audit.toString().contains('private payload')

        where:
        code << ['PREVIEW_EXPIRED', 'PREVIEW_NOT_READY', 'SOURCE_ACCESS_DENIED']
    }

    def "resource reads reject paths outside opaque preview grammar: #uri"() {
        given:
        def calls = new BoundaryCalls([:])

        when:
        Map out = runResource('read', calls, [uri: uri])

        then:
        out.error.code == -32602
        calls.requests.every { it.name == 'create#moqui.ai.AiToolCall' }

        where:
        uri << ['file:///secret', 'hotwax-document://preview/../pdf', URI + '?token=x',
                'hotwax-document://preview/test-preview/page/0', URI + '/extra', null]
    }

    def "resource reads fail closed on changed URI or mismatched media"() {
        given:
        def calls = new BoundaryCalls([(NS + 'read#DocumentArtifact'): [artifactResult: artifact]])

        when:
        Map out = runResource('read', calls, [uri: URI])

        then:
        out.error.code == -32603
        !out.toString().contains('private payload')

        where:
        artifact << [[uri: PNG_URI, mimeType: 'application/pdf', blob: PDF.encodeBase64().toString()],
                     [uri: URI, mimeType: 'text/html', blob: 'private payload'],
                     [uri: URI, mimeType: 'application/pdf', blob: '%%%'],
                     [uri: URI, mimeType: 'application/pdf', blob: 'private payload'.bytes.encodeBase64().toString()]]
    }

    def "resource listing filters descriptors and uses private zero TTL"() {
        given:
        def calls = new BoundaryCalls([(NS + 'list#DocumentArtifacts'): [documentResult: [ok: true, resources: [
                [uri: URI, name: 'PDF', mimeType: 'application/pdf', size: PDF.length, artifactKey: 'pdf'],
                [uri: 'file:///secret', name: 'Secret', mimeType: 'text/plain', size: 3]]]]])

        when:
        Map out = runResource('list', calls, [:])

        then:
        out.result == [resources: [[uri: URI, name: 'PDF', mimeType: 'application/pdf', size: PDF.length]], ttlMs: 0, cacheScope: 'private']
    }

    def "modern resource Mcp-Name is the URI and mismatch stops dispatch"() {
        given:
        System.setProperty('mcp_enabled', 'Y')
        def calls = new BoundaryCalls(['ai.mcp.McpMethodServices.read#Resource': [result: [contents: [], ttlMs: 0, cacheScope: 'private']]])
        Map request = [jsonrpc: '2.0', id: 1, method: 'resources/read', protocolVersionHeader: '2026-07-28',
                mcpMethodHeader: 'resources/read', mcpNameHeader: URI,
                params: [uri: URI, _meta: ['io.modelcontextprotocol/protocolVersion': '2026-07-28',
                        'io.modelcontextprotocol/clientCapabilities': [:]]]]

        when:
        Map good = runScript('DispatchRequest', calls, request)
        Map bad = runScript('DispatchRequest', calls, request + [mcpNameHeader: 'other'])

        then:
        good.httpStatus == 200
        good.response.result.cacheScope == 'private'
        bad.httpStatus == 400
        bad.response.error.code == -32020
        calls.requests.count { it.name == 'ai.mcp.McpMethodServices.read#Resource' } == 1
    }

    def "a malformed base64 resource name is a protocol mismatch rather than an exception"() {
        given:
        System.setProperty('mcp_enabled', 'Y')
        def calls = new BoundaryCalls([:])

        when:
        Map out = runScript('DispatchRequest', calls, [jsonrpc: '2.0', id: 1, method: 'resources/read',
                protocolVersionHeader: '2026-07-28', mcpMethodHeader: 'resources/read', mcpNameHeader: '=?base64?%%%?=',
                params: [uri: URI, _meta: ['io.modelcontextprotocol/protocolVersion': '2026-07-28',
                        'io.modelcontextprotocol/clientCapabilities': [:]]]])

        then:
        out.httpStatus == 400
        out.response.error.code == -32020
        calls.requests.empty
    }

    def "binary resources enforce the decoded byte cap before reaching the wire"() {
        when:
        DocumentMcpSupport.binary([uri: URI, mimeType: 'application/pdf', blob: PDF.encodeBase64().toString()], URI, 5)

        then:
        thrown(IllegalArgumentException)
    }

    private static Map tool(String action, String adapter) {
        return [name: 'document_test', serviceName: NS + action, resultAdapter: adapter, auditProfile: 'document-metadata',
                inputSchema: [type: 'object', properties: [documentTemplateId: [:], source: [:], reason: [:], sourceIds: [:], previewId: [:]]], fixedParameters: [:]]
    }

    private static Map runExec(BoundaryCalls calls, Map definition, Map args) {
        return runScript('ExecTool', calls, [toolDef: definition, arguments: args]).result
    }

    private static Map runResource(String operation, BoundaryCalls calls, Map params, boolean authenticated = true) {
        return runScript('DocumentResources', calls, [operation: operation, params: params], authenticated)
    }

    private static Map runScript(String script, BoundaryCalls calls, Map values, boolean authenticated = true) {
        Map message = [hasError: { false }, clearErrors: { -> }, getErrorsString: { -> 'private payload' }]
        Map logger = [info: { Object... ignored -> }, error: { Object... ignored -> }, warn: { Object... ignored -> }]
        Map ec = [user: [userAccount: authenticated ? [userId: 'U1'] : null, userId: authenticated ? 'U1' : null],
                  service: [sync: { -> calls.newCall() }], message: message, logger: logger, factory: [componentInfoMap: [:]]]
        Map defaults = [jsonrpc: null, method: null, id: null, params: null, protocolVersionHeader: null,
                        mcpMethodHeader: null, mcpNameHeader: null, parseError: null]
        Binding binding = new Binding(defaults + values + [ec: ec])
        new GroovyShell(McpDocumentTransportTests.classLoader, binding).evaluate(new File('script/ai/mcp/' + script + '.groovy'))
        return binding.variables
    }

    static class BoundaryCalls {
        Map<String, Map> replies
        List<Map> requests = []
        Map audit
        BoundaryCalls(Map<String, Map> replies) { this.replies = replies }
        Object newCall() {
            Map request = [:]
            def call = new Expando()
            call.name = { String name -> request.name = name; call }
            call.parameters = { Map arguments -> request.arguments = arguments; call }
            call.requireNewTransaction = { boolean ignored -> call }
            call.call = { ->
                requests.add(request)
                if (request.name == 'create#moqui.ai.AiToolCall') { audit = request.arguments; return [:] }
                assert replies.containsKey(request.name): 'Unexpected service: ' + request.name
                return replies[request.name]
            }
            return call
        }
    }
}
