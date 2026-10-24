import groovy.json.JsonOutput
import org.moqui.ai.ClassificationProvider

def results=ec.cache.getCache('ai.classification.results')
def failures=ec.cache.getCache('ai.classification.failures')
Map cached=results.get(classificationKey) ?: failures.get(classificationKey)
if(cached!=null) {
    ec.context.putAll(cached+[cacheHit:true])
    return null
}
String runId=ec.entity.sequencedIdPrimary('moqui.ai.AiAgentRun',null,null)
Map outcome=[agentRunId:runId,statusId:'AI_RUN_FAILED',cacheHit:false,label:null,errorType:null]
def persist={String service,Map values->
    ec.service.sync().name(service).requireNewTransaction(true).parameters(values).call()
    !ec.message.hasError()
}
if(!persist('create#moqui.ai.AiAgentRun',[agentRunId:runId,agentName:('Text classification '+cacheScope).take(63),
        userId:ec.user.userId,startedDate:ec.user.nowTimestamp,statusId:'AI_RUN_RUNNING',
        providerName:'openai',modelName:'gpt-6-luna',userMessage:JsonOutput.toJson([text:text,labels:labels])])) return null
Map response=[:]
try {
    def provider=ec.factory.getTool('AI',Object.class).getProvider('openai')
    if(!(provider instanceof ClassificationProvider)) throw new IllegalStateException('OpenAI classification provider unavailable')
    response=provider.classify([text:text,labels:labels])
    if(!response.containsKey('label') || !(response.label==null || response.label instanceof String) ||
            (response.label!=null && !labels.contains(response.label))) throw new IllegalStateException('Invalid classification result')
    outcome.label=response.label
    outcome.statusId='AI_RUN_COMPLETED'
} catch(Exception failure) {
    Throwable cause=failure
    while(cause.cause!=null && cause.cause!=cause) cause=cause.cause
    outcome.errorType=(cause instanceof java.util.concurrent.TimeoutException || cause instanceof java.net.http.HttpTimeoutException) ? 'TIMEOUT' : 'PROVIDER_ERROR'
    ec.logger.warn('Optional OpenAI classification unavailable: '+outcome.errorType)
}
if(!persist('create#moqui.ai.AiAgentRunStep',[agentRunId:runId,stepSeqId:'1',stepType:'llm_call',
        success:outcome.statusId=='AI_RUN_COMPLETED'?'Y':'N',tokensIn:response.tokensIn ?: 0L,
        tokensOut:response.tokensOut ?: 0L,finishReason:outcome.errorType ?: 'choice'])) return null
if(!persist('update#moqui.ai.AiAgentRun',[agentRunId:runId,endedDate:ec.user.nowTimestamp,statusId:outcome.statusId,
        assistantMessage:outcome.statusId=='AI_RUN_COMPLETED'?JsonOutput.toJson(response):null,
        iterations:1,tokensIn:response.tokensIn ?: 0L,tokensOut:response.tokensOut ?: 0L,
        servedByModelId:'gpt-6-luna',providerRunId:response.providerRunId,errorText:outcome.errorType])) return null
if(outcome.statusId=='AI_RUN_COMPLETED') results.put(classificationKey,new LinkedHashMap(outcome))
else failures.put(classificationKey,new LinkedHashMap(outcome))
ec.context.putAll(outcome)
return null
