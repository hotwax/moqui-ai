import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.entity.EntityCondition

class FactTreeRefusalTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String logEntity = "co.hotwax.datamanager.DataManagerLog"
    @Shared List<String> testFactTypeIds = ["RULES_TEST_NO_ENTITY", "RULES_TEST_NO_CONDITION_FIELD", "RULES_TEST_NO_FACT_FIELD",
            "RULES_TEST_NOT_PLAIN", "RULES_TEST_FIXED_TEXT", "RULES_TEST_NOT_A_LIST", "RULES_TEST_SELF", "RULES_TEST_LOOP_A",
            "RULES_TEST_LOOP_B", "RULES_TEST_DATES_NO_FIELDS", "RULES_TEST_DATES_ON_VALUE", "RULES_TEST_AGE_NOT_DATE",
            "RULES_TEST_AGE_NOT_INTEGER"]

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.user.internalLoginUser("john.doe")
        ec.artifactExecution.disableAuthz()
        removeTestFactTypes()
        for (String factTypeId in testFactTypeIds) create("FactType", [factTypeId: factTypeId])

        create("FactNode", [factTypeId: "RULES_TEST_NO_ENTITY", nodeSeqId: "01", sequenceNum: 1, nodeTypeEnumId: "FNT_RECORD",
                entityName: "NoSuchEntity"])

        create("FactNode", [factTypeId: "RULES_TEST_NO_CONDITION_FIELD", nodeSeqId: "01", sequenceNum: 1, nodeTypeEnumId: "FNT_RECORD",
                entityName: logEntity])
        create("FactNodeCondition", [factTypeId: "RULES_TEST_NO_CONDITION_FIELD", nodeSeqId: "01", conditionSeqId: "01",
                entityFieldName: "noSuchField", operatorEnumId: "ENTCO_EQUALS", parameterName: "logId"])

        create("FactNode", [factTypeId: "RULES_TEST_NO_FACT_FIELD", nodeSeqId: "01", sequenceNum: 1, nodeTypeEnumId: "FNT_VALUE",
                parameterName: "logId"])

        create("FactField", [factTypeId: "RULES_TEST_NOT_PLAIN", fieldName: "logId", valueTypeEnumId: "FVT_TEXT"])
        create("FactNode", [factTypeId: "RULES_TEST_NOT_PLAIN", nodeSeqId: "01", sequenceNum: 1, nodeTypeEnumId: "FNT_VALUE",
                fieldName: "logId", parameterName: "logId; System.exit(0)"])

        create("FactField", [factTypeId: "RULES_TEST_FIXED_TEXT", fieldName: "note", valueTypeEnumId: "FVT_TEXT"])
        create("FactNode", [factTypeId: "RULES_TEST_FIXED_TEXT", nodeSeqId: "01", sequenceNum: 1, nodeTypeEnumId: "FNT_VALUE",
                fieldName: "note", fixedValue: '${ec.user.username}'])

        create("FactField", [factTypeId: "RULES_TEST_NOT_A_LIST", fieldName: "logs", valueTypeEnumId: "FVT_TEXT"])
        create("FactNode", [factTypeId: "RULES_TEST_NOT_A_LIST", nodeSeqId: "01", sequenceNum: 1, nodeTypeEnumId: "FNT_LIST",
                fieldName: "logs", entityName: logEntity])

        create("FactField", [factTypeId: "RULES_TEST_SELF", fieldName: "children", valueTypeEnumId: "FVT_LIST", itemFactTypeId: "RULES_TEST_SELF"])
        create("FactNode", [factTypeId: "RULES_TEST_SELF", nodeSeqId: "01", sequenceNum: 1, nodeTypeEnumId: "FNT_LIST",
                fieldName: "children", entityName: logEntity])

        create("FactField", [factTypeId: "RULES_TEST_DATES_NO_FIELDS", fieldName: "logCount", valueTypeEnumId: "FVT_INTEGER"])
        create("FactNode", [factTypeId: "RULES_TEST_DATES_NO_FIELDS", nodeSeqId: "01", sequenceNum: 1, nodeTypeEnumId: "FNT_COUNT",
                fieldName: "logCount", entityName: logEntity, filterByDate: "Y"])

        create("FactField", [factTypeId: "RULES_TEST_DATES_ON_VALUE", fieldName: "logId", valueTypeEnumId: "FVT_TEXT"])
        create("FactNode", [factTypeId: "RULES_TEST_DATES_ON_VALUE", nodeSeqId: "01", sequenceNum: 1, nodeTypeEnumId: "FNT_VALUE",
                fieldName: "logId", parameterName: "logId", filterByDate: "Y"])

        for (String factTypeId in ["RULES_TEST_AGE_NOT_DATE", "RULES_TEST_AGE_NOT_INTEGER"])
            create("FactNode", [factTypeId: factTypeId, nodeSeqId: "01", sequenceNum: 1, nodeTypeEnumId: "FNT_RECORD", entityName: logEntity])
        create("FactField", [factTypeId: "RULES_TEST_AGE_NOT_DATE", fieldName: "logAgeDays", valueTypeEnumId: "FVT_INTEGER"])
        create("FactNode", [factTypeId: "RULES_TEST_AGE_NOT_DATE", nodeSeqId: "02", parentNodeSeqId: "01", sequenceNum: 2,
                nodeTypeEnumId: "FNT_AGE", fieldName: "logAgeDays", entityFieldName: "logId"])
        create("FactField", [factTypeId: "RULES_TEST_AGE_NOT_INTEGER", fieldName: "logAge", valueTypeEnumId: "FVT_TEXT"])
        create("FactNode", [factTypeId: "RULES_TEST_AGE_NOT_INTEGER", nodeSeqId: "02", parentNodeSeqId: "01", sequenceNum: 2,
                nodeTypeEnumId: "FNT_AGE", fieldName: "logAge", entityFieldName: "createdDate"])

        create("FactField", [factTypeId: "RULES_TEST_LOOP_A", fieldName: "bees", valueTypeEnumId: "FVT_LIST", itemFactTypeId: "RULES_TEST_LOOP_B"])
        create("FactNode", [factTypeId: "RULES_TEST_LOOP_A", nodeSeqId: "01", sequenceNum: 1, nodeTypeEnumId: "FNT_LIST",
                fieldName: "bees", entityName: logEntity])
        create("FactField", [factTypeId: "RULES_TEST_LOOP_B", fieldName: "ays", valueTypeEnumId: "FVT_LIST", itemFactTypeId: "RULES_TEST_LOOP_A"])
        create("FactNode", [factTypeId: "RULES_TEST_LOOP_B", nodeSeqId: "01", sequenceNum: 1, nodeTypeEnumId: "FNT_LIST",
                fieldName: "ays", entityName: logEntity])
    }

    def cleanupSpec() {
        removeTestFactTypes()
        ec.artifactExecution.enableAuthz()
        ec.user.logoutUser()
        ec.destroy()
    }

    void create(String entityName, Map values) {
        ec.entity.makeValue("moqui.ai." + entityName).setAll(values).create()
    }

    void removeTestFactTypes() {
        ec.entity.find("moqui.ai.FactNodeCondition").condition("factTypeId", "in", testFactTypeIds).deleteAll()
        ec.entity.find("moqui.ai.FactNode").condition("factTypeId", "in", testFactTypeIds)
                .condition("parentNodeSeqId", EntityCondition.ComparisonOperator.IS_NOT_NULL, null).deleteAll()
        for (String entityName in ["FactNode", "FactField", "FactType"])
            ec.entity.find("moqui.ai." + entityName).condition("factTypeId", "in", testFactTypeIds).deleteAll()
    }

    Map buildFact(String factTypeId) {
        ec.message.clearAll()
        Map result = ec.service.sync().name("ai.rules.RuleServices.build#Fact")
                .parameters([factTypeId: factTypeId, parameters: [logId: "NO_SUCH_LOG"]]).call()
        Map built = [fact: result?.fact, errors: ec.message.errors.collect()]
        ec.message.clearAll()
        return built
    }

    def "a fact tree with #problem is refused"() {
        when:
        Map built = buildFact(factTypeId)

        then:
        built.fact == null
        built.errors == [expectedError]

        where:
        problem                          | factTypeId                      || expectedError
        "an unknown entity"              | "RULES_TEST_NO_ENTITY"          || "Node 01 of RULES_TEST_NO_ENTITY reads entity NoSuchEntity, which does not exist"
        "a condition on an unknown field"| "RULES_TEST_NO_CONDITION_FIELD" || "Node 01 of RULES_TEST_NO_CONDITION_FIELD has a condition on field noSuchField, which co.hotwax.datamanager.DataManagerLog does not have"
        "a value node with no field"     | "RULES_TEST_NO_FACT_FIELD"      || "Node 01 of RULES_TEST_NO_FACT_FIELD names no field to fill"
        "a caller value that is code"    | "RULES_TEST_NOT_PLAIN"          || "Node 01 of RULES_TEST_NOT_PLAIN reads caller value logId; System.exit(0), which is not a plain name"
        "a list node on a single field"  | "RULES_TEST_NOT_A_LIST"         || "Node 01 of RULES_TEST_NOT_A_LIST fills field logs, which is not a list of facts"
        "a list of itself"               | "RULES_TEST_SELF"               || "Node 01 of RULES_TEST_SELF fills field children with fact type RULES_TEST_SELF, so fact type RULES_TEST_SELF would contain itself"
        "a list that leads back to it"   | "RULES_TEST_LOOP_A"             || "Node 01 of RULES_TEST_LOOP_B fills field ays with fact type RULES_TEST_LOOP_A, so fact type RULES_TEST_LOOP_A would contain itself"
        "a date filter on dateless rows" | "RULES_TEST_DATES_NO_FIELDS"    || "Node 01 of RULES_TEST_DATES_NO_FIELDS keeps only rows current now, but co.hotwax.datamanager.DataManagerLog has no fromDate and thruDate"
        "a date filter on a value node"  | "RULES_TEST_DATES_ON_VALUE"     || "Node 01 of RULES_TEST_DATES_ON_VALUE keeps only rows current now, but it reads no rows"
        "an age of a field that is no date" | "RULES_TEST_AGE_NOT_DATE"   || "Node 02 of RULES_TEST_AGE_NOT_DATE counts days since field logId, which is not a date"
        "an age into a text field"       | "RULES_TEST_AGE_NOT_INTEGER"    || "Node 02 of RULES_TEST_AGE_NOT_INTEGER fills field logAge with an age in days, so the field must hold an integer"
    }

    def "a fixed value is kept as text and never run"() {
        when:
        Map built = buildFact("RULES_TEST_FIXED_TEXT")

        then:
        built.errors.isEmpty()
        built.fact == [note: '${ec.user.username}']
    }
}
