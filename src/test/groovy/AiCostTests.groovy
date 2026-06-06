import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.entity.EntityValue
import org.moqui.ai.provider.MockProvider

class AiCostTests extends Specification {
    @Shared ExecutionContext ec
    // The active Shiro realm (co.hotwax.auth.OfbizShiroRealm) authenticates against the OFBiz UserLogin
    // model, not moqui.security.UserAccount, so the test user needs Party/Person/UserLogin rows for
    // internalLoginUser("AiTestUser") to succeed. Must be called inside a committed (runRequireNew) tx.
    private void ensureTestUser() {
        ec.entity.makeValue("org.apache.ofbiz.party.party.Party").setAll([partyId: "AiTestUser", partyTypeId: "PERSON"]).createOrUpdate()
        ec.entity.makeValue("org.apache.ofbiz.party.party.Person").setAll([partyId: "AiTestUser", firstName: "AI", lastName: "Test User"]).createOrUpdate()
        ec.entity.makeValue("org.apache.ofbiz.security.login.UserLogin").setAll([userLoginId: "AiTestUser", partyId: "AiTestUser", enabled: "Y"]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
    }

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ensureTestUser()
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "CostAgent", providerName: "mock",
                modelName: "mock-1", systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
            // Purge any residue from a prior run (file-backed H2 persists across runs) so spend totals are deterministic
            purgeCostAgentRuns()
        })
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.service.sync().name("ai.CostServices.store#AiModelPrice").parameters([providerName: "mock",
            modelName: "mock-1", inputPricePerMillion: 3.0G, outputPricePerMillion: 15.0G]).call()
    }
    // AiAgentRunStep / AiToolCall hold real FKs to AiAgentRun; delete children before the runs
    private void purgeCostAgentRuns() {
        def runIds = ec.entity.find("moqui.ai.AiAgentRun").condition("agentName", "CostAgent")
            .list().collect { it.agentRunId }
        for (String runId in runIds) {
            ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", runId).deleteAll()
            ec.entity.find("moqui.ai.AiAgentRunStep").condition("agentRunId", runId).deleteAll()
            ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", runId).deleteAll()
        }
    }
    def cleanupSpec() {
        if (ec == null) return
        ec.artifactExecution.disableAuthz()
        purgeCostAgentRuns()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "CostAgent").deleteAll()
        ec.entity.find("moqui.ai.AiModelPrice").condition("providerName", "mock").deleteAll()
        ec.artifactExecution.enableAuthz()
        ec.destroy()
    }
    def cleanup() { MockProvider.reset() }

    def "computes cost from per-million prices"() {
        expect:
        org.moqui.ai.CostCalc.cost(1000L, 500L, 3.0G, 15.0G) == 0.010500G
        org.moqui.ai.CostCalc.cost(0L, 0L, 3.0G, 15.0G) == 0.000000G
    }

    def "a run stamps estimatedCost and get#AiSpend sums it"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.message.clearErrors()
        purgeCostAgentRuns()
        MockProvider.reset()
        MockProvider.enqueue([assistantText: "ok", finishReason: "stop", toolCalls: [], tokensIn: 1000L, tokensOut: 500L])
        when:
        Map out = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentName: "CostAgent", userMessage: "hi"]).call()
        EntityValue run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", out.agentRunId).one()
        then:
        out.statusId == "AI_RUN_COMPLETED"
        (out.estimatedCost as BigDecimal) == 0.010500G
        (run.estimatedCost as BigDecimal) == 0.010500G       // 1000@$3/M + 500@$15/M
        when:
        Map spend = ec.service.sync().name("ai.CostServices.get#AiSpend").parameters([agentName: "CostAgent"]).call()
        then:
        (spend.totalCost as BigDecimal) == 0.010500G
        (spend.totalTokensIn as Long) == 1000L
        (spend.runCount as Long) == 1L
        cleanup:
        ec.artifactExecution.enableAuthz()
    }

    def "get#AiSpend groups by agent"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.message.clearErrors()
        MockProvider.reset()
        MockProvider.enqueue([assistantText: "ok", finishReason: "stop", toolCalls: [], tokensIn: 200L, tokensOut: 100L])
        ec.service.sync().name("ai.AgentServices.run#Agent").parameters([agentName: "CostAgent", userMessage: "again"]).call()
        when:
        Map spend = ec.service.sync().name("ai.CostServices.get#AiSpend").parameters([groupBy: "agent"]).call()
        then:
        spend.rows.any { it.key == "CostAgent" && (it.runCount as Long) >= 1L }
        cleanup:
        ec.artifactExecution.enableAuthz()
    }
}
