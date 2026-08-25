import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui

/** MCP step 5: rate limiting via Moqui's ArtifactTarpit (design decision 11). The seed rows
 *  in data/McpSecurityData.xml cap dispatch#Request per user; because dispatch is the one
 *  service every MCP request passes through, this is a total per-caller cap on the endpoint.
 *  MUST run LAST in MoquiSuite: tripping the tarpit locks this spec's dedicated user for the
 *  tarpitDuration, and the hit cache lives for the factory's lifetime. A dedicated user keeps
 *  the other specs' buckets untouched (the tarpit counter is per user per artifact). */
class McpTarpitTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() {
        System.setProperty('mcp_enabled', 'Y')   // the endpoint ships dark (default N)
        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
        ec.transaction.runRequireNew(30, "mcp tarpit test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/McpSecurityData.xml").load()
            ec.entity.makeValue("org.apache.ofbiz.party.party.Party").setAll([partyId: "McpTarpitUser", partyTypeId: "PERSON"]).createOrUpdate()
            ec.entity.makeValue("org.apache.ofbiz.party.party.Person").setAll([partyId: "McpTarpitUser", firstName: "Tarpit", lastName: "Test User"]).createOrUpdate()
            ec.entity.makeValue("org.apache.ofbiz.security.login.UserLogin").setAll([userLoginId: "McpTarpitUser", partyId: "McpTarpitUser", enabled: "Y"]).createOrUpdate()
            ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: "McpTarpitUser", username: "McpTarpitUser", userFullName: "Tarpit Test User"]).createOrUpdate()
            // the tarpit lock is DB-persisted (moqui.security.ArtifactTarpitLock) and survives
            // across JVM runs: a suite rerun within tarpitDuration would start locked out and
            // trip on call 1 — clear this user's stale lock so the test is deterministic
            ec.entity.find("moqui.security.ArtifactTarpitLock").condition("userId", "McpTarpitUser").deleteAll()
        })
        ec.artifactExecution.enableAuthz()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("McpTarpitUser")
    }
    def cleanupSpec() { if (ec != null) ec.destroy() }

    def "the dispatch tarpit trips after the seeded per-user limit"() {
        given: "the production tarpit setting, restored for this test only"
        // MoquiDevConf.xml deliberately sets AT_SERVICE tarpit-enabled=false in dev mode, so
        // without this the mechanism under test is config-disabled and the test would be vacuous
        def tarpitEnabledMap = ((org.moqui.impl.context.ExecutionContextFactoryImpl) ec.factory).artifactTypeTarpitEnabled
        def AT_SERVICE = org.moqui.context.ArtifactExecutionInfo.AT_SERVICE
        Boolean devSetting = tarpitEnabledMap.get(AT_SERVICE)
        tarpitEnabledMap.put(AT_SERVICE, true)

        when: "one user hammers the endpoint; every request passes through dispatch#Request"
        int tripped = -1
        for (int i = 1; i <= 140; i++) {
            try {
                ec.service.sync().name("ai.mcp.McpServices.dispatch#Request")
                        .parameters([jsonrpc: '2.0', method: 'server/discover', id: i,
                                     params: [_meta: ['io.modelcontextprotocol/protocolVersion': '2026-07-28',
                                                      'io.modelcontextprotocol/clientCapabilities': [:]]],
                                     protocolVersionHeader: '2026-07-28', mcpMethodHeader: 'server/discover']).call()
            } catch (org.moqui.context.ArtifactTarpitException e) {
                tripped = i
                break
            }
        }

        then: "the seeded limit (120 hits / 60s) locks the user out, roughly at the limit"
        tripped > 100
        tripped <= 140

        cleanup: "put the dev-mode setting back"
        tarpitEnabledMap.put(AT_SERVICE, devSetting)
    }
}
