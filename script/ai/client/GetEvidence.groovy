// Explicit membership is required even for an administrator using the client tool surface.
def membership = ec.entity.find('moqui.ai.AiClientMember').condition('clientId', clientId)
    .condition('userId', ec.user.userId ?: '').one()
if (!ec.user.userId || membership == null) { ec.message.addError('No access to this client knowledge space.'); return null }
def row = ec.entity.find('moqui.ai.AiClientEvidence').condition('evidenceId',evidenceId).one()
if (!row || !(row.clientId in [clientId,'GLOBAL'])) { ec.message.addError('Evidence not found in this client space.'); return null }
evidence = new LinkedHashMap(row)
evidence.remove('searchText')
return null
