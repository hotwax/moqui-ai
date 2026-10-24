import groovy.json.JsonOutput
import java.security.MessageDigest

List choices=labels.findAll { it instanceof String && !it.trim().isEmpty() }.unique().sort()
if(choices.size()!=labels.size() || choices.isEmpty()) {
    ec.message.addError('Classification labels must be unique nonblank strings')
    return null
}
String normalized=text.toString().trim().toLowerCase(Locale.ROOT)
String key=MessageDigest.getInstance('SHA-256').digest(JsonOutput.toJson(['v1','gpt-6-luna',cacheScope,normalized,choices]).getBytes('UTF-8')).encodeHex().toString()
def results=ec.cache.getCache('ai.classification.results')
def failures=ec.cache.getCache('ai.classification.failures')
Map cached=results.get(key) ?: failures.get(key)
if(cached!=null) {
    ec.context.putAll(cached+[cacheHit:true])
    return null
}
boolean loadedHere=false
Map outcome=ec.factory.getTool('AI',Object.class).coalesceClassification(key, {
    loadedHere=true
    ec.service.sync().name('ai.DecisionServices.request#TextClassification')
        .parameters([classificationKey:key,text:normalized,labels:choices,cacheScope:cacheScope]).call()
})
if(outcome && !loadedHere) outcome=outcome+[cacheHit:true]
if(outcome) ec.context.putAll(outcome)
return null
