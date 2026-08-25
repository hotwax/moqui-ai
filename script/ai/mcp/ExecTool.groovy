/* Implementation of ai.mcp.McpServices.exec#Tool — run ONE tool call (spec: "Call flow").
 * Contract in service/ai/mcp/McpServices.xml. Arguments are filtered to the exposed
 * inputSchema properties (an argument outside the schema never reaches the service), fixed
 * parameters are injected on top so the client can never override them, and the backing
 * service runs in its own transaction so a failure rolls back only itself. Failures become
 * isError:true text the model can react to — the message facade is left clean and no
 * exception or stack trace ever reaches the wire. Every call (including a refused one) is
 * audited as an AiToolCall row with sourceEnumId AI_TCS_MCP (design decision 12). */

long startMs = System.currentTimeMillis()
Map svcResult = null
String errText = null

// A real user, not the anonymous-view login: the dispatch chain is anonymous so
// discover/list work without credentials, and that anonymous grant would otherwise satisfy
// the backing service's authenticate="true" (verified live: an anonymous caller got real
// data). A real user has a UserAccount; the anonymous login never does. Interim gate until
// the OAuth resource-server step replaces it with 401s.
if (ec.user.userAccount == null) {
    errText = "Authentication required: this MCP server runs tools as the calling Moqui user; send credentials the server accepts (e.g. HTTP Basic or Bearer)."
} else {
    Map schemaProps = (Map) ((Map) toolDef.inputSchema).get('properties')
    Map args = [:]
    if (arguments instanceof Map)
        for (def e in arguments.entrySet()) if (schemaProps.containsKey(e.key)) args.put(e.key, e.value)
    Map fixed = (Map) toolDef.fixedParameters
    if (fixed) args.putAll(fixed)   // server-fixed values always win

    try {
        svcResult = ec.service.sync().name((String) toolDef.serviceName)
                .parameters(args).requireNewTransaction(true).call()
        if (ec.message.hasError()) { errText = ec.message.errorsString; ec.message.clearErrors() }
    } catch (org.moqui.context.AuthenticationRequiredException e) {
        ec.message.clearErrors()
        errText = "Authentication required: this MCP server runs tools as the calling Moqui user; send credentials the server accepts (e.g. HTTP Basic or Bearer)."
    } catch (org.moqui.context.ArtifactAuthorizationException e) {
        ec.message.clearErrors()
        errText = "Not permitted: your account is not authorized for this tool."
    } catch (Throwable t) {
        ec.logger.error("MCP tool '${toolDef.name}' execution error", t)
        ec.message.clearErrors()
        errText = "Tool execution failed."
    }
}

// audit: one AiToolCall row per call, refused ones included (who tried also matters);
// guarded like AgentRunner's observability writes — an audit failure never aborts the call
try {
    ec.service.sync().name("create#moqui.ai.AiToolCall").parameters([
            sourceEnumId: 'AI_TCS_MCP', userId: ec.user?.userId,
            toolName: toolDef.name, serviceName: toolDef.serviceName,
            arguments: arguments != null ? groovy.json.JsonOutput.toJson(arguments) : null,
            result: errText == null ? groovy.json.JsonOutput.toJson(svcResult ?: [:]) : null,
            success: errText == null ? 'Y' : 'N', errorText: errText,
            durationMs: (System.currentTimeMillis() - startMs)]).call()
} catch (Throwable t) { ec.logger.warn("MCP audit write for tool '${toolDef.name}' failed (continuing): ${t.message}") }

if (errText != null) {
    result = [content: [[type: 'text', text: errText]], isError: true]
} else {
    Map body = svcResult ?: [:]
    result = [content: [[type: 'text', text: groovy.json.JsonOutput.toJson(body)]],
              structuredContent: body, isError: false]
}

// ScriptServiceRunner uses the script's RETURN VALUE as the whole service result when it is
// a Map (ScriptServiceRunner.java:60); return null so out-parameters are read from context.
return null
