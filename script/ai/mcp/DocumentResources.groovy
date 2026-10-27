/* Private resource methods. Called only by the three literal McpMethodServices wrappers;
 * no request value is ever used as a service name. Authorization/expiry/hash validation
 * belongs to Poorti and runs under the caller for every list/read. */
import org.moqui.ai.DocumentMcpSupport
import org.moqui.ai.AuditRedactor

long startMs = System.currentTimeMillis()
String correlationId = UUID.randomUUID().toString()
String outcome = null
Map auditMetadata = [:]
result = null
error = null

if (ec.user.userAccount == null) {
    outcome = 'AUTHENTICATION_REQUIRED'
    error = [code: -32001, message: 'Authentication required to access document resources.']
} else if (operation == 'read' && !DocumentMcpSupport.validUri(params?.uri)) {
    outcome = 'INVALID_RESOURCE_URI'
    error = [code: -32602, message: 'Invalid document resource URI.']
} else if (!(operation in ['read', 'list', 'templates'])) {
    outcome = 'INVALID_RESOURCE_METHOD'
    error = [code: -32602, message: 'Invalid document resource operation.']
} else {
    try {
        if (operation == 'templates') {
            result = DocumentMcpSupport.privateResult([resourceTemplates: DocumentMcpSupport.templates()])
        } else if (operation == 'list') {
            Map input = [:]
            if (params?.cursor instanceof String && params.cursor.length() <= 1024) input.cursor = params.cursor
            Map serviceOut = ec.service.sync()
                    .name('co.hotwax.poorti.document.DocumentTemplateServices.list#DocumentArtifacts')
                    .parameters(input).requireNewTransaction(true).call()
            if (ec.message.hasError()) { ec.message.clearErrors(); throw new IllegalStateException('Document resource listing failed') }
            Map documentResult = serviceOut.documentResult instanceof Map ? (Map) serviceOut.documentResult : null
            if (documentResult?.ok != true) {
                outcome = documentResult?.code instanceof String && documentResult.code ==~ /[A-Z][A-Z0-9_]{0,63}/ ?
                        documentResult.code : 'RESOURCE_UNAVAILABLE'
                error = [code: -32002, message: 'Document resources are unavailable.', data: [code: outcome]]
            } else {
                if (!(documentResult.resources instanceof List) || documentResult.resources.size() > 1000)
                    throw new IllegalArgumentException('Invalid document resource list')
                List<Map> resources = ((List) documentResult.resources).collect { DocumentMcpSupport.descriptor(it) }.findAll { it != null }
                Map fields = [resources: resources]
                if (documentResult.nextCursor instanceof String && documentResult.nextCursor.length() <= 1024) fields.nextCursor = documentResult.nextCursor
                result = DocumentMcpSupport.privateResult(fields)
                auditMetadata = [totalCount: resources.size()]
            }
        } else {
            Map serviceOut = ec.service.sync()
                    .name('co.hotwax.poorti.document.DocumentTemplateServices.read#DocumentArtifact')
                    .parameters([uri: params.uri]).requireNewTransaction(true).call()
            if (ec.message.hasError()) { ec.message.clearErrors(); throw new IllegalStateException('Document resource read failed') }
            if (serviceOut.documentResult instanceof Map && serviceOut.documentResult.ok == false) {
                outcome = serviceOut.documentResult.code instanceof String && serviceOut.documentResult.code ==~ /[A-Z][A-Z0-9_]{0,63}/ ?
                        serviceOut.documentResult.code : 'RESOURCE_UNAVAILABLE'
                error = [code: -32002, message: 'Document resource is unavailable.', data: [code: outcome]]
            } else {
                Map binary = DocumentMcpSupport.binary(serviceOut.artifactResult, (String) params.uri)
                result = DocumentMcpSupport.privateResult([contents: [binary]])
                auditMetadata = [size: Base64.decoder.decode((String) binary.blob).length]
            }
        }
    } catch (org.moqui.context.AuthenticationRequiredException ignored) {
        ec.message.clearErrors()
        outcome = 'AUTHENTICATION_REQUIRED'
        error = [code: -32001, message: 'Authentication required to access document resources.']
    } catch (org.moqui.context.ArtifactAuthorizationException ignored) {
        ec.message.clearErrors()
        outcome = 'ACCESS_DENIED'
        error = [code: -32002, message: 'Document resource is unavailable.', data: [code: outcome]]
    } catch (Throwable failure) {
        ec.message.clearErrors()
        outcome = 'DOCUMENT_RESOURCE_FAILED'
        ec.logger.error("MCP document resource failed correlationId=${correlationId} type=${failure.class.name}")
        error = [code: -32603, message: 'Document resource request failed.', data: [correlationId: correlationId]]
    }
}

// A single metadata-only audit for every request, including denials. Only the preview ID
// and page number are extracted from a valid URI; cursors, binary/customer content and
// exception messages never persist.
try {
    String trustedService = operation == 'read' ?
            'co.hotwax.poorti.document.DocumentTemplateServices.read#DocumentArtifact' :
            (operation == 'list' ? 'co.hotwax.poorti.document.DocumentTemplateServices.list#DocumentArtifacts' :
                    'ai.mcp.McpMethodServices.list#ResourceTemplates')
    ec.service.sync().name('create#moqui.ai.AiToolCall').parameters([
            sourceEnumId: 'AI_TCS_MCP', userId: ec.user?.userId,
            toolName: operation == 'templates' ? 'resources/templates/list' : 'resources/' + operation,
            serviceName: trustedService,
            arguments: AuditRedactor.toJson(DocumentMcpSupport.uriMetadata(params?.uri)),
            result: AuditRedactor.toJson(DocumentMcpSupport.metadata(auditMetadata)),
            success: error == null ? 'Y' : 'N', errorText: outcome,
            durationMs: System.currentTimeMillis() - startMs]).call()
} catch (Throwable failure) {
    ec.logger.warn("MCP document resource audit failed correlationId=${correlationId} type=${failure.class.name}")
}
return null
