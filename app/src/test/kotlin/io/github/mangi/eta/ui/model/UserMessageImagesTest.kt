package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentConversationCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMessageImagesTest {
    @Test
    fun encodeKeepsLegacyStringArrayWhenSourceMatchesPreview() {
        val encoded = encodeUserMessageImages(
            previews = listOf("data:image/jpeg;base64,aaa"),
            sources = listOf("data:image/jpeg;base64,aaa"),
        )
        val decoded = decodeUserMessageImages(encoded)
        val previews = decoded.previews
        val sources = decoded.sources
        assertEquals(listOf("data:image/jpeg;base64,aaa"), previews)
        assertEquals(emptyList<String>(), sources)
        assertTrue(encoded.contains("data:image/jpeg;base64,aaa"))
        assertFalse(encoded.contains("\"source\""))
    }

    @Test
    fun encodeStoresOriginalPathAlongsidePreview() {
        val encoded = encodeUserMessageImages(
            previews = listOf("data:image/jpeg;base64,thumb"),
            sources = listOf("/cache/eta-chat-images/c1/photo.jpg"),
        )
        val decoded = decodeUserMessageImages(encoded)
        val previews = decoded.previews
        val sources = decoded.sources
        assertEquals(listOf("data:image/jpeg;base64,thumb"), previews)
        assertEquals(listOf("/cache/eta-chat-images/c1/photo.jpg"), sources)
    }

    @Test
    fun decodeLegacyStringArrayLeavesSourcesEmpty() {
        val decoded = decodeUserMessageImages("""["data:image/jpeg;base64,thumb"]""")
        val previews = decoded.previews
        val sources = decoded.sources
        assertEquals(listOf("data:image/jpeg;base64,thumb"), previews)
        assertEquals(emptyList<String>(), sources)
        assertEquals(
            "data:image/jpeg;base64,thumb",
            UserMessageUi(id = "u1", content = "View image", images = previews, imageSources = sources)
                .fullImageSourceAt(0),
        )
    }

    @Test
    fun attachesOriginalPathsFromHistoryForLegacyPreviews() {
        val history = listOf(
            AgentConversationCodec.durableMessage(
                AgentConversationCodec.userPersistedImageMessage(
                    text = "Look at this image",
                    images = listOf(
                        AgentConversationCodec.PersistedImage(
                            path = "/cache/eta-chat-images/c1/photo.jpg",
                            mimeType = "image/jpeg",
                            displayName = "chat-image-1.jpg",
                        ),
                    ),
                ),
            ),
        )
        val messages = listOf(
            UserMessageUi(
                id = "u1",
                content = "Look at this image",
                images = listOf("data:image/jpeg;base64,thumb"),
            ),
        )
        val attached = attachUserImageSources(messages, history)
        val user = attached.single() as UserMessageUi
        assertEquals(listOf("/cache/eta-chat-images/c1/photo.jpg"), user.imageSources)
        assertEquals("/cache/eta-chat-images/c1/photo.jpg", user.fullImageSourceAt(0))
    }

    @Test
    fun doesNotOverrideExplicitImageSources() {
        val history = listOf(
            AgentConversationCodec.durableMessage(
                AgentConversationCodec.userPersistedImageMessage(
                    text = "Look at this image",
                    images = listOf(
                        AgentConversationCodec.PersistedImage(
                            path = "/cache/eta-chat-images/c1/other.jpg",
                            mimeType = "image/jpeg",
                            displayName = "chat-image-1.jpg",
                        ),
                    ),
                ),
            ),
        )
        val messages = listOf(
            UserMessageUi(
                id = "u1",
                content = "Look at this image",
                images = listOf("preview"),
                imageSources = listOf("/already/original.jpg"),
            ),
        )
        val attached = attachUserImageSources(messages, history)
        assertEquals(
            listOf("/already/original.jpg"),
            (attached.single() as UserMessageUi).imageSources,
        )
    }

    @Test
    fun encodeStoresVideoKindAndDuration() {
        val encoded = encodeUserMessageImages(
            previews = listOf("data:image/jpeg;base64,thumb"),
            sources = listOf("/cache/eta-chat-images/c1/clip.mp4"),
            videoFlags = listOf(true),
            durationsMs = listOf(12_500L),
        )
        val decoded = decodeUserMessageImages(encoded)
        assertEquals(listOf("data:image/jpeg;base64,thumb"), decoded.previews)
        assertEquals(listOf("/cache/eta-chat-images/c1/clip.mp4"), decoded.sources)
        assertEquals(listOf(true), decoded.videoFlags)
        assertEquals(listOf(12_500L), decoded.durationsMs)
        assertTrue(encoded.contains("\"kind\":\"video\""))
    }
}
