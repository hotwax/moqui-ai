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
        // Register providers. Mock is always available (no config).
        registerProvider(new MockProvider())
        // Provider-init failure isolation: register a real provider only when its key is configured,
        // and never let provider setup break boot. Read config via System props/env (no ec exists
        // yet at ToolFactory.init, so ecf.resource.expand is unavailable here).
        try {
            String anthKey = prop("ai_anthropic_key")
            if (anthKey) {
                registerProvider(new org.moqui.ai.provider.AnthropicProvider(anthKey,
                    prop("ai_anthropic_base_url") ?: "https://api.anthropic.com",
                    prop("ai_anthropic_version") ?: "2023-06-01",
                    (prop("ai_timeout_seconds") ?: "60") as int))
                logger.info("AI: registered Anthropic provider")
            }
        } catch (Throwable t) { logger.warn("AI: skipped Anthropic provider init: ${t.message}") }
        // Fail-loud at boot: a bad service ref in any ai/*.tools.xml stops startup.
        this.toolCatalog = DefinitionLoader.loadCatalog(ecf)
        logger.info("AiToolFactory initialized: ${toolCatalog.size()} tools, ${providers.size()} providers")
    }

    @Override AiToolFactory getInstance(Object... parameters) {
        if (ecf == null) throw new IllegalStateException("AiToolFactory not initialized")
        return this
    }

    @Override void destroy() { logger.info("AiToolFactory destroyed") }

    /** Read a config value from System property then environment (Moqui default-property exposes both). */
    private static String prop(String name) {
        String v = System.getProperty(name)
        if (v == null || v.isEmpty()) v = System.getenv(name)
        return (v == null || v.isEmpty()) ? null : v
    }

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
