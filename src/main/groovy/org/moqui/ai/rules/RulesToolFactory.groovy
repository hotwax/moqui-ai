package org.moqui.ai.rules

import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ToolFactory

class RulesToolFactory implements ToolFactory<RuleSets> {
    private RuleSets ruleSets

    @Override
    void init(ExecutionContextFactory ecf) {
        ruleSets = new RuleSets(ecf)
        ruleSets.buildAll()
    }

    @Override
    RuleSets getInstance(Object... parameters) { return ruleSets }
}
