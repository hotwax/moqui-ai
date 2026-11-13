import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityValue

class ListFactTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String digitalOrderId
    @Shared String plainOrderId

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.user.internalLoginUser("john.doe")
        ec.artifactExecution.disableAuthz()
        digitalOrderId = salesOrderIdWhere(true)
        plainOrderId = salesOrderIdWhere(false)
    }

    def cleanupSpec() {
        ec.artifactExecution.enableAuthz()
        ec.user.logoutUser()
        ec.destroy()
    }

    String salesOrderIdWhere(boolean hasDigitalItem) {
        for (EntityValue order in ec.entity.find("org.apache.ofbiz.order.order.OrderHeader").condition("orderTypeId", "SALES_ORDER")
                .orderBy("-orderDate").limit(300).list()) {
            List<String> productIds = ec.entity.find("org.apache.ofbiz.order.order.OrderItem").condition("orderId", order.orderId)
                    .list()*.productId
            if (!productIds) continue
            Set<String> productTypeIds = ec.entity.find("org.apache.ofbiz.product.product.Product")
                    .condition("productId", EntityCondition.ComparisonOperator.IN, productIds).list()*.productTypeId as Set
            if (("DIGITAL_GOOD" in productTypeIds) == hasDigitalItem) return order.orderId
        }
        return null
    }

    Map buildOrder(String orderId) {
        ec.service.sync().name("ai.rules.RuleServices.build#Fact")
                .parameters([factTypeId: "salesOrder", parameters: [orderId: orderId]]).call().fact
    }

    Map evaluate(Map fact) {
        ec.message.clearAll()
        Map result = ec.service.sync().name("ai.rules.RuleServices.evaluate#RuleSet")
                .parameters([ruleSetId: "HAS_DIGITAL_GOOD", facts: [[factTypeId: "salesOrder", fact: fact]]]).call()
        Map evaluated = [outcomes: result?.outcomes, errors: ec.message.errors.collect()]
        ec.message.clearAll()
        return evaluated
    }

    def "a sales order fact holds one item fact per order item, with its product type"() {
        when:
        Map fact = buildOrder(digitalOrderId)
        List<EntityValue> orderItems = ec.entity.find("org.apache.ofbiz.order.order.OrderItem").condition("orderId", digitalOrderId).list()

        then:
        digitalOrderId != null
        fact.orderId == digitalOrderId
        fact.items.size() == orderItems.size()
        fact.items*.orderItemSeqId as Set == orderItems*.orderItemSeqId as Set
        fact.items.every { it.productTypeId == ec.entity.find("org.apache.ofbiz.product.product.Product")
                .condition("productId", it.productId).one().productTypeId }
        fact.items.any { it.productTypeId == "DIGITAL_GOOD" }
    }

    def "a decision reads its direct fact types and the item types of their lists"() {
        when:
        Map digital = ec.service.sync().name("ai.rules.RuleServices.get#DecisionFactTypes").parameters([ruleSetId: "HAS_DIGITAL_GOOD"]).call()
        Map switchOn = ec.service.sync().name("ai.rules.RuleServices.get#DecisionFactTypes").parameters([ruleSetId: "SWITCH_ON_A_JOB"]).call()

        then:
        digital.directFactTypeIds == ["salesOrder"]
        digital.factTypeIds == ["salesOrder", "orderItem"]
        switchOn.directFactTypeIds == ["checkResult"]
        switchOn.factTypeIds == ["checkResult"]
    }

    def "an order with a digital good item matches the exists rule"() {
        when:
        Map evaluated = evaluate(buildOrder(digitalOrderId))

        then:
        evaluated.errors.isEmpty()
        evaluated.outcomes == ["HAS_DIGITAL"]
    }

    def "an order without one does not"() {
        when:
        Map fact = buildOrder(plainOrderId)
        Map evaluated = evaluate(fact)

        then:
        plainOrderId != null
        fact.items.size() > 0
        fact.items.every { it.productTypeId != "DIGITAL_GOOD" }
        evaluated.errors.isEmpty()
        evaluated.outcomes == ["NO_DIGITAL"]
    }

    def "an order with no items does not match"() {
        when:
        Map evaluated = evaluate([orderId: "NO_ITEMS", salesChannelEnumId: "WEB_SALES_CHANNEL", items: []])

        then:
        evaluated.errors.isEmpty()
        evaluated.outcomes == ["NO_DIGITAL"]
    }

    def "a passed-in order is refused with every problem of its items"() {
        when:
        Map evaluated = evaluate([orderId: "T9", salesChannelEnumId: "WEB_SALES_CHANNEL",
                items: [[orderItemSeqId: "01", productTypeId: "DIGITAL_GOOD"], [orderItemSeqId: "02", quantity: "abc"], "not a fact"]])

        then:
        evaluated.outcomes == null
        evaluated.errors == ["Fact type orderItem has no field quantity", "Item 3 of field items of salesOrder must be a fact"]
    }

    def "an item line outside an exists line is refused"() {
        when:
        ec.message.clearAll()
        ec.service.sync().name("ai.rules.RuleServices.store#Rule").parameters([ruleSetId: "HAS_DIGITAL_GOOD",
                ruleName: "loose item line", priority: 5,
                conditions: [[conditionSeqId: "01", conditionTypeId: "RCT_COMPARE", factTypeId: "orderItem",
                        fieldName: "productTypeId", operatorEnumId: "ENTCO_EQUALS", compareValue: "DIGITAL_GOOD"]],
                actions: [[actionTypeId: "RAT_OUTCOME", outcomeId: "HAS_DIGITAL"]]]).call()
        List<String> errors = ec.message.errors.collect()
        ec.message.clearAll()

        then:
        errors.any { it.contains("orderItem is a list item type, so its lines must sit under an exists line") }
        ec.entity.find("moqui.ai.RuleSetRule").condition("ruleSetId", "HAS_DIGITAL_GOOD").condition("ruleName", "loose item line").count() == 0
    }
}
