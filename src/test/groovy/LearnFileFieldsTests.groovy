import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui

class LearnFileFieldsTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String refundsFactTypeId = "RULES_TEST_REFUNDS"
    @Shared String shipmentsFactTypeId = "RULES_TEST_SHIPMENTS"
    @Shared String refusedFactTypeId = "RULES_TEST_REFUSED_FILE"

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.user.internalLoginUser("john.doe")
        ec.artifactExecution.disableAuthz()
        removeTestKinds()
    }

    def cleanupSpec() {
        removeTestKinds()
        ec.artifactExecution.enableAuthz()
        ec.user.logoutUser()
        ec.destroy()
    }

    void removeTestKinds() {
        for (String factTypeId in [refundsFactTypeId, shipmentsFactTypeId, refusedFactTypeId]) {
            ec.entity.find("moqui.ai.FactField").condition("factTypeId", factTypeId).deleteAll()
            ec.entity.find("moqui.ai.FactType").condition("factTypeId", factTypeId).deleteAll()
        }
    }

    Map learn(String factTypeId, String fileName, String fileText) {
        Map result = ec.service.sync().name("ai.rules.RuleServices.learn#FileFields")
                .parameters([factTypeId: factTypeId, fileName: fileName, fileText: fileText]).call()
        Map learned = [newFieldNames: result?.newFieldNames,
                fields: result?.fields?.collectEntries { [(it.fieldName): it.valueTypeEnumId] },
                errors: ec.message.errors.collect()]
        ec.message.clearAll()
        return learned
    }

    def "a new kind of file gets one field per column, each with the kind of value its values fit"() {
        when:
        Map learned = learn(refundsFactTypeId, "refunds.csv", "orderId,amount,reason\n100234,25.00,damaged\n100235,40.50,late\n")

        then:
        learned.errors == []
        learned.newFieldNames == ["orderId", "amount", "reason"]
        learned.fields == [amount: "FVT_DECIMAL", orderId: "FVT_INTEGER", reason: "FVT_TEXT"]
        ec.entity.find("moqui.ai.FactType").condition("factTypeId", refundsFactTypeId).count() == 1
    }

    def "the same kind with a new column adds only that column and keeps the fields it has"() {
        when:
        Map learned = learn(refundsFactTypeId, "refunds-week-2.csv", "orderId,amount,reason,currency\n100236,abc,lost,USD\n")

        then:
        learned.errors == []
        learned.newFieldNames == ["currency"]
        learned.fields == [amount: "FVT_DECIMAL", currency: "FVT_TEXT", orderId: "FVT_INTEGER", reason: "FVT_TEXT"]
    }

    def "a JSON file gets its fields too, and an empty value does not decide the kind"() {
        when:
        Map learned = learn(shipmentsFactTypeId, "shipments.json",
                '[{"sku": "A1", "quantity": 2, "giftWrap": true, "shippedAt": "2026-11-01 10:00:00", "note": ""},' +
                ' {"sku": "B7", "quantity": 1, "giftWrap": false, "shippedAt": "2026-11-02 16:30:00", "note": "fragile"}]')

        then:
        learned.errors == []
        learned.fields == [giftWrap: "FVT_BOOLEAN", note: "FVT_TEXT", quantity: "FVT_INTEGER", shippedAt: "FVT_DATE_TIME", sku: "FVT_TEXT"]
    }

    def "yes or no values in any capital letters are learned as boolean, and other words as text"() {
        when:
        Map learned = learn(shipmentsFactTypeId, "shipments-week-2.csv", "giftWrap,signed,priority\nTRUE,y,maybe\nfalse,N,high\n")

        then:
        learned.errors == []
        learned.newFieldNames == ["signed", "priority"]
        learned.fields.giftWrap == "FVT_BOOLEAN"
        learned.fields.signed == "FVT_BOOLEAN"
        learned.fields.priority == "FVT_TEXT"
    }

    def "a file that cannot be learned is refused and records nothing"() {
        when:
        Map learned = learn(refusedFactTypeId, fileName, fileText)

        then:
        learned.errors.any { it.contains(problem) }
        learned.fields == null
        ec.entity.find("moqui.ai.FactType").condition("factTypeId", refusedFactTypeId).count() == 0

        where:
        fileName        | fileText                                         | problem
        "empty.csv"     | "orderId,amount\n"                               | "has no records"
        "refunds.txt"   | "orderId,amount\n100234,25.00\n"                 | "is not a CSV or JSON file"
        "nested.json"   | '[{"orderId": 100234, "items": [{"sku": "A1"}]}]' | "holds more than one value in items"
        "broken.json"   | '[{"orderId": '                                  | "could not be read"
    }
}
