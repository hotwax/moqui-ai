package org.moqui.ai.rules

import org.drools.model.DSL
import org.drools.model.Drools
import org.drools.model.Global
import org.drools.model.PatternDSL
import org.drools.model.Rule
import org.drools.model.RuleItemBuilder
import org.drools.model.functions.Block2
import org.drools.model.functions.Predicate1
import org.drools.model.impl.ModelImpl
import org.drools.model.prototype.PrototypeDSL
import org.drools.modelcompiler.KieBaseBuilder
import org.kie.api.prototype.PrototypeFact
import org.kie.api.prototype.PrototypeFactInstance
import org.moqui.context.ExecutionContext

class RuleSetBuilder {
    final ExecutionContext ec
    final String ruleSetId
    final Map<String, PrototypeFact> prototypes = [:]
    final Global<List> matches = DSL.globalOf(List.class, "org.moqui.ai.rules", BuiltRuleSet.MATCHES_GLOBAL)

    RuleSetBuilder(ExecutionContext ec, String ruleSetId) {
        this.ec = ec
        this.ruleSetId = ruleSetId
    }

    BuiltRuleSet build() {
        Map decisionFactTypes = ec.service.sync().name("ai.rules.RuleServices.get#DecisionFactTypes")
                .parameters([ruleSetId: ruleSetId]).disableAuthz().call()
        ((List<String>) decisionFactTypes.factTypeIds).each { prototypes.put(it, PrototypeDSL.prototypeFact(it)) }

        ModelImpl model = new ModelImpl().addGlobal(matches)
        List<Map> rules = ec.entity.find("moqui.ai.RuleSetRule").condition("ruleSetId", ruleSetId)
                .useCache(false).disableAuthz().listMaster("default")
        for (Map rule in rules) model.addRule(buildRule(rule))
        return new BuiltRuleSet(ruleSetId, KieBaseBuilder.createKieBaseFromModel(model), prototypes)
    }

    private Rule buildRule(Map rule) {
        Map preparedLines = ec.service.sync().name("ai.rules.RuleLineServices.prepare#RuleLines")
                .parameters([ruleSetId: ruleSetId, ruleName: rule.ruleName, conditions: rule.conditions]).disableAuthz().call()
        if (preparedLines.problems) throw new IllegalArgumentException(((List) preparedLines.problems).join("; "))

        List<RuleItemBuilder> items = []
        ((Map<String, Map>) preparedLines.factMatches).each { String factTypeId, Map factMatch ->
            PrototypeDSL.PrototypePatternDef pattern = PrototypeDSL.protoPattern(PrototypeDSL.variable(prototypes.get(factTypeId), "${factTypeId}_${rule.ruleId}"))
            pattern.expr("${rule.ruleId}_${factTypeId}", { PrototypeFactInstance fact -> ItemChecks.matches(factMatch, fact.asMap()) }.dehydrate() as Predicate1<PrototypeFactInstance>)
            items.add(pattern)
        }
        items.add(consequenceFor(rule))

        return PatternDSL.rule("org.moqui.ai.rules", "${rule.ruleName} (${rule.ruleId})")
                .attribute(Rule.Attribute.SALIENCE, (rule.priority ?: 0) as Integer)
                .build(items as RuleItemBuilder[])
    }

    private RuleItemBuilder consequenceFor(Map rule) {
        List<String> problems = []
        List<Map> preparedActions = ((List<Map>) rule.actions ?: []).sort(false) { it.actionSeqId }.withIndex().collect { Map action, int index ->
            Map actionType = (Map) action.actionType
            if (actionType == null) {
                problems.add("Action ${index + 1}: action type ${action.actionTypeId} is not known".toString())
                return [:]
            }
            Map prepared = ec.service.sync().name((String) actionType.prepareServiceName)
                    .parameters([ruleSetId: ruleSetId, action: action]).disableAuthz().call()
            problems.addAll(((List<String>) prepared.problems).collect { "Action ${index + 1}: ${it}".toString() })
            return prepared
        }
        if (problems) throw new IllegalArgumentException("Rule ${rule.ruleName}: " + problems.join("; "))

        Map ruleInfo = [ruleId: rule.ruleId, ruleName: rule.ruleName, priority: rule.priority]
        List<Map> ruleMatches = preparedActions*.outcomeId.findAll { it }.collect { ruleInfo + [outcomeId: it] } ?: [ruleInfo + [outcomeId: null]]
        List<Map> factsToAdd = preparedActions*.factToAdd.findAll { it }
        Map<String, PrototypeFact> factPrototypes = prototypes

        return DSL.on(matches).execute({ Drools drools, List matchList ->
            matchList.addAll(ruleMatches)
            for (Map factToAdd in factsToAdd) {
                PrototypeFactInstance newFact = factPrototypes.get((String) factToAdd.factTypeId).newInstance()
                ((Map) factToAdd.values).each { String fieldName, Object value -> newFact.put(fieldName, value) }
                drools.insert(newFact)
            }
        }.dehydrate() as Block2<Drools, List>)
    }
}
