// Installed only in the isolated POC. Collector location/user are operator configuration, never tool arguments.
if (System.getProperty('ai_client_collectors_enabled') != 'Y') { ec.message.addError('Local collectors disabled.'); return null }
boolean loggedInHere = false
try {
 if (!ec.user.userId && ec.artifactExecution.getAuthzDisabled()) {
  ec.user.internalLoginUser(System.getProperty('ai_client_collector_user'))
  loggedInHere = true
 }
 if (!ec.user.isInGroup('ADMIN')) { ec.message.addError('Administrator required for local collectors.'); return null }
 String script = System.getProperty('ai_client_collector_script')
 if (!script || !new File(script).isFile()) { ec.message.addError('Collector script not configured.'); return null }
 for (String source in ['github','local','docs']) {
  Process child = new ProcessBuilder('/opt/homebrew/bin/python3', script, source)
      .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
  if (!child.waitFor(240,java.util.concurrent.TimeUnit.SECONDS)) {
   child.destroyForcibly(); ec.message.addError('Local collector timed out.'); return null
  }
  if (child.exitValue()!=0) { ec.message.addError('Local collector failed: '+source); return null }
 }
 def result=ec.service.sync().name('ai.ClientKnowledgeServices.sync#EvidenceInbox').call()
 imported=result.imported; unchanged=result.unchanged
} finally { if(loggedInHere) ec.user.logoutLocal() }
return null
