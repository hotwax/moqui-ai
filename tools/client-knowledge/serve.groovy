import org.moqui.Moqui
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.util.concurrent.Executors

// Local POC adapter only: OS-local caller maps to a dedicated Moqui user with Rails membership.
// No arbitrary services or operational tools cross this boundary. Production needs real user auth.
def factory=Moqui.getExecutionContextFactory()
def allowed=['search_documentation','search_client_knowledge','get_client_evidence','record_client_question','list_client_questions','ingest_client_evidence'] as Set
File html=new File(System.getProperty('ai_client_collector_script')).parentFile.toPath().resolve('index.html').toFile()
def server=HttpServer.create(new InetSocketAddress('127.0.0.1',8097),0)
server.executor=Executors.newFixedThreadPool(4)
def send={ex,int code,Object value ->
 byte[] bytes=(value instanceof String ? value : JsonOutput.toJson(value)).getBytes('UTF-8')
 ex.responseHeaders.set('Content-Type',value instanceof String ? 'text/html; charset=utf-8':'application/json')
 ex.responseHeaders.set('Cache-Control','no-store')
 ex.responseHeaders.set('X-Content-Type-Options','nosniff')
 ex.responseHeaders.set('Content-Security-Policy',"default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'")
 ex.sendResponseHeaders(code,bytes.length); ex.responseBody.withCloseable { it.write(bytes) }
}
def handler = { ex ->
 def ec=null
 try {
  String host=ex.requestHeaders.getFirst('Host')
  String origin=ex.requestHeaders.getFirst('Origin')
  if (!(host in ['127.0.0.1:8097','localhost:8097']) || (origin && !(origin in ['http://127.0.0.1:8097','http://localhost:8097']))) {
   send(ex,403,[error:'Local origin required']); return
  }
  if (ex.requestMethod=='GET' && ex.requestURI.path=='/') { send(ex,200,html.text); return }
  if (ex.requestMethod!='POST' || !(ex.requestURI.path in ['/mcp/json','/local/sync','/local/status'])) { send(ex,404,[error:'Not found']); return }
  if (!(ex.requestHeaders.getFirst('Content-Type') ?: '').startsWith('application/json')) { send(ex,415,[error:'JSON required']); return }
  byte[] bytes=ex.requestBody.readNBytes(150001)
  if (bytes.length>150000) { send(ex,413,[error:'Request too large']); return }
  Map input=bytes.length ? (Map)new JsonSlurper().parse(bytes) : [:]
  ec=Moqui.getExecutionContext()
  ec.user.internalLoginUser('knowledge-local')
  if (ex.requestURI.path=='/local/sync') {
   def result=ec.service.sync().name('ai.ClientKnowledgeServices.sync#EvidenceInbox').call()
   send(ex,ec.message.hasError() ? 400 : 200, ec.message.hasError() ? [error:'Ingestion failed; inspect local logs'] : result); return
  }
  if (ex.requestURI.path=='/local/status') {
   Map counts=[:]
   for (String c in ['rails','GLOBAL']) counts[c]=ec.entity.find('moqui.ai.AiClientEvidence').condition('clientId',c).condition('isCurrent','Y').count()
   def runs=ec.entity.find('moqui.service.job.ServiceJobRun').condition('jobName','client_knowledge_refresh').disableAuthz().orderBy('-startTime').limit(1).list()
   send(ex,200,[counts:counts,questions:ec.entity.find('moqui.ai.AiClientQuestion').condition('clientId','rails').count(),sync:runs.collect { [startTime:it.startTime?.toString(),endTime:it.endTime?.toString(),hasError:it.hasError] }]); return
  }
  if (input.method=='tools/call' && !allowed.contains(input.params?.name)) {
   send(ex,200,[jsonrpc:'2.0',id:input.id,error:[code:-32602,message:'This local adapter exposes only client knowledge tools.']]); return
  }
  if (!(input.method in ['initialize','notifications/initialized','tools/list','tools/call','ping','server/discover'])) {
   send(ex,200,[jsonrpc:'2.0',id:input.id,error:[code:-32601,message:'Method not available.']]); return
  }
  Map out=ec.service.sync().name('ai.mcp.McpServices.dispatch#Request').parameters([
   jsonrpc:input.jsonrpc,method:input.method,id:input.id,params:input.params,
   protocolVersionHeader:ex.requestHeaders.getFirst('MCP-Protocol-Version'),
   mcpMethodHeader:ex.requestHeaders.getFirst('Mcp-Method'),mcpNameHeader:ex.requestHeaders.getFirst('Mcp-Name')]).call()
  if (input.method=='tools/list' && out.response?.result?.tools) out.response.result.tools=out.response.result.tools.findAll { allowed.contains(it.name) }
  if (out.response==null) { ex.sendResponseHeaders((out.httpStatus?:202) as int,-1); ex.close() }
  else send(ex,(out.httpStatus?:200) as int,out.response)
 } catch (Throwable t) {
  System.err.println('Local request failed: '+t.class.simpleName)
  send(ex,500,[error:'Local request failed; inspect service logs.'])
 } finally { if(ec!=null) ec.destroy() }
}
server.createContext('/', handler as com.sun.net.httpserver.HttpHandler)
server.start()
if (System.getProperty('ai_client_run_job_now') == 'Y') {
 def jobEc=Moqui.getExecutionContext()
 try { jobEc.artifactExecution.disableAuthz(); jobEc.service.job('client_knowledge_refresh').run() } finally { jobEc.destroy() }
}
println 'KNOWLEDGE_POC_READY http://127.0.0.1:8097'
Runtime.runtime.addShutdownHook(new Thread({ server.stop(1); Moqui.destroyActiveExecutionContextFactory() }))
new java.util.concurrent.CountDownLatch(1).await()
