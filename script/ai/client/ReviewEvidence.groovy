// Explicit membership is required even for an administrator using the client tool surface.
def membership = ec.entity.find('moqui.ai.AiClientMember').condition('clientId', clientId)
    .condition('userId', ec.user.userId ?: '').one()
if (!ec.user.userId || membership == null) { ec.message.addError('No access to this client knowledge space.'); return null }
if (membership.canCurate != 'Y') { ec.message.addError('Client curator access required.'); return null }
if (!(trustLevel in ['confirmed','rejected'])) { ec.message.addError('Review must be confirmed or rejected.'); return null }
def row = ec.entity.find('moqui.ai.AiClientEvidence').condition('evidenceId',evidenceId).condition('clientId',clientId).one()
if (!row || row.isCurrent != 'Y') { ec.message.addError('Current evidence not found.'); return null }
if (row.sourceType == 'interaction' && trustLevel == 'confirmed') { ec.message.addError('A client question cannot be confirmed as policy; cite a decision source.'); return null }
row.setAll([trustLevel:trustLevel,reviewNote:reviewNote,reviewedByUserId:ec.user.userId,reviewedAt:ec.user.nowTimestamp]).update()
return null
