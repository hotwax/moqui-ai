package org.moqui.ai.rules

import javax.cache.Cache
import org.moqui.context.ExecutionContext
import org.moqui.context.ExecutionContextFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class RuleSets {
    protected final static Logger logger = LoggerFactory.getLogger(RuleSets.class)
    static final String CACHE_NAME = "ai.rules.built.rule.set"

    final ExecutionContextFactory ecf

    RuleSets(ExecutionContextFactory ecf) { this.ecf = ecf }

    BuiltRuleSet builtRuleSet(String ruleSetId) {
        BuiltRuleSet built = cache().get(ruleSetId)
        return built != null ? built : buildAndCache(ruleSetId)
    }

    private synchronized BuiltRuleSet buildAndCache(String ruleSetId) {
        Cache<String, BuiltRuleSet> cache = cache()
        BuiltRuleSet built = cache.get(ruleSetId)
        if (built == null) {
            built = new RuleSetBuilder(ecf.getExecutionContext(), ruleSetId).build()
            cache.put(ruleSetId, built)
        }
        return built
    }

    private Cache<String, BuiltRuleSet> cache() {
        return ecf.getCache().getCache(CACHE_NAME)
    }

    void buildAll() {
        ExecutionContext ec = ecf.getExecutionContext()
        try {
            ec.artifactExecution.disableAuthz()
            long startTime = System.currentTimeMillis()
            List<String> ruleSetIds = ec.entity.find("moqui.ai.RuleSet").list()*.ruleSetId
            int builtCount = 0
            for (String ruleSetId in ruleSetIds) {
                try {
                    builtRuleSet(ruleSetId)
                    builtCount++
                } catch (Throwable t) {
                    logger.error("Could not build decision ${ruleSetId}", t)
                }
            }
            logger.info("Built ${builtCount} of ${ruleSetIds.size()} decisions in ${System.currentTimeMillis() - startTime} ms")
        } finally {
            ec.destroy()
        }
    }
}
