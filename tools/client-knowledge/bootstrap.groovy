import org.moqui.Moqui
import java.sql.Timestamp

def ec = Moqui.getExecutionContext()
try {
 ec.artifactExecution.disableAuthz()
 ec.transaction.runRequireNew(120,'Client knowledge local bootstrap',{
  for (def rr in ec.entity.getConfEntityFileLocations()) if (!rr.location.endsWith('.eecas.xml')) ec.entity.makeDataLoader().location(rr.location).load()
  for (String name in ['EntityTypeData','SecurityTypeData','MoquiSetupData']) ec.entity.makeDataLoader().location('/Users/adityapatel/Documents/GitHub/moqui/maarg-oms/moqui-framework/framework/data/'+name+'.xml').load()
  for (String path in ['data/SetupData.xml','data/AiStatusData.xml','data/AiConversationStatusData.xml','data/McpSecurityData.xml'])
   ec.entity.makeDataLoader().location('component://moqui-ai/'+path).load()
  ec.entity.makeValue('org.apache.ofbiz.party.party.Party').setAll([partyId:'KNOW_LOCAL',partyTypeId:'PERSON']).createOrUpdate()
  ec.entity.makeValue('org.apache.ofbiz.security.login.UserLogin').setAll([userLoginId:'knowledge-local',partyId:'KNOW_LOCAL',enabled:'Y']).createOrUpdate()
  ec.entity.makeValue('moqui.security.UserAccount').setAll([userId:'KNOW_LOCAL',username:'knowledge-local',userFullName:'Local Rails Knowledge Curator',hasLoggedOut:'N']).createOrUpdate()
  ec.entity.makeValue('moqui.security.UserGroupMember').setAll([userId:'KNOW_LOCAL',userGroupId:'ADMIN',fromDate:Timestamp.valueOf('2026-01-01 00:00:00')]).createOrUpdate()
  for (String id in ['rails','GLOBAL']) {
   ec.entity.makeValue('moqui.ai.AiClientSpace').setAll([clientId:id,clientName:id=='rails'?'Rails':'HotWax Documentation']).createOrUpdate()
   ec.entity.makeValue('moqui.ai.AiClientMember').setAll([clientId:id,userId:'KNOW_LOCAL',canCurate:'Y']).createOrUpdate()
  }
 })
 println 'BOOTSTRAP_OK'
} finally { ec.destroy(); Moqui.destroyActiveExecutionContextFactory() }

System.exit(0)
