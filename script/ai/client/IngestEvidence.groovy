// Explicit membership is required even for an administrator using the client tool surface.
def membership = ec.entity.find('moqui.ai.AiClientMember').condition('clientId', clientId)
    .condition('userId', ec.user.userId ?: '').one()
if (!ec.user.userId || membership == null) { ec.message.addError('No access to this client knowledge space.'); return null }
if (membership.canCurate != 'Y') { ec.message.addError('Client curator access required.'); return null }
import java.security.MessageDigest
import groovy.json.JsonOutput
if (!(sourceType in ['documentation','decision_record','meeting','github','interaction'])) {
    ec.message.addError('Unsupported source type.'); return null
}
if (clientId == 'GLOBAL' && sourceType != 'documentation') {
    ec.message.addError('Only product documentation belongs in GLOBAL.'); return null
}
// Do not persist obvious credentials accidentally pasted into a conversation or source.
if (body =~ /(?i)(-----BEGIN [A-Z ]*PRIVATE KEY|(?:authorization\s*:\s*bearer|api[_-]?key|password|access[_-]?token)\s*[:=]\s*["']?[^\s"']{8,}|gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9]{20,})/) {
    ec.message.addError('Source appears to contain a credential; redact before importing.'); return null
}
if (sourceDate.after(new java.sql.Timestamp(System.currentTimeMillis()+300000))) {
    ec.message.addError('Source date cannot be in the future.'); return null
}
String hash = MessageDigest.getInstance('SHA-256').digest(JsonOutput.toJson(
    [clientId,sourceKey,sourceUrl,sourceType,title,body]).getBytes('UTF-8')).encodeHex().toString()
evidenceId = MessageDigest.getInstance('SHA-256').digest((hash+'|'+sourceDate.toString()).getBytes('UTF-8')).encodeHex().toString().substring(0,40)
def existing = ec.entity.find('moqui.ai.AiClientEvidence').condition('evidenceId',evidenceId).one()
changed = existing == null
if (existing != null) return null
// Ignore delayed snapshots; a source cannot roll back its current version silently.
def current = ec.entity.find('moqui.ai.AiClientEvidence').condition('clientId',clientId)
    .condition('sourceKey',sourceKey).condition('isCurrent','Y').one()
if (current && current.contentHash == hash) { evidenceId=current.evidenceId; changed=false; return null }
if (current && sourceDate.before(current.sourceDate)) {
    ec.message.addError('Older source snapshot refused.'); return null
}
ec.entity.find('moqui.ai.AiClientEvidence').condition('clientId',clientId)
    .condition('sourceKey',sourceKey).condition('isCurrent','Y').updateAll([isCurrent:'N'])
ec.entity.makeValue('moqui.ai.AiClientEvidence').setAll([
    evidenceId:evidenceId,clientId:clientId,sourceKey:sourceKey,sourceUrl:sourceUrl,sourceType:sourceType,
    title:title,body:body,searchText:(title+' '+body).toLowerCase(Locale.ROOT),contentHash:hash,
    sourceDate:sourceDate,observedAt:ec.user.nowTimestamp,createdByUserId:ec.user.userId,
    isCurrent:'Y',trustLevel:'observed']).create()
return null
