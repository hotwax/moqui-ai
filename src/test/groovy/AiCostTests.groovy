import spock.lang.*

class AiCostTests extends Specification {
    def "computes cost from per-million prices"() {
        expect:
        // 1000 in @ $3/M + 500 out @ $15/M = 0.003 + 0.0075 = 0.0105
        org.moqui.ai.CostCalc.cost(1000L, 500L, 3.0G, 15.0G) == 0.010500G
        org.moqui.ai.CostCalc.cost(0L, 0L, 3.0G, 15.0G) == 0.000000G
    }
}
