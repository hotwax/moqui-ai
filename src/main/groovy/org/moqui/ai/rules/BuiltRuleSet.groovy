package org.moqui.ai.rules

import org.kie.api.KieBase
import org.kie.api.prototype.PrototypeFact
import org.kie.api.prototype.PrototypeFactInstance
import org.kie.api.runtime.KieSession
import org.moqui.context.ExecutionContext

class BuiltRuleSet {
    static final String MATCHES_GLOBAL = "matches"
    static final int MAX_RULE_FIRINGS = 1000

    final String ruleSetId
    final KieBase kieBase
    final Map<String, PrototypeFact> prototypes

    BuiltRuleSet(String ruleSetId, KieBase kieBase, Map<String, PrototypeFact> prototypes) {
        this.ruleSetId = ruleSetId
        this.kieBase = kieBase
        this.prototypes = prototypes
    }

    Map decide(ExecutionContext ec, List<Map> facts, boolean firstMatchOnly) {
        List<Map> matchList = []
        KieSession session = kieBase.newKieSession()
        try {
            session.setGlobal(MATCHES_GLOBAL, matchList)
            for (Map factEntry in facts) {
                PrototypeFactInstance factInstance = prototypes.get((String) factEntry.factTypeId).newInstance()
                ((Map) factEntry.fact).each { String fieldName, Object value -> factInstance.put(fieldName, value) }
                session.insert(factInstance)
            }
            if (session.fireAllRules(MAX_RULE_FIRINGS) >= MAX_RULE_FIRINGS) {
                ec.message.addError("Decision ${ruleSetId} stopped after ${MAX_RULE_FIRINGS} rule firings")
                return null
            }
        } finally {
            session.dispose()
        }
        List<Map> matchedRules = matchList.sort { a, b -> (b.priority <=> a.priority) ?: (a.ruleName <=> b.ruleName) }
        List<String> outcomeIds = matchedRules*.outcomeId.findAll { it != null }.unique()
        return [matchedRules: matchedRules, outcomes: firstMatchOnly ? outcomeIds.take(1) : outcomeIds]
    }
}
