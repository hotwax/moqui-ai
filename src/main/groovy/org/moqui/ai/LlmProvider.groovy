package org.moqui.ai

/** The only contract a new provider implements. Map-based, like ElasticFacade.
 *  The agentic loop talks ONLY to this. See the plan's "Canonical Map shapes" for the
 *  request/response Map contract. */
interface LlmProvider {
    /** Registry key: "mock" | "anthropic" | "openai" | "google". Matches AiAgent.providerName. */
    String getName()
    /** request Map in (model, systemContext, messages, tools), response Map out
     *  (assistantText, toolCalls, tokensIn, tokensOut, finishReason). Impl makes the HTTP call. */
    Map chat(Map request)
}
