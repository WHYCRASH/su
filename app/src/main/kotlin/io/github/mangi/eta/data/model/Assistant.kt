package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

internal object AssistantDefaults {
    val ENABLED_SKILL_IDS: List<String> = listOf(
        "self-improving-agent",
        "skill-creator",
        "skill-installer",
    )
}

@Serializable
data class AssistantProfile(
    val id: String,
    val name: String,
    val prompt: String,
    val avatarFileName: String? = null,
    val createdAt: Long = 0L,
    val memoryEnabled: Boolean = true,
    val enabledSkillIds: List<String> = AssistantDefaults.ENABLED_SKILL_IDS,
)

internal object AssistantPrompt {
    const val DEFAULT_ID = "default"
    const val DEFAULT_NAME = "Daiyu"

    const val DEFAULT_BODY =
        "**先做事，少客套。** 不要用「好的！」「很高兴为你效劳」开头，直接帮用户把事情做完。\n" +
            "\n" +
            "**要有立场。** 可以不同意、可以有偏好，也可以觉得有些事有趣、有些事无聊。\n" +
            "\n" +
            "**先行动，再提问。** 能查的先查，带答案回来，而不是先抛一堆问题。"

    const val EMPTY_PROMPT =
        "You are Daiyu, an AI assistant running on an Android device." +
            "You can answer questions, communicate, and operate the device using available tools." +
            "Respond in the user language, concisely, directly, and naturally."

    fun identity(name: String): String {
        val safe = name.trim().ifBlank { DEFAULT_NAME }
        return "You are $safe, an AI assistant running on an Android device." +
            "You can answer questions, communicate, and operate the device using available tools." +
            "Respond in the user language, concisely, directly, and naturally."
    }

    fun build(name: String, prompt: String): String {
        val body = prompt.trim()
        if (body.isEmpty()) return identity(name)
        return identity(name) + "\n\nPersona:\n" + body
    }
}

internal object AssistantStorage {
    fun id(raw: String): String {
        require(raw.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))) { "助手 ID 无效" }
        return raw
    }
}

