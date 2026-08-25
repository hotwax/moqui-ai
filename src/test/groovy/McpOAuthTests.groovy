import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.McpBearerValidator

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT

/** MCP step 6: the OAuth resource layer (spec: "Wire behaviour" / "Token validation").
 *  McpBearerValidator is pure: token string + trusted JWKSet + expected issuer/audience in,
 *  claims out or a loud failure. Tests mint RS256 tokens against a generated keypair, so no
 *  IdP and no HTTP are involved — the JWKS fetch is the caller's seam. */
class McpOAuthTests extends Specification {
    @Shared ExecutionContext ec
    @Shared RSAKey rsaKey
    @Shared JWKSet jwks
    static final String ISS = "https://idp.test/realms/mcp"
    static final String AUD = "https://oms.test/mcp"

    def setupSpec() {
        System.setProperty('mcp_enabled', 'Y')
        ec = Moqui.getExecutionContext()
        rsaKey = new RSAKeyGenerator(2048).keyID("test-key-1").generate()
        jwks = new JWKSet(rsaKey.toPublicJWK())

        // engine seam: config via properties, issuer metadata pre-seeded in the cache the
        // engine reads (a real deployment fills it from the AuthFlow's OIDC discovery)
        System.setProperty('mcp_auth_flow_id', 'TEST_IDP')
        System.setProperty('mcp_audience', AUD)
        System.setProperty('mcp_canonical_uri', 'http://localhost:8080/mcp/json')
        ec.cache.getCache("ai.mcp.oauth.meta").put('TEST_IDP', [issuer: ISS, jwksJson: jwks.toString()])

        // the mapped user: token sub -> UserAccount.externalUserId; the realm needs UserLogin too
        ec.artifactExecution.disableAuthz()
        ec.transaction.runRequireNew(30, "mcp oauth test setup", {
            ec.entity.makeValue("org.apache.ofbiz.party.party.Party").setAll([partyId: "McpOauthUser", partyTypeId: "PERSON"]).createOrUpdate()
            ec.entity.makeValue("org.apache.ofbiz.party.party.Person").setAll([partyId: "McpOauthUser", firstName: "OAuth", lastName: "Test User"]).createOrUpdate()
            ec.entity.makeValue("org.apache.ofbiz.security.login.UserLogin").setAll([userLoginId: "McpOauthUser", partyId: "McpOauthUser", enabled: "Y"]).createOrUpdate()
            ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: "McpOauthUser", username: "McpOauthUser", userFullName: "OAuth Test User", externalUserId: "idp-sub-1"]).createOrUpdate()
        })
        ec.artifactExecution.enableAuthz()
    }
    def cleanupSpec() {
        // unpollute the JVM-wide properties for the specs that run after this one
        System.clearProperty('mcp_auth_flow_id')
        if (ec != null) ec.destroy()
    }
    def setup() { ((org.moqui.impl.context.UserFacadeImpl) ec.user).logoutUser(); ec.message.clearErrors() }

    private Map dispatch(Map extra) {
        Map p = [jsonrpc: '2.0', id: 1,
                 protocolVersionHeader: '2026-07-28']
        p.putAll(extra)
        return (Map) ec.service.sync().name("ai.mcp.McpServices.dispatch#Request").parameters(p).call()
    }
    private Map callParams(String toolName, Map arguments) {
        return [method: 'tools/call', mcpMethodHeader: 'tools/call', mcpNameHeader: toolName,
                params: [_meta: ['io.modelcontextprotocol/protocolVersion': '2026-07-28',
                                 'io.modelcontextprotocol/clientCapabilities': [:]],
                         name: toolName, arguments: arguments]]
    }

    private String mint(Map claimOverrides = [:]) {
        def now = new Date()
        def builder = new JWTClaimsSet.Builder()
                .issuer((String) claimOverrides.getOrDefault('iss', ISS))
                .audience((String) claimOverrides.getOrDefault('aud', AUD))
                .subject((String) claimOverrides.getOrDefault('sub', 'idp-sub-1'))
                .issueTime(now)
                .expirationTime((Date) claimOverrides.getOrDefault('exp', new Date(now.time + 300_000)))
        if (claimOverrides.scope != null) builder.claim('scope', claimOverrides.scope)
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.keyID).build(), builder.build())
        jwt.sign(new RSASSASigner((RSAKey) claimOverrides.getOrDefault('signWith', rsaKey)))
        return jwt.serialize()
    }

    def "a valid token yields its claims, subject and scopes included"() {
        when:
        Map claims = McpBearerValidator.validate(mint([scope: 'mcp:spend other']), jwks, ISS, AUD)

        then:
        claims.sub == 'idp-sub-1'
        ((List) claims.scopes).contains('mcp:spend')
    }

    def "a token for another audience is rejected — never accepted, never forwarded"() {
        when:
        McpBearerValidator.validate(mint([aud: 'https://other.test/api']), jwks, ISS, AUD)

        then:
        IllegalArgumentException e = thrown()
        e.message.toLowerCase().contains('audience')
    }

    def "a token from another issuer is rejected"() {
        when:
        McpBearerValidator.validate(mint([iss: 'https://evil.test']), jwks, ISS, AUD)

        then:
        thrown(IllegalArgumentException)
    }

    def "an expired token is rejected"() {
        when:
        McpBearerValidator.validate(mint([exp: new Date(System.currentTimeMillis() - 120_000)]), jwks, ISS, AUD)

        then:
        IllegalArgumentException e = thrown()
        e.message.toLowerCase().contains('expired')
    }

    def "a token signed with an unknown key is rejected"() {
        given: "a different keypair the JWKS has never seen"
        RSAKey rogue = new RSAKeyGenerator(2048).keyID("test-key-1").generate()

        when:
        McpBearerValidator.validate(mint([signWith: rogue]), jwks, ISS, AUD)

        then:
        thrown(IllegalArgumentException)
    }

    def "garbage is rejected, not parsed into an exception leak"() {
        when:
        McpBearerValidator.validate("not-a-jwt", jwks, ISS, AUD)

        then:
        thrown(IllegalArgumentException)
    }

    // ---- the engine path ----

    def "with the endpoint disabled every request is a dark 404"() {
        given:
        System.setProperty('mcp_enabled', 'N')

        when:
        Map out = dispatch([method: 'server/discover', mcpMethodHeader: 'server/discover',
                            params: [_meta: ['io.modelcontextprotocol/protocolVersion': '2026-07-28',
                                             'io.modelcontextprotocol/clientCapabilities': [:]]]])

        then:
        out.httpStatus == 404
        out.response == null

        cleanup:
        System.setProperty('mcp_enabled', 'Y')
    }

    def "a valid Bearer token with the right scope runs the tool as the mapped user"() {
        when:
        Map out = dispatch(callParams('get_ai_spend', [:]) + [authorizationHeader: 'Bearer ' + mint([scope: 'mcp:spend'])])
        Map result = (Map) ((Map) out.response).result

        then:
        out.httpStatus == 200
        result.isError == false
        ec.entity.find("moqui.ai.AiToolCall").condition("toolName", "get_ai_spend")
                .orderBy("-toolCallId").list()[0].userId == "McpOauthUser"
    }

    def "tools/call without credentials is HTTP 401 with WWW-Authenticate pointing at the metadata"() {
        when:
        Map out = dispatch(callParams('get_ai_spend', [:]))

        then:
        out.httpStatus == 401
        ((String) out.wwwAuthenticate).contains('Bearer')
        ((String) out.wwwAuthenticate).contains('resource_metadata')
        ((Map) ((Map) out.response).error) != null
    }

    def "an invalid token is HTTP 401, with the safe reason"() {
        when:
        Map out = dispatch(callParams('get_ai_spend', [:]) + [authorizationHeader: 'Bearer not-a-jwt'])

        then:
        out.httpStatus == 401
        ((String) out.wwwAuthenticate).contains('invalid_token')
    }

    def "a valid token whose subject maps to no account is HTTP 403"() {
        when:
        Map out = dispatch(callParams('get_ai_spend', [:]) + [authorizationHeader: 'Bearer ' + mint([sub: 'idp-sub-unknown', scope: 'mcp:spend'])])

        then:
        out.httpStatus == 403
    }

    def "a token missing the tool's scope is HTTP 403 insufficient_scope naming the scope"() {
        when: "get_ai_spend declares scope mcp:spend; the token carries only other"
        Map out = dispatch(callParams('get_ai_spend', [:]) + [authorizationHeader: 'Bearer ' + mint([scope: 'other'])])

        then:
        out.httpStatus == 403
        ((String) out.wwwAuthenticate).contains('insufficient_scope')
        ((String) out.wwwAuthenticate).contains('mcp:spend')
    }

    def "a Moqui web login still works as the fallback: no token, no 401"() {
        given: "the deployment's existing auth (Basic and the Bearer-JWT patch) logged a user in"
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("McpOauthUser")

        when:
        Map out = dispatch(callParams('get_ai_spend', [:]))
        Map result = (Map) ((Map) out.response).result

        then:
        out.httpStatus == 200
        result.isError == false
    }

    def "discover and list stay public even with OAuth configured"() {
        when:
        Map out = dispatch([method: 'tools/list', mcpMethodHeader: 'tools/list',
                            params: [_meta: ['io.modelcontextprotocol/protocolVersion': '2026-07-28',
                                             'io.modelcontextprotocol/clientCapabilities': [:]]]])

        then:
        out.httpStatus == 200
        ((List) ((Map) ((Map) out.response).result).tools).size() >= 2
    }
}
