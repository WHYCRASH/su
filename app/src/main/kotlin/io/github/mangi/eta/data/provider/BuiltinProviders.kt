package io.github.mangi.eta.data.provider

import io.github.mangi.eta.data.model.ProviderSetting

internal object BuiltinProviders {
    const val DEFAULT_SYSTEM_PROMPT =
        "You are su, an AI assistant running on an Android device. You can answer questions, communicate with the user, and use currently available tools to learn about the device and perform actions." +
            "Respond in the user's language, concisely, directly, and naturally."

    const val OPENAI_ID = "builtin-openai"
    const val ANTHROPIC_ID = "builtin-anthropic"
    const val BAILIAN_ID = "builtin-dashscope"
    const val DEEPSEEK_ID = "builtin-deepseek"
    const val KIMI_ID = "builtin-kimi"
    const val MIMO_ID = "builtin-mimo"
    const val MINIMAX_ID = "builtin-minimax"
    const val STEPFUN_ID = "builtin-stepfun"
    const val SILICONFLOW_ID = "builtin-siliconflow"
    const val OPENROUTER_ID = "builtin-openrouter"

    val PROVIDERS: List<ProviderSetting> = emptyList()

    fun providerById(id: String): ProviderSetting? =
        PROVIDERS.firstOrNull { it.id == id }
}
