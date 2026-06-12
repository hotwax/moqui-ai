// Workforce thread aggregate — backs ai.WorkforceServices.get#ConversationDetail.
// Kept in Groovy (not mini-lang) for the per-message toolCalls JSON parse, which XML actions can't do;
// the surrounding plain reads (agent, latest run, pending requests) ride along in the same script.
// Bound from the calling service actions: conv (the AiConversation value), conversationId.
// Out-params set: conversation, agent, messageList, latestRun, pendingRequestList.

conversation = conv.getMap()
def agentEv = ec.entity.find("moqui.ai.AiAgent").condition("agentId", conv.agentId).one()
agent = agentEv != null ? [agentId: agentEv.agentId, agentName: agentEv.agentName, statusId: agentEv.statusId] : null
def slurper = new groovy.json.JsonSlurper()
messageList = []
for (def m in ec.entity.find("moqui.ai.AiConversationMessage")
        .condition("conversationId", conversationId).orderBy("messageSeqId").list()) {
    def toolCalls = null
    if (m.toolCalls) { try { toolCalls = slurper.parseText(m.toolCalls as String) } catch (Exception e) { /* malformed JSON: render the message without cards */ } }
    messageList.add([messageSeqId: m.messageSeqId, role: m.role, content: m.content,
        toolCalls: toolCalls, toolCallId: m.toolCallId, agentRunId: m.agentRunId, createdDate: m.createdDate])
}
def runs = ec.entity.find("moqui.ai.AiAgentRun").condition("conversationId", conversationId)
    .orderBy("-startedDate").limit(1).list()
def run = runs ? runs[0] : null
latestRun = run != null ? [agentRunId: run.agentRunId, statusId: run.statusId, errorText: run.errorText] : null
pendingRequestList = []
if (run != null && run.statusId == "AI_RUN_SUSPENDED")
    for (def p in ec.entity.find("moqui.ai.AiToolCallRequest").condition("agentRunId", run.agentRunId)
            .condition("statusId", "AI_TCREQ_PENDING").orderBy("requestedDate").list())
        pendingRequestList.add([toolCallRequestId: p.toolCallRequestId, toolName: p.toolName,
            serviceName: p.serviceName, arguments: p.arguments])
