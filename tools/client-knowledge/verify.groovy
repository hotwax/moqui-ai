import org.moqui.Moqui
import java.sql.Timestamp

def ec=Moqui.getExecutionContext()
int checks=0
def call={String verb,Map input -> ec.service.sync().name('ai.ClientKnowledgeServices.'+verb).parameters(input).call() }
def ok={boolean cond,String description -> assert cond : description; checks++; println 'PASS '+description }
try {
 ec.user.internalLoginUser('knowledge-local')
 def input=[clientId:'rails',sourceKey:'poc-regression',sourceUrl:'https://example.invalid/regression',sourceType:'decision_record',title:'Regression isolation fixture',body:'Warehouse priority purplezebra',sourceDate:Timestamp.valueOf('2026-01-01 00:00:00')]
 def first=call('ingest#Evidence',input)
 ok(!ec.message.hasError() && first?.evidenceId!=null,'real Moqui service persists evidence')
 def again=call('ingest#Evidence',input)
 ok(again.changed==false && first.evidenceId==again.evidenceId,'idempotent ingestion')
 ec.entity.find('moqui.ai.AiClientMember').condition('clientId','rails').condition('userId','KNOW_LOCAL').one().set('canCurate','N').update()
 call('ingest#Evidence',input+[body:'Should not write'])
 ok(ec.message.hasError(),'reader cannot ingest evidence'); ec.message.clearErrors()
 call('review#Evidence',[clientId:'rails',evidenceId:first.evidenceId,trustLevel:'confirmed',reviewNote:'Should not approve'])
 ok(ec.message.hasError(),'reader cannot confirm evidence'); ec.message.clearErrors()
 ec.entity.find('moqui.ai.AiClientMember').condition('clientId','rails').condition('userId','KNOW_LOCAL').one().set('canCurate','Y').update()
 def found=call('search#Evidence',[clientId:'rails',query:'purplezebra'])
 ok(found.matches.any { it.evidenceId==first.evidenceId },'source retrieved with citation')
 call('search#Evidence',[clientId:'another-client',query:'purplezebra'])
 ok(ec.message.hasError(),'cross-client search denied'); ec.message.clearErrors()
 call('ingest#Evidence',input+[clientId:'GLOBAL',sourceType:'meeting'])
 ok(ec.message.hasError(),'private meeting cannot enter shared documentation'); ec.message.clearErrors()
 call('review#Evidence',[clientId:'rails',evidenceId:first.evidenceId,trustLevel:'confirmed',reviewNote:'Regression-only evidence review'])
 ok(!ec.message.hasError(),'curator review succeeds')
 def newer=call('ingest#Evidence',input+[body:'Updated warehouse rule purplezebra',sourceDate:Timestamp.valueOf('2026-01-02 00:00:00')])
 call('ingest#Evidence',input+[body:'Delayed earlier snapshot'])
 ok(ec.message.hasError(),'out-of-order changed snapshot rejected'); ec.message.clearErrors()
 def replay=call('ingest#Evidence',input)
 ok(replay.changed==false,'old duplicate event does not restore superseded evidence')
 def previous=call('get#Evidence',[clientId:'rails',evidenceId:first.evidenceId]).evidence
 def current=call('get#Evidence',[clientId:'rails',evidenceId:newer.evidenceId]).evidence
 ok(previous.isCurrent=='N' && current.trustLevel=='observed','changed source supersedes old version and requires new review')
 call('record#Question',[clientId:'rails',question:'Is this deployed?',answer:'Yes',resolution:'answered'])
 ok(ec.message.hasError(),'uncited answer rejected'); ec.message.clearErrors()
 call('record#Question',[clientId:'rails',question:'Is this deployed?',answer:'Unknown',resolution:'answered',evidenceIds:[first.evidenceId]])
 ok(ec.message.hasError(),'superseded citations rejected'); ec.message.clearErrors()
 def question=call('record#Question',[clientId:'rails',question:'POC regression: deployed?',resolution:'needs_human'])
 ok(question.questionId!=null,'unresolved interaction captured without learning it as policy')
 def catalog=ec.service.sync().name('ai.mcp.McpServices.get#ToolCatalog').call()
 ok(catalog.toolList.any { it.name=='search_client_knowledge' },'native MCP catalog exposes search')
 def tool=catalog.toolList.find { it.name=='search_client_knowledge' }
 def result=ec.service.sync().name('ai.mcp.McpServices.exec#Tool').parameters([toolDef:tool,arguments:[clientId:'rails',query:'purplezebra']]).call()
 ok(result.result.isError==false && result.result.structuredContent.matches.size()>0,'native MCP tools/call returns database evidence')
 def audits=ec.entity.find('moqui.ai.AiToolCall').condition('toolName','search_client_knowledge').condition('userId','KNOW_LOCAL').count()
 ok(audits>0,'native MCP audit records caller and tool')
 ec.entity.find('moqui.ai.AiClientQuestion').condition('questionId',question.questionId).deleteAll()
 def reverted=call('ingest#Evidence',input+[sourceDate:Timestamp.valueOf('2026-01-03 00:00:00')])
 ok(reverted.changed && reverted.evidenceId!=first.evidenceId,'genuine later source reversion creates a new current observation')
 println "VERIFICATION_OK ${checks} checks"
} finally {
 ec.artifactExecution.disableAuthz()
 ec.entity.find('moqui.ai.AiClientMember').condition('clientId','rails').condition('userId','KNOW_LOCAL').one().set('canCurate','Y').update()
 ec.entity.find('moqui.ai.AiClientEvidence').condition('clientId','rails').condition('sourceKey','poc-regression').deleteAll()
 ec.destroy(); Moqui.destroyActiveExecutionContextFactory()
}

System.exit(0)
