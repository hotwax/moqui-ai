package org.moqui.ai

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor

/** OAuth 2.1 resource-server token validation for the MCP endpoint (spec: "Token
 *  validation"; design step 6). PURE: token + trusted JWKSet + expected issuer and
 *  audience in, claims out or a loud IllegalArgumentException. Fetching and caching the
 *  JWKS (from the moqui-sso AuthFlow's OIDC discovery) is the caller's seam, which is what
 *  lets tests mint tokens against a generated keypair with no IdP and no HTTP.
 *
 *  The audience check is the rule the MCP spec is most insistent about: a token issued for
 *  another service must be rejected, never accepted, and this server never forwards a
 *  client's token upstream. nimbus-jose-jwt is provided at runtime by moqui-sso's lib. */
class McpBearerValidator {

    /** Validate and return the claims that matter: [sub, scopes (List), claims (full Map)]. */
    static Map<String, Object> validate(String token, JWKSet trustedKeys, String expectedIssuer, String expectedAudience) {
        JWTClaimsSet claims
        try {
            DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>()
            processor.setJWSKeySelector(new JWSVerificationKeySelector<SecurityContext>(
                    JWSAlgorithm.RS256, new ImmutableJWKSet<SecurityContext>(trustedKeys)))
            // exactMatchClaims enforces iss + aud; requiredClaims enforces presence of exp/sub;
            // the verifier also checks exp/nbf against the clock (with its default small skew)
            processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<SecurityContext>(
                    expectedAudience,
                    new JWTClaimsSet.Builder().issuer(expectedIssuer).build(),
                    new HashSet<String>(['exp', 'sub'])))
            claims = processor.process(token, null)
        } catch (Exception e) {
            // one exception type out, message safe to send to a client (no token contents)
            throw new IllegalArgumentException(mapReason(e), e)
        }

        // the scope claim is a space-separated string per RFC 6749/8693 practice
        String scopeStr = claims.getStringClaim('scope')
        List<String> scopes = scopeStr ? scopeStr.split(' ').toList() : []
        return [sub: claims.subject, scopes: scopes, claims: claims.getClaims()] as Map<String, Object>
    }

    private static String mapReason(Exception e) {
        String m = e.getMessage() ?: e.getClass().getSimpleName()
        String lower = m.toLowerCase()
        if (lower.contains('expired')) return 'Token expired'
        if (lower.contains('audience') || lower.contains('aud ')) return 'Token audience does not include this server'
        if (lower.contains('issuer') || lower.contains('iss ')) return 'Token issuer is not trusted'
        if (lower.contains('signature') || lower.contains('no matching key')) return 'Token signature invalid'
        return 'Token invalid: ' + m
    }
}
