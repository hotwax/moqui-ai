import groovy.json.JsonSlurper
if (!ec.user.isInGroup('ADMIN')) { ec.message.addError('Administrator required for local inbox sync.'); return null }
String configured = System.getProperty('ai_client_inbox')
if (!configured) { ec.message.addError('ai_client_inbox is not configured.'); return null }
File folder = new File(configured).canonicalFile
if (!folder.isDirectory()) { ec.message.addError('Inbox directory missing.'); return null }
imported = 0; unchanged = 0
for (File f in (folder.listFiles() ?: []).findAll { it.name.endsWith('.json') }.sort { it.name }.take(2000)) {
    if (java.nio.file.Files.isSymbolicLink(f.toPath()) || f.length() > 150000) continue
    Map input = (Map) new JsonSlurper().parse(f)
    Map res = ec.service.sync().name('ai.ClientKnowledgeServices.ingest#Evidence')
        .parameters(input).requireNewTransaction(true).call()
    if (ec.message.hasError()) return null
    if (res.changed) imported++ else unchanged++
}
return null
