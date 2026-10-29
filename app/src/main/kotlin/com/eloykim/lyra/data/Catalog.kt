package com.eloykim.lyra.data

/**
 * 内置的模型目录种子（2026-10）。
 *
 * 这只是「出厂数据」，不是真相来源：表里的值随时可以被 refreshCatalog() 覆盖。
 * 价格单位：美元 / 1M tokens；拿不到公开价的填 null，不编造。
 */
internal object Catalog {

    const val VERSION = 4

    data class SeedProvider(
        val id: String,
        val label: String,
        val baseUrl: String?,
        val docsUrl: String?,
        val sort: Int,
    )

    data class SeedModel(
        val providerId: String,
        val id: String,
        val label: String,
        val contextWindow: Int?,
        val supportsTools: Boolean = true,
        val supportsVision: Boolean = false,
        val inputPrice: Double? = null,
        val outputPrice: Double? = null,
        val deprecated: Boolean = false,
        val sort: Int = 0,
    )

    val providers = listOf(
        SeedProvider("openai", "OpenAI", "https://api.openai.com/v1", "https://platform.openai.com", 0),
        SeedProvider("anthropic", "Anthropic", "https://api.anthropic.com", "https://docs.anthropic.com", 1),
        SeedProvider("gemini", "Google Gemini", "https://generativelanguage.googleapis.com", "https://ai.google.dev", 2),
        SeedProvider("deepseek", "DeepSeek", "https://api.deepseek.com", "https://api-docs.deepseek.com", 3),
        SeedProvider("qwen", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "https://help.aliyun.com/zh/model-studio", 4),
        SeedProvider("moonshot", "Kimi", "https://api.moonshot.cn/v1", "https://platform.moonshot.cn", 5),
        SeedProvider("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "https://open.bigmodel.cn", 6),
        SeedProvider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/models", 7),
        SeedProvider("local", "本地 / 自建", null, null, 8),
    )

    val models = listOf(
        // OpenAI
        SeedModel("openai", "gpt-5", "GPT-5", 128_000, supportsVision = true, inputPrice = 5.0, outputPrice = 25.0, sort = 0),
        SeedModel("openai", "gpt-5-mini", "GPT-5 mini", 128_000, supportsVision = true, inputPrice = 1.0, outputPrice = 0.4, sort = 1),
        SeedModel("openai", "gpt-4o", "GPT-4o", 128_000, supportsVision = true, inputPrice = 2.5, outputPrice = 10.0, sort = 2),
        SeedModel("openai", "gpt-4o-mini", "GPT-4o mini", 128_000, supportsVision = true, inputPrice = 0.15, outputPrice = 0.6, sort = 3),
        SeedModel("openai", "o3-mini", "o3-mini", 128_000, inputPrice = 2.2, outputPrice = 4.4, sort = 4),

        // Anthropic
        SeedModel("anthropic", "claude-opus-4.5", "Claude Opus 4.5", 200_000, supportsVision = true, inputPrice = 2.5, outputPrice = 12.5, sort = 0),
        SeedModel("anthropic", "claude-sonnet-4.5", "Claude Sonnet 4.5", 200_000, supportsVision = true, inputPrice = 1.5, outputPrice = 7.5, sort = 1),
        SeedModel("anthropic", "claude-haiku-4", "Claude Haiku 4", 200_000, supportsVision = true, inputPrice = 0.5, outputPrice = 2.5, sort = 2),

        // Google
        SeedModel("gemini", "gemini-2.5-pro", "Gemini 2.5 Pro", 2_000_000, supportsVision = true, inputPrice = 2.5, outputPrice = 5.0, sort = 0),
        SeedModel("gemini", "gemini-2.0-flash-exp", "Gemini 2.0 Flash (exp)", 1_000_000, supportsVision = true, inputPrice = 0.0, outputPrice = 0.0, sort = 1),
        SeedModel("gemini", "gemini-1.5-flash", "Gemini 1.5 Flash", 1_000_000, supportsVision = true, inputPrice = 0.075, outputPrice = 0.3, sort = 2),

        // DeepSeek
        SeedModel("deepseek", "deepseek-flash", "DeepSeek Flash", 256_000, inputPrice = 0.1, outputPrice = 0.3, sort = -1),
        SeedModel("deepseek", "deepseek-v4.1-flash", "DeepSeek V4.1 Flash", 256_000, inputPrice = 0.1, outputPrice = 0.3, sort = 0),
        SeedModel("deepseek", "deepseek-v4.1", "DeepSeek V4.1", 256_000, inputPrice = 0.6, outputPrice = 1.8, sort = 1),
        SeedModel("deepseek", "deepseek-chat", "DeepSeek Chat (旧)", 128_000, inputPrice = 0.21, outputPrice = 0.28, deprecated = true, sort = 9),
        SeedModel("deepseek", "deepseek-reasoner", "DeepSeek Reasoner (旧)", 128_000, inputPrice = 0.84, outputPrice = 0.28, deprecated = true, sort = 10),

        // 阿里通义（价格未公开统一口径，留空）
        SeedModel("qwen", "qwen-max", "Qwen Max", 128_000, sort = 0),
        SeedModel("qwen", "qwen-plus", "Qwen Plus", 128_000, sort = 1),
        SeedModel("qwen", "qwen-turbo", "Qwen Turbo", 128_000, sort = 2),

        // 月之暗面
        SeedModel("moonshot", "kimi-k2", "Kimi K2", 256_000, sort = 0),
        SeedModel("moonshot", "moonshot-v1-8k", "Moonshot v1 8K", 8_000, sort = 1),

        // 智谱
        SeedModel("zhipu", "glm-4.6", "GLM-4.6", 200_000, sort = 0),
        SeedModel("zhipu", "glm-4-flash", "GLM-4 Flash", 128_000, sort = 1),

        // 聚合
        SeedModel("openrouter", "openrouter/auto", "Auto (自动路由)", null, sort = 0),

        // 本地
        SeedModel("local", "llama3.1", "Llama 3.1 (本地)", 128_000, sort = 0),
        SeedModel("local", "qwen2.5", "Qwen 2.5 (本地)", 128_000, sort = 1),
    )
}
