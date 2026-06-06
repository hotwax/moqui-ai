package org.moqui.ai

import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ToolFactory
import org.moqui.ai.provider.MockProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Registers `ec.factory.getTool("AI", AiToolFactory.class)`. Holds the provider registry
 *  and the in-memory tool catalog BUILT FROM AiTool rows (DB is the source of truth — spec D3),
 *  lazy-loaded on first access and rebuildable via refreshCatalog(). Agents/knowledge are
 *  NOT held here — they are read from entities per run (see AgentRunner). */
class AiToolFactory implements ToolFactory<AiToolFactory> {
    protected final static Logger logger = LoggerFactory.getLogger(AiToolFactory.class)

    protected ExecutionContextFactory ecf = null
    private volatile Map<String, Map> toolCatalog = [:]        // keyed by toolId
    private volatile Map<String, Map> toolsByName = [:]        // toolName -> toolDef
    private volatile boolean catalogLoaded = false
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
        try {
            String openaiKey = prop("ai_openai_key")
            if (openaiKey) {
                registerProvider(new org.moqui.ai.provider.OpenAiProvider(openaiKey,
                    prop("ai_openai_base_url") ?: "https://api.openai.com/v1",
                    (prop("ai_timeout_seconds") ?: "60") as int))
                logger.info("AI: registered OpenAI provider")
            }
        } catch (Throwable t) { logger.warn("AI: skipped OpenAI provider init: ${t.message}") }
        // Catalog is built from AiTool rows on first access (no ExecutionContext exists at
        // ToolFactory.init, so the DB read is deferred — the loop always has an ec). A bad service
        // ref is now caught at authoring time (store#AiTool) rather than at boot.
        logger.info("AiToolFactory initialized: ${providers.size()} providers (catalog lazy-loaded from AiTool rows)")
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

    // ---- tool catalog (each entry is a toolDef Map; built from AiTool rows, keyed by toolId) ----
    private void ensureCatalog() { if (!catalogLoaded) refreshCatalog() }

    /** (Re)build the catalog from AiTool rows + regenerate schemas on demand. Atomic swap. */
    synchronized void refreshCatalog() {
        Map<String, Map> byId = DefinitionLoader.loadCatalog(ecf)
        Map<String, Map> byName = [:]
        for (Map td in byId.values()) byName.put(td.toolName as String, td)
        this.toolCatalog = byId; this.toolsByName = byName; this.catalogLoaded = true
        logger.info("AiToolFactory catalog refreshed: ${byId.size()} active+exposable tools")
    }

    Map getToolById(String toolId) { ensureCatalog(); return toolCatalog.get(toolId) }
    Map getToolByName(String toolName) { ensureCatalog(); return toolsByName.get(toolName) }
    Map<String, Map> getToolCatalog() { ensureCatalog(); return toolCatalog }

    ExecutionContextFactory getEcf() { return ecf }
}
