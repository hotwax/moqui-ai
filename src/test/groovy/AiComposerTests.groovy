import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.AiToolFactory
import org.moqui.ai.provider.MockProvider
import org.moqui.entity.EntityValue

/** Composer Assistant: meta-tool services, preview override, seeded agent, and the
 *  compose -> preview -> gated-activate integration flow. Built on the registry keystone
 *  (opaque toolId/agentId, ai.ToolServices.store#AiTool / ai.AgentServices.store#AiAgent,
 *  effectEnumId, AI_AGENT_DRAFT, the human-approval gate). */
class AiComposerTests extends Specification {
    @Shared ExecutionContext ec
    @Shared AiToolFactory ai

    // The active Shiro realm (co.hotwax.auth.OfbizShiroRealm) authenticates against the OFBiz UserLogin
    // model, not moqui.security.UserAccount, so the test user needs Party/Person/UserLogin rows for
    // internalLoginUser("AiTestUser") to succeed. Must be called inside a committed (runRequireNew) tx.
    private void ensureTestUser() {
        ec.entity.makeValue("org.apache.ofbiz.party.party.Party").setAll([partyId: "AiTestUser", partyTypeId: "PERSON"]).createOrUpdate()
        ec.entity.makeValue("org.apache.ofbiz.party.party.Person").setAll([partyId: "AiTestUser", firstName: "AI", lastName: "Test User"]).createOrUpdate()
        ec.entity.makeValue("org.apache.ofbiz.security.login.UserLogin").setAll([userLoginId: "AiTestUser", partyId: "AiTestUser", enabled: "Y"]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
    }

    /** Seed a curated read-only tool (the notnaked OMS order-summary service) via the keystone authoring
     *  gate; returns its toolId. exposable=Y by the READ_ONLY default. Idempotent across reruns on the
     *  persistent DB: drop any prior tool with the derived toolName first. */
    private Map storeOrdersTool(String noun = "orders", String desc = "List recent orders.") {
        ec.entity.find("moqui.ai.AiTool").condition("toolName", "list_${noun}").deleteAll()
        Map out = ec.service.sync().name("ai.ToolServices.store#AiTool").parameters([
            serviceName: "notnaked.OmsAiServices.get#OrderSummaryList",
            verb: "list", noun: noun, description: desc]).call()
        ec.message.clearErrors()
        return out
    }

    /** Drop an agent + its grants by name so store#AiAgent's unique-name check never trips on a leftover
     *  from a prior (possibly failed) run on the persistent test DB. */
    private void dropAgentByName(String name) {
        for (def a in ec.entity.find("moqui.ai.AiAgent").condition("agentName", name).list()) {
            ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", a.agentId).deleteAll()
            ec.entity.find("moqui.ai.AiAgent").condition("agentId", a.agentId).deleteAll()
        }
    }

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ai = ec.factory.getTool("AI", AiToolFactory.class)
        ec.artifactExecution.disableAuthz()
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiTestToolData.xml").load()
            // the AI Ops authz grants (incl. moqui.basic.Status.* reads needed by entity-auto checkStatus
            // when an authenticate service flips a statusId, e.g. activate#Agent draft->active)
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiSecurityData.xml").load()
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

    // ---- Task 1: find#Capability + describe#Capability ----

    def "find#Capability returns only exposable+active tools, matched by keyword"() {
        given:
        ec.artifactExecution.disableAuthz()
        storeOrdersTool()
        when:
        Map out = ec.service.sync().name("ai.ComposerServices.find#Capability")
            .parameters([query: "order"]).call()
        then:
        out.capabilityList.size() >= 1
        out.capabilityList.every { it.exposable == "Y" && it.statusId == "AI_TOOL_ACTIVE" }
        out.capabilityList.any { it.toolName == "list_orders" }
        // a non-matching keyword excludes it
        ec.service.sync().name("ai.ComposerServices.find#Capability")
            .parameters([query: "zzznomatch"]).call().capabilityList.isEmpty()
        cleanup:
        ec.entity.find("moqui.ai.AiTool").condition("toolName", "list_orders").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "describe#Capability returns purpose + input schema for one tool"() {
        given:
        ec.artifactExecution.disableAuthz()
        Map s = storeOrdersTool()
        when:
        Map out = ec.service.sync().name("ai.ComposerServices.describe#Capability")
            .parameters([toolId: s.toolId]).call()
        then:
        out.toolName == "list_orders"
        out.description == "List recent orders."
        out.effect == "AI_TOOL_READ_ONLY"
        // schema is generated on demand from the live service definition
        (out.inputSchema as Map).properties.containsKey("maxOrders")
        cleanup:
        ec.entity.find("moqui.ai.AiTool").condition("toolId", s.toolId).deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    // ---- Task 2: list#DomainTerm (stub) + propose#Naming (best-guess) ----

    def "list#DomainTerm returns distinct catalog nouns (KB stub)"() {
        given:
        ec.artifactExecution.disableAuthz()
        storeOrdersTool()
        when:
        Map out = ec.service.sync().name("ai.ComposerServices.list#DomainTerm").parameters([:]).call()
        then:
        out.termList.contains("orders")
        out.source == "catalog"   // documents the stub seam; flips to "knowledgebase" later
        cleanup:
        ec.entity.find("moqui.ai.AiTool").condition("toolName", "list_orders").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "propose#Naming returns a wire-safe verb_noun suggestion grounded in the catalog"() {
        given:
        ec.artifactExecution.disableAuthz(); ec.message.clearErrors()
        when:
        Map out = ec.service.sync().name("ai.ComposerServices.propose#Naming")
            .parameters([intent: "an assistant that summarizes recent orders for a store manager"]).call()
        then:
        out.agentNameSuggestion != null
        out.descriptionSuggestion != null
        // suggestion is snake_case / wire-safe by construction
        out.agentNameSuggestion ==~ /^[a-z0-9-]+$/
        cleanup: ec.artifactExecution.enableAuthz()
    }

    def "propose#Naming refines the suggestion via the data-defined naming agent when available"() {
        given:
        ec.artifactExecution.disableAuthz(); MockProvider.reset(); ec.message.clearErrors()
        // the namer ships in data (AiComposerData: AICMP_NAMING_AGENT); route it through mock here.
        // responseSchema mirrors the seeded agent so run#Agent surfaces a typed structuredResult.
        ec.transaction.runRequireNew(30, "setup", {
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "AICMP_NAMING_AGENT", agentName: "agent-namer",
                providerName: "mock", modelName: "mock-1", systemPrompt: "x", maxIterations: 1, statusId: "AI_AGENT_ACTIVE",
                responseSchema: '{"type":"object","properties":{"name":{"type":"string"},"description":{"type":"string"}},"required":["name","description"],"additionalProperties":false}']).createOrUpdate()
        })
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        // the naming agent returns a typed structuredResult (responseSchema); the service reads it directly — no JSON parse
        MockProvider.enqueue([structuredResult: [name: "Orders Maven", description: "Summarizes recent orders."],
            finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        when:
        Map out = ec.service.sync().name("ai.ComposerServices.propose#Naming")
            .parameters([intent: "summarize recent orders for a store manager"]).call()
        then:
        out.agentNameSuggestion == "orders-maven"          // lowercased + wire-safe by the service
        out.descriptionSuggestion == "Summarizes recent orders."
        cleanup:
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", "AICMP_NAMING_AGENT").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    // ---- Task 3: set#Guardrail ----

    def "set#Guardrail flips a grant's requiresApprovalOverride"() {
        given:
        ec.artifactExecution.disableAuthz()
        Map t = storeOrdersTool()
        dropAgentByName("GuardDraft")
        Map a = ec.service.sync().name("ai.AgentServices.store#AiAgent").parameters([
            agentName: "GuardDraft", providerName: "mock", modelName: "mock-1",
            systemPrompt: "x", statusId: "AI_AGENT_DRAFT"]).call()
        ec.service.sync().name("store#moqui.ai.AiAgentTool")
            .parameters([agentId: a.agentId, toolId: t.toolId]).call()
        ec.message.clearErrors()
        when:
        ec.service.sync().name("ai.ComposerServices.set#Guardrail")
            .parameters([agentId: a.agentId, toolId: t.toolId, requiresApproval: "Y"]).call()
        then:
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", a.agentId)
            .condition("toolId", t.toolId).one().requiresApprovalOverride == "Y"
        cleanup:
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", a.agentId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", a.agentId).deleteAll()
        ec.entity.find("moqui.ai.AiTool").condition("toolId", t.toolId).deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "store#AiAgent defaults a runnable provider/model/prompt for a fresh draft"() {
        given:
        ec.artifactExecution.disableAuthz()
        dropAgentByName("DefaultsDraft")
        ec.message.clearErrors()
        when: "the Composer drafts an agent with only a name + description (no model specified)"
        Map a = ec.service.sync().name("ai.AgentServices.store#AiAgent").parameters([
            agentName: "DefaultsDraft", description: "summarize recent orders"]).call()
        then: "the created draft is runnable without hand-editing — defaulted provider/model/bound/prompt"
        def ag = ec.entity.find("moqui.ai.AiAgent").condition("agentId", a.agentId).one()
        ag.statusId == "AI_AGENT_DRAFT"
        ag.providerName == "openai"
        ag.modelName == "gpt-4o-mini"
        ag.maxIterations == 5
        ag.systemPrompt == "summarize recent orders"
        cleanup:
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", a.agentId).deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    // ---- Task 4: request#Capability + AiCapabilityRequest ----

    def "request#Capability records a gap in AI_CAPREQ_OPEN status"() {
        given: ec.artifactExecution.disableAuthz(); ec.message.clearErrors()
        when:
        Map out = ec.service.sync().name("ai.ComposerServices.request#Capability")
            .parameters([intent: "cancel an order", suggestedVerb: "cancel", suggestedNoun: "order",
                         notes: "asked during a compose session"]).call()
        then:
        out.capabilityRequestId != null
        def r = ec.entity.find("moqui.ai.AiCapabilityRequest")
            .condition("capabilityRequestId", out.capabilityRequestId).one()
        r.statusId == "AI_CAPREQ_OPEN"
        r.intent == "cancel an order"
        cleanup:
        ec.entity.find("moqui.ai.AiCapabilityRequest").condition("capabilityRequestId", out.capabilityRequestId).deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    // ---- Task 5: AgentRunner preview override ----

    def "preview override suspends on a mutating tool but not the read-only one"() {
        given:
        ec.artifactExecution.disableAuthz()
        MockProvider.reset()
        // read-only tool: reuse the seeded TL_ECHO (toolName get_echo) — creating a new one would
        // collide on the unique toolName. Only the mutating set_echo is authored here.
        Map mut = ec.service.sync().name("ai.ToolServices.store#AiTool").parameters([
            serviceName: "moqui.ai.test.TestServices.set#Echo", verb: "set", noun: "echo",
            description: "write echo", exposable: "Y"]).call()      // MUTATING (curator blesses exposable)
        ec.message.clearErrors()
        dropAgentByName("PrevDraft")
        Map ag = ec.service.sync().name("ai.AgentServices.store#AiAgent").parameters([
            agentName: "PrevDraft", providerName: "mock", modelName: "mock-1",
            systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_DRAFT"]).call()
        ec.service.sync().name("store#moqui.ai.AiAgentTool").parameters([agentId: ag.agentId, toolId: "TL_ECHO"]).call()
        ec.service.sync().name("store#moqui.ai.AiAgentTool").parameters([agentId: ag.agentId, toolId: mut.toolId]).call()
        ai.refreshCatalog()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: null, finishReason: "tool_use", toolCalls: [
            [id: "r1", name: "get_echo", arguments: [text: "read me"]],
            [id: "w1", name: "set_echo", arguments: [text: "write me"]]], tokensIn: 1L, tokensOut: 1L])
        when:
        Map r = new org.moqui.ai.AgentRunner(ec).runPreview(ag.agentId as String, "go")
        then:
        r.statusId == "AI_RUN_SUSPENDED"
        // exactly the mutating call is held; whole turn suspended -> nothing executed yet
        def appr = ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", r.agentRunId).list()
        appr.size() == 1
        appr[0].toolName == "set_echo"
        ec.entity.find("moqui.ai.AiToolCall").condition("agentRunId", r.agentRunId).list().isEmpty()
        cleanup:
        ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", r.agentRunId).deleteAll()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", ag.agentId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", ag.agentId).deleteAll()
        ec.entity.find("moqui.ai.AiTool").condition("toolId", mut.toolId).deleteAll()
        ai.refreshCatalog()
        ec.artifactExecution.enableAuthz()
    }

    // ---- Task 6: preview#Agent service ----

    def "preview#Agent surfaces the held mutating calls"() {
        given:
        ec.artifactExecution.disableAuthz(); MockProvider.reset()
        ec.entity.find("moqui.ai.AiTool").condition("toolName", "set_echo").deleteAll()  // isolation
        Map mut = ec.service.sync().name("ai.ToolServices.store#AiTool").parameters([
            serviceName: "moqui.ai.test.TestServices.set#Echo", verb: "set", noun: "echo",
            description: "write echo", exposable: "Y"]).call()
        ec.message.clearErrors()
        dropAgentByName("PrevDraft2")
        Map ag = ec.service.sync().name("ai.AgentServices.store#AiAgent").parameters([
            agentName: "PrevDraft2", providerName: "mock", modelName: "mock-1",
            systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_DRAFT"]).call()
        ec.service.sync().name("store#moqui.ai.AiAgentTool").parameters([agentId: ag.agentId, toolId: mut.toolId]).call()
        ai.refreshCatalog()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "w1", name: "set_echo", arguments: [text: "x"]]], tokensIn: 1L, tokensOut: 1L])
        when:
        Map out = ec.service.sync().name("ai.ComposerServices.preview#Agent")
            .parameters([agentId: ag.agentId, testMessage: "go"]).call()
        then:
        out.statusId == "AI_RUN_SUSPENDED"
        out.heldCalls.size() == 1
        out.heldCalls[0].toolName == "set_echo"
        out.agentRunId != null
        cleanup:
        ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", out.agentRunId).deleteAll()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", ag.agentId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", ag.agentId).deleteAll()
        ec.entity.find("moqui.ai.AiTool").condition("toolId", mut.toolId).deleteAll()
        ai.refreshCatalog()
        ec.artifactExecution.enableAuthz()
    }

    // ---- Task 7: activate#Agent ----

    def "activate#Agent flips a valid draft to active"() {
        given:
        ec.artifactExecution.disableAuthz()
        Map t = storeOrdersTool()
        dropAgentByName("ActDraft")
        Map a = ec.service.sync().name("ai.AgentServices.store#AiAgent").parameters([
            agentName: "ActDraft", providerName: "mock", modelName: "mock-1",
            systemPrompt: "x", statusId: "AI_AGENT_DRAFT"]).call()
        ec.service.sync().name("store#moqui.ai.AiAgentTool").parameters([agentId: a.agentId, toolId: t.toolId]).call()
        ec.message.clearErrors()
        when:
        ec.service.sync().name("ai.ComposerServices.activate#Agent").parameters([agentId: a.agentId]).call()
        then:
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", a.agentId).one().statusId == "AI_AGENT_ACTIVE"
        cleanup:
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", a.agentId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", a.agentId).deleteAll()
        ec.entity.find("moqui.ai.AiTool").condition("toolId", t.toolId).deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "activate#Agent refuses when a granted tool is not exposable"() {
        given:
        ec.artifactExecution.disableAuthz()
        // a tool the curator later un-exposed (or a denylisted one): exposable=N
        Map t = storeOrdersTool("secret", "x")
        ec.entity.find("moqui.ai.AiTool").condition("toolId", t.toolId).updateAll([exposable: "N"])
        dropAgentByName("ActDraftBad")
        Map a = ec.service.sync().name("ai.AgentServices.store#AiAgent").parameters([
            agentName: "ActDraftBad", providerName: "mock", modelName: "mock-1",
            systemPrompt: "x", statusId: "AI_AGENT_DRAFT"]).call()
        ec.service.sync().name("store#moqui.ai.AiAgentTool").parameters([agentId: a.agentId, toolId: t.toolId]).call()
        ec.message.clearErrors()
        when:
        ec.service.sync().name("ai.ComposerServices.activate#Agent").parameters([agentId: a.agentId]).call()
        then:
        ec.message.hasError()
        ec.message.errorsString.toLowerCase().contains("exposable")
        // still a draft — not activated
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", a.agentId).one().statusId == "AI_AGENT_DRAFT"
        cleanup:
        ec.message.clearErrors()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", a.agentId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", a.agentId).deleteAll()
        ec.entity.find("moqui.ai.AiTool").condition("toolId", t.toolId).deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    // ---- Task 8: seeded composer-assistant + meta-tool catalog + grants ----

    def "composer-assistant seed loads with its meta-tool grants"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiComposerData.xml").load()
        when:
        def agent = ec.entity.find("moqui.ai.AiAgent").condition("agentName", "composer-assistant").one()
        def grants = ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", agent.agentId).list()
        def grantedToolNames = grants.collect { g -> ec.entity.find("moqui.ai.AiTool")
            .condition("toolId", g.toolId).one()?.toolName } as Set
        then:
        agent != null
        agent.statusId == "AI_AGENT_ACTIVE"
        agent.systemPrompt.contains("Composer")
        // the full meta-tool set is granted
        grantedToolNames.containsAll(["find_capability","describe_capability","list_domain_terms",
            "propose_naming","draft_agent","grant_capability","set_guardrail","preview_agent",
            "activate_agent","request_capability"] as Set)
        // activation requires approval (the commit gate)
        ec.entity.find("moqui.ai.AiTool").condition("toolName", "activate_agent").one().requiresApproval == "Y"
        cleanup: ec.artifactExecution.enableAuthz()
    }

    // ---- Task 9: e2e compose -> preview -> gated-activate ----

    def "e2e: compose a draft, preview it, then gated-activate it to active"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiComposerData.xml").load()
        // a curated read-only target tool the built agent will use
        Map target = storeOrdersTool()
        MockProvider.reset()
        // make the composer-assistant use the mock provider for this test
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "composer-assistant")
            .updateAll([providerName: "mock", modelName: "mock-1"])
        ai.refreshCatalog()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        dropAgentByName("orders-summary-bot")   // isolation: clear any leftover from a prior run
        String draftAgentId = ec.entity.sequencedIdPrimary("moqui.ai.AiAgent", null, null)
        Map conv = ec.service.sync().name("ai.AgentServices.create#Conversation")
            .parameters([agentId: "AICMP_AGENT"]).call()
        ec.message.clearErrors()

        // Turn 1: discover the capability
        MockProvider.enqueue([finishReason: "tool_use", toolCalls: [[id: "t1", name: "find_capability",
            arguments: [query: "orders"]]], tokensIn: 1L, tokensOut: 1L])
        // Turn 2: draft the agent (status DRAFT) — pass an explicit agentId so the test can track it
        MockProvider.enqueue([finishReason: "tool_use", toolCalls: [[id: "t2", name: "draft_agent",
            arguments: [agentId: draftAgentId, agentName: "orders-summary-bot", providerName: "mock",
                        modelName: "mock-1", systemPrompt: "Summarize orders.", statusId: "AI_AGENT_DRAFT"]]],
            tokensIn: 1L, tokensOut: 1L])
        // Turn 3: grant the target tool to the draft
        MockProvider.enqueue([finishReason: "tool_use", toolCalls: [[id: "t3", name: "grant_capability",
            arguments: [agentId: draftAgentId, toolId: target.toolId]]], tokensIn: 1L, tokensOut: 1L])
        // Turn 4: stop (assistant tells the user the draft is ready to preview)
        MockProvider.enqueue([assistantText: "Draft ready — want to preview it?", finishReason: "stop",
            toolCalls: [], tokensIn: 1L, tokensOut: 1L])

        when: "the build conversation runs"
        ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentId: "AICMP_AGENT", userMessage: "build an order summary agent",
                         conversationId: conv.conversationId]).call()
        then: "the draft exists with its grant"
        def draft = ec.entity.find("moqui.ai.AiAgent").condition("agentId", draftAgentId).one()
        draft.statusId == "AI_AGENT_DRAFT"
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", draftAgentId).list().size() == 1

        when: "the user previews the draft (read-only target -> runs on real data, completes)"
        // the draft's own provider is mock; script a single stop turn for its preview run
        MockProvider.enqueue([assistantText: "Here are the recent orders.", finishReason: "stop",
            toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        Map prev = ec.service.sync().name("ai.ComposerServices.preview#Agent")
            .parameters([agentId: draftAgentId, testMessage: "summarize orders"]).call()
        then: "no mutating call held (the only tool is read-only); preview completed"
        prev.statusId == "AI_RUN_COMPLETED"
        (prev.heldCalls as List).isEmpty()

        when: "the assistant proposes activation -> gated -> approve"
        MockProvider.enqueue([finishReason: "tool_use", toolCalls: [[id: "t5", name: "activate_agent",
            arguments: [agentId: draftAgentId]]], tokensIn: 1L, tokensOut: 1L])
        MockProvider.enqueue([assistantText: "Activated.", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        Map suspended = ec.service.sync().name("ai.AgentServices.run#Agent")
            .parameters([agentId: "AICMP_AGENT", userMessage: "activate it",
                         conversationId: conv.conversationId]).call()
        then: "activation suspended the Composer run on the commit gate"
        suspended.statusId == "AI_RUN_SUSPENDED"
        (suspended.toolCallRequestIds as List).size() == 1
        // draft NOT yet active
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", draftAgentId).one().statusId == "AI_AGENT_DRAFT"

        when: "a human approves -> resume dispatches activate#Agent"
        ec.service.sync().name("ai.ToolCallRequestServices.approve#ToolCallRequest")
            .parameters([toolCallRequestId: (suspended.toolCallRequestIds as List)[0]]).call()
        then: "the draft is now ACTIVE and runnable"
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", draftAgentId).one().statusId == "AI_AGENT_ACTIVE"

        cleanup:
        ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", suspended.agentRunId).deleteAll()
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", conv.conversationId).deleteAll()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", conv.conversationId).deleteAll()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", draftAgentId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", draftAgentId).deleteAll()
        ec.entity.find("moqui.ai.AiTool").condition("toolId", target.toolId).deleteAll()
        // restore the seeded composer-assistant provider (flipped to mock at setup for this test) so a
        // shared dev DB (e.g. a live demo's hcsd_notnaked) isn't left with composer-assistant on mock.
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "composer-assistant")
            .updateAll([providerName: "openai", modelName: "gpt-4o-mini"])
        ai.refreshCatalog()
        ec.artifactExecution.enableAuthz()
    }

    // ---- Preview-run hygiene: a preview SUSPENDS on would-be mutating calls only to SHOW them, then
    //      the run is abandoned. Those throwaway AI_RUN_SUSPENDED runs + AI_TCREQ_PENDING approvals must
    //      be marked (isPreview), kept out of the operator queue, and removed on discard
    //      (design open question; spec §12.1 cleanup). ----

    def "runPreview marks its AiAgentRun as a preview run (isPreview=Y)"() {
        given:
        ec.artifactExecution.disableAuthz(); MockProvider.reset()
        ec.entity.find("moqui.ai.AiTool").condition("toolName", "set_echo").deleteAll()  // isolation
        Map mut = ec.service.sync().name("ai.ToolServices.store#AiTool").parameters([
            serviceName: "moqui.ai.test.TestServices.set#Echo", verb: "set", noun: "echo",
            description: "write echo", exposable: "Y"]).call()
        ec.message.clearErrors()
        dropAgentByName("PrevMarkDraft")
        Map ag = ec.service.sync().name("ai.AgentServices.store#AiAgent").parameters([
            agentName: "PrevMarkDraft", providerName: "mock", modelName: "mock-1",
            systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_DRAFT"]).call()
        ec.service.sync().name("store#moqui.ai.AiAgentTool").parameters([agentId: ag.agentId, toolId: mut.toolId]).call()
        ai.refreshCatalog()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "w1", name: "set_echo", arguments: [text: "x"]]], tokensIn: 1L, tokensOut: 1L])
        when:
        Map r = new org.moqui.ai.AgentRunner(ec).runPreview(ag.agentId as String, "go")
        then: "the preview run is flagged so the queue/discard logic can recognize it"
        r.statusId == "AI_RUN_SUSPENDED"
        ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", r.agentRunId).one().isPreview == "Y"
        cleanup:
        ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", r.agentRunId).deleteAll()
        ec.entity.find("moqui.ai.AiAgentRunStep").condition("agentRunId", r.agentRunId).deleteAll()
        ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", r.agentRunId).deleteAll()
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", ag.agentId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", ag.agentId).deleteAll()
        ec.entity.find("moqui.ai.AiTool").condition("toolId", mut.toolId).deleteAll()
        ai.refreshCatalog()
        ec.artifactExecution.enableAuthz()
    }

    def "get#PendingToolCallRequest omits preview-run approvals but keeps real pending ones"() {
        given: "a normal suspended run (isPreview null) and a preview suspended run, each with a pending approval"
        ec.artifactExecution.disableAuthz()
        String normRun = "TST_RUN_NORM", prevRun = "TST_RUN_PREV", normAppr = "TST_APPR_NORM", prevAppr = "TST_APPR_PREV"
        for (id in [normRun, prevRun]) ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", id).deleteAll()
        for (id in [normAppr, prevAppr]) ec.entity.find("moqui.ai.AiToolCallRequest").condition("toolCallRequestId", id).deleteAll()
        // normal run: isPreview left null (the realistic case for pre-existing rows) -> must stay in the queue
        ec.entity.makeValue("moqui.ai.AiAgentRun").setAll([agentRunId: normRun, statusId: "AI_RUN_SUSPENDED"]).create()
        ec.entity.makeValue("moqui.ai.AiAgentRun").setAll([agentRunId: prevRun, statusId: "AI_RUN_SUSPENDED", isPreview: "Y"]).create()
        ec.entity.makeValue("moqui.ai.AiToolCallRequest").setAll([toolCallRequestId: normAppr, agentRunId: normRun,
            toolName: "set_echo", statusId: "AI_TCREQ_PENDING", requestedDate: ec.user.nowTimestamp]).create()
        ec.entity.makeValue("moqui.ai.AiToolCallRequest").setAll([toolCallRequestId: prevAppr, agentRunId: prevRun,
            toolName: "set_echo", statusId: "AI_TCREQ_PENDING", requestedDate: ec.user.nowTimestamp]).create()
        when: "the operator queue is read (no run filter)"
        Map out = ec.service.sync().name("ai.ToolCallRequestServices.get#PendingToolCallRequest").parameters([:]).call()
        List ids = (out.approvalList as List).collect { it.toolCallRequestId }
        then: "the real pending approval is present; the preview one is excluded"
        ids.contains(normAppr)
        !ids.contains(prevAppr)
        cleanup:
        for (id in [normAppr, prevAppr]) ec.entity.find("moqui.ai.AiToolCallRequest").condition("toolCallRequestId", id).deleteAll()
        for (id in [normRun, prevRun]) ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", id).deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "discard#Draft deletes the draft's preview runs and their held approvals"() {
        given: "a draft with a mutating tool, previewed once so it suspended with a held approval"
        ec.artifactExecution.disableAuthz(); MockProvider.reset()
        ec.entity.find("moqui.ai.AiTool").condition("toolName", "set_echo").deleteAll()  // isolation
        Map mut = ec.service.sync().name("ai.ToolServices.store#AiTool").parameters([
            serviceName: "moqui.ai.test.TestServices.set#Echo", verb: "set", noun: "echo",
            description: "write echo", exposable: "Y"]).call()
        ec.message.clearErrors()
        dropAgentByName("DiscDraft")
        Map ag = ec.service.sync().name("ai.AgentServices.store#AiAgent").parameters([
            agentName: "DiscDraft", providerName: "mock", modelName: "mock-1",
            systemPrompt: "x", maxIterations: 5, statusId: "AI_AGENT_DRAFT"]).call()
        ec.service.sync().name("store#moqui.ai.AiAgentTool").parameters([agentId: ag.agentId, toolId: mut.toolId]).call()
        ai.refreshCatalog()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
        MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "w1", name: "set_echo", arguments: [text: "x"]]], tokensIn: 1L, tokensOut: 1L])
        Map prev = ec.service.sync().name("ai.ComposerServices.preview#Agent")
            .parameters([agentId: ag.agentId, testMessage: "go"]).call()
        assert prev.statusId == "AI_RUN_SUSPENDED"
        assert ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", prev.agentRunId).list().size() == 1
        when: "the draft is discarded"
        ec.service.sync().name("ai.ComposerServices.discard#Draft").parameters([agentId: ag.agentId]).call()
        then: "the draft, its grants, its preview run, the run's steps, and the held approval are all gone"
        ec.entity.find("moqui.ai.AiAgent").condition("agentId", ag.agentId).one() == null
        ec.entity.find("moqui.ai.AiAgentTool").condition("agentId", ag.agentId).list().isEmpty()
        ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", prev.agentRunId).one() == null
        ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", prev.agentRunId).list().isEmpty()
        ec.entity.find("moqui.ai.AiAgentRunStep").condition("agentRunId", prev.agentRunId).list().isEmpty()
        cleanup:
        ec.entity.find("moqui.ai.AiTool").condition("toolId", mut.toolId).deleteAll()
        ai.refreshCatalog()
        ec.artifactExecution.enableAuthz()
    }
}
