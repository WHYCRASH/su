package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import java.util.UUID

/** A supplement continues the original user-logic turn; the run may change while the turnId stays. */
internal object AgentContinuationBuilder {
    fun build(
        request: AgentRuntimeWire.RunRequest,
        response: AgentModelClient.ModelResponse.Text,
        supplement: String,
        newRunId: String = "run-${UUID.randomUUID()}",
        createdAt: Long = System.currentTimeMillis(),
        requestId: String = "",
        imagesJson: String = "[]",
    ): AgentRuntimeWire.RunRequest {
        val baseHistory = request.history +
            AgentModelClient.buildUserHistoryMessage(request.prompt, request.images).copy(turnId = request.effectiveTurnId) +
            response.transcript.map { message ->
                if (message.turnId.isBlank() && !io.github.mangi.eta.agent.model.AgentContextCompactor.isCompressionSummary(message))
                    message.copy(turnId = request.effectiveTurnId) else message
            }
        val handoff = request.handoff?.let { original ->
            if (original.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
                return@let original.copy(id = newRunId)
            }
            val payload = AgentUiHandoffPayload.from(original.payload)
            val nextSupplement = AgentUiHandoffPayload.Supplement(
                index = (payload.supplements.maxOfOrNull { it.index } ?: 0) + 1,
                text = supplement,
                createdAt = createdAt,
                requestId = requestId,
                imagesJson = imagesJson,
            )
            original.copy(
                id = newRunId,
                payload = AgentUiHandoffPayload(
                    conversationId = payload.conversationId,
                    promptSupplement = nextSupplement,
                ).toJson(),
            )
        }
        return request.copy(
            runId = newRunId,
            turnId = request.effectiveTurnId,
            modelSessionId = request.effectiveModelSessionId,
            prompt = supplement,
            images = continuationImages(supplement, imagesJson, request.config.supportsVision, request.config.supportsVideo),
            history = baseHistory,
            handoff = handoff,
        )
    }

    private fun continuationImages(text: String, raw: String, vision: Boolean, video: Boolean): List<AgentModelClient.ModelImage> {
        if (io.github.mangi.eta.agent.model.AgentSupplementMedia.persistedImages(raw).isEmpty()) return emptyList()
        val persisted = io.github.mangi.eta.agent.model.AgentSupplementMedia.userMessage(text, raw)
        val hydrated = io.github.mangi.eta.agent.media.AgentHistoryImageHydrator.hydrate(
            AgentModelClient.ConversationMessage(role = "user", content = text,
                contentJson = persisted.getJSONArray("content").toString()), vision, video)
        val parts = org.json.JSONArray(hydrated.contentJson)
        return (0 until parts.length()).mapNotNull { index ->
            val part = parts.optJSONObject(index) ?: return@mapNotNull null
            val type = part.optString("type")
            if (type != "image_url" && type != "video_url") return@mapNotNull null
            AgentModelClient.ModelImage(reference = part.getJSONObject(type).getString("url"),
                mimeType = if (type == "video_url") "video/mp4" else "image/jpeg",
                bytes = (part.getJSONObject(type).getString("url").substringAfter("base64,", "").length.toLong() * 3 / 4).toInt(),
                source = "steering")
        }
    }

}
