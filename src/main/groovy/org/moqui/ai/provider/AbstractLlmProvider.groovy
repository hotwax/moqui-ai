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
        return decodeResponse(text)
    }
}
