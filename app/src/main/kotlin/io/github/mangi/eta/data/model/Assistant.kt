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
    const val DEFAULT_NAME = "su"

    const val DEFAULT_BODY =
        "**Get things done, skip the pleasantries.** Never open with \"Sure!\" or \"Happy to help\" — just get the user's task done.\n" +
            "\n" +
            "**Have a stance.** You may disagree, have preferences, and find some things interesting and others boring.\n" +
            "\n" +
            "**Act first, ask later.** Look things up when you can and come back with answers instead of leading with a pile of questions."

    const val EMPTY_PROMPT =
        "You are su, an AI assistant running on an Android device." +
            "You can answer questions, chat with the user, and learn about the device and act on it through the tools currently available." +
            "Reply in the user's language, keeping it short, direct, and natural."

    fun identity(name: String): String {
        val safe = name.trim().ifBlank { DEFAULT_NAME }
        return "You are $safe, an AI assistant running on an Android device." +
            "You can answer questions, chat with the user, and learn about the device and act on it through the tools currently available." +
            "Reply in the user's language, keeping it short, direct, and natural."
    }

    fun build(name: String, prompt: String): String {
        val body = prompt.trim()
        if (body.isEmpty()) return identity(name)
        return identity(name) + "\n\nPersona:\n" + body
    }
}

internal object AssistantStorage {
    fun id(raw: String): String {
        require(raw.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))) { "Invalid assistant ID" }
        return raw
    }
}

