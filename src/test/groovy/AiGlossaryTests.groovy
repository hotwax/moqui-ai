import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.entity.EntityValue
import org.moqui.entity.EntityCondition

/** Builder Knowledgebase / domain glossary: the three entities + status/enum seed data, seed#DomainGlossary
 *  (derive nouns from the entity model + UDM concepts, verbs from the service catalog), find#DomainTerm
 *  (lexical, APPROVED-only, ranked), naming-signal capture (in-service hook on store#AiTool/store#AiAgent
 *  + defensive EECA floor), promote#TermsFromSignals (threshold -> LEARNED+SUGGESTED), the Composer-facing
 *  list#DomainTerm + propose#Naming snapping, the curation services, and the full learning loop.
 *
 *  Harness: setup-data writes wrapped in ec.transaction.runRequireNew(...) + ensureTestUser() (the active
 *  Shiro realm authenticates against the OFBiz UserLogin model, so internalLoginUser needs Party/UserLogin
 *  rows, written in a committed tx). Fixed-PK test rows are delete-before-create so the suite is rerun-safe
 *  on the persistent DB. cleanupSpec removes every glossary row this class may create so it leaves the DB
 *  as it found it (the Composer suite, which runs first, asserts an empty-glossary "catalog" source). */
class AiGlossaryTests extends Specification {
    @Shared ExecutionContext ec

    private void ensureTestUser() {
        ec.entity.makeValue("org.apache.ofbiz.party.party.Party").setAll([partyId: "AiTestUser", partyTypeId: "PERSON"]).createOrUpdate()
        ec.entity.makeValue("org.apache.ofbiz.party.party.Person").setAll([partyId: "AiTestUser", firstName: "AI", lastName: "Test User"]).createOrUpdate()
        ec.entity.makeValue("org.apache.ofbiz.security.login.UserLogin").setAll([userLoginId: "AiTestUser", partyId: "AiTestUser", enabled: "Y"]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: "AiTestUser", username: "AiTestUser", userFullName: "AI Test User"]).createOrUpdate()
    }

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
        ec.transaction.runRequireNew(30, "ai test setup", {
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiStatusData.xml").load()
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiGlossaryData.xml").load()
            ec.entity.makeDataLoader().location("component://moqui-ai/data/AiSecurityData.xml").load()
            ensureTestUser()
        })
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.artifactExecution.enableAuthz()
    }
    def cleanupSpec() {
        if (ec == null) return
        // leave the DB as we found it: remove all glossary rows this class may have created
        ec.artifactExecution.disableAuthz()
        ec.transaction.runRequireNew(30, "ai glossary teardown", {
            ec.entity.find("moqui.ai.AiTermSynonym").deleteAll()
            ec.entity.find("moqui.ai.AiDomainTerm").deleteAll()
            ec.entity.find("moqui.ai.AiNamingSignal").deleteAll()
        })
        ec.artifactExecution.enableAuthz()
        ec.destroy()
    }
    def setup() {
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        ec.artifactExecution.disableAuthz()
        ec.message.clearErrors()
    }
    def cleanup() { ec.artifactExecution.enableAuthz() }

    // ---- Task 1: entities + status/enum seed data ----

    def "glossary entities + status/enum seed data exist"() {
        given:
        ec.entity.find("moqui.ai.AiTermSynonym").condition("termId", "TERMNOUN1").deleteAll()
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", "TERMNOUN1").deleteAll()
        ec.entity.find("moqui.ai.AiNamingSignal").condition("signalId", "SIG1").deleteAll()
        when:
        ec.entity.makeValue("moqui.ai.AiDomainTerm").setAll([termId: "TERMNOUN1", term: "return",
            termKind: "AI_TERM_NOUN", sourceType: "AI_TSRC_SEEDED", statusId: "AI_TERM_APPROVED", usageCount: 0]).create()
        ec.entity.makeValue("moqui.ai.AiTermSynonym").setAll([termId: "TERMNOUN1", synonym: "rma",
            sourceType: "AI_TSRC_CURATED", statusId: "AI_TERM_APPROVED"]).create()
        ec.entity.makeValue("moqui.ai.AiNamingSignal").setAll([signalId: "SIG1", signalType: "AI_SIG_TOOL_NAME",
            intentText: "list returns", suggestedName: "list_returns", chosenName: "list_rmas",
            wasOverridden: "Y", userId: "U1", createdDate: ec.user.nowTimestamp]).create()
        then:
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", "TERMNOUN1").one().term == "return"
        ec.entity.find("moqui.ai.AiTermSynonym").condition("termId", "TERMNOUN1").condition("synonym", "rma").one() != null
        ec.entity.find("moqui.ai.AiNamingSignal").condition("signalId", "SIG1").one().wasOverridden == "Y"
        ec.entity.find("moqui.basic.StatusItem").condition("statusId", "AI_TERM_APPROVED").one() != null
        ec.entity.find("moqui.basic.Enumeration").condition("enumId", "AI_TERM_NOUN").one() != null
        ec.entity.find("moqui.basic.Enumeration").condition("enumId", "AI_TSRC_SEEDED").one() != null
        ec.entity.find("moqui.basic.Enumeration").condition("enumId", "AI_SIG_TOOL_NAME").one() != null
        cleanup:
        ec.entity.find("moqui.ai.AiTermSynonym").condition("termId", "TERMNOUN1").deleteAll()
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", "TERMNOUN1").deleteAll()
        ec.entity.find("moqui.ai.AiNamingSignal").condition("signalId", "SIG1").deleteAll()
    }

    // ---- Task 2: seed#DomainGlossary ----

    def "seed#DomainGlossary derives noun + verb terms, SEEDED + APPROVED, and is idempotent"() {
        given: "a clean glossary"
        ec.entity.find("moqui.ai.AiDomainTerm").condition("sourceType", "AI_TSRC_SEEDED").deleteAll()
        when: "first run"
        Map r1 = ec.service.sync().name("ai.GlossaryServices.seed#DomainGlossary").parameters([:]).call()
        then: "nouns from the entity model (OrderHeader -> order) + the curated UDM concepts are present, APPROVED + SEEDED"
        (r1.nounsAdded as int) > 0
        (r1.verbsAdded as int) > 0
        def order = ec.entity.find("moqui.ai.AiDomainTerm").condition("term", "order").condition("termKind", "AI_TERM_NOUN").one()
        order != null && order.statusId == "AI_TERM_APPROVED" && order.sourceType == "AI_TSRC_SEEDED"
        // a verb observed from the existing exposable services (e.g. get / find / list)
        ec.entity.find("moqui.ai.AiDomainTerm").condition("term", "list").condition("termKind", "AI_TERM_VERB").one() != null
        when: "second run absorbs nothing new"
        Map r2 = ec.service.sync().name("ai.GlossaryServices.seed#DomainGlossary").parameters([:]).call()
        then: "idempotent — no duplicate rows, nothing re-added"
        (r2.nounsAdded as int) == 0
        (r2.verbsAdded as int) == 0
        ec.entity.find("moqui.ai.AiDomainTerm").condition("term", "order").condition("termKind", "AI_TERM_NOUN").list().size() == 1
        cleanup:
        ec.entity.find("moqui.ai.AiDomainTerm").condition("sourceType", "AI_TSRC_SEEDED").deleteAll()
    }

    // ---- Task 3: find#DomainTerm ----

    def "find#DomainTerm matches term+synonym, filters APPROVED + kind, ranks by match x usageCount"() {
        given: "two approved nouns (one reinforced) + one suggested (must be excluded) + a synonym"
        ec.entity.find("moqui.ai.AiTermSynonym").condition("termId", "FT1").deleteAll()
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", EntityCondition.LIKE, "FT%").deleteAll()
        ec.entity.makeValue("moqui.ai.AiDomainTerm").setAll([termId: "FT1", term: "return", termKind: "AI_TERM_NOUN",
            sourceType: "AI_TSRC_SEEDED", statusId: "AI_TERM_APPROVED", usageCount: 5]).create()
        ec.entity.makeValue("moqui.ai.AiDomainTerm").setAll([termId: "FT2", term: "refund", termKind: "AI_TERM_NOUN",
            sourceType: "AI_TSRC_SEEDED", statusId: "AI_TERM_APPROVED", usageCount: 0]).create()
        ec.entity.makeValue("moqui.ai.AiDomainTerm").setAll([termId: "FT3", term: "rebate", termKind: "AI_TERM_NOUN",
            sourceType: "AI_TSRC_LEARNED", statusId: "AI_TERM_SUGGESTED", usageCount: 99]).create()
        ec.entity.makeValue("moqui.ai.AiTermSynonym").setAll([termId: "FT1", synonym: "rma",
            sourceType: "AI_TSRC_CURATED", statusId: "AI_TERM_APPROVED"]).create()
        when: "search for an RMA (a synonym of return) — should resolve via synonym"
        Map bySyn = ec.service.sync().name("ai.GlossaryServices.find#DomainTerm")
            .parameters([text: "create an rma for the customer", kind: "AI_TERM_NOUN"]).call()
        then: "return matched (via synonym), suggested 'rebate' excluded despite huge usageCount"
        (bySyn.terms as List).find { it.term == "return" } != null
        (bySyn.terms as List).find { it.term == "rebate" } == null
        when: "a query that hits both 'return' and 'refund' literally"
        Map both = ec.service.sync().name("ai.GlossaryServices.find#DomainTerm")
            .parameters([text: "return refund", kind: "AI_TERM_NOUN"]).call()
        then: "'return' ranks above 'refund' (equal match, higher usageCount)"
        List terms = both.terms as List
        terms.findIndexOf { it.term == "return" } < terms.findIndexOf { it.term == "refund" }
        cleanup:
        ec.entity.find("moqui.ai.AiTermSynonym").condition("termId", "FT1").deleteAll()
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", EntityCondition.LIKE, "FT%").deleteAll()
    }

    // ---- Task 4: capture naming signals ----

    def "authoring a tool with an overridden name writes one AiNamingSignal (Y)"() {
        given:
        ec.entity.find("moqui.ai.AiNamingSignal").condition("intentText", "list the echoes").deleteAll()
        ec.entity.find("moqui.ai.AiTool").condition("serviceName", "moqui.ai.test.TestServices.get#Echo").deleteAll()
        ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser("AiTestUser")
        when: "store#AiTool with a Composer proposal that the human overrode"
        // store#AiTool is the keystone service; it accepts suggestedName + intentText pass-through.
        ec.service.sync().name("ai.ToolServices.store#AiTool").parameters([
            serviceName: "moqui.ai.test.TestServices.get#Echo", verb: "list", noun: "echoes",
            suggestedName: "get_echo", intentText: "list the echoes", description: "x"]).call()
        then: "exactly one signal, overridden, tool-name type"
        def sigs = ec.entity.find("moqui.ai.AiNamingSignal").condition("signalType", "AI_SIG_TOOL_NAME")
            .condition("intentText", "list the echoes").list()
        sigs.size() == 1
        sigs[0].suggestedName == "get_echo"
        sigs[0].chosenName == "list_echoes"   // toolName derived verb_noun (keystone)
        sigs[0].wasOverridden == "Y"
        cleanup:
        ec.entity.find("moqui.ai.AiNamingSignal").condition("intentText", "list the echoes").deleteAll()
        ec.entity.find("moqui.ai.AiTool").condition("serviceName", "moqui.ai.test.TestServices.get#Echo").deleteAll()
    }

    def "seed#DomainGlossary writes do not produce naming signals"() {
        given:
        ec.entity.find("moqui.ai.AiDomainTerm").condition("sourceType", "AI_TSRC_SEEDED").deleteAll()
        long before = ec.entity.find("moqui.ai.AiNamingSignal").count()
        when:
        ec.service.sync().name("ai.GlossaryServices.seed#DomainGlossary").parameters([:]).call()
        then:
        ec.entity.find("moqui.ai.AiNamingSignal").count() == before
        cleanup:
        ec.entity.find("moqui.ai.AiDomainTerm").condition("sourceType", "AI_TSRC_SEEDED").deleteAll()
    }

    // ---- Task 5: back the Composer — list#DomainTerm + propose#Naming ----

    def "list#DomainTerm returns the grounding slice (thin wrapper over find#DomainTerm)"() {
        given:
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", "LT1").deleteAll()
        ec.entity.makeValue("moqui.ai.AiDomainTerm").setAll([termId: "LT1", term: "shipment", termKind: "AI_TERM_NOUN",
            sourceType: "AI_TSRC_SEEDED", statusId: "AI_TERM_APPROVED", usageCount: 1]).create()
        when:
        Map out = ec.service.sync().name("ai.GlossaryServices.list#DomainTerm")
            .parameters([text: "track a shipment", kind: "AI_TERM_NOUN"]).call()
        then:
        (out.terms as List).find { it.term == "shipment" } != null
        cleanup:
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", "LT1").deleteAll()
    }

    def "propose#Naming snaps a raw verb/noun to the nearest approved glossary terms"() {
        given: "approved 'return' noun with synonym 'rma', approved 'list' verb"
        ec.entity.find("moqui.ai.AiTermSynonym").condition("termId", "PN1").deleteAll()
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", EntityCondition.LIKE, "PN%").deleteAll()
        ec.entity.makeValue("moqui.ai.AiDomainTerm").setAll([termId: "PN1", term: "return", termKind: "AI_TERM_NOUN",
            sourceType: "AI_TSRC_CURATED", statusId: "AI_TERM_APPROVED", usageCount: 3]).create()
        ec.entity.makeValue("moqui.ai.AiTermSynonym").setAll([termId: "PN1", synonym: "rma",
            sourceType: "AI_TSRC_CURATED", statusId: "AI_TERM_APPROVED"]).create()
        ec.entity.makeValue("moqui.ai.AiDomainTerm").setAll([termId: "PN2", term: "list", termKind: "AI_TERM_VERB",
            sourceType: "AI_TSRC_SEEDED", statusId: "AI_TERM_APPROVED", usageCount: 0]).create()
        when: "the Composer proposes a raw verb+noun using the dialect word 'rmas'"
        Map out = ec.service.sync().name("ai.GlossaryServices.propose#Naming")
            .parameters([proposedVerb: "list", proposedNoun: "rmas", intentText: "list all rmas"]).call()
        then: "noun snaps to the canonical 'return' (via synonym 'rma'); verb stays 'list'; grounded name emitted"
        out.verb == "list"
        out.noun == "return"
        out.toolName == "list_return"
        (out.groundingTerms as List).size() >= 1
        cleanup:
        ec.entity.find("moqui.ai.AiTermSynonym").condition("termId", "PN1").deleteAll()
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", EntityCondition.LIKE, "PN%").deleteAll()
    }

    // ---- Task 6: promote#TermsFromSignals ----

    def "promote#TermsFromSignals inserts recurring chosen names as LEARNED + SUGGESTED past threshold"() {
        given: "three signals choosing 'rmas' (>= threshold 3), one choosing 'widget' (below)"
        ec.entity.find("moqui.ai.AiNamingSignal").condition("userId", "PromUser").deleteAll()
        ec.entity.find("moqui.ai.AiDomainTerm").condition("term", "rmas").deleteAll()
        ec.entity.find("moqui.ai.AiDomainTerm").condition("term", "widget").deleteAll()
        3.times { i ->
            ec.entity.makeValue("moqui.ai.AiNamingSignal").setAll([signalId: "PS${i}", signalType: "AI_SIG_TOOL_NAME",
                intentText: "list rmas", suggestedName: "list_returns", chosenName: "list_rmas",
                wasOverridden: "Y", userId: "PromUser", createdDate: ec.user.nowTimestamp]).create()
        }
        ec.entity.makeValue("moqui.ai.AiNamingSignal").setAll([signalId: "PSW", signalType: "AI_SIG_TOOL_NAME",
            intentText: "make widget", suggestedName: "create_widget", chosenName: "create_widget",
            wasOverridden: "N", userId: "PromUser", createdDate: ec.user.nowTimestamp]).create()
        and: "'list' is a known approved verb so it is stripped, leaving the noun token to learn"
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", "PV1").deleteAll()
        ec.entity.makeValue("moqui.ai.AiDomainTerm").setAll([termId: "PV1", term: "list", termKind: "AI_TERM_VERB",
            sourceType: "AI_TSRC_SEEDED", statusId: "AI_TERM_APPROVED", usageCount: 0]).create()
        when:
        Map out = ec.service.sync().name("ai.GlossaryServices.promote#TermsFromSignals")
            .parameters([threshold: 3]).call()
        then: "the recurring chosen NOUN token ('rmas') becomes a LEARNED + SUGGESTED term; widget does not"
        (out.proposed as int) >= 1
        def rmas = ec.entity.find("moqui.ai.AiDomainTerm").condition("term", "rmas").one()
        rmas != null && rmas.statusId == "AI_TERM_SUGGESTED" && rmas.sourceType == "AI_TSRC_LEARNED"
        ec.entity.find("moqui.ai.AiDomainTerm").condition("term", "widget").one() == null
        when: "re-run is idempotent (already proposed -> not re-added)"
        Map out2 = ec.service.sync().name("ai.GlossaryServices.promote#TermsFromSignals").parameters([threshold: 3]).call()
        then:
        ec.entity.find("moqui.ai.AiDomainTerm").condition("term", "rmas").list().size() == 1
        cleanup:
        ec.entity.find("moqui.ai.AiDomainTerm").condition("term", "rmas").deleteAll()
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", "PV1").deleteAll()
        ec.entity.find("moqui.ai.AiNamingSignal").condition("userId", "PromUser").deleteAll()
    }

    // ---- Task 7: curation services + the full loop ----

    def "approve#DomainTerm flips SUGGESTED -> APPROVED; reject#DomainTerm -> REJECTED"() {
        given:
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", "CU1").deleteAll()
        ec.entity.makeValue("moqui.ai.AiDomainTerm").setAll([termId: "CU1", term: "rmas", termKind: "AI_TERM_NOUN",
            sourceType: "AI_TSRC_LEARNED", statusId: "AI_TERM_SUGGESTED", usageCount: 3]).create()
        when:
        ec.service.sync().name("ai.GlossaryServices.approve#DomainTerm").parameters([termId: "CU1"]).call()
        then:
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", "CU1").one().statusId == "AI_TERM_APPROVED"
        when:
        ec.service.sync().name("ai.GlossaryServices.reject#DomainTerm").parameters([termId: "CU1"]).call()
        then:
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", "CU1").one().statusId == "AI_TERM_REJECTED"
        cleanup:
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", "CU1").deleteAll()
    }

    def "store#DomainTerm creates a CURATED term and can attach a synonym"() {
        given:
        ec.entity.find("moqui.ai.AiTermSynonym").condition("synonym", "bo").deleteAll()
        ec.entity.find("moqui.ai.AiDomainTerm").condition("term", "backorder").deleteAll()
        when:
        Map r = ec.service.sync().name("ai.GlossaryServices.store#DomainTerm")
            .parameters([term: "backorder", termKind: "AI_TERM_NOUN", description: "demand beyond stock", synonym: "bo"]).call()
        then:
        def t = ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", r.termId).one()
        t.sourceType == "AI_TSRC_CURATED" && t.statusId == "AI_TERM_APPROVED"
        ec.entity.find("moqui.ai.AiTermSynonym").condition("termId", r.termId).condition("synonym", "bo").one() != null
        cleanup:
        ec.entity.find("moqui.ai.AiTermSynonym").condition("termId", r.termId).deleteAll()
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", r.termId).deleteAll()
    }

    def "the full loop: override signal -> promote -> approve as synonym -> reflected in next propose#Naming"() {
        given: "an approved canonical noun 'return' (no dialect yet) + verb 'list'"
        ec.entity.find("moqui.ai.AiTermSynonym").condition("termId", "LOOP_R").deleteAll()
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", EntityCondition.LIKE, "LOOP%").deleteAll()
        ec.entity.find("moqui.ai.AiDomainTerm").condition("term", "rmas").deleteAll()
        ec.entity.find("moqui.ai.AiNamingSignal").condition("intentText", "list rmas").deleteAll()
        ec.entity.makeValue("moqui.ai.AiDomainTerm").setAll([termId: "LOOP_R", term: "return", termKind: "AI_TERM_NOUN",
            sourceType: "AI_TSRC_SEEDED", statusId: "AI_TERM_APPROVED", usageCount: 0]).create()
        ec.entity.makeValue("moqui.ai.AiDomainTerm").setAll([termId: "LOOP_L", term: "list", termKind: "AI_TERM_VERB",
            sourceType: "AI_TSRC_SEEDED", statusId: "AI_TERM_APPROVED", usageCount: 0]).create()
        and: "the deployment repeatedly renames list_returns -> list_rmas (signals)"
        3.times { i ->
            ec.service.sync().name("ai.GlossaryServices.capture#NamingSignal").parameters([signalType: "AI_SIG_TOOL_NAME",
                chosenName: "list_rmas", suggestedName: "list_returns", intentText: "list rmas"]).call()
        }
        when: "promote proposes 'rmas'; the Curator approves it AS A SYNONYM of return"
        ec.service.sync().name("ai.GlossaryServices.promote#TermsFromSignals").parameters([threshold: 3]).call()
        // Curator decides 'rmas' is the dialect for 'return': add it as an approved synonym, reject the bare term
        ec.service.sync().name("ai.GlossaryServices.store#DomainTerm")
            .parameters([termId: "LOOP_R", synonym: "rmas"]).call()   // store onto existing canonical term
        def bare = ec.entity.find("moqui.ai.AiDomainTerm").condition("term", "rmas").condition("termKind", "AI_TERM_NOUN").one()
        if (bare) ec.service.sync().name("ai.GlossaryServices.reject#DomainTerm").parameters([termId: bare.termId]).call()
        and: "a later propose#Naming for the same dialect intent now snaps to 'return'"
        Map out = ec.service.sync().name("ai.GlossaryServices.propose#Naming")
            .parameters([proposedVerb: "list", proposedNoun: "rmas", intentText: "list all rmas"]).call()
        then: "the learned dialect resolved: 'rmas' -> canonical 'return'"
        out.noun == "return"
        out.toolName == "list_return"
        cleanup:
        ec.entity.find("moqui.ai.AiTermSynonym").condition("termId", "LOOP_R").deleteAll()
        ec.entity.find("moqui.ai.AiDomainTerm").condition("termId", EntityCondition.LIKE, "LOOP%").deleteAll()
        ec.entity.find("moqui.ai.AiDomainTerm").condition("term", "rmas").deleteAll()
        ec.entity.find("moqui.ai.AiNamingSignal").condition("intentText", "list rmas").deleteAll()
    }
}
