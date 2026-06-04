package org.moqui.ai.provider

import org.moqui.ai.LlmProvider
import org.moqui.util.RestClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Shared transport for HTTP providers: RestClient POST of an encoded body, error mapping,
 *  and the encode/decode template. All data is Map-based. Concrete adapters implement only
 *  the wire format (encodeRequest Map->String, decodeResponse String->Map). */
abstract class AbstractLlmProvider implements LlmProvider {
    protected final static Logger logger = LoggerFactory.getLogger(AbstractLlmProvider.class)

    protected final String apiKey
    protected final String baseUrl
    protected final int timeoutSeconds

    AbstractLlmProvider(String apiKey, String baseUrl, int timeoutSeconds) {
        this.apiKey = apiKey; this.baseUrl = baseUrl; this.timeoutSeconds = timeoutSeconds
    }

    /** The endpoint path appended to baseUrl, e.g. "/v1/messages". */
    protected abstract String endpointPath()
    /** Provider-specific auth/version headers. */
    protected abstract Map<String, String> authHeaders()
    /** Encode the normalized request Map into the provider's JSON body. */
    abstract String encodeRequest(Map request)
    /** Decode the provider's JSON response text into a normalized response Map. */
    abstract Map decodeResponse(String responseText)

    /** Provider function/tool names must match ^[a-zA-Z0-9_-]+$, but Moqui service names contain
     *  '.' and '#'. Sanitize for the wire; map back when a tool call returns. Shared by all HTTP
     *  providers (OpenAI, Anthropic, ...). */
    static String sanitizeName(String n) { n == null ? null : n.replaceAll('[^a-zA-Z0-9_-]', '_') }

    /** Optional hook: after decode, a provider may normalize a structured-output answer into
     *  resp.structuredResult. Default no-op. Called only when request.responseSchema is set. */
    protected void applyStructured(Map resp, Map request) { }

    @Override
    Map chat(Map request) {
        String body = encodeRequest(request)
        RestClient rc = new RestClient().uri(baseUrl + endpointPath())
            .method('POST').contentType("application/json").timeout(timeoutSeconds).text(body)
        authHeaders().each { k, v -> rc.addHeader(k, v) }
        def resp
        try {
            resp = rc.call()
        } catch (Exception e) {
            throw new RuntimeException("LLM provider ${name} HTTP error: ${e.message}", e)
        }
        int sc = resp.getStatusCode()
        String text = resp.text()
        // Fail loudly on a non-2xx — otherwise an error body parses as an empty completion and the
        // run silently "completes" with no answer (masking real errors, e.g. a 400 bad request).
        if (sc < 200 || sc >= 300) throw new RuntimeException("LLM provider ${name} HTTP ${sc}: ${text}")

        Map decoded = decodeResponse(text)
        remapToolNames(decoded, request)
        if (request.responseSchema) applyStructured(decoded, request)
        return decoded
    }

    /** Tool/function names are sanitized for the wire (see {@link #sanitizeName}); a provider therefore
     *  returns the SANITIZED name on a tool call. Map each decoded tool-call name back to the original
     *  Moqui service name so catalog lookups (AiToolFactory.getTool — the approval gate and dispatch)
     *  resolve. The reverse map is rebuilt from THIS request's tool list each call, so no mutable
     *  per-request state is kept on the (possibly shared/singleton) provider. Names with no match —
     *  e.g. the synthetic structured_output tool — are left unchanged. Mutates decoded; returns it. */
    protected Map remapToolNames(Map decoded, Map request) {
        Map<String, String> backToReal = [:]
        for (Map t in (request?.tools ?: []) as List<Map>) backToReal[sanitizeName(t.name as String)] = t.name as String
        for (Map tc in (decoded?.toolCalls ?: []) as List<Map>) tc.name = backToReal[tc.name as String] ?: tc.name
        return decoded
    }
}
