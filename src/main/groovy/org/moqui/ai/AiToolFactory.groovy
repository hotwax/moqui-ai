package org.moqui.ai

import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ToolFactory
import org.moqui.ai.provider.MockProvider
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Registers `ec.factory.getTool("AI", AiToolFactory.class)`. Holds the provider registry
 *  and the immutable, file-defined tool catalog (rebuilt on reload). Agents/knowledge are
 *  NOT held here — they are read from entities per run (see AgentRunner). */
class AiToolFactory implements ToolFactory<AiToolFactory> {
    protected final static Logger logger = LoggerFactory.getLogger(AiToolFactory.class)

    protected ExecutionContextFactory ecf = null
    private volatile Map<String, Map> toolCatalog = [:]
    private final Map<String, LlmProvider> providers = [:]

    AiToolFactory() { }

    @Override String getName() { return "AI" }

    @Override void init(ExecutionContextFactory ecf) {
        this.ecf = ecf
        // Register providers. Mock is always available (no config). Real providers added in Task 9.
        registerProvider(new MockProvider())
        // Fail-loud at boot: a bad service ref in any ai/*.tools.xml stops startup.
        this.toolCatalog = DefinitionLoader.loadCatalog(ecf)
        logger.info("AiToolFactory initialized: ${toolCatalog.size()} tools, ${providers.size()} providers")
    }

    @Override AiToolFactory getInstance(Object... parameters) {
        if (ecf == null) throw new IllegalStateException("AiToolFactory not initialized")
        return this
    }

    @Override void destroy() { logger.info("AiToolFactory destroyed") }

    // ---- provider registry ----
    void registerProvider(LlmProvider p) { providers.put(p.name, p) }
    LlmProvider getProvider(String name) {
        LlmProvider p = providers.get(name)
        if (p == null) throw new IllegalArgumentException("No AI provider registered: ${name}")
        return p
    }

    // ---- tool catalog (each entry is a toolDef Map) ----
    Map getTool(String serviceName) { return toolCatalog.get(serviceName) }
    Map<String, Map> getToolCatalog() { return toolCatalog }

    /** Reload the catalog from disk. On any validation error, keep the last-good catalog and rethrow. */
    void reloadCatalog() { this.toolCatalog = DefinitionLoader.loadCatalog(ecf) }

    /** Test/util helper: validate + merge a <tools> snippet (used by tests; fail-loud). */
    void loadToolsFromText(String toolsXml) {
        Map<String, Map> merged = new LinkedHashMap<>(toolCatalog)
        DefinitionLoader.parseToolsNode(ecf, MNode.parseText("tools.xml", toolsXml), merged)
        this.toolCatalog = merged
    }

    ExecutionContextFactory getEcf() { return ecf }
}
