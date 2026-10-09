package org.moqui.ai

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.moqui.util.SystemBinding
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Masks credentials out of the tool-call audit before it is written. AiToolCall.arguments/result
 *  and AiToolCallRequest.arguments are plain-text JSON that outlives the call, so a secret passed to
 *  a tool (an API client secret) or returned by one (an access key) must never land there.
 *
 *  The value is first normalized the way the audit serializes it (JsonOutput, read back), so bean
 *  properties, iterator items and the like are covered exactly as they would be written. Then, at any
 *  depth, the value under every key matching the secret-name pattern becomes MARKER, and so does the
 *  value of a {name|key: secret name, value: ...} pair (HTTP headers, metafields). Everything else is
 *  copied unchanged. The copy is for the audit only: the service still receives, and the caller still
 *  gets, the real values.
 *
 *  The pattern is BUILT_IN_PATTERN, always, plus any names a deployment adds through the
 *  ai_audit_redact_pattern property (a regex, see MoquiConf.xml). Keys are matched case-insensitively,
 *  anywhere in the key. The property can only add names, so no value of it switches masking off. */
class AuditRedactor {
    protected final static Logger logger = LoggerFactory.getLogger(AuditRedactor.class)

    static final String MARKER = "***redacted***"
    /** Always applied. token(?-i:(?!s)) skips only the lowercase plural: tokensIn, totalTokensOut and
     *  maxTokens are LLM usage counts, while accessToken, tokenId and tokenString are still masked. */
    static final String BUILT_IN_PATTERN = "pass(?:word|wd|phrase)|pwd|secret|token(?-i:(?!s))" +
            "|api[-_]?key|private[-_]?key|access[-_]?key|credential|authorization"
    /** Containers nested deeper than this are masked whole instead of walked (fails closed). */
    static final int MAX_DEPTH = 32

    private static final Pattern BUILT_IN = Pattern.compile(BUILT_IN_PATTERN, Pattern.CASE_INSENSITIVE)
    private static volatile Map.Entry<String, Pattern> compiled = new AbstractMap.SimpleImmutableEntry<>("", BUILT_IN)

    /** The audit JSON for a tool call's arguments or result. Never throws: a value that cannot be
     *  serialized (a cyclic graph, a failing getter) is stored as the marker, so the row is still written. */
    static String toJson(Object value) {
        try {
            return JsonOutput.toJson(redact(value))
        } catch (Exception | StackOverflowError e) {
            logger.warn("Tool-call audit value could not be serialized, storing ${MARKER} instead: ${e.class.name}")
            return JsonOutput.toJson(MARKER)
        }
    }

    /** A masked copy of value, as the plain Maps, Lists and scalars the audit JSON holds. Never
     *  modifies value. A null under a secret-named key stays null: there is nothing to hide, and
     *  "not sent" is worth keeping. */
    static Object redact(Object value) { return maskedCopy(normalized(value), sensitiveKeyPattern(), 0) }

    /** JsonOutput's view of value, read back, so everything the audit would write is a Map or a List
     *  the walk can see into. */
    private static Object normalized(Object value) {
        if (value == null || value instanceof CharSequence || value instanceof Number || value instanceof Boolean) return value
        return new JsonSlurper().parseText(JsonOutput.toJson(value))
    }

    private static Object maskedCopy(Object value, Pattern sensitive, int depth) {
        if (value instanceof Map) {
            if (depth >= MAX_DEPTH) return MARKER
            Map map = (Map) value
            // a name/value pair names its secret in a field: {name: 'Authorization', value: ...}
            boolean secretPair = ['name', 'key'].any { String f ->
                map.get(f) instanceof CharSequence && sensitive.matcher(map.get(f).toString()).find() }
            Map copy = new LinkedHashMap()
            for (Map.Entry e in map.entrySet()) {
                boolean mask = e.value != null && e.key != null &&
                        (sensitive.matcher(e.key.toString()).find() || (secretPair && e.key == 'value'))
                copy.put(e.key, mask ? MARKER : maskedCopy(e.value, sensitive, depth + 1))
            }
            return copy
        }
        if (value instanceof List) {
            if (depth >= MAX_DEPTH) return MARKER
            List copy = []
            for (Object item in (List) value) copy.add(maskedCopy(item, sensitive, depth + 1))
            return copy
        }
        return value
    }

    /** BUILT_IN plus the names ai_audit_redact_pattern adds, compiled once per distinct value. A
     *  property that does not compile is logged and ignored: the built-in names still apply. */
    static Pattern sensitiveKeyPattern() {
        String extra = SystemBinding.getPropOrEnv("ai_audit_redact_pattern")?.trim() ?: ""
        Map.Entry<String, Pattern> current = compiled
        if (current.key == extra) return current.value
        Pattern pattern = BUILT_IN
        if (extra) {
            try {
                pattern = Pattern.compile(BUILT_IN_PATTERN + "|(?:" + extra + ")", Pattern.CASE_INSENSITIVE)
            } catch (PatternSyntaxException e) {
                logger.error("ai_audit_redact_pattern is not a valid regex, masking with the built-in names only: ${e.description}")
            }
        }
        compiled = new AbstractMap.SimpleImmutableEntry<>(extra, pattern)
        return pattern
    }
}
