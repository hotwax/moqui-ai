package org.moqui.ai

import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ToolFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Registers `ec.factory.getTool("AI", AiToolFactory.class)`. Singleton holds the
 *  provider registry and the in-memory tool catalog. Mirrors moqui-kie's KieToolFactory. */
class AiToolFactory implements ToolFactory<AiToolFactory> {
    protected final static Logger logger = LoggerFactory.getLogger(AiToolFactory.class)

    protected ExecutionContextFactory ecf = null

    /** No-arg constructor required by the ToolFactory SPI. */
    AiToolFactory() { }

    @Override String getName() { return "AI" }

    @Override void init(ExecutionContextFactory ecf) {
        this.ecf = ecf
        logger.info("AiToolFactory initialized")
    }

    @Override AiToolFactory getInstance(Object... parameters) {
        if (ecf == null) throw new IllegalStateException("AiToolFactory not initialized")
        return this
    }

    @Override void destroy() {
        logger.info("AiToolFactory destroyed")
    }
}
