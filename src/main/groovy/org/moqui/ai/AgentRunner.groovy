package org.moqui.ai

import groovy.json.JsonOutput
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** The provider-agnostic agentic loop. All data is Map-based (Moqui idiom, like ElasticFacade).
 *  Holds NO enclosing transaction: LLM calls happen outside any tx; each tool call runs in its
 *  own tx (ec.service.sync default); each observability write runs in its own tx via persist(),
 *  guarded so failures only warn. run() returns a runResult Map (see "Canonical Map shapes"). */
class AgentRunner {
    protected final static Logger logger = LoggerFactory.getLogger(AgentRunner.class)

    private final ExecutionContext ec
    private final AiToolFactory ai

    AgentRunner(ExecutionContext ec, AiToolFactory ai) { this.ec = ec; this.ai = ai }

    /** Phase 1 entry — stateless single turn. */
    Map run(String agentName, String userMessage) { return run(agentName, userMessage, null) }

    /** @param conversationId optional; when set, prior conversation messages are replayed and
     *  this turn's messages are persisted back.
     *  @return runResult Map: [assistantMessage, agentRunId, conversationId, tokensIn, tokensOut,
     *  iterations, truncated, statusId, servedByModelId, providerRunId, structuredResult] */
    Map run(String agentName, String userMessage, String conversationId) {
        EntityValue agent = ec.entity.find("moqui.ai.AiAgent")
            .condition("agentName", agentName).useCache(true).one()
        if (agent == null) throw new IllegalArgumentException("Unknown agent: ${agentName}")

        int maxIter = (agent.maxIterations ?: 8) as int
        long maxTokens = (agent.maxTokens ?: 0L) as long          // 0 = no limit
        int maxToolCalls = (agent.maxToolCallsPerTurn ?: 20) as int

        Map responseSchema = agent.responseSchema ?
            new groovy.json.JsonSlurper().parseText(agent.responseSchema as String) as Map : null

        LlmProvider provider = ai.getProvider(agent.providerName as String)
        List<Map> toolSchemas = loadToolSchemas(agentName)

        String runId = ec.entity.sequencedIdPrimary("moqui.ai.AiAgentRun", null, null)
        Map result = [agentRunId: runId, conversationId: conversationId, assistantMessage: null,
                      tokensIn: 0L, tokensOut: 0L, iterations: 0, truncated: false, statusId: "AI_RUN_RUNNING",
                      structuredResult: null, servedByModelId: agent.modelName as String, providerRunId: null]
        persist("create#moqui.ai.AiAgentRun", [agentRunId: runId, agentName: agentName, conversationId: conversationId,
            userId: ec.user.userId, fromDate: ec.user.nowTimestamp, statusId: "AI_RUN_RUNNING",
            providerName: agent.providerName, modelName: agent.modelName, userMessage: userMessage])

        // history replay: prior conversation messages, then this turn's user message
        List<Map> messages = conversationId ? loadConversationMessages(conversationId) : []
        messages.add([role: "user", content: userMessage])
        if (conversationId) persistConversationMessage(conversationId, runId, [role: "user", content: userMessage])

        int stepSeq = 0
        try {
            for (int i = 0; i < maxIter; i++) {
                result.iterations = i + 1
                // request Map in, response Map out -- external HTTP, no tx held
                Map resp = provider.chat([model: agent.modelName, systemContext: agent.systemPrompt,
                        messages: messages, tools: toolSchemas, responseSchema: responseSchema])
                long inTok = (resp.tokensIn ?: 0L) as long
                long outTok = (resp.tokensOut ?: 0L) as long
                result.tokensIn += inTok; result.tokensOut += outTok
                if (resp.providerRunId) result.providerRunId = resp.providerRunId
                if (resp.structuredResult != null) result.structuredResult = resp.structuredResult
                stepSeq++
                persist("create#moqui.ai.AiAgentRunStep", [agentRunId: runId, stepSeqId: stepSeq as String,
                    stepType: "llm_call", tokensIn: inTok, tokensOut: outTok, finishReason: resp.finishReason])

                if (maxTokens > 0 && ((result.tokensIn as long) + (result.tokensOut as long)) > maxTokens)
                    return finish(result, runId, conversationId, "AI_RUN_ABORTED", "Per-run token ceiling exceeded")

                List toolCalls = (resp.toolCalls ?: []) as List
                if (!toolCalls) {
                    result.assistantMessage = resp.assistantText ?:
                        (result.structuredResult != null ? groovy.json.JsonOutput.toJson(result.structuredResult) : "")
                    if (conversationId) persistConversationMessage(conversationId, runId,
                        [role: "assistant", content: result.assistantMessage])
                    return finish(result, runId, conversationId, "AI_RUN_COMPLETED", null)
                }
                if (toolCalls.size() > maxToolCalls)
                    return finish(result, runId, conversationId, "AI_RUN_ABORTED", "Tool-calls-per-turn ceiling exceeded")

                // record the assistant turn that requested tools, then dispatch each
                Map assistantTurn = [role: "assistant", toolCalls: toolCalls]
                messages.add(assistantTurn)
                if (conversationId) persistConversationMessage(conversationId, runId, assistantTurn)
                for (Map tc in toolCalls) {
                    String resultJson = dispatchTool(runId, stepSeq, tc)
                    Map toolMsg = [role: "tool", toolCallId: tc.id, content: resultJson]
                    messages.add(toolMsg)
                    if (conversationId) persistConversationMessage(conversationId, runId, toolMsg)
                }
            }
            return finish(result, runId, conversationId, "AI_RUN_TRUNCATED", null)   // ran out of iterations
        } catch (Throwable t) {
            logger.error("Agent run ${runId} failed", t)
            return finish(result, runId, conversationId, "AI_RUN_FAILED", t.message)
        }
    }

    /** Dispatch one tool-call Map via ec.service.sync (its own tx; Moqui authz applies). Tool
     *  errors are caught and returned as a JSON error so the loop can recover. */
    private String dispatchTool(String runId, int stepSeq, Map tc) {
        Map td = ai.getTool(tc.name as String)
        long start = System.currentTimeMillis()
        String resultJson; boolean success; String errorText = null
        if (td == null) {
            success = false; errorText = "Tool not in catalog: ${tc.name}"
            resultJson = JsonOutput.toJson([error: errorText])
        } else {
            try {
                ec.message.clearErrors()   // evaluate this tool call on its own error state
                Map out = ec.service.sync().name(td.serviceName as String)
                        .parameters((tc.arguments ?: [:]) as Map).call()
                if (ec.message.hasError()) {
                    success = false; errorText = ec.message.errorsString; ec.message.clearErrors()
                    resultJson = JsonOutput.toJson([error: errorText])
                } else {
                    success = true; resultJson = JsonOutput.toJson(out ?: [:])
                }
            } catch (Throwable t) {
                success = false; errorText = t.message; resultJson = JsonOutput.toJson([error: t.message])
            }
        }
        persist("create#moqui.ai.AiToolCall", [agentRunId: runId, stepSeqId: stepSeq as String,
            toolCallId: tc.id, toolName: tc.name, serviceName: td?.serviceName,
            arguments: JsonOutput.toJson(tc.arguments ?: [:]), result: resultJson,
            success: success ? "Y" : "N", errorText: errorText,
            durationMs: (System.currentTimeMillis() - start) as int])
        return resultJson
    }

    /** Build the agent's granted tools as a List of toolSchema Maps. */
    private List<Map> loadToolSchemas(String agentName) {
        List<Map> schemas = []
        for (EntityValue grant in ec.entity.find("moqui.ai.AiAgentTool")
                .condition("agentName", agentName).useCache(true).list()) {
            Map td = ai.getTool(grant.toolName as String)
            if (td == null) {
                logger.warn("Agent ${agentName} grants unknown tool ${grant.toolName}; skipping")
                continue
            }
            schemas.add([name: td.toolName, description: td.description, parameters: td.schema])
        }
        return schemas
    }

    /** Finalize: set status + truncated on the result Map, persist the run update, bump the
     *  conversation's lastActivityDate when present, return it. */
    private Map finish(Map result, String runId, String conversationId, String statusId, String errorText) {
        result.statusId = statusId
        result.truncated = (statusId == "AI_RUN_TRUNCATED")
        persist("update#moqui.ai.AiAgentRun", [agentRunId: runId, thruDate: ec.user.nowTimestamp,
            statusId: statusId, assistantMessage: result.assistantMessage, iterations: result.iterations,
            tokensIn: result.tokensIn, tokensOut: result.tokensOut, errorText: errorText,
            servedByModelId: result.servedByModelId, providerRunId: result.providerRunId])
        if (conversationId) persist("update#moqui.ai.AiConversation",
            [conversationId: conversationId, lastActivityDate: ec.user.nowTimestamp])
        return result
    }

    /** Load persisted conversation messages, in order, as a List of message Maps. */
    private List<Map> loadConversationMessages(String conversationId) {
        List<Map> out = []
        for (EntityValue m in ec.entity.find("moqui.ai.AiConversationMessage")
                .condition("conversationId", conversationId).orderBy("messageSeqId").list()) {
            Map msg = [role: m.role, content: m.content]
            if (m.toolCallId) msg.toolCallId = m.toolCallId
            if (m.toolCalls) msg.toolCalls = new groovy.json.JsonSlurper().parseText(m.toolCalls as String)
            out.add(msg)
        }
        return out
    }

    /** Persist one message to the conversation (own short tx; guarded — never aborts the run). */
    private void persistConversationMessage(String conversationId, String runId, Map msg) {
        try {
            EntityValue v = ec.entity.makeValue("moqui.ai.AiConversationMessage")
            v.set("conversationId", conversationId)
            v.setSequencedIdSecondary()
            v.setAll([role: msg.role, content: msg.content, toolCallId: msg.toolCallId,
                toolCalls: msg.toolCalls != null ? JsonOutput.toJson(msg.toolCalls) : null,
                agentRunId: runId, fromDate: ec.user.nowTimestamp])
            v.create()
        } catch (Throwable t) { logger.warn("Conversation message persist failed (continuing): ${t.message}") }
    }

    /** Persistence never aborts the run: each write is its own service call (own tx); on
     *  failure we log a warning and continue. */
    private void persist(String serviceName, Map params) {
        try { ec.service.sync().name(serviceName).parameters(params).call() }
        catch (Throwable t) { logger.warn("Observability write ${serviceName} failed (continuing): ${t.message}") }
    }
}
