import spock.lang.*
import org.drools.model.DSL
import org.drools.model.Index
import org.drools.model.PatternDSL
import org.drools.model.Rule
import org.drools.model.functions.Block1
import org.drools.model.impl.ModelImpl
import org.drools.model.prototype.PrototypeDSL
import org.drools.model.prototype.PrototypeVariable
import org.drools.modelcompiler.KieBaseBuilder
import org.kie.api.KieBase
import org.kie.api.prototype.PrototypeFact
import org.kie.api.prototype.PrototypeFactInstance
import org.kie.api.runtime.KieSession

class DroolsRuleTests extends Specification {
    KieBase switchOnKieBase(PrototypeFact checkResult, List<String> outcomes) {
        PrototypeVariable result = PrototypeDSL.variable(checkResult)
        Rule switchOn = PatternDSL.rule("switchOn").build(
                PrototypeDSL.protoPattern(result).expr("refusedCount", Index.ConstraintType.EQUAL, 0),
                DSL.on(result).execute({ PrototypeFactInstance fact -> outcomes.add("SWITCH_ON") } as Block1))
        return KieBaseBuilder.createKieBaseFromModel(new ModelImpl().addRule(switchOn))
    }

    void fire(KieBase kieBase, PrototypeFact checkResult, Integer refusedCount) {
        PrototypeFactInstance fact = checkResult.newInstance()
        fact.put("refusedCount", refusedCount)
        KieSession session = kieBase.newKieSession()
        try {
            session.insert(fact)
            session.fireAllRules()
        } finally {
            session.dispose()
        }
    }

    def "a prototype rule built in code fires on a matching fact only"() {
        given:
        PrototypeFact checkResult = PrototypeDSL.prototypeFact("checkResult")
        List<String> outcomes = []
        KieBase kieBase = switchOnKieBase(checkResult, outcomes)

        when:
        fire(kieBase, checkResult, 0)
        then:
        outcomes == ["SWITCH_ON"]

        when:
        outcomes.clear()
        fire(kieBase, checkResult, 2)
        then:
        outcomes.isEmpty()
    }
}
