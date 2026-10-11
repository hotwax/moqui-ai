// Explicit membership is required even for an administrator using the client tool surface.
def membership = ec.entity.find('moqui.ai.AiClientMember').condition('clientId', clientId)
    .condition('userId', ec.user.userId ?: '').one()
if (!ec.user.userId || membership == null) { ec.message.addError('No access to this client knowledge space.'); return null }
import groovy.json.JsonOutput
resolution = resolution ?: 'open'
if (!(resolution in ['open','answered','needs_human'])) { ec.message.addError('Invalid resolution.'); return null }
if (resolution == 'answered' && (!answer || !evidenceIds)) { ec.message.addError('An answered question needs an answer and citations.'); return null }
if ((evidenceIds ?: []).size() > 20) { ec.message.addError('At most 20 citations are allowed.'); return null }
for (def id in (evidenceIds ?: [])) {
    def row = ec.entity.find('moqui.ai.AiClientEvidence').condition('evidenceId',id).one()
    if (!row || !(row.clientId in [clientId,'GLOBAL']) || row.isCurrent != 'Y' || row.trustLevel == 'rejected') {
        ec.message.addError('Citation is unavailable for this client.'); return null
    }
}
questionId = UUID.randomUUID().toString()
ec.entity.makeValue('moqui.ai.AiClientQuestion').setAll([questionId:questionId,clientId:clientId,
    question:question,answer:answer,evidenceIds:JsonOutput.toJson(evidenceIds ?: []),resolution:resolution,
    createdDate:ec.user.nowTimestamp,createdByUserId:ec.user.userId]).create()
return null
