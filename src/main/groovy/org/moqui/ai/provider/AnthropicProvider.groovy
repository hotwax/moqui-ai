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

    private static final String STRUCTURED_TOOL_NAME = "structured_output"

    /** Effort level → Anthropic thinking budget_tokens (min 1024). Tunable. */
    private static int effortToBudget(String effort) {
        switch (effort) {
            case "low": return 1024
            case "medium": return 8192
            case "high": return 24576
            default: return 0
        }
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
            toolsList.add([name: STRUCTURED_TOOL_NAME,
                description: "Return your final answer as structured data matching this schema. Call this tool exactly once, only when you have the final answer.",
                input_schema: request.responseSchema])
            body.tools = toolsList
            // No business tools => force the structured tool for a deterministic one-shot answer —
            // UNLESS reasoning is on (Anthropic forbids a forced tool_choice while thinking; offer it auto).
            if (!request.tools && !request.reasoning?.effort) body.tool_choice = [type: "tool", name: STRUCTURED_TOOL_NAME]
        }
        // Extended thinking. v1: ONLY when there are no business tools — Anthropic requires preserving
        // thinking blocks across tool_result turns, which our message shape does not carry yet (deferred).
        // (responseSchema's synthetic tool is terminal — no tool_result round-trip — so thinking is safe with it.)
        if (request.reasoning?.effort && !request.tools) {
            int budget = effortToBudget(request.reasoning.effort as String)
            if (budget > 0) {
                body.thinking = [type: "enabled", budget_tokens: budget]
                body.max_tokens = Math.max((body.max_tokens as int), budget + 4096)
            }
        }
        return JsonOutput.toJson(body)
    }

    @Override
    protected void applyStructured(Map resp, Map request) {
        List<Map> tcs = (resp.toolCalls ?: []) as List<Map>
        Map structured = tcs.find { it.name == STRUCTURED_TOOL_NAME }
        if (structured != null) {
            resp.structuredResult = structured.arguments
            // Remove only the synthetic call; any co-emitted business tool calls are preserved so
            // AgentRunner still dispatches them (a later turn's structured_output overwrites, latest wins).
            resp.toolCalls = tcs.findAll { it.name != STRUCTURED_TOOL_NAME }
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
