// Workforce inbox aggregate — backs ai.WorkforceServices.list#Conversation.
// Kept in Groovy (not mini-lang) on purpose: two batched group-by-first reductions (latest run per
// conversation, first pending request per suspended run), an in-memory derive/filter, and an in-memory
// sort + pagination — none of which XML actions express without going N+1 or being outright impossible.
// In-params bound from context: derivedStatus, pageSize, pageIndex. Out-param set: conversationList.

def convs = ec.entity.find("moqui.ai.AiConversationActivity")
    .selectFields(["conversationId", "agentId", "userId", "title", "createdDate", "statusId", "lastActivityDate"])
    .orderBy("-lastActivityDate").list()
// batched enrichment: agent names + latest run per conversation + first pending request per suspended run
def agentNames = [:]
for (def a in ec.entity.find("moqui.ai.AiAgent").list()) agentNames[a.agentId] = a.agentName
def latestRun = [:]
def convIds = convs.collect { it.conversationId }
if (convIds) for (def r in ec.entity.find("moqui.ai.AiAgentRun")
        .condition("conversationId", "in", convIds).orderBy("-startedDate").list())
    if (!latestRun.containsKey(r.conversationId)) latestRun[r.conversationId] = r
def pendingByRun = [:]
def suspendedRunIds = latestRun.values().findAll { it.statusId == "AI_RUN_SUSPENDED" }.collect { it.agentRunId }
if (suspendedRunIds) for (def p in ec.entity.find("moqui.ai.AiToolCallRequest")
        .condition("agentRunId", "in", suspendedRunIds).condition("statusId", "AI_TCREQ_PENDING")
        .orderBy("requestedDate").list())
    if (!pendingByRun.containsKey(p.agentRunId)) pendingByRun[p.agentRunId] = p
conversationList = []
for (def c in convs) {
    def run = latestRun[c.conversationId]
    String ds = "idle"; String pendingToolName = null
    if (run != null) {
        if (run.statusId == "AI_RUN_SUSPENDED") { ds = "pending"; pendingToolName = pendingByRun[run.agentRunId]?.toolName }
        else if (run.statusId == "AI_RUN_RUNNING") ds = "running"
        else if (run.statusId in ["AI_RUN_FAILED", "AI_RUN_ABORTED", "AI_RUN_TRUNCATED"]) ds = "error"
    }
    if (derivedStatus && ds != derivedStatus) continue
    conversationList.add([conversationId: c.conversationId, agentId: c.agentId,
        agentName: agentNames[c.agentId], title: c.title, userId: c.userId,
        lastActivityDate: c.lastActivityDate ?: c.createdDate,
        derivedStatus: ds, pendingToolName: pendingToolName])
}
// Re-sort on the COALESCED date: AiConversationActivity.lastActivityDate is max(message.createdDate)
// over an optional join, so a message-less (brand-new) conversation has a null DB sort key and would
// otherwise sink to the nulls-last edge. Sort by (lastActivityDate ?: createdDate) so newest activity
// — including a just-started conversation — is genuinely first before paginating.
conversationList.sort { a, b -> b.lastActivityDate <=> a.lastActivityDate }
int ps = (pageSize ?: 50) as int; int pi = (pageIndex ?: 0) as int
int from = Math.min(pi * ps, conversationList.size())
conversationList = conversationList.subList(from, Math.min(from + ps, conversationList.size()))
