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
    private static final String REMEMBER_TOOL = "remember"
    private static final String SUMMARY_INSTRUCTION =
        "You are compacting a conversation to save context. Update the running summary to incorporate " +
        "the new messages below. Be concise but PRESERVE decisions, commitments, identifiers, and any " +
        "values that may matter later. Output only the updated summary text."

    private final ExecutionContext ec
    private final AiToolFactory ai

    /** Preview mode: treat EVERY mutating tool as requiresApproval, so a draft can be sandbox-run on
     *  real data with nothing irreversible executed (mutating calls suspend via the normal gate). */
    private boolean forceApprovalOnMutating = false

    AgentRunner(ExecutionContext ec, AiToolFactory ai) { this.ec = ec; this.ai = ai }

    /** Phase 1 entry — stateless single turn. */
    Map run(String agentId, String userMessage) { return run(agentId, userMessage, null) }

    /** Phase-Composer preview: run an agent (typically a draft) once, with mutating tools forced to
     *  suspend for approval. Always stateless (no conversation): read-only tools run on real data,
     *  mutating ones are held. Returns the run result (statusId AI_RUN_SUSPENDED if it proposed a write). */
    Map runPreview(String agentNameOrId, String userMessage) {
        this.forceApprovalOnMutating = true
        try { return run(agentNameOrId, userMessage, null) }
        finally { this.forceApprovalOnMutating = false }
    }

    /** @param agentId the agent's stable opaque id. run#Agent takes the id only; a human agentName is
     *  resolved to an id at the conversation-entry layer (create#Conversation / run#Conversation), not here.
     *  @param conversationId optional; when set, prior conversation messages are replayed and
     *  this turn's messages are persisted back.
     *  @return runResult Map: [assistantMessage, agentRunId, conversationId, tokensIn, tokensOut,
     *  iterations, truncated, statusId, servedByModelId, servedProviderName, providerRunId,
     *  structuredResult, estimatedCost] */
    Map run(String agentId, String userMessage, String conversationId) {
        // useCache(false): the agent registry mutates at runtime (the Composer drafts/activates
        // agents) and seed data may be loaded out-of-band by a separate `gradlew load` process,
        // which cannot invalidate this JVM's cache. A cached by-PK miss would otherwise survive as
        // a stale "Unknown agent" even after the row exists. This lookup runs once per agent run,
        // immediately before multi-second provider calls, so a fresh read costs nothing meaningful.
        EntityValue agent = ec.entity.find("moqui.ai.AiAgent")
            .condition("agentId", agentId).useCache(false).one()
        if (agent == null) throw new IllegalArgumentException("Unknown agent: ${agentId}")

        boolean ctxSummarize = (agent.contextStrategy == "summarize")
        boolean ctxOn = (agent.contextStrategy == "window") || ctxSummarize

        Map responseSchema = agent.responseSchema ?
            new groovy.json.JsonSlurper().parseText(agent.responseSchema as String) as Map : null

        List<Map> candidates = loadModelCandidates(agentId, agent)
        Map primary = candidates[0]
        List<Map> toolSchemas = withRememberTool(loadToolSchemas(agentId), ctxOn, conversationId)

        String runId = ec.entity.sequencedIdPrimary("moqui.ai.AiAgentRun", null, null)
        Map result = [agentRunId: runId, conversationId: conversationId, assistantMessage: null,
                      tokensIn: 0L, tokensOut: 0L, iterations: 0, truncated: false, statusId: "AI_RUN_RUNNING",
                      structuredResult: null, servedByModelId: primary.modelName as String,
                      servedProviderName: primary.providerName as String, providerRunId: null, estimatedCost: 0G]
        persist("create#moqui.ai.AiAgentRun", [agentRunId: runId, agentId: agentId,
            agentName: agent.agentName, conversationId: conversationId,
            userId: ec.user.userId, startedDate: ec.user.nowTimestamp, statusId: "AI_RUN_RUNNING",
            isPreview: (forceApprovalOnMutating ? "Y" : null),
            providerName: primary.providerName, modelName: primary.modelName, userMessage: userMessage])

        // history replay: prior conversation messages, then this turn's user message
        EntityValue conv = null
        if (conversationId) {
            conv = ec.entity.find("moqui.ai.AiConversation").condition("conversationId", conversationId).one()
            if (conv == null) throw new IllegalArgumentException("Unknown conversation: ${conversationId}")
            if (conv.agentId != agentId) throw new IllegalArgumentException("Conversation ${conversationId} belongs to a different agent: ${conv.agentId}")
        }
        String summaryText = conv?.summaryText
        String summaryWatermark = conv?.summaryThruMessageSeqId
        List<Map> messages = conversationId
            ? loadConversationMessages(conversationId, ctxSummarize ? summaryWatermark : null) : []
        int replayCount = messages.size()
        messages.add([role: "user", content: userMessage])
        if (conversationId) {
            persistConversationMessage(conversationId, runId, [role: "user", content: userMessage])
            if (!conv.title) {
                ec.service.async().name("ai.AgentServices.generate#ConversationTitle")
                    .parameters([conversationId: conversationId, userMessage: userMessage]).call()
            }
        }

        return continueAgent(agent, runId, conversationId, candidates, toolSchemas, responseSchema,
            [messages: messages, replayCount: replayCount, stepSeq: 0, candIdx: 0,
             summaryText: summaryText, summaryWatermark: summaryWatermark, result: result])
    }

    /** Runs the agentic loop from the given state until COMPLETED/TRUNCATED/ABORTED/FAILED or
     *  SUSPENDED (a turn proposed a requiresApproval tool). Shared by run() (fresh) and resume().
     *  Static config (maxIter, ctxOn, candidates, toolSchemas, responseSchema) is re-derived from
     *  the agent / passed in — never serialized; only mutable loop state lives in st. */
    private Map continueAgent(EntityValue agent, String runId, String conversationId, List<Map> candidates,
            List<Map> toolSchemas, Map responseSchema, Map st) {
        int maxIter = (agent.maxIterations ?: 8) as int
        long maxTokens = (agent.maxTokens ?: 0L) as long
        int maxToolCalls = (agent.maxToolCallsPerTurn ?: 20) as int
        boolean ctxSummarize = (agent.contextStrategy == "summarize")
        boolean ctxOn = (agent.contextStrategy == "window") || ctxSummarize
        int ctxMsgs = (agent.contextWindowMessages ?: 20) as int
        int ctxChars = (agent.contextWindowChars ?: 48000) as int
        Map primary = candidates[0]
        String reasoningEffort = agent.reasoningEffort as String
        Map reasoning = (reasoningEffort in ['low', 'medium', 'high']) ? [effort: reasoningEffort] : null

        List<Map> messages = st.messages as List<Map>
        int replayCount = st.replayCount as int
        int stepSeq = st.stepSeq as int
        int candIdx = st.candIdx as int
        String summaryText = st.summaryText as String
        String summaryWatermark = st.summaryWatermark as String
        Map result = st.result as Map
        try {
            for (int i = result.iterations as int; i < maxIter; i++) {
                result.iterations = i + 1
                // request Map in, response Map out -- external HTTP, no tx held
                // Re-assembled every iteration on purpose: a tool may call `remember` mid-run, so a
                // later iteration must see the new fact (and re-window the grown history). Do not hoist.
                String sysCtx = agent.systemPrompt as String
                // --- Knowledge injection (unconditional — any contextStrategy, even off) ---
                int knowledgeCap = (agent.knowledgeMaxChars as Integer) ?: (System.getProperty('ai_knowledge_max_chars') as Integer ?: 24000)
                List<Map> knowledgeTopics = loadAgentKnowledge(agent.agentId as String, runId, knowledgeCap)
                sysCtx = ContextAssembler.withKnowledge(sysCtx, knowledgeTopics)
                // --- end knowledge injection ---
                List<Map> sendMessages = messages
                if (ctxOn) {
                    int rc = Math.min(replayCount, messages.size())
                    Map asm = ContextAssembler.windowHistory(messages.subList(0, rc),
                        messages.subList(rc, messages.size()), ctxMsgs, ctxChars)
                    sendMessages = asm.messages as List<Map>
                    List<Map> droppedMsgs = (asm.droppedMessages ?: []) as List<Map>
                    // Summarize each overflow set at most ONCE per run: summaryWatermark advances on
                    // success, so later iterations (which see the same dropped prefix, since the replayed
                    // messages list is fixed for the run) skip re-folding identical messages.
                    // messageSeqId is lexically sortable (Moqui sequenced id), so > compares correctly.
                    String dropThru = droppedMsgs ? (droppedMsgs.last().messageSeqId as String) : null
                    if (ctxSummarize && droppedMsgs && (summaryWatermark == null || dropThru > summaryWatermark)) {
                        String rolled = summarizeOverflow(primary, summaryText, droppedMsgs, result)
                        if (rolled != null) {
                            summaryText = rolled
                            summaryWatermark = dropThru
                            persist("update#moqui.ai.AiConversation", [conversationId: conversationId,
                                summaryText: summaryText, summaryThruMessageSeqId: summaryWatermark])
                        }
                    }
                    sysCtx = ContextAssembler.withFacts(
                        ContextAssembler.withSummary(sysCtx, summaryText), loadFacts(conversationId))
                    if ((asm.dropped as int) > 0) {
                        stepSeq++
                        persist("create#moqui.ai.AiAgentRunStep", [agentRunId: runId, stepSeqId: stepSeq as String,
                            stepType: ctxSummarize ? "compaction" : "context_trim",
                            finishReason: "dropped:${asm.dropped}" as String])
                    }
                }
                Map call = callWithFailover(candidates, candIdx,
                        [systemContext: sysCtx, messages: sendMessages, tools: toolSchemas,
                         responseSchema: responseSchema, reasoning: reasoning], runId)
                candIdx = call.idx as int                     // sticky: stay on the working candidate
                result.servedProviderName = call.providerName
                result.servedByModelId = call.modelName
                for (Map fa in (call.failedAttempts as List<Map>)) {   // observability for each skipped candidate
                    stepSeq++
                    persist("create#moqui.ai.AiAgentRunStep", [agentRunId: runId, stepSeqId: stepSeq as String,
                        stepType: "llm_call", success: "N", finishReason: "provider_error:${fa.providerName}:${fa.modelName}" as String])
                }
                Map resp = call.resp as Map
                long inTok = (resp.tokensIn ?: 0L) as long
                long outTok = (resp.tokensOut ?: 0L) as long
                result.tokensIn += inTok; result.tokensOut += outTok
                if (resp.providerRunId) result.providerRunId = resp.providerRunId
                if (resp.structuredResult != null) result.structuredResult = resp.structuredResult
                stepSeq++
                persist("create#moqui.ai.AiAgentRunStep", [agentRunId: runId, stepSeqId: stepSeq as String,
                    stepType: "llm_call", success: "Y", tokensIn: inTok, tokensOut: outTok, finishReason: resp.finishReason])

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

                // record the assistant turn that requested tools
                Map assistantTurn = [role: "assistant", toolCalls: toolCalls]
                messages.add(assistantTurn)
                // (refinement 1) do NOT persist the assistant turn yet — the approval gate may suspend.

                // ----- approval gate: if any call this turn needs approval, SUSPEND the whole turn -----
                List<Map> needApproval = (toolCalls as List<Map>).findAll { Map tc ->
                    Map td = ai.getToolByName(tc.name as String)
                    if (td == null) return false
                    if (td.requiresApproval) return true
                    // preview: force-gate any MUTATING tool so a draft never executes a write on real data
                    return forceApprovalOnMutating && (td.effectEnumId == "AI_TOOL_MUTATING")
                }
                if (needApproval) {
                    List<String> approvalIds = []
                    for (Map tc in needApproval) {
                        String approvalId = ec.entity.sequencedIdPrimary("moqui.ai.AiToolApproval", null, null)
                        approvalIds.add(approvalId)
                        Map td = ai.getToolByName(tc.name as String)
                        persistRequired("create#moqui.ai.AiToolApproval", [approvalId: approvalId, agentRunId: runId,
                            stepSeqId: stepSeq as String, toolCallId: tc.id, toolName: tc.name, serviceName: td?.serviceName,
                            arguments: JsonOutput.toJson(tc.arguments ?: [:]), statusId: "AI_APPR_PENDING",
                            requestedByUserId: ec.user.userId, requestedDate: ec.user.nowTimestamp])
                    }
                    persistRequired("update#moqui.ai.AiAgentRun", [agentRunId: runId, statusId: "AI_RUN_SUSPENDED",
                        pendingState: JsonOutput.toJson([messages: messages, replayCount: replayCount, stepSeq: stepSeq,
                            candIdx: candIdx, summaryText: summaryText, summaryWatermark: summaryWatermark,
                            result: result, turnToolCalls: toolCalls])])
                    result.statusId = "AI_RUN_SUSPENDED"; result.awaitingApproval = true; result.approvalIds = approvalIds
                    return result
                }

                // not suspending: persist the assistant turn now (as before), then dispatch each
                if (conversationId) persistConversationMessage(conversationId, runId, assistantTurn)
                for (Map tc in toolCalls) {
                    String resultJson
                    if (ctxOn && tc.name == REMEMBER_TOOL) {
                        resultJson = rememberFact(runId, stepSeq, conversationId, tc)
                    } else {
                        resultJson = dispatchTool(runId, stepSeq, tc)
                    }
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

    /** Resume a suspended run once its approvals are decided: execute the gated turn per each
     *  decision (approved/non-gated → dispatch; rejected → a denial result the model can react to),
     *  then continue the loop. Returns the run result. No-op (returns current status) if not suspended. */
    Map resume(String agentRunId) {
        EntityValue run = ec.entity.find("moqui.ai.AiAgentRun").condition("agentRunId", agentRunId).one()
        if (run == null) throw new IllegalArgumentException("Unknown run: ${agentRunId}")
        if (run.statusId != "AI_RUN_SUSPENDED") return [agentRunId: agentRunId, statusId: run.statusId]

        EntityValue agent = ec.entity.find("moqui.ai.AiAgent").condition("agentId", run.agentId).useCache(false).one()  // fresh read (see run(): registry mutates at runtime / out-of-band loads)
        String conversationId = run.conversationId
        boolean ctxOn = (agent.contextStrategy == "window") || (agent.contextStrategy == "summarize")
        List<Map> candidates = loadModelCandidates(run.agentId as String, agent)
        List<Map> toolSchemas = withRememberTool(loadToolSchemas(run.agentId as String), ctxOn, conversationId)
        Map responseSchema = agent.responseSchema ?
            new groovy.json.JsonSlurper().parseText(agent.responseSchema as String) as Map : null

        Map st = new groovy.json.JsonSlurper().parseText(run.pendingState as String) as Map
        List<Map> messages = st.messages as List<Map>
        int stepSeq = st.stepSeq as int
        List<Map> turnToolCalls = st.turnToolCalls as List<Map>

        Map<String, EntityValue> approvals = [:]
        for (EntityValue a in ec.entity.find("moqui.ai.AiToolApproval").condition("agentRunId", agentRunId).list())
            approvals.put(a.toolCallId as String, a)

        // Fail-closed precondition (whole-turn): a requiresApproval tool must NEVER execute without an
        // explicit decision. If ANY gated call in the turn is still undecided (PENDING, or its approval
        // row is missing), the turn is not ready — leave the run SUSPENDED untouched and return. The
        // production caller (decide#ToolCall) only resumes once the last approval is decided; this guards
        // misuse / a double-fired decide, making a premature resume a safe no-op (not consume-and-deny).
        boolean anyUndecided = turnToolCalls.any { tc ->
            if (!ai.getToolByName(tc.name as String)?.requiresApproval) return false   // non-gated: no approval needed
            String s = approvals.get(tc.id as String)?.statusId
            return s != "AI_APPR_APPROVED" && s != "AI_APPR_REJECTED"
        }
        if (anyUndecided) return [agentRunId: agentRunId, statusId: "AI_RUN_SUSPENDED", awaitingApproval: true]

        // (refinement 1) the assistant tool-call turn was withheld from the conversation at suspend
        // (only added to in-memory messages); persist it now so the conversation holds a complete
        // tool_call -> tool_result sequence (never an orphan).
        if (conversationId) persistConversationMessage(conversationId, agentRunId, [role: "assistant", toolCalls: turnToolCalls])

        // execute the suspended turn per decision, appending tool-result messages
        for (Map tc in turnToolCalls) {
            EntityValue appr = approvals.get(tc.id as String)
            String resultJson
            if (appr != null && appr.statusId == "AI_APPR_REJECTED") {
                Map rejTd = ai.getToolByName(tc.name as String)
                resultJson = JsonOutput.toJson([error: "Denied by user${appr.decisionNote ? ': ' + appr.decisionNote : ''}"])
                persist("create#moqui.ai.AiToolCall", [agentRunId: agentRunId, stepSeqId: stepSeq as String,
                    toolCallId: tc.id, toolId: rejTd?.toolId, toolName: tc.name, serviceName: rejTd?.serviceName,
                    arguments: JsonOutput.toJson(tc.arguments ?: [:]), result: resultJson, success: "N",
                    errorText: "rejected", durationMs: 0])
            } else {
                resultJson = (ctxOn && tc.name == REMEMBER_TOOL) ?
                    rememberFact(agentRunId, stepSeq, conversationId, tc) : dispatchTool(agentRunId, stepSeq, tc)
            }
            Map toolMsg = [role: "tool", toolCallId: tc.id, content: resultJson]
            messages.add(toolMsg)
            if (conversationId) persistConversationMessage(conversationId, agentRunId, toolMsg)
        }

        // rehydrate the result Map (keys come back from JSON; ints/longs need care)
        Map result = st.result as Map
        result.tokensIn = (result.tokensIn ?: 0L) as long
        result.tokensOut = (result.tokensOut ?: 0L) as long
        result.iterations = (result.iterations ?: 0) as int
        result.estimatedCost = 0G   // recomputed in finish()
        persist("update#moqui.ai.AiAgentRun", [agentRunId: agentRunId, statusId: "AI_RUN_RUNNING", pendingState: null])
        return continueAgent(agent, agentRunId, conversationId, candidates, toolSchemas, responseSchema,
            [messages: messages, replayCount: st.replayCount as int, stepSeq: stepSeq, candIdx: st.candIdx as int,
             summaryText: st.summaryText as String, summaryWatermark: st.summaryWatermark as String, result: result])
    }

    /** Dispatch one tool-call Map via ec.service.sync (its own tx; Moqui authz applies). Tool
     *  errors are caught and returned as a JSON error so the loop can recover. */
    private String dispatchTool(String runId, int stepSeq, Map tc) {
        Map td = ai.getToolByName(tc.name as String)
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
            toolCallId: tc.id, toolId: td?.toolId, toolName: tc.name, serviceName: td?.serviceName,
            arguments: JsonOutput.toJson(tc.arguments ?: [:]), result: resultJson,
            success: success ? "Y" : "N", errorText: errorText,
            durationMs: (System.currentTimeMillis() - start) as int])
        return resultJson
    }

    /** Ordered provider/model candidates for failover: AiAgentModel rows by priority, or the agent's
     *  own providerName/modelName when no chain is defined (backward-compatible). */
    private List<Map> loadModelCandidates(String agentId, EntityValue agent) {
        List<Map> candidates = []
        for (EntityValue m in ec.entity.find("moqui.ai.AiAgentModel")
                .condition("agentId", agentId).orderBy("priority").useCache(false).list())
            candidates.add([providerName: m.providerName, modelName: m.modelName])
        if (candidates.isEmpty())
            candidates.add([providerName: agent.providerName, modelName: agent.modelName])
        return candidates
    }

    /** Handle the built-in `remember` tool: persist a keyed fact with the run's conversationId
     *  injected server-side (never model-supplied), and record an AiToolCall audit row like any
     *  tool. Returns a JSON confirmation/error for the model. */
    private String rememberFact(String runId, int stepSeq, String conversationId, Map tc) {
        Map args = (tc.arguments ?: [:]) as Map
        long start = System.currentTimeMillis()
        String resultJson; boolean success; String errorText = null
        if (!conversationId) {
            success = false; errorText = "no conversation to remember into"
            resultJson = JsonOutput.toJson([error: errorText])
        } else {
            try {
                ec.message.clearErrors()
                ec.service.sync().name("ai.FactServices.remember#Fact").parameters([
                    conversationId: conversationId, agentRunId: runId,
                    factKey: args.factKey, factValue: args.factValue]).call()
                if (ec.message.hasError()) {
                    success = false; errorText = ec.message.errorsString; ec.message.clearErrors()
                    resultJson = JsonOutput.toJson([error: errorText])
                } else {
                    success = true; resultJson = JsonOutput.toJson([remembered: args.factKey])
                }
            } catch (Throwable t) {
                success = false; errorText = t.message; resultJson = JsonOutput.toJson([error: t.message])
            }
        }
        persist("create#moqui.ai.AiToolCall", [agentRunId: runId, stepSeqId: stepSeq as String,
            toolCallId: tc.id, toolName: tc.name, serviceName: "ai.FactServices.remember#Fact",
            arguments: JsonOutput.toJson(args), result: resultJson,
            success: success ? "Y" : "N", errorText: errorText,
            durationMs: (System.currentTimeMillis() - start) as int])
        return resultJson
    }

    /** Pinned facts for a conversation (ADR 0001 fidelity store), as [factKey, factValue] Maps.
     *  Guarded: a load failure returns [] so the run proceeds without injection (logged). */
    private List<Map> loadFacts(String conversationId) {
        if (!conversationId) return []
        try {
            List<Map> facts = []
            for (EntityValue f in ec.entity.find("moqui.ai.AiConversationFact")
                    .condition("conversationId", conversationId).orderBy("factKey").list())
                facts.add([factKey: f.factKey, factValue: f.factValue])
            return facts
        } catch (Throwable t) { logger.warn("Fact load failed (continuing without facts): ${t.message}"); return [] }
    }

    /**
     * Loads approved + effective knowledge topics for the agent, applies the char cap,
     * and records a context_trim step if topics are dropped.
     * Truncation: whole-topic drop only — never emit a partial body.
     * Topics arrive sorted by topicName (find#AgentKnowledge guarantees this).
     */
    private List<Map> loadAgentKnowledge(String agentId, String agentRunId, int capChars) {
        try {
            Map result = ec.service.sync().name('ai.KnowledgeServices.find#AgentKnowledge')
                .parameters([agentId: agentId]).call()
            List<Map> all = result?.topics ?: []
            if (!all) return []

            List<Map> included = []
            List<String> dropped = []
            int used = 0

            for (Map topic in all) {
                int topicLen = (topic.content as String)?.length() ?: 0
                if (used + topicLen <= capChars) {
                    included.add(topic)
                    used += topicLen
                } else {
                    dropped.add(topic.topicName as String)
                }
            }

            if (dropped) {
                ec.logger.warn("Knowledge cap (${capChars} chars) exceeded for agent ${agentId}. Dropped topics: ${dropped.join(', ')}")
                try {
                    ec.service.sync().name('create#moqui.ai.AiAgentRunStep').parameters([
                        agentRunId : agentRunId,
                        stepSeqId  : ec.entity.sequencedIdPrimary('moqui.ai.AiAgentRunStep', null, null),
                        stepType   : 'context_trim',
                        finishReason: "knowledge_cap: dropped ${dropped.size()} topic(s): ${dropped.join(', ')}"
                    ]).call()
                } catch (Throwable t2) { ec.logger.warn("Could not record knowledge_cap trim step: ${t2.message}") }
            }
            return included
        } catch (Throwable t) {
            ec.logger.warn("Knowledge load failed for agent ${agentId} (continuing without knowledge): ${t.message}")
            return []
        }
    }

    /** Roll the overflow into the conversation summary using the agent's own PRIMARY model (note:
     *  this does not follow the loop's sticky failover candidate — compaction is best-effort and
     *  falls back to plain windowing on any failure). Returns the new summary text, or null. */
    private String summarizeOverflow(Map primary, String existingSummary, List<Map> overflow, Map result) {
        try {
            StringBuilder sb = new StringBuilder()
            if (existingSummary) sb.append("Existing summary:\n").append(existingSummary).append("\n\n")
            sb.append("New messages to fold in:\n")
            for (Map m in overflow) sb.append("[").append(m.role).append("] ").append(m.content ?: "").append("\n")
            LlmProvider p = ai.getProvider(primary.providerName as String)
            Map resp = p.chat([model: primary.modelName, systemContext: SUMMARY_INSTRUCTION,
                messages: [[role: "user", content: sb.toString()]]])
            String text = resp.assistantText as String
            if (!text) return null
            result.tokensIn += (resp.tokensIn ?: 0L) as long
            result.tokensOut += (resp.tokensOut ?: 0L) as long
            return text
        } catch (Throwable t) {
            logger.warn("Compaction summarization failed (falling back to windowing): ${t.message}")
            return null
        }
    }

    /** Effective price for (provider, model) at now → estimated cost from token counts, or 0 when
     *  no price is configured (cost stays 0; never blocks a run). */
    private BigDecimal estimateCost(String providerName, String modelName, long tokensIn, long tokensOut) {
        EntityValue price = ec.entity.find("moqui.ai.AiModelPrice")
            .condition("providerName", providerName).condition("modelName", modelName)
            .conditionDate("fromDate", "thruDate", ec.user.nowTimestamp)
            .orderBy("-fromDate").useCache(true).list().getFirst()
        if (price == null) return 0G
        return CostCalc.cost(tokensIn, tokensOut,
            price.inputPricePerMillion as BigDecimal, price.outputPricePerMillion as BigDecimal)
    }

    /** Sticky failover: try candidates from startIdx in order; the first whose provider.chat()
     *  succeeds wins. Returns [resp, idx, providerName, modelName, failedAttempts]; throws the last
     *  error if every remaining candidate fails. Pure (no persistence) — the caller records steps. */
    private Map callWithFailover(List<Map> candidates, int startIdx, Map baseRequest, String runId) {
        RuntimeException last = null
        List<Map> failed = []
        for (int i = startIdx; i < candidates.size(); i++) {
            Map c = candidates[i]
            try {
                LlmProvider p = ai.getProvider(c.providerName as String)
                Map resp = p.chat(baseRequest + [model: c.modelName])
                return [resp: resp, idx: i, providerName: c.providerName, modelName: c.modelName, failedAttempts: failed]
            } catch (RuntimeException e) {
                last = e
                logger.warn("Agent run ${runId} candidate ${c.providerName}:${c.modelName} failed, trying next: ${e.message}")
                failed.add([providerName: c.providerName, modelName: c.modelName])
            }
        }
        throw (last ?: new RuntimeException("No model candidates configured for agent run ${runId}"))
    }

    /** Build the agent's granted tools as a List of toolSchema Maps (wire name = toolName). */
    private List<Map> loadToolSchemas(String agentId) {
        List<Map> schemas = []
        for (EntityValue grant in ec.entity.find("moqui.ai.AiAgentTool")
                .condition("agentId", agentId).useCache(false).list()) {   // fresh: the Composer grants tools to agents at runtime; a stale grant list would hide a just-granted capability
            Map td = ai.getToolById(grant.toolId as String)
            if (td == null) {
                logger.warn("Agent ${agentId} grants unknown/ineligible tool ${grant.toolId}; skipping")
                continue
            }
            schemas.add([name: td.toolName, description: td.description, parameters: td.schema])
        }
        return schemas
    }

    /** Finalize: set status + truncated on the result Map, persist the run update, return it.
     *  (lastActivityDate is derived via the AiConversationActivity view-entity, not stored.) */
    private Map finish(Map result, String runId, String conversationId, String statusId, String errorText) {
        result.statusId = statusId
        result.truncated = (statusId == "AI_RUN_TRUNCATED")
        result.estimatedCost = estimateCost(result.servedProviderName as String, result.servedByModelId as String,
            result.tokensIn as long, result.tokensOut as long)
        persist("update#moqui.ai.AiAgentRun", [agentRunId: runId, endedDate: ec.user.nowTimestamp,
            statusId: statusId, assistantMessage: result.assistantMessage, iterations: result.iterations,
            tokensIn: result.tokensIn, tokensOut: result.tokensOut, errorText: errorText,
            providerName: result.servedProviderName, servedByModelId: result.servedByModelId,
            providerRunId: result.providerRunId, estimatedCost: result.estimatedCost])
        return result
    }

    /** Load persisted conversation messages, in order, as message Maps (incl. messageSeqId).
     *  When afterSeqId is set, only messages with a greater messageSeqId are returned (the live
     *  tail past a compaction watermark). */
    private List<Map> loadConversationMessages(String conversationId, String afterSeqId = null) {
        List<Map> out = []
        def finder = ec.entity.find("moqui.ai.AiConversationMessage")
            .condition("conversationId", conversationId).orderBy("messageSeqId")
        if (afterSeqId) finder.condition("messageSeqId", "greater", afterSeqId)
        for (EntityValue m in finder.list()) {
            Map msg = [role: m.role, content: m.content, messageSeqId: m.messageSeqId]
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
                agentRunId: runId, createdDate: ec.user.nowTimestamp])
            v.create()
        } catch (Throwable t) { logger.warn("Conversation message persist failed (continuing): ${t.message}") }
    }

    /** Persistence never aborts the run: each write is its own service call (own tx); on
     *  failure we log a warning and continue. */
    private void persist(String serviceName, Map params) {
        try { ec.service.sync().name(serviceName).parameters(params).call() }
        catch (Throwable t) { logger.warn("Observability write ${serviceName} failed (continuing): ${t.message}") }
    }

    /** Like persist(), but does NOT swallow errors — for load-bearing writes (suspend state) where a
     *  failure must fail the run loudly (caught by the loop) rather than leave a zombie. */
    private void persistRequired(String serviceName, Map params) {
        ec.service.sync().name(serviceName).parameters(params).call()
    }

    /** The built-in remember tool schema appended when context mgmt is on and there is a conversation. */
    private List<Map> withRememberTool(List<Map> toolSchemas, boolean ctxOn, String conversationId) {
        if (!(ctxOn && conversationId)) return toolSchemas
        return toolSchemas + [[name: REMEMBER_TOOL,
            description: "Record a durable, confirmed value (e.g. a confirmed order total, address, or decision) so it is never lost from context. Call this the moment you confirm a value that must persist across the conversation.",
            parameters: [type: "object", required: ["factKey", "factValue"], properties: [
                factKey: [type: "string", description: "short stable identifier, e.g. order_total"],
                factValue: [type: "string", description: "the confirmed value"]]]]]
    }

    /** Asynchronously auto-generate a short, descriptive title for a conversation based on
     *  its first user message, using the conversation agent's configured LLM provider. */
    static void generateConversationTitle(ExecutionContext ec, String conversationId, String userMessage) {
        EntityValue conv = ec.entity.find("moqui.ai.AiConversation").condition("conversationId", conversationId).one()
        if (conv == null || conv.title) return

        EntityValue agent = ec.entity.find("moqui.ai.AiAgent").condition("agentId", conv.agentId).one()
        if (agent == null) return

        def ai = ec.factory.getTool("AI", AiToolFactory.class)
        def providerName = agent.providerName ?: System.getProperty('ai_default_provider') ?: 'openai'
        def modelName = agent.modelName ?: System.getProperty('ai_default_model') ?: 'gpt-4o-mini'

        def p = ai.getProvider(providerName)
        def sysPrompt = "You are a conversation auto-naming assistant. Generate a short, descriptive title (maximum 5-6 words) for a chat conversation based on the first user message. Do not use quotation marks, markdown, or any surrounding text. Just output the clean title."

        try {
            def resp = p.chat([
                model: modelName,
                systemContext: sysPrompt,
                messages: [[role: 'user', content: userMessage]]
            ])
            String title = resp.assistantText
            if (title) {
                title = title.trim()
                if (title.startsWith('"') && title.endsWith('"')) {
                    title = title.substring(1, title.length() - 1).trim()
                } else if (title.startsWith("'") && title.endsWith("'")) {
                    title = title.substring(1, title.length() - 1).trim()
                }
                if (title.length() > 255) title = title.substring(0, 252) + "..."

                conv.title = title
                conv.update()
            }
        } catch (Exception e) {
            logger.warn("Auto-naming conversation ${conversationId} failed: ${e.message}")
        }
    }
}

