import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.AiToolFactory
import org.moqui.ai.ContextAssembler
import org.moqui.ai.provider.MockProvider
import org.moqui.entity.EntityValue

class AiContextTests extends Specification {
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

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ai = ec.factory.getTool("AI", AiToolFactory.class)
        ec.artifactExecution.disableAuthz()
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ensureTestUser()
        })
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.artifactExecution.enableAuthz()
    }
    def cleanupSpec() {
        if (ec == null) return
        ec.destroy()
    }
    def setup() {
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.message.clearErrors()
    }
    def cleanup() { MockProvider.reset() }

    def "withFacts appends a Known facts block to the system prompt"() {
        when:
        String s = ContextAssembler.withFacts("Be helpful.",
            [[factKey: "order_total", factValue: "\$4,812.50"], [factKey: "ship_to", factValue: "123 Main St"]])
        then:
        s.contains("Be helpful.")
        s.contains("## Known facts")
        s.contains("order_total: \$4,812.50")
        s.contains("ship_to: 123 Main St")
    }

    def "withFacts is a no-op when there are no facts"() {
        expect:
        ContextAssembler.withFacts("Be helpful.", []) == "Be helpful."
        ContextAssembler.withFacts("Be helpful.", null) == "Be helpful."
    }

    def "windowHistory keeps the last N replayed messages plus the whole current turn"() {
        given:
        List replayed = (1..6).collect { [role: "user", content: "old ${it}"] }
        List current = [[role: "user", content: "now"]]
        when:
        Map r = ContextAssembler.windowHistory(replayed, current, 2, 1000000)
        then:
        r.dropped == 4
        r.messages.size() == 3                       // 2 kept replayed + 1 current
        r.messages[0].content == "old 5"
        r.messages[1].content == "old 6"
        r.messages[2].content == "now"               // current turn always last, never dropped
    }

    def "windowHistory never orphans a tool result at the kept-window boundary"() {
        given:
        // replayed ends with an assistant tool-call + its tool result; keeping "last 1" would
        // start the window on the orphan tool result -- it must be dropped instead.
        List replayed = [
            [role: "user", content: "q"],
            [role: "assistant", toolCalls: [[id: "c1", name: "x", arguments: [:]]]],
            [role: "tool", toolCallId: "c1", content: "{}"]]
        List current = [[role: "user", content: "now"]]
        when:
        Map r = ContextAssembler.windowHistory(replayed, current, 1, 1000000)
        then:
        !r.messages.any { it.role == "tool" }        // the orphan tool result was dropped
        r.messages.last().content == "now"
    }

    def "windowHistory char guard drops more from the front when over the cap"() {
        given:
        List replayed = (1..5).collect { [role: "user", content: ("x" * 100)] }   // ~100 chars each
        List current = [[role: "user", content: "now"]]
        when:
        Map r = ContextAssembler.windowHistory(replayed, current, 5, 120)          // cap ~120 chars
        then:
        (r.messages.sum { (it.content ?: "").length() } as int) <= 120 + 3
        r.messages.last().content == "now"
        r.dropped >= 4
    }

    def "withSummary prepends a Conversation summary block when summary is present"() {
        when:
        String s = ContextAssembler.withSummary("Be helpful.", "Customer confirmed 3 units, net-30 terms.")
        then:
        s.contains("Be helpful.")
        s.contains("## Conversation summary (earlier turns)")
        s.contains("Customer confirmed 3 units, net-30 terms.")
    }

    def "withSummary is a no-op when there is no summary"() {
        expect:
        ContextAssembler.withSummary("Be helpful.", null) == "Be helpful."
        ContextAssembler.withSummary("Be helpful.", "") == "Be helpful."
    }

    def "windowHistory returns the dropped messages (oldest replayed not kept)"() {
        given:
        List replayed = (1..6).collect { [role: "user", content: "old ${it}"] }
        List current = [[role: "user", content: "now"]]
        when:
        Map r = ContextAssembler.windowHistory(replayed, current, 2, 1000000)
        then:
        r.dropped == 4
        (r.droppedMessages as List).collect { it.content } == ["old 1", "old 2", "old 3", "old 4"]
        (r.messages as List).collect { it.content } == ["old 5", "old 6", "now"]
    }

    def "windowed agent sends only the last N replayed messages to the provider"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.LAST_REQUEST = null
        String convId = ec.entity.sequencedIdPrimary("moqui.ai.AiConversation", null, null)
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "WinAgent", agentName: "WinAgent", providerName: "mock",
                modelName: "mock-1", systemPrompt: "be terse", maxIterations: 3, statusId: "AI_AGENT_ACTIVE",
                contextStrategy: "window", contextWindowMessages: 2, contextWindowChars: 1000000]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiConversation").setAll([conversationId: convId, agentId:"WinAgent",
                userId: "AiTestUser", fromDate: ec.user.nowTimestamp, statusId: "AI_CONV_ACTIVE", title: "ctx test"]).createOrUpdate()
            (1..5).each { i ->
                EntityValue m = ec.entity.makeValue("moqui.ai.AiConversationMessage")
                m.set("conversationId", convId); m.setSequencedIdSecondary()
                m.setAll([role: "user", content: "old ${i}", fromDate: ec.user.nowTimestamp]); m.create()
            }
        })
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "ok", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        when:
        new org.moqui.ai.AgentRunner(ec, ai).run("WinAgent", "newest", convId)
        List sent = org.moqui.ai.provider.MockProvider.LAST_REQUEST.messages as List
        then:
        sent.size() == 3                             // 2 windowed replayed + this turn's user message
        sent.last().content == "newest"
        cleanup:
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "WinAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "agent records a fact via the remember tool"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        String convId = ec.entity.sequencedIdPrimary("moqui.ai.AiConversation", null, null)
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "MemAgent", agentName: "MemAgent", providerName: "mock",
                modelName: "mock-1", systemPrompt: "x", maxIterations: 4, statusId: "AI_AGENT_ACTIVE",
                contextStrategy: "window", contextWindowMessages: 20, contextWindowChars: 1000000]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiConversation").setAll([conversationId: convId, agentId:"MemAgent",
                userId: "AiTestUser", fromDate: ec.user.nowTimestamp, statusId: "AI_CONV_ACTIVE", title: "ctx test"]).createOrUpdate()
        })
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "r1", name: "remember", arguments: [factKey: "order_total", factValue: "\$4,812.50"]]],
            tokensIn: 1L, tokensOut: 1L])
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "noted", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        when:
        Map out = new org.moqui.ai.AgentRunner(ec, ai).run("MemAgent", "the total is \$4,812.50", convId)
        EntityValue fact = ec.entity.find("moqui.ai.AiConversationFact")
            .condition("conversationId", convId).condition("factKey", "order_total").one()
        then:
        out.statusId == "AI_RUN_COMPLETED"
        fact != null
        fact.factValue == "\$4,812.50"
        fact.agentRunId == out.agentRunId
        cleanup:
        ec.entity.find("moqui.ai.AiConversationFact").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "MemAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "remembering the same factKey supersedes the value and preserves createdDate"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        String convId = ec.entity.sequencedIdPrimary("moqui.ai.AiConversation", null, null)
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "SupAgent", agentName: "SupAgent", providerName: "mock",
                modelName: "mock-1", systemPrompt: "x", maxIterations: 4, statusId: "AI_AGENT_ACTIVE",
                contextStrategy: "window", contextWindowMessages: 20, contextWindowChars: 1000000]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiConversation").setAll([conversationId: convId, agentId:"SupAgent",
                userId: "AiTestUser", fromDate: ec.user.nowTimestamp, statusId: "AI_CONV_ACTIVE", title: "ctx test"]).createOrUpdate()
        })
        // run 1: remember order_total = first value
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "r1", name: "remember", arguments: [factKey: "order_total", factValue: "\$100.00"]]], tokensIn: 1L, tokensOut: 1L])
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "ok", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        Map run1 = new org.moqui.ai.AgentRunner(ec, ai).run("SupAgent", "total is \$100", convId)
        def created = ec.entity.find("moqui.ai.AiConversationFact").condition("conversationId", convId).condition("factKey", "order_total").one().createdDate
        when:
        // run 2: remember the SAME key with a corrected value
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "r2", name: "remember", arguments: [factKey: "order_total", factValue: "\$250.00"]]], tokensIn: 1L, tokensOut: 1L])
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "updated", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        Map run2 = new org.moqui.ai.AgentRunner(ec, ai).run("SupAgent", "correction: total is \$250", convId)
        EntityValue fact = ec.entity.find("moqui.ai.AiConversationFact").condition("conversationId", convId).condition("factKey", "order_total").one()
        then:
        ec.entity.find("moqui.ai.AiConversationFact").condition("conversationId", convId).list().size() == 1   // superseded, not duplicated
        fact.factValue == "\$250.00"                       // value updated
        fact.agentRunId == run2.agentRunId                 // agentRunId advanced to the newer run
        fact.createdDate == created                         // createdDate preserved across the update
        cleanup:
        ec.entity.find("moqui.ai.AiConversationFact").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "SupAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "context management OFF replays the full history unchanged (no regression)"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.LAST_REQUEST = null
        String convId = ec.entity.sequencedIdPrimary("moqui.ai.AiConversation", null, null)
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "NoCtxAgent", agentName: "NoCtxAgent", providerName: "mock",
                modelName: "mock-1", systemPrompt: "x", maxIterations: 3, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiConversation").setAll([conversationId: convId, agentId:"NoCtxAgent",
                userId: "AiTestUser", fromDate: ec.user.nowTimestamp, statusId: "AI_CONV_ACTIVE", title: "ctx test"]).createOrUpdate()
            (1..5).each { i ->
                EntityValue m = ec.entity.makeValue("moqui.ai.AiConversationMessage")
                m.set("conversationId", convId); m.setSequencedIdSecondary()
                m.setAll([role: "user", content: "old ${i}", fromDate: ec.user.nowTimestamp]); m.create()
            }
        })
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "ok", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        when:
        new org.moqui.ai.AgentRunner(ec, ai).run("NoCtxAgent", "newest", convId)
        List sent = org.moqui.ai.provider.MockProvider.LAST_REQUEST.messages as List
        then:
        sent.size() == 6                             // 5 replayed + this turn, unchanged
        org.moqui.ai.provider.MockProvider.LAST_REQUEST.systemContext == "x"   // no facts block
        cleanup:
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "NoCtxAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "a remembered fact survives window eviction and reaches a later call"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        String convId = ec.entity.sequencedIdPrimary("moqui.ai.AiConversation", null, null)
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "FidAgent", agentName: "FidAgent", providerName: "mock",
                modelName: "mock-1", systemPrompt: "base", maxIterations: 4, statusId: "AI_AGENT_ACTIVE",
                contextStrategy: "window", contextWindowMessages: 1, contextWindowChars: 1000000]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiConversation").setAll([conversationId: convId, agentId:"FidAgent",
                userId: "AiTestUser", fromDate: ec.user.nowTimestamp, statusId: "AI_CONV_ACTIVE", title: "ctx test"]).createOrUpdate()
        })
        // Turn 1: agent remembers the total, then stops.
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "r1", name: "remember", arguments: [factKey: "order_total", factValue: "\$4,812.50"]]],
            tokensIn: 1L, tokensOut: 1L])
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "noted", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        new org.moqui.ai.AgentRunner(ec, ai).run("FidAgent", "the order total is \$4,812.50", convId)
        when:
        // Turn 2: many turns later the early message is windowed out (window=1), but the FACT must persist.
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.LAST_REQUEST = null
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "the total was \$4,812.50", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        new org.moqui.ai.AgentRunner(ec, ai).run("FidAgent", "what was the order total?", convId)
        String sysSent = org.moqui.ai.provider.MockProvider.LAST_REQUEST.systemContext as String
        then:
        sysSent.contains("## Known facts")
        sysSent.contains("order_total: \$4,812.50")     // survived eviction, injected every call
        cleanup:
        ec.entity.find("moqui.ai.AiConversationFact").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "FidAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "summarize strategy compacts overflow into a persisted rolling summary"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.LAST_REQUEST = null
        String convId = ec.entity.sequencedIdPrimary("moqui.ai.AiConversation", null, null)
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "SumAgent", agentName: "SumAgent", providerName: "mock",
                modelName: "mock-1", systemPrompt: "base", maxIterations: 3, statusId: "AI_AGENT_ACTIVE",
                contextStrategy: "summarize", contextWindowMessages: 2, contextWindowChars: 1000000]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiConversation").setAll([conversationId: convId, agentId:"SumAgent",
                userId: "AiTestUser", fromDate: ec.user.nowTimestamp, statusId: "AI_CONV_ACTIVE", title: "ctx test"]).createOrUpdate()
            (1..5).each { i ->
                EntityValue m = ec.entity.makeValue("moqui.ai.AiConversationMessage")
                m.set("conversationId", convId); m.setSequencedIdSecondary()
                m.setAll([role: "user", content: "old ${i}", fromDate: ec.user.nowTimestamp]); m.create()
            }
        })
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "SUMMARY: discussed old 1-3", finishReason: "stop", toolCalls: [], tokensIn: 5L, tokensOut: 3L])
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "ok", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        when:
        new org.moqui.ai.AgentRunner(ec, ai).run("SumAgent", "newest", convId)
        EntityValue conv = ec.entity.find("moqui.ai.AiConversation").condition("conversationId", convId).one()
        String sysSent = org.moqui.ai.provider.MockProvider.LAST_REQUEST.systemContext as String
        List sent = org.moqui.ai.provider.MockProvider.LAST_REQUEST.messages as List
        then:
        conv.summaryText == "SUMMARY: discussed old 1-3"
        conv.summaryThruMessageSeqId != null
        sysSent.contains("## Conversation summary (earlier turns)")
        sysSent.contains("SUMMARY: discussed old 1-3")
        sent.collect { it.content } == ["old 4", "old 5", "newest"]
        cleanup:
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "SumAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "summarization failure falls back to windowing and the run still completes"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.LAST_REQUEST = null
        String convId = ec.entity.sequencedIdPrimary("moqui.ai.AiConversation", null, null)
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "SumFailAgent", agentName: "SumFailAgent", providerName: "mock",
                modelName: "mock-1", systemPrompt: "base", maxIterations: 3, statusId: "AI_AGENT_ACTIVE",
                contextStrategy: "summarize", contextWindowMessages: 2, contextWindowChars: 1000000]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiConversation").setAll([conversationId: convId, agentId:"SumFailAgent",
                userId: "AiTestUser", fromDate: ec.user.nowTimestamp, statusId: "AI_CONV_ACTIVE", title: "ctx test"]).createOrUpdate()
            (1..5).each { i ->
                EntityValue m = ec.entity.makeValue("moqui.ai.AiConversationMessage")
                m.set("conversationId", convId); m.setSequencedIdSecondary()
                m.setAll([role: "user", content: "old ${i}", fromDate: ec.user.nowTimestamp]); m.create()
            }
        })
        org.moqui.ai.provider.MockProvider.enqueue([__error: "summary provider down"])
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "ok", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        when:
        Map out = new org.moqui.ai.AgentRunner(ec, ai).run("SumFailAgent", "newest", convId)
        EntityValue conv = ec.entity.find("moqui.ai.AiConversation").condition("conversationId", convId).one()
        List sent = org.moqui.ai.provider.MockProvider.LAST_REQUEST.messages as List
        then:
        out.statusId == "AI_RUN_COMPLETED"
        conv.summaryText == null
        sent.collect { it.content } == ["old 4", "old 5", "newest"]
        cleanup:
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "SumFailAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "summarize does not re-summarize the same overflow across a multi-iteration run"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        String convId = ec.entity.sequencedIdPrimary("moqui.ai.AiConversation", null, null)
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "SumMultiAgent", agentName: "SumMultiAgent", providerName: "mock",
                modelName: "mock-1", systemPrompt: "base", maxIterations: 5, statusId: "AI_AGENT_ACTIVE",
                contextStrategy: "summarize", contextWindowMessages: 2, contextWindowChars: 1000000]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiConversation").setAll([conversationId: convId, agentId:"SumMultiAgent",
                userId: "AiTestUser", fromDate: ec.user.nowTimestamp, statusId: "AI_CONV_ACTIVE", title: "ctx test"]).createOrUpdate()
            (1..5).each { i ->
                EntityValue m = ec.entity.makeValue("moqui.ai.AiConversationMessage")
                m.set("conversationId", convId); m.setSequencedIdSecondary()
                m.setAll([role: "user", content: "old ${i}", fromDate: ec.user.nowTimestamp]); m.create()
            }
        })
        // [0] summarization call (iter 1); [1] iter-1 answer = call remember (forces iter 2);
        // [2] iter-2 answer = "done". If the loop double-summarized on iter 2, it would consume [2]
        // for the summary and the iter-2 main chat would fall through to the empty default.
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "S1", finishReason: "stop", toolCalls: [], tokensIn: 2L, tokensOut: 1L])
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: null, finishReason: "tool_use",
            toolCalls: [[id: "r1", name: "remember", arguments: [factKey: "k", factValue: "v"]]], tokensIn: 1L, tokensOut: 1L])
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "done", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        when:
        Map out = new org.moqui.ai.AgentRunner(ec, ai).run("SumMultiAgent", "newest", convId)
        EntityValue conv = ec.entity.find("moqui.ai.AiConversation").condition("conversationId", convId).one()
        then:
        out.statusId == "AI_RUN_COMPLETED"
        out.assistantMessage == "done"            // iter-2 answer reached the main chat (no spurious 2nd summarize)
        conv.summaryText == "S1"                   // summary rolled exactly once, not re-folded
        cleanup:
        ec.entity.find("moqui.ai.AiConversationFact").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "SumMultiAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "a persisted summary carries forward and is reused without re-summarizing"() {
        given:
        ec.artifactExecution.disableAuthz()
        org.moqui.ai.provider.MockProvider.reset()
        String convId = ec.entity.sequencedIdPrimary("moqui.ai.AiConversation", null, null)
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentId: "CarryAgent", agentName: "CarryAgent", providerName: "mock",
                modelName: "mock-1", systemPrompt: "base", maxIterations: 3, statusId: "AI_AGENT_ACTIVE",
                contextStrategy: "summarize", contextWindowMessages: 2, contextWindowChars: 1000000]).createOrUpdate()
            ec.entity.makeValue("moqui.ai.AiConversation").setAll([conversationId: convId, agentId:"CarryAgent",
                userId: "AiTestUser", fromDate: ec.user.nowTimestamp, statusId: "AI_CONV_ACTIVE", title: "ctx test",
                summaryText: "earlier: customer confirmed 3 units", summaryThruMessageSeqId: "00003"]).createOrUpdate()
            // one live message past the watermark -> below window(2) -> NO overflow -> NO summarization call
            EntityValue m = ec.entity.makeValue("moqui.ai.AiConversationMessage")
            m.set("conversationId", convId); m.setSequencedIdSecondary()
            m.setAll([role: "user", content: "live one", fromDate: ec.user.nowTimestamp]); m.create()
        })
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.LAST_REQUEST = null
        // only the main answer is enqueued; if the code wrongly re-summarized, it would consume this
        org.moqui.ai.provider.MockProvider.enqueue([assistantText: "answer", finishReason: "stop", toolCalls: [], tokensIn: 1L, tokensOut: 1L])
        when:
        Map out = new org.moqui.ai.AgentRunner(ec, ai).run("CarryAgent", "and now?", convId)
        EntityValue conv = ec.entity.find("moqui.ai.AiConversation").condition("conversationId", convId).one()
        String sysSent = org.moqui.ai.provider.MockProvider.LAST_REQUEST.systemContext as String
        then:
        out.statusId == "AI_RUN_COMPLETED"
        out.assistantMessage == "answer"                           // main answer not consumed by a stray summarize
        sysSent.contains("earlier: customer confirmed 3 units")    // existing summary injected (carried forward)
        conv.summaryText == "earlier: customer confirmed 3 units"  // unchanged — not regenerated (no overflow)
        conv.summaryThruMessageSeqId == "00003"                    // watermark unchanged
        cleanup:
        ec.entity.find("moqui.ai.AiConversationMessage").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiConversation").condition("conversationId", convId).deleteAll()
        ec.entity.find("moqui.ai.AiAgent").condition("agentName", "CarryAgent").deleteAll()
        ec.artifactExecution.enableAuthz()
    }
}
