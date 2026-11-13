import java.sql.Timestamp
import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui

class FactValueTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.user.internalLoginUser("john.doe")
        ec.artifactExecution.disableAuthz()
    }

    def cleanupSpec() {
        ec.artifactExecution.enableAuthz()
        ec.user.logoutUser()
        ec.destroy()
    }

    Map convert(String valueTypeEnumId, Object value) {
        ec.service.sync().name("ai.rules.FactValueServices.convert#FactValue")
                .parameters([valueTypeEnumId: valueTypeEnumId, value: value]).call()
    }

    def "#valueTypeEnumId turns #value into #expected"() {
        when:
        Map converted = convert(valueTypeEnumId, value)

        then:
        converted.problem == null
        converted.convertedValue == expected
        converted.convertedValue?.getClass() == expected?.getClass()

        where:
        valueTypeEnumId | value       || expected
        "FVT_TEXT"      | 12          || "12"
        "FVT_INTEGER"   | "12"        || 12L
        "FVT_INTEGER"   | " 12 "      || 12L
        "FVT_INTEGER"   | 12          || 12L
        "FVT_INTEGER"   | "12.0"      || 12L
        "FVT_DECIMAL"   | "40.50"     || new BigDecimal("40.50")
        "FVT_DECIMAL"   | 3           || new BigDecimal("3")
        "FVT_BOOLEAN"   | "Y"         || true
        "FVT_BOOLEAN"   | "False"     || false
        "FVT_BOOLEAN"   | true        || true
        "FVT_INTEGER"   | null        || null
    }

    def "#valueTypeEnumId refuses #value"() {
        when:
        Map converted = convert(valueTypeEnumId, value)

        then:
        converted.convertedValue == null
        converted.problem == expectedProblem

        where:
        valueTypeEnumId | value          || expectedProblem
        "FVT_INTEGER"   | "12.5"         || "12.5 is not a whole number"
        "FVT_INTEGER"   | "abc"          || "abc is not a whole number"
        "FVT_INTEGER"   | 12.5           || "12.5 is not a whole number"
        "FVT_DECIMAL"   | "abc"          || "abc is not a number"
        "FVT_BOOLEAN"   | "abc"          || "abc is not yes or no"
        "FVT_BOOLEAN"   | "yes"          || "yes is not yes or no"
        "FVT_DATE_TIME" | "not a date"   || "not a date is not a date and time"
        "FVT_LIST"      | "[]"           || "value kind FVT_LIST does not hold a single value"
        "FNT_RECORD"    | "x"            || "value kind FNT_RECORD does not hold a single value"
    }

    def "a date and time is read by Moqui's own parser"() {
        when:
        Map converted = convert("FVT_DATE_TIME", "2026-11-03 10:15:00")

        then:
        converted.problem == null
        converted.convertedValue instanceof Timestamp
        converted.convertedValue == ec.l10n.parseTimestamp("2026-11-03 10:15:00", null)
    }

    def "a column's kind is guessed in sequenceNum order, with text when nothing fits"() {
        expect:
        ec.service.sync().name("ai.rules.FactValueServices.guess#FactValueType").parameters([values: values]).call()
                .valueTypeEnumId == expected

        where:
        values                    || expected
        ["12", "7"]               || "FVT_INTEGER"
        ["40.50", "12"]           || "FVT_DECIMAL"
        ["y", "N"]                || "FVT_BOOLEAN"
        ["2026-11-03 10:15:00"]   || "FVT_DATE_TIME"
        ["12", "abc"]             || "FVT_TEXT"
        [null, " "]               || "FVT_TEXT"
    }
}
