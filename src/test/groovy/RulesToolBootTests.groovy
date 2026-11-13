import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.ai.rules.RuleSets

class RulesToolBootTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() { ec = Moqui.getExecutionContext() }
    def cleanupSpec() { if (ec != null) ec.destroy() }

    def "every decision is built when the server starts"() {
        when:
        List<String> builtRuleSetIds = ec.cache.getCache(RuleSets.CACHE_NAME).collect { it.key }

        then:
        ec.getTool("Rules", RuleSets) != null
        builtRuleSetIds.containsAll(["SWITCH_ON_A_JOB", "HAS_DIGITAL_GOOD", "HOLD_TASK_GROUP", "HOLD_TASK_RESOLVER"])
    }
}
