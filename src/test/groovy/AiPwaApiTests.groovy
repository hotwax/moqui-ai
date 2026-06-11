import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.AiToolFactory
import org.moqui.ai.provider.MockProvider

/** Services added for the company-app PWA REST facade (service/ai.rest.xml): agent detail,
 *  model list, capability-search approval flag, instruction enhancement, workforce inbox/detail. */
class AiPwaApiTests extends Specification {
    @Shared ExecutionContext ec
    @Shared AiToolFactory ai

    private void ensureTestUser() {
        ec.entity.makeValue("org.apache.ofbiz.party.party.Party").setAll([partyId: "AiTestUser", partyTypeId: "PERSON"]).createOrUpdate()
        ec.entity.makeValue("org.apache.ofbiz.party.party.Person").setAll([partyId: "AiTestUser", firstName: "AI", lastName: "Test User"]).createOrUpdate()
        ec.entity.makeValue("org.apache.ofbiz.security.login.UserLogin").setAll([userLoginId: "AiTestUser", partyId: "AiTestUser", enabled: "Y"]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
    }

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ai = ec.factory.getTool("AI", AiToolFactory.class)
        ec.artifactExecution.disableAuthz()
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiTestToolData.xml").load()
            ensureTestUser()
        })
        ai.refreshCatalog()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.artifactExecution.enableAuthz()
    }
    def cleanupSpec() { if (ec != null) ec.destroy() }
    def setup() {
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
    }
    def cleanup() { MockProvider.reset() }

    def "get#AgentDetail returns agent fields plus grants with resolved approval flags"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.transaction.runRequireNew(30, "setup", {
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "PwaDetailAgent", agentName: "PwaDetailAgent",
                providerName: "mock", modelName: "mock-1", systemPrompt: "x", statusId: "AI_AGENT_DRAFT"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiAgentTool").setAll([agentId: "PwaDetailAgent", toolId: "TL_GATED",
                requiresApprovalOverride: "N"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiAgentTool").setAll([agentId: "PwaDetailAgent", toolId: "TL_ECHO"]).createOrUpdate()
        })
        when:
        Map out = ec.service.sync().name("ai.AgentServices.get#AgentDetail").parameters([agentId: "PwaDetailAgent"]).call()
        then:
        out.agent.agentName == "PwaDetailAgent"
        (out.toolList as List).size() == 2
        with((out.toolList as List).find { it.toolId == "TL_GATED" }) {
            requiresApproval == "Y"; requiresApprovalOverride == "N"; effectiveRequiresApproval == "N"
        }
        with((out.toolList as List).find { it.toolId == "TL_ECHO" }) {
            requiresApproval == "N"; requiresApprovalOverride == null; effectiveRequiresApproval == "N"
        }
        cleanup:
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", "PwaDetailAgent").deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "PwaDetailAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "list#Model returns options and reasoning efforts"() {
        when:
        Map out = ec.service.sync().name("ai.AgentServices.list#Model").parameters([:]).call()
        then:
        out.reasoningEffortList == ["none", "low", "medium", "high"]
        out.modelList instanceof List
        !(out.modelList as List).isEmpty()
        out.defaultModelName != null
    }

    def "find#Capability exposes the requiresApproval default"() {
        when:
        Map out = ec.service.sync().name("ai.ComposerServices.find#Capability").parameters([query: "gated"]).call()
        then:
        def gated = (out.capabilityList as List).find { it.toolId == "TL_GATED" }
        gated != null
        gated.requiresApproval == "Y"
    }
}
