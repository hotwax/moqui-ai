package org.moqui.ai

/** Builds the per-call context view (ADR 0001 Phase 1). Pure: operates on Lists/Strings, no ec.
 *  - withFacts: inject pinned facts as a "## Known facts" block appended to the system prompt.
 *  - windowHistory: bound the REPLAYED prior-turn history (message-count + char-estimate guard),
 *    tool-pair-safe; the current turn is passed through whole (never trimmed). */
class ContextAssembler {

    /** Append pinned facts to the system prompt. No-op when there are no facts. */
    static String withFacts(String systemPrompt, List<Map> facts) {
        if (!facts) return systemPrompt
        StringBuilder sb = new StringBuilder(systemPrompt ?: "")
        sb.append("\n\n## Known facts (always honor these confirmed values)\n")
        for (Map f in facts) sb.append("- ").append(f.factKey).append(": ").append(f.factValue).append("\n")
        return sb.toString()
    }

    /** Append a rolling-summary block to the system prompt. No-op when there is no summary. */
    static String withSummary(String systemPrompt, String summaryText) {
        if (!summaryText) return systemPrompt
        StringBuilder sb = new StringBuilder(systemPrompt ?: "")
        sb.append("\n\n## Conversation summary (earlier turns)\n").append(summaryText).append("\n")
        return sb.toString()
    }

    /** Keep the last `maxMessages` of `replayed` (then char-guard trims more from the front),
     *  tool-pair-safe, and append the whole `current` turn. Returns [messages, dropped]. */
    static Map windowHistory(List<Map> replayed, List<Map> current, int maxMessages, int maxChars) {
        List<Map> src = replayed ?: []
        List<Map> cur = current ?: []
        int total = src.size()
        // 1) message-count window: keep the last maxMessages of the replayed prefix
        int start = Math.max(0, total - Math.max(0, maxMessages))
        List<Map> kept = new ArrayList<>(src.subList(start, total))
        // 2) tool-pair safety: never start the kept window on an orphaned tool result
        while (!kept.isEmpty() && kept[0].role == "tool") kept.remove(0)
        // 3) char-estimate guard: drop more from the front (tool-pair-safe) until under the cap.
        //    The current turn is always counted but never dropped (Phase 1 does not compact it).
        int curChars = charLen(cur)
        while (!kept.isEmpty() && (charLen(kept) + curChars) > maxChars) {
            kept.remove(0)
            while (!kept.isEmpty() && kept[0].role == "tool") kept.remove(0)
        }
        List<Map> out = new ArrayList<>(kept); out.addAll(cur)
        List<Map> droppedMessages = new ArrayList<>(src.subList(0, total - kept.size()))
        return [messages: out, dropped: total - kept.size(), droppedMessages: droppedMessages]
    }

    private static int charLen(List<Map> msgs) {
        int n = 0
        for (Map m in msgs) {
            n += ((m.content ?: "") as String).length()
            if (m.toolCalls) n += groovy.json.JsonOutput.toJson(m.toolCalls).length()
        }
        return n
    }
}
