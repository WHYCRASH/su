package io.github.mangi.eta.ui.components

/**
 * Creates an independent Compose state boundary for the chat stage.
 *
 * When switching conversations, LazyListState, the streaming Markdown state, and the bottom-follow task must be recreated,
 * otherwise the old conversation's list position may be reused by the new conversation.
 */
internal fun chatConversationCompositionKey(conversationId: String?): String =
    conversationId?.let { "conversation:$it" } ?: "conversation:draft"
