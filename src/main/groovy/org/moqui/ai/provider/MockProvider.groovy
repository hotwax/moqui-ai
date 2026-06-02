package org.moqui.ai.provider

import org.moqui.ai.LlmProvider
import java.util.concurrent.ConcurrentLinkedQueue

/** Deterministic provider for tests. A test enqueues the response Maps the loop should
 *  receive, in order. When the queue is empty, returns a benign "stop" so the loop
 *  always terminates. Registered under name "mock" (always available, no config). */
class MockProvider implements LlmProvider {
    private static final ConcurrentLinkedQueue<Map> SCRIPT = new ConcurrentLinkedQueue<>()

    static void enqueue(Map r) { SCRIPT.add(r) }
    static void reset() { SCRIPT.clear() }

    @Override String getName() { return "mock" }

    @Override Map chat(Map request) {
        Map r = SCRIPT.poll()
        if (r != null) return r
        return [assistantText: "", finishReason: "stop", toolCalls: [], tokensIn: 0L, tokensOut: 0L]
    }
}
