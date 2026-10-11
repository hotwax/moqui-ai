import org.moqui.Moqui

def ec=Moqui.getExecutionContext()
try {
 ec.artifactExecution.disableAuthz()
 ec.entity.find('moqui.service.job.ServiceJob').condition('jobName',org.moqui.entity.EntityCondition.NOT_EQUAL,'client_knowledge_refresh').updateAll([paused:'Y'])
 ec.entity.makeValue('moqui.service.job.ServiceJob').setAll([
 jobName:'client_knowledge_refresh',description:'Local Rails GitHub and document evidence refresh',
 serviceName:'ai.ClientKnowledgeServices.refresh#LocalClientSources',cronExpression:'0 */15 * * * ?',
 paused:'N',localOnly:'Y',transactionTimeout:600,minRetryTime:15]).createOrUpdate()
 // No topics, subscribers, email, or outbound messages.
 println 'LOCAL_JOB_CONFIGURED'
} finally { ec.destroy(); Moqui.destroyActiveExecutionContextFactory() }

System.exit(0)
