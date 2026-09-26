package org.moqui.ai

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.util.SystemBinding

/** RFC 9728 OAuth 2.0 Protected Resource Metadata for the MCP endpoint (spec: design
 *  decision 13 — screen names cannot start with a dot, so /.well-known needs this one small
 *  servlet). Serves only when the endpoint is enabled and a trusted AuthFlow is configured;
 *  otherwise 404. The issuer comes from the OidcFlow's discoveryUri, which by OIDC discovery
 *  convention is <issuer>/.well-known/openid-configuration. */
@CompileStatic
class McpMetadataServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) {
        String authFlowId = SystemBinding.getPropOrEnv('mcp_auth_flow_id')
        if (SystemBinding.getPropOrEnv('mcp_enabled') != 'Y' || !authFlowId) { response.setStatus(404); return }

        ExecutionContextFactoryImpl ecfi = (ExecutionContextFactoryImpl) getServletContext().getAttribute("executionContextFactory")
        if (ecfi == null) { response.setStatus(503); return }
        ExecutionContextImpl eci = ecfi.getEci()
        try {
            def flow = eci.entityFacade.find('moqui.security.sso.OidcFlow')
                    .condition('authFlowId', authFlowId).disableAuthz().one()
            String discoveryUri = (String) flow?.getNoCheckSimple('discoveryUri')
            if (discoveryUri == null) { response.setStatus(404); return }
            String issuer = discoveryUri.replaceFirst('/\\.well-known/openid-configuration$', '')

            String json = JsonOutput.toJson([
                    resource: SystemBinding.getPropOrEnv('mcp_canonical_uri'),
                    authorization_servers: [issuer],
                    bearer_methods_supported: ['header']])
            response.setStatus(200)
            response.setContentType('application/json')
            response.setContentLength(json.getBytes('UTF-8').length)
            response.writer.write(json)
            response.writer.flush()
        } finally {
            eci.destroy()
        }
    }
}
