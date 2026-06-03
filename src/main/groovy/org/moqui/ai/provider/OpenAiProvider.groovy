package org.moqui.ai.provider

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/** OpenAI Chat Completions adapter. Maps the normalized request/response Maps to/from
 *  OpenAI's messages + function tool-calls wire format. */
class OpenAiProvider extends AbstractLlmProvider {
    OpenAiProvider(String apiKey, String baseUrl, int timeoutSeconds) {
        super(apiKey, baseUrl, timeoutSeconds)
    }

    @Override String getName() { return "openai" }
    @Override protected String endpointPath() { return "/chat/completions" }
    @Override protected Map<String, String> authHeaders() { return ["Authorization": "Bearer ${apiKey}".toString()] }

    /** OpenAI function names must match ^[a-zA-Z0-9_-]+$, but Moqui service names contain '.' and
     *  '#'. Sanitize for the wire; map back to the real name when a tool call comes back. */
    static String sanitizeName(String n) { n == null ? null : n.replaceAll('[^a-zA-Z0-9_-]', '_') }

    @Override
    Map chat(Map request) {
        Map<String, String> backToReal = [:]
        for (Map t in (request.tools ?: []) as List<Map>) backToReal[sanitizeName(t.name as String)] = t.name as String
        Map resp = super.chat(request)   // encodeRequest sanitizes; transport + status check in base
        for (Map tc in (resp.toolCalls ?: []) as List<Map>) tc.name = backToReal[tc.name as String] ?: tc.name
        return resp
    }

    @Override
    String encodeRequest(Map request) {
        List<Map> apiMessages = []
        // OpenAI: system prompt is a message, prepended
        if (request.systemContext) apiMessages.add([role: "system", content: request.systemContext])
        for (Map m in (request.messages ?: []) as List<Map>) {
            if (m.role == "tool") {
                apiMessages.add([role: "tool", tool_call_id: m.toolCallId, content: m.content])
            } else if (m.role == "assistant" && m.toolCalls) {
                apiMessages.add([role: "assistant", content: null,
                    tool_calls: (m.toolCalls as List<Map>).collect { tc ->
                        [id: tc.id, type: "function",
                         function: [name: sanitizeName(tc.name as String), arguments: JsonOutput.toJson(tc.arguments ?: [:])]] }])
            } else {
                apiMessages.add([role: m.role, content: m.content])
            }
        }
        Map body = [model: request.model, messages: apiMessages]
        if (request.tools) body.tools = (request.tools as List<Map>).collect { t ->
            [type: "function", function: [name: sanitizeName(t.name as String), description: t.description, parameters: t.parameters]] }
        return JsonOutput.toJson(body)
    }

    @Override
    Map decodeResponse(String responseText) {
        Map json = new JsonSlurper().parseText(responseText) as Map
        Map choice = ((json.choices ?: []) as List)[0] as Map
        Map msg = (choice?.message ?: [:]) as Map
        Map usage = (json.usage ?: [:]) as Map
        List<Map> toolCalls = []
        for (Map tc in (msg.tool_calls ?: []) as List<Map>) {
            Map fn = (tc.function ?: [:]) as Map
            Map args = [:]
            try { args = (fn.arguments ? new JsonSlurper().parseText(fn.arguments as String) : [:]) as Map }
            catch (Exception ignored) { args = [:] }   // model occasionally emits malformed args
            toolCalls.add([id: tc.id, name: fn.name, arguments: args])
        }
        return [finishReason: choice?.finish_reason,
                tokensIn: (usage.prompt_tokens ?: 0L) as long,
                tokensOut: (usage.completion_tokens ?: 0L) as long,
                toolCalls: toolCalls,
                assistantText: msg.content ?: null]
    }
}
