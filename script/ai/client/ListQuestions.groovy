// Explicit membership is required even for an administrator using the client tool surface.
def membership = ec.entity.find('moqui.ai.AiClientMember').condition('clientId', clientId)
    .condition('userId', ec.user.userId ?: '').one()
if (!ec.user.userId || membership == null) { ec.message.addError('No access to this client knowledge space.'); return null }
questions = ec.entity.find('moqui.ai.AiClientQuestion').condition('clientId',clientId)
    .orderBy('-createdDate').limit(30).list().collect { new LinkedHashMap(it) }
return null
