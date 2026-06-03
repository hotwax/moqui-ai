package org.moqui.ai.provider

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/** Anthropic Messages API adapter. Maps the normalized request/response Maps to/from
 *  Anthropic's tool_use/tool_result content blocks. */
class AnthropicProvider extends AbstractLlmProvider {
    private final String anthropicVersion

    AnthropicProvider(String apiKey, String baseUrl, String anthropicVersion, int timeoutSeconds) {
        super(apiKey, baseUrl, timeoutSeconds); this.anthropicVersion = anthropicVersion
    }

    @Override String getName() { return "anthropic" }
    @Override protected String endpointPath() { return "/v1/messages" }
    @Override protected Map<String, String> authHeaders() {
        return ["x-api-key": apiKey, "anthropic-version": anthropicVersion]
    }

    @Override
    String encodeRequest(Map request) {
        List<Map> apiMessages = []
        for (Map m in (request.messages ?: []) as List<Map>) {
            if (m.role == "tool") {
                apiMessages.add([role: "user", content: [[type: "tool_result",
                    tool_use_id: m.toolCallId, content: m.content]]])
            } else if (m.role == "assistant" && m.toolCalls) {
                apiMessages.add([role: "assistant", content: (m.toolCalls as List<Map>).collect { tc ->
                    [type: "tool_use", id: tc.id, name: sanitizeName(tc.name as String), input: tc.arguments] }])
            } else {
                apiMessages.add([role: m.role, content: m.content])
            }
        }
        Map body = [model: request.model, max_tokens: 4096, messages: apiMessages]
        if (request.systemContext) body.system = request.systemContext
        if (request.tools) body.tools = (request.tools as List<Map>).collect { t ->
            [name: sanitizeName(t.name as String), description: t.description, input_schema: t.parameters] }
        if (request.responseSchema) {
            List<Map> toolsList = (body.tools ?: []) as List<Map>
            toolsList.add([name: "structured_output",
                description: "Return your final answer as structured data matching this schema. Call this tool exactly once, only when you have the final answer.",
                input_schema: request.responseSchema])
            body.tools = toolsList
            // No business tools => force the structured tool for a deterministic one-shot answer.
            // With business tools, leave tool_choice auto so the model can use them first.
            if (!request.tools) body.tool_choice = [type: "tool", name: "structured_output"]
        }
        return JsonOutput.toJson(body)
    }

    @Override
    protected void applyStructured(Map resp, Map request) {
        List<Map> tcs = (resp.toolCalls ?: []) as List<Map>
        Map structured = tcs.find { it.name == "structured_output" }
        if (structured != null) {
            resp.structuredResult = structured.arguments
            resp.toolCalls = tcs.findAll { it.name != "structured_output" }
            resp.finishReason = "structured_output"
        }
    }

    @Override
    Map decodeResponse(String responseText) {
        Map json = new JsonSlurper().parseText(responseText) as Map
        Map usage = (json.usage ?: [:]) as Map
        List<Map> toolCalls = []
        StringBuilder text = new StringBuilder()
        for (Map block in (json.content ?: []) as List<Map>) {
            if (block.type == "text") text.append(block.text as String)
            else if (block.type == "tool_use") toolCalls.add(
                [id: block.id, name: block.name, arguments: (block.input ?: [:])])
        }
        return [finishReason: json.stop_reason,
                providerRunId: json.id,
                tokensIn: (usage.input_tokens ?: 0L) as long,
                tokensOut: (usage.output_tokens ?: 0L) as long,
                toolCalls: toolCalls,
                assistantText: text.length() > 0 ? text.toString() : null]
    }
}
