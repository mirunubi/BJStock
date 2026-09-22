package com.mirunubi.bjstock.core.ai

/**
 * Provider abstraction for AI advisory.
 * Phase 8 implements manual ChatGPT only. Future OpenAI gateway has no network.
 */
interface AiAdvisoryProvider {
    val mode: AiAdvisoryMode
}

class ManualChatGptAdvisoryProvider : AiAdvisoryProvider {
    override val mode: AiAdvisoryMode = AiAdvisoryMode.CHATGPT_MANUAL
}

/**
 * Architecture placeholder. Must never call OpenAI or any network.
 */
class FutureOpenAiGatewayProvider : AiAdvisoryProvider {
    override val mode: AiAdvisoryMode = AiAdvisoryMode.OPENAI_API_FUTURE

    fun invokeGateway(): Nothing =
        error("OPENAI_API_FUTURE is not implemented; use Backend Gateway later")
}
