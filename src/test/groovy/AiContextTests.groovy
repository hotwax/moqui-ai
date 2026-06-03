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

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ai = ec.factory.getTool("AI", AiToolFactory.class)
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        ec.entity.makeValue("moqui.security.UserAccount")
            .setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
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

    def "windowed agent sends only the last N replayed messages to the provider"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.LAST_REQUEST = null
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "WinAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "be terse", maxIterations: 3, statusId: "AI_AGENT_ACTIVE",
            contextStrategy: "window", contextWindowMessages: 2, contextWindowChars: 1000000]).createOrUpdate()
        String convId = ec.entity.sequencedIdPrimary("moqui.ai.AiConversation", null, null)
        ec.entity.makeValue("moqui.ai.AiConversation").setAll([conversationId: convId, agentName: "WinAgent",
            userId: "AiTestUser", fromDate: ec.user.nowTimestamp, statusId: "AI_CONV_ACTIVE"]).createOrUpdate()
        (1..5).each { i ->
            EntityValue m = ec.entity.makeValue("moqui.ai.AiConversationMessage")
            m.set("conversationId", convId); m.setSequencedIdSecondary()
            m.setAll([role: "user", content: "old ${i}", fromDate: ec.user.nowTimestamp]); m.create()
        }
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
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        org.moqui.ai.provider.MockProvider.reset()
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "MemAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "x", maxIterations: 4, statusId: "AI_AGENT_ACTIVE",
            contextStrategy: "window", contextWindowMessages: 20, contextWindowChars: 1000000]).createOrUpdate()
        String convId = ec.entity.sequencedIdPrimary("moqui.ai.AiConversation", null, null)
        ec.entity.makeValue("moqui.ai.AiConversation").setAll([conversationId: convId, agentName: "MemAgent",
            userId: "AiTestUser", fromDate: ec.user.nowTimestamp, statusId: "AI_CONV_ACTIVE"]).createOrUpdate()
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

    def "context management OFF replays the full history unchanged (no regression)"() {
        given:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
        org.moqui.ai.provider.MockProvider.reset()
        org.moqui.ai.provider.MockProvider.LAST_REQUEST = null
        ec.entity.makeValue("moqui.ai.AiAgent").setAll([agentName: "NoCtxAgent", providerName: "mock",
            modelName: "mock-1", systemPrompt: "x", maxIterations: 3, statusId: "AI_AGENT_ACTIVE"]).createOrUpdate()
        String convId = ec.entity.sequencedIdPrimary("moqui.ai.AiConversation", null, null)
        ec.entity.makeValue("moqui.ai.AiConversation").setAll([conversationId: convId, agentName: "NoCtxAgent",
            userId: "AiTestUser", fromDate: ec.user.nowTimestamp, statusId: "AI_CONV_ACTIVE"]).createOrUpdate()
        (1..5).each { i ->
            EntityValue m = ec.entity.makeValue("moqui.ai.AiConversationMessage")
            m.set("conversationId", convId); m.setSequencedIdSecondary()
            m.setAll([role: "user", content: "old ${i}", fromDate: ec.user.nowTimestamp]); m.create()
        }
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
}
