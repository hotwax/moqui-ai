import spock.lang.*
import org.moqui.ai.AuditRedactor

/** The tool-call audit redaction rules (AuditRedactor): which keys count as secret-named, what is
 *  walked, and how the ai_audit_redact_pattern property extends the built-in names. The call-level
 *  proof that the AiToolCall / AiToolCallRequest rows are masked is in McpCallTests,
 *  AgentRunnerTests, AiApprovalTests and AiContextTests. */
class AuditRedactorTests extends Specification {
    static final String PROP = 'ai_audit_redact_pattern'
    static final String M = AuditRedactor.MARKER
    String priorPattern

    // each case starts with the property unset; it is restored afterwards
    def setup() { priorPattern = System.getProperty(PROP); System.clearProperty(PROP) }
    def cleanup() { if (priorPattern != null) System.setProperty(PROP, priorPattern) else System.clearProperty(PROP) }

    /** Serialized by JsonOutput from its properties, like any bean a service might return. */
    static class Creds { String user = 'u'; String password = 'p' }

    def "the value under a secret-named key is masked, matched case-insensitively anywhere in the key: #key"() {
        expect:
        AuditRedactor.redact([(key): 'v']) == [(key): M]

        where:
        key << ['password', 'currentPassword', 'passwd', 'passphrase', 'keystorePassphrase', 'dbPwd',
                'clientSecret', 'oldSharedSecret', 'SECRET', 'token', 'shopAccessToken', 'sessionToken',
                'tokenSecret', 'tokenString', 'apiKey', 'api_key', 'x-api-key', 'privateKeyText', 'private_key',
                'accessKeyId', 'aws_access_key_id', 'secretAccessKey', 'credentials', 'Authorization']
    }

    def "other keys keep their values, including LLM token counts: #key"() {
        expect:
        AuditRedactor.redact([(key): 42]) == [(key): 42]

        where:
        key << ['tokensIn', 'tokensOut', 'totalTokensIn', 'totalTokensOut', 'maxTokens', 'factKey', 'text', 'shopDomain']
    }

    def "maps and lists are walked at any depth; a secret-named container is masked whole"() {
        given:
        Map input = [text: 'hi', ids: ['a', 'b'], credentials: [user: 'u', pass: 'p'],
                     connection: [host: 'h', secretAccessKey: 's', queues: [[name: 'q', apiKey: 'k'], 'plain']]]

        expect:
        AuditRedactor.redact(input) == [text: 'hi', ids: ['a', 'b'], credentials: M,
                connection: [host: 'h', secretAccessKey: M, queues: [[name: 'q', apiKey: M], 'plain']]]
    }

    def "arrays and non-list collections are walked too"() {
        expect:
        AuditRedactor.redact([rows: [[token: 't', n: 1]] as Object[]]) == [rows: [[token: M, n: 1]]]
        AuditRedactor.redact([rows: [[apiKey: 'k']] as LinkedHashSet]) == [rows: [[apiKey: M]]]
    }

    def "whatever the audit JSON would hold is masked: bean properties, Expando, iterator items, map entries"() {
        expect:
        AuditRedactor.redact([creds: new Creds()]) == [creds: [user: 'u', password: M]]
        AuditRedactor.redact([obj: new Expando(password: 'p', user: 'u')]) == [obj: [password: M, user: 'u']]
        AuditRedactor.redact([rows: [[apiKey: 'k']].iterator()]) == [rows: [[apiKey: M]]]
        AuditRedactor.redact([pairs: [new AbstractMap.SimpleEntry('password', 'p')]]) == [pairs: [[key: 'password', value: M]]]
    }

    def "a name/value pair that names a secret has its value masked"() {
        expect:
        AuditRedactor.redact([headers: [[name: 'Authorization', value: 'Bearer x'], [name: 'Accept', value: 'application/json']],
                              metafields: [[key: 'api_token', value: 't'], [key: 'color', value: 'red']]]) ==
                [headers: [[name: 'Authorization', value: M], [name: 'Accept', value: 'application/json']],
                 metafields: [[key: 'api_token', value: M], [key: 'color', value: 'red']]]
    }

    def "the input is never modified: the service and the caller keep the real values"() {
        given:
        Map input = [clientSecret: 's', connection: [secretAccessKey: 'k', queues: [[apiKey: 'a']]]]

        when:
        AuditRedactor.redact(input)

        then:
        input.clientSecret == 's'
        ((Map) input.connection).secretAccessKey == 'k'
        ((Map) ((List) ((Map) input.connection).queues)[0]).apiKey == 'a'
    }

    def "a null under a secret-named key stays null, and scalars pass through unchanged"() {
        expect:
        AuditRedactor.redact([clientSecret: null, n: 1]) == [clientSecret: null, n: 1]
        AuditRedactor.redact(null) == null
        AuditRedactor.redact('text') == 'text'
        AuditRedactor.toJson([accessToken: 'x', n: 1]) == '{"accessToken":"***redacted***","n":1}'
    }

    def "nesting deeper than MAX_DEPTH is masked whole"() {
        given:
        Map deep = [leaf: 'deep-value']
        (AuditRedactor.MAX_DEPTH + 8).times { deep = [n: deep] }

        when:
        String json = AuditRedactor.toJson(deep)

        then:
        json.contains(M)
        !json.contains('deep-value')
    }

    def "a value that cannot be serialized is stored as the marker instead of failing the write"() {
        given:
        Map cyclic = [name: 'loop']
        cyclic.put('next', cyclic)

        when:
        String json = AuditRedactor.toJson(cyclic)

        then:
        notThrown(Throwable)
        json == '"' + M + '"'
    }

    def "ai_audit_redact_pattern adds names to the built-in ones"() {
        given:
        System.setProperty(PROP, 'shopDomain|consumerKey')

        expect:
        AuditRedactor.redact([shopDomain: 'd', consumerKey: 'c', password: 'p', text: 't']) ==
                [shopDomain: M, consumerKey: M, password: M, text: 't']
    }

    def "no value of ai_audit_redact_pattern switches the built-in names off: #desc"() {
        given:
        System.setProperty(PROP, value)

        expect:
        AuditRedactor.redact([password: 'p', text: 't']) == [password: M, text: 't']

        where:
        desc                 | value
        'blank'              | ' '
        'tab'                | '\t'
        'invalid regex'      | '(unclosed'
        'never-matching'     | 'x^'
        'empty lookahead'    | '(?!)'
        'unrelated name'     | 'shopDomain'
    }
}
