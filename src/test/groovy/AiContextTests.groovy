import spock.lang.*
import org.moqui.ai.ContextAssembler

class AiContextTests extends Specification {

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
}
