import spock.lang.*
import org.moqui.ai.provider.MockProvider

class MockProviderTests extends Specification {
    def cleanup() { MockProvider.reset() }

    def "returns scripted response Maps in order then defaults to a stop"() {
        given:
        def r1 = [toolCalls: [[id: "c1", name: "get#Echo", arguments: [text: "hi"]]],
                  finishReason: "tool_use", tokensIn: 10L, tokensOut: 5L]
        def r2 = [assistantText: "done", finishReason: "stop", tokensIn: 8L, tokensOut: 3L]
        MockProvider.enqueue(r1); MockProvider.enqueue(r2)
        def provider = new MockProvider()

        expect:
        provider.name == "mock"
        provider.chat([model: "mock-1"]).is(r1)
        provider.chat([model: "mock-1"]).is(r2)
        provider.chat([model: "mock-1"]).finishReason == "stop"
    }
}
