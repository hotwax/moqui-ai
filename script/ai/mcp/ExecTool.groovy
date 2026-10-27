/* Implementation of ai.mcp.McpServices.exec#Tool — run ONE tool call (spec: "Call flow").
 * Contract in service/ai/mcp/McpServices.xml. Arguments are filtered to the exposed
 * inputSchema properties (an argument outside the schema never reaches the service), fixed
 * parameters are injected on top so the client can never override them, and the backing
 * service runs in its own transaction so a failure rolls back only itself. Failures become
 * isError:true text the model can react to — the message facade is left clean and no
 * exception or stack trace ever reaches the wire. Every call (including a refused one) is
 * audited as an AiToolCall row with sourceEnumId AI_TCS_MCP (design decision 12), recording
 * the filtered arguments and the result with every secret-named value masked. */

import org.moqui.ai.AuditRedactor
import org.moqui.ai.DocumentMcpSupport

long startMs = System.currentTimeMillis()
Map svcResult = null
String errText = null
Map adaptedResult = null
boolean documentProfile = toolDef.resultAdapter != null || toolDef.auditProfile != null ||
        DocumentMcpSupport.TOOL_SERVICES.contains(toolDef.serviceName)
String correlationId = documentProfile ? UUID.randomUUID().toString() : null

// the arguments the backing service runs with: only exposed schema properties, then the
// fixed parameters on top. The audit records these too, not the raw client input.
Map schemaProps = (Map) ((Map) toolDef.inputSchema)?.get('properties')
Map args = [:]
if (arguments instanceof Map)
    for (def e in arguments.entrySet()) if (schemaProps?.containsKey(e.key)) args.put(e.key, e.value)
Map fixed = (Map) toolDef.fixedParameters
if (fixed) args.putAll(fixed)   // server-fixed values always win

// A real user, not the anonymous-view login: the dispatch chain is anonymous so
// discover/list work without credentials, and that anonymous grant would otherwise satisfy
// the backing service's authenticate="true" (verified live: an anonymous caller got real
// data). A real user has a UserAccount; the anonymous login never does. Interim gate until
// the OAuth resource-server step replaces it with 401s.
if (documentProfile && DocumentMcpSupport.catalogOptions((String) toolDef.serviceName,
        (String) toolDef.resultAdapter, (String) toolDef.auditProfile) == null) {
    errText = 'Document tool configuration is invalid.'
} else if (ec.user.userAccount == null) {
    errText = "Authentication required: this MCP server runs tools as the calling Moqui user; send credentials the server accepts (e.g. HTTP Basic or Bearer)."
} else {
    try {
        svcResult = ec.service.sync().name((String) toolDef.serviceName)
                .parameters(args).requireNewTransaction(true).call()
        if (ec.message.hasError()) {
            errText = documentProfile ? 'The document operation failed.' : ec.message.errorsString
            ec.message.clearErrors()
        } else if (documentProfile) {
            adaptedResult = DocumentMcpSupport.toolResult(svcResult, (String) toolDef.resultAdapter, { String uri ->
                // A descriptor is never a callable service or a source of inline bytes.
                // Poorti reauthorizes current caller/source access and expiry on every read.
                Map artifactOut = ec.service.sync()
                        .name('co.hotwax.poorti.document.DocumentTemplateServices.read#DocumentArtifact')
                        .parameters([uri: uri]).requireNewTransaction(true).call()
                if (ec.message.hasError()) { ec.message.clearErrors(); throw new IllegalStateException('Artifact read failed') }
                return artifactOut.artifactResult
            })
        }
    } catch (org.moqui.context.AuthenticationRequiredException e) {
        ec.message.clearErrors()
        errText = "Authentication required: this MCP server runs tools as the calling Moqui user; send credentials the server accepts (e.g. HTTP Basic or Bearer)."
    } catch (org.moqui.context.ArtifactAuthorizationException e) {
        ec.message.clearErrors()
        errText = "Not permitted: your account is not authorized for this tool."
    } catch (Throwable t) {
        if (documentProfile) ec.logger.error("MCP document tool execution failed correlationId=${correlationId} type=${t.class.name}")
        else ec.logger.error("MCP tool '${toolDef.name}' execution error", t)
        ec.message.clearErrors()
        errText = "Tool execution failed."
    }
}

if (documentProfile && errText != null) {
    String code = ec.user.userAccount == null ? 'AUTHENTICATION_REQUIRED' :
            (errText.startsWith('Not permitted') ? 'ACCESS_DENIED' : 'DOCUMENT_EXECUTION_FAILED')
    adaptedResult = DocumentMcpSupport.failure(code, errText, [correlationId: correlationId])
}

// audit: one AiToolCall row per call, refused ones included (who tried also matters);
// guarded like AgentRunner's observability writes — an audit failure never aborts the call.
// The row is plain text that outlives the call, so it gets masked copies of args and result
// (AuditRedactor); the caller still gets the real result below.
try {
    Map auditBody = documentProfile ? DocumentMcpSupport.metadata((Map) adaptedResult?.structuredContent) : null
    boolean succeeded = errText == null && (!documentProfile || adaptedResult?.isError == false)
    def auditCall = ec.service.sync().name("create#moqui.ai.AiToolCall").parameters([
            sourceEnumId: 'AI_TCS_MCP', userId: ec.user?.userId,
            toolName: toolDef.name, serviceName: toolDef.serviceName,
            arguments: AuditRedactor.toJson(documentProfile ? DocumentMcpSupport.metadata(args) : args),
            result: documentProfile ? AuditRedactor.toJson(auditBody) :
                    (errText == null ? AuditRedactor.toJson(svcResult ?: [:]) : null),
            success: succeeded ? 'Y' : 'N', errorText: documentProfile ? auditBody?.code : errText,
            durationMs: (System.currentTimeMillis() - startMs)])
    // Only the fixed metadata audit uses system authority and an isolated transaction:
    // a caller's audit denial must not mark the document operation rollback-only.
    if (documentProfile) auditCall.requireNewTransaction(true).disableAuthz().call()
    else auditCall.call()
} catch (Throwable t) {
    if (documentProfile) ec.logger.warn("MCP document audit write failed correlationId=${correlationId} type=${t.class.name}")
    else ec.logger.warn("MCP audit write for tool '${toolDef.name}' failed (continuing): ${t.message}")
}

if (documentProfile) {
    result = adaptedResult
} else if (errText != null) {
    result = [content: [[type: 'text', text: errText]], isError: true]
} else {
    Map body = svcResult ?: [:]
    result = [content: [[type: 'text', text: groovy.json.JsonOutput.toJson(body)]],
              structuredContent: body, isError: false]
}

// ScriptServiceRunner uses the script's RETURN VALUE as the whole service result when it is
// a Map (ScriptServiceRunner.java:60); return null so out-parameters are read from context.
return null
