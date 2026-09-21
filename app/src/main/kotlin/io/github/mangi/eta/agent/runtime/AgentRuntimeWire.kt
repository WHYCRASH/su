package io.github.mangi.eta.agent.runtime

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentContextCompactor
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import java.io.Closeable
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * AgentRuntime cross-process communication protocol.
 *
 * The entry process communicates with [AgentRuntimeService] in the module's own process via bind + Messenger:
 * it sends one run request and receives an event stream and the final result.
 *
 * AIDL is not introduced: structured fields use [Bundle], while image bodies, session history, and result transcripts all use [ParcelFileDescriptor], to avoid occupying the Binder transaction buffer.
 */
internal object AgentRuntimeWire {
    const val AGENT_UI_HANDOFF_SOURCE = "agent_ui"
    const val ETA_VOICE_HANDOFF_SOURCE = "eta_voice"

    internal class PayloadTooLargeException(sizeBytes: Int) : IllegalArgumentException(
        "Agent Runtime request metadata is too large ($sizeBytes bytes); shorten the input or session history and try again"
    )

    /** Intent action used by bind to obtain the server-side Messenger. */
    const val ACTION_BIND = "io.github.mangi.eta.agent.runtime.BIND"

    // Messenger.what
    /** client -> service: starts an Agent run; [Message.replyTo] carries the client Messenger. */
    const val MSG_START_RUN = 1

    /** service -> client: pushes an [AgentEvent]. */
    const val MSG_EVENT = 2

    /** service -> client: the final result. */
    const val MSG_RESULT = 3

    /** client -> service: cancels the current run. */
    const val MSG_CANCEL = 4

    /** client -> service: acknowledges that a final result has been successfully displayed by the entry layer. */
    const val MSG_ACK_RESULT = 5

    /** client -> service: fetches final results that have not yet been acknowledged as displayed by the entry layer. */
    const val MSG_DRAIN_RESULTS = 6

    /** service -> client: returns a set of final results that have not yet been acknowledged as displayed. */
    const val MSG_DRAIN_RESULTS_RESPONSE = 7

    /** service -> client: the requested image has been ingested; the entry process can close the file descriptor and delete the temporary file. */
    const val MSG_REQUEST_INGESTED = 8

    /** client -> service: queries the run that is currently still executing or committing its terminal state. */
    const val MSG_QUERY_ACTIVE_RUN = 9

    /** service -> client: returns the current active runId; an empty string means none. */
    const val MSG_QUERY_ACTIVE_RUN_RESPONSE = 10

    /** client -> service: resubscribes to the specified run's safe event replay, live events, and final result. */
    const val MSG_ATTACH_RUN = 11

    /** service -> client: returns whether resubscription to the specified run succeeded. */
    const val MSG_ATTACH_RUN_RESPONSE = 12

    /** client -> service: appends supplementary instructions to the current run. */
    const val MSG_STEER_RUN = 13

    /** client -> service: pauses the current run. */
    const val MSG_PAUSE_RUN = 14

    /** client -> service: resumes the current run. */
    const val MSG_RESUME_RUN = 15

    /** client -> service: compacts within the current run without starting a new run. */
    const val MSG_COMPACT_RUN = 16

    private const val MODULE_PACKAGE = "io.github.mangi.eta"
    private const val SERVICE_CLASS = "io.github.mangi.eta.agent.runtime.AgentRuntimeService"

    private const val KEY_TYPE = "type"
    private const val KEY_RUN_ID = "run_id"
    private const val KEY_RUN_IDS = "run_ids"
    private const val KEY_PROMPT = "prompt"
    private const val KEY_STEER_TEXT = "steer_text"
    private const val KEY_COMPACT_KEEP_RECENT = "compact_keep_recent"
    private const val KEY_MODEL_SESSION_ID = "model_session_id"
    private const val KEY_PROVIDER_ID = "provider_id"
    private const val KEY_PROVIDER_NAME = "provider_name"
    private const val KEY_PROVIDER_TYPE = "provider_type"
    private const val KEY_PROVIDER_SOURCE_TYPE = "provider_source_type"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_MODEL_DISPLAY_NAME = "model_display_name"
    private const val KEY_CONTEXT_WINDOW = "context_window"
    private const val KEY_SYSTEM_PROMPT = "system_prompt"
    private const val KEY_ANTHROPIC_VERSION = "anthropic_version"
    private const val KEY_OPENAI_ENDPOINT_MODE = "openai_endpoint_mode"
    private const val KEY_RESPONSES_STRIP_REASONING_STATUS = "responses_strip_reasoning_status"
    private const val KEY_HOSTED_WEB_SEARCH_ENABLED = "hosted_web_search_enabled"
    private const val KEY_TERMINAL_TOOLS = "terminal_tools"
    private const val KEY_BROWSER_TOOLS = "browser_tools"
    private const val KEY_DEVICE_DIRECT_TOOLS = "device_direct_tools"
    private const val KEY_DEVICE_SENSITIVE_READ_TOOLS = "device_sensitive_read_tools"
    private const val KEY_DEVICE_SENSITIVE_ACTION_TOOLS = "device_sensitive_action_tools"
    private const val KEY_THINKING_ENABLED = "thinking_enabled"
    private const val KEY_SUPPORTS_VISION = "supports_vision"
    private const val KEY_SUPPORTS_VIDEO = "supports_video"
    private const val KEY_REASONING_EFFORT = "reasoning_effort"
    private const val KEY_REASONING_CAPABILITIES_JSON = "reasoning_capabilities_json"
    private const val KEY_EXTRA_BODY_JSON = "extra_body_json"
    private const val KEY_CUSTOM_HEADERS_JSON = "custom_headers_json"
    private const val KEY_CUSTOM_BODY_JSON = "custom_body_json"
    private const val KEY_IMAGES = "images"
    internal const val KEY_HISTORY = "history"
    internal const val KEY_HISTORY_FD = "history_fd"
    internal const val KEY_CONTENT_JSON = "content_json"
    internal const val KEY_TOOL_CALL_ID = "tool_call_id"
    internal const val KEY_TOOL_CALLS_JSON = "tool_calls_json"
    internal const val KEY_ROLE = "role"
    private const val KEY_DATA_URL = "data_url"
    private const val KEY_IMAGE_URL = "image_url"
    private const val KEY_IMAGE_FD = "image_fd"
    private const val KEY_MIME_TYPE = "mime_type"
    private const val KEY_BYTES = "bytes"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"
    private const val KEY_SOURCE = "source"
    private const val KEY_OK = "ok"
    internal const val KEY_CONTENT = "content"
    internal const val KEY_REASONING_CONTENT = "reasoning_content"
    private const val KEY_ERROR = "error"
    private const val KEY_RESULT = "result"
    internal const val KEY_TRANSCRIPT_JSON = "transcript_json"
    internal const val KEY_TRANSCRIPT_FD = "transcript_fd"
    private const val KEY_HANDOFF = "handoff"
    private const val KEY_HANDOFF_ID = "handoff_id"
    private const val KEY_HANDOFF_SOURCE = "handoff_source"
    private const val KEY_HANDOFF_PAYLOAD = "handoff_payload"
    private const val KEY_HANDOFF_DISMISS_ENTRY_SURFACE_ON_FOREGROUND_OPERATION =
        "handoff_dismiss_entry_surface_on_foreground_operation"
    private const val KEY_HISTORY_ALREADY_COMPACTED = "history_already_compacted"
    private const val KEY_CREATED_AT = "created_at"
    private const val KEY_RESULTS = "results"
    private const val MAX_RESULT_CONTENT_CHARS = 64_000
    private const val MAX_RESULT_REASONING_CHARS = 32_000
    private const val MAX_DRAIN_CONTENT_CHARS = 16_000
    private const val MAX_DRAIN_REASONING_CHARS = 4_000
    private const val TRUNCATED_SUFFIX = "\n\n[Cross-process result is too long and has been truncated]"
    private const val MAX_START_REQUEST_PARCEL_BYTES = 768 * 1024

    data class RunRequest(
        val runId: String,
        val prompt: String,
        val config: AgentModelClient.ModelConfig,
        val images: List<AgentModelClient.ModelImage>,
        val history: List<AgentModelClient.ConversationMessage> = emptyList(),
        val handoff: EntryHandoff? = null,
        val historyAlreadyCompacted: Boolean = false,
        val modelSessionId: String = "",
        val assistantId: String = config.assistantId,
        val turnId: String = "",
    ) {
        val effectiveTurnId: String get() = turnId.ifBlank { runId }
        // Legacy entries continue using session handoff; entries without a persistent session use the first run as the session starting point.
        val effectiveModelSessionId: String
            get() = modelSessionId.ifBlank {
                handoff?.takeIf { it.source == AGENT_UI_HANDOFF_SOURCE }
                    ?.let { AgentUiHandoffPayload.from(it.payload).conversationId }
                    ?.takeIf { it.isNotBlank() } ?: runId
            }
    }

    /**
     * Representation of a single image at the IPC layer. Remote URLs can be placed directly in the Bundle; local or inline images pass only a read-only file descriptor.
     */
    data class WireImage(
        val remoteUrl: String? = null,
        val fileDescriptor: ParcelFileDescriptor? = null,
        val mimeType: String,
        val bytes: Int,
        val width: Int? = null,
        val height: Int? = null,
        val source: String = "unknown",
    )

    /** The receiver holds the file descriptor until image materialization completes in the background; after it is closed, it cannot be used again. */
    class IncomingRunRequest internal constructor(
        val request: RunRequest,
        val images: List<WireImage>,
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            images.forEach { image -> runCatching { image.fileDescriptor?.close() } }
        }
    }

    data class RunResult(
        val runId: String,
        val ok: Boolean,
        val content: String,
        val error: String? = null,
        val reasoningContent: String = "",
        val transcript: List<AgentModelClient.ConversationMessage> = emptyList(),
    )

    data class EntryHandoff(
        val id: String,
        val source: String,
        val payload: String,
        val dismissEntrySurfaceOnForegroundOperation: Boolean = false
    )

    data class CompletedRun(
        val handoff: EntryHandoff,
        val result: RunResult,
        val createdAt: Long
    )

    fun serviceIntent(): Intent =
        Intent(ACTION_BIND).setComponent(ComponentName(MODULE_PACKAGE, SERVICE_CLASS))

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun toBundle(request: RunRequest, images: List<WireImage>, historyDescriptor: ParcelFileDescriptor): Bundle {
        require(images.size == request.images.size) { "The number of image transfer items does not match the number of requested images" }
        val imageBundles = images.map { image ->
            require((image.remoteUrl == null) xor (image.fileDescriptor == null)) {
                "An image transfer item must contain exactly one of a remote URL or a file descriptor"
            }
            Bundle().apply {
                image.remoteUrl?.let { putString(KEY_IMAGE_URL, it) }
                image.fileDescriptor?.let { putParcelable(KEY_IMAGE_FD, it) }
                putString(KEY_MIME_TYPE, image.mimeType)
                putInt(KEY_BYTES, image.bytes)
                image.width?.let { putInt(KEY_WIDTH, it) }
                image.height?.let { putInt(KEY_HEIGHT, it) }
                putString(KEY_SOURCE, image.source)
            }
        }
        return requestBundle(request, imageBundles, historyDescriptor)
    }

    /** For compatibility with legacy clients and protocol tests; history still uses file descriptors, and the legacy data URL image method is used only as a fallback. */
    fun toLegacyBundle(request: RunRequest, historyDescriptor: ParcelFileDescriptor): Bundle = requestBundle(
        request = request,
        historyDescriptor = historyDescriptor,
        imageBundles = request.images.map { image ->
            Bundle().apply {
                putString(KEY_DATA_URL, image.reference)
                putString(KEY_MIME_TYPE, image.mimeType)
                putInt(KEY_BYTES, image.bytes)
                image.width?.let { putInt(KEY_WIDTH, it) }
                image.height?.let { putInt(KEY_HEIGHT, it) }
                putString(KEY_SOURCE, image.source)
            }
        },
    )

    private fun requestBundle(
        request: RunRequest,
        imageBundles: List<Bundle>,
        historyDescriptor: ParcelFileDescriptor,
    ): Bundle = Bundle().apply {
        putString(KEY_RUN_ID, request.runId)
        putString("logical_turn_id", request.turnId)
        putString("assistant_id", request.assistantId)
        putString(KEY_PROMPT, request.prompt)
        putString(KEY_MODEL_SESSION_ID, request.modelSessionId)
        putString(KEY_PROVIDER_ID, request.config.providerId)
        putString(KEY_PROVIDER_NAME, request.config.providerName)
        putString(KEY_PROVIDER_TYPE, request.config.providerType)
        putString(KEY_PROVIDER_SOURCE_TYPE, request.config.providerSourceType)
        putString(KEY_BASE_URL, request.config.baseUrl)
        putString(KEY_API_KEY, request.config.apiKey)
        putString(KEY_MODEL, request.config.model)
        putString(KEY_MODEL_DISPLAY_NAME, request.config.modelDisplayName)
        request.config.contextWindow?.let { putInt(KEY_CONTEXT_WINDOW, it) }
        putString(KEY_SYSTEM_PROMPT, request.config.systemPrompt)
        putString(KEY_ANTHROPIC_VERSION, request.config.anthropicVersion)
        putString(KEY_OPENAI_ENDPOINT_MODE, request.config.openAiEndpointMode)
        putBoolean(KEY_RESPONSES_STRIP_REASONING_STATUS, request.config.responsesStripReasoningStatus)
        putBoolean(KEY_HOSTED_WEB_SEARCH_ENABLED, request.config.hostedWebSearchEnabled)
        putBoolean(KEY_TERMINAL_TOOLS, request.config.terminalTools)
        putBoolean(KEY_BROWSER_TOOLS, request.config.browserTools)
        putBoolean(KEY_DEVICE_DIRECT_TOOLS, request.config.deviceDirectTools)
        putBoolean(KEY_DEVICE_SENSITIVE_READ_TOOLS, request.config.deviceSensitiveReadTools)
        putBoolean(KEY_DEVICE_SENSITIVE_ACTION_TOOLS, request.config.deviceSensitiveActionTools)
        putBoolean(KEY_THINKING_ENABLED, request.config.effectiveReasoningEffort.enablesReasoning)
        putBoolean(KEY_SUPPORTS_VISION, request.config.supportsVision)
        putBoolean(KEY_SUPPORTS_VIDEO, request.config.supportsVideo)
        putString(KEY_REASONING_EFFORT, request.config.effectiveReasoningEffort.wireValue)
        request.config.reasoningCapabilities?.let {
            putString(KEY_REASONING_CAPABILITIES_JSON, json.encodeToString(it))
        }
        putString(KEY_EXTRA_BODY_JSON, request.config.extraBodyJson)
        putString(KEY_CUSTOM_HEADERS_JSON, json.encodeToString(request.config.customHeaders))
        putString(KEY_CUSTOM_BODY_JSON, json.encodeToString(request.config.customBody))
        putBoolean(KEY_HISTORY_ALREADY_COMPACTED, request.historyAlreadyCompacted)
        request.handoff?.let { putBundle(KEY_HANDOFF, toBundle(it)) }
        putParcelable(KEY_HISTORY_FD, historyDescriptor)
        putParcelableArrayList(
            KEY_IMAGES,
            ArrayList(imageBundles)
        )
    }.also(::requireStartRequestWithinBinderBudget)

    fun incomingRunRequestFromBundle(bundle: Bundle): IncomingRunRequest {
        val images = mutableListOf<WireImage>()
        try {
            bundle.getParcelableArrayList(KEY_IMAGES, Bundle::class.java).orEmpty().forEach { image ->
                val descriptor = image.getParcelable(KEY_IMAGE_FD, ParcelFileDescriptor::class.java)
                val reference = image.getString(KEY_IMAGE_URL)
                    ?: image.getString(KEY_DATA_URL) // For compatibility with entry processes that still inline data URLs prior to upgrade.
                require((reference == null) xor (descriptor == null)) {
                    "An image transfer item must contain exactly one of a reference or a file descriptor"
                }
                images += WireImage(
                    remoteUrl = reference,
                    fileDescriptor = descriptor,
                    mimeType = image.getString(KEY_MIME_TYPE).orEmpty(),
                    bytes = image.getInt(KEY_BYTES),
                    width = image.optionalInt(KEY_WIDTH),
                    height = image.optionalInt(KEY_HEIGHT),
                    source = image.getString(KEY_SOURCE).orEmpty(),
                )
            }
            return IncomingRunRequest(
                request = requestFromBundle(bundle, images = emptyList()),
                images = images,
            )
        } catch (throwable: Throwable) {
            closeImageDescriptors(bundle)
            throw throwable
        }
    }

    /** Requests that are rejected or fail to parse do not enter [IncomingRunRequest]; their descriptors must be explicitly released. */
    fun closeImageDescriptors(bundle: Bundle?) {
        runCatching {
            bundle?.getParcelableArrayList(KEY_IMAGES, Bundle::class.java).orEmpty().forEach { image ->
                image.getParcelable(KEY_IMAGE_FD, ParcelFileDescriptor::class.java)?.close()
            }
        }
    }

    /** Only used for legacy protocol reads that lack file descriptors. */
    fun runRequestFromBundle(bundle: Bundle): RunRequest =
        incomingRunRequestFromBundle(bundle).use { incoming ->
            require(incoming.images.none { it.fileDescriptor != null }) {
                "Requests containing file descriptors must first be materialized in the Runtime background"
            }
            incoming.request.copy(
                images = incoming.images.map { image ->
                    AgentModelClient.ModelImage(
                        reference = image.remoteUrl.orEmpty(),
                        mimeType = image.mimeType,
                        bytes = image.bytes,
                        width = image.width,
                        height = image.height,
                        source = image.source,
                    )
                },
            )
        }

    private fun requestFromBundle(
        bundle: Bundle,
        images: List<AgentModelClient.ModelImage>,
    ): RunRequest = RunRequest(
            runId = bundle.getString(KEY_RUN_ID).orEmpty(),
            turnId = bundle.getString("logical_turn_id").orEmpty(),
            assistantId = bundle.getString("assistant_id").orEmpty(),
            prompt = bundle.getString(KEY_PROMPT).orEmpty(),
            modelSessionId = bundle.getString(KEY_MODEL_SESSION_ID).orEmpty(),
            config = AgentModelClient.ModelConfig(
                assistantId = bundle.getString("assistant_id").orEmpty(),
                providerId = bundle.getString(KEY_PROVIDER_ID).orEmpty(),
                providerName = bundle.getString(KEY_PROVIDER_NAME).orEmpty(),
                providerType = bundle.getString(KEY_PROVIDER_TYPE).orEmpty()
                    .ifBlank { io.github.mangi.eta.data.model.ProviderTypes.OPENAI_COMPATIBLE },
                providerSourceType = bundle.getString(KEY_PROVIDER_SOURCE_TYPE).orEmpty(),
                baseUrl = bundle.getString(KEY_BASE_URL).orEmpty(),
                apiKey = bundle.getString(KEY_API_KEY).orEmpty(),
                model = bundle.getString(KEY_MODEL).orEmpty(),
                modelDisplayName = bundle.getString(KEY_MODEL_DISPLAY_NAME).orEmpty(),
                contextWindow = bundle.optionalInt(KEY_CONTEXT_WINDOW),
                systemPrompt = bundle.getString(KEY_SYSTEM_PROMPT).orEmpty(),
                anthropicVersion = bundle.getString(KEY_ANTHROPIC_VERSION).orEmpty()
                    .ifBlank { io.github.mangi.eta.data.model.AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION },
                openAiEndpointMode = bundle.getString(KEY_OPENAI_ENDPOINT_MODE).orEmpty()
                    .ifBlank { io.github.mangi.eta.data.model.OpenAiEndpointMode.CHAT_COMPLETIONS },
                responsesStripReasoningStatus = bundle.getBoolean(KEY_RESPONSES_STRIP_REASONING_STATUS, false),
                hostedWebSearchEnabled = bundle.getBoolean(KEY_HOSTED_WEB_SEARCH_ENABLED, false),
                terminalTools = bundle.getBoolean(KEY_TERMINAL_TOOLS),
                browserTools = if (bundle.containsKey(KEY_BROWSER_TOOLS)) {
                    bundle.getBoolean(KEY_BROWSER_TOOLS)
                } else {
                    true
                },
                deviceDirectTools = if (bundle.containsKey(KEY_DEVICE_DIRECT_TOOLS)) {
                    bundle.getBoolean(KEY_DEVICE_DIRECT_TOOLS)
                } else {
                    true
                },
                deviceSensitiveReadTools =
                    bundle.getBoolean(KEY_DEVICE_SENSITIVE_READ_TOOLS, false),
                deviceSensitiveActionTools =
                    bundle.getBoolean(KEY_DEVICE_SENSITIVE_ACTION_TOOLS, false),
                thinkingEnabled = bundle.getBoolean(KEY_THINKING_ENABLED),
                supportsVision = bundle.getBoolean(KEY_SUPPORTS_VISION, false),
                supportsVideo = bundle.getBoolean(KEY_SUPPORTS_VIDEO, false),
                reasoningEffort = if (bundle.containsKey(KEY_REASONING_EFFORT)) {
                    ReasoningEffort.fromWireValue(bundle.getString(KEY_REASONING_EFFORT))
                        ?: ReasoningEffort.DEFAULT
                } else {
                    ReasoningEffort.fromLegacy(bundle.getBoolean(KEY_THINKING_ENABLED))
                },
                reasoningCapabilities = decodeReasoningCapabilities(
                    bundle.getString(KEY_REASONING_CAPABILITIES_JSON)
                ),
                extraBodyJson = bundle.getString(KEY_EXTRA_BODY_JSON).orEmpty(),
                customHeaders = decodeCustomHeaders(bundle.getString(KEY_CUSTOM_HEADERS_JSON)),
                customBody = decodeCustomBody(bundle.getString(KEY_CUSTOM_BODY_JSON))
            ),
            history = AgentRuntimeHistoryTransfer.readFromBundle(bundle),
            images = images,
            handoff = bundle.getBundle(KEY_HANDOFF)?.let(::entryHandoffFromBundle),
            historyAlreadyCompacted = if (bundle.containsKey(KEY_HISTORY_ALREADY_COMPACTED)) {
                bundle.getBoolean(KEY_HISTORY_ALREADY_COMPACTED)
            } else {
                false
            }
        )

    fun toBundle(handoff: EntryHandoff): Bundle = Bundle().apply {
        putString(KEY_HANDOFF_ID, handoff.id)
        putString(KEY_HANDOFF_SOURCE, handoff.source)
        putString(KEY_HANDOFF_PAYLOAD, handoff.payload)
        putBoolean(
            KEY_HANDOFF_DISMISS_ENTRY_SURFACE_ON_FOREGROUND_OPERATION,
            handoff.dismissEntrySurfaceOnForegroundOperation
        )
    }

    fun entryHandoffFromBundle(bundle: Bundle): EntryHandoff {
        val source = bundle.getString(KEY_HANDOFF_SOURCE).orEmpty()
        return EntryHandoff(
            id = bundle.getString(KEY_HANDOFF_ID).orEmpty(),
            source = source,
            payload = bundle.getString(KEY_HANDOFF_PAYLOAD).orEmpty(),
            dismissEntrySurfaceOnForegroundOperation = if (
                bundle.containsKey(KEY_HANDOFF_DISMISS_ENTRY_SURFACE_ON_FOREGROUND_OPERATION)
            ) {
                bundle.getBoolean(KEY_HANDOFF_DISMISS_ENTRY_SURFACE_ON_FOREGROUND_OPERATION)
            } else {
                false
            }
        )
    }

    fun toBundle(
        result: RunResult,
        transcriptDescriptor: ParcelFileDescriptor? = null,
    ): Bundle = result.toBundle(
        compactForDrain = false,
        transcriptDescriptor = transcriptDescriptor,
    )

    private fun RunResult.toBundle(
        compactForDrain: Boolean,
        transcriptDescriptor: ParcelFileDescriptor? = null,
    ): Bundle = Bundle().apply {
        putString(KEY_RUN_ID, runId)
        putBoolean(KEY_OK, ok)
        putString(
            KEY_CONTENT,
            content.boundedText(
                if (compactForDrain) MAX_DRAIN_CONTENT_CHARS else MAX_RESULT_CONTENT_CHARS
            ),
        )
        putString(
            KEY_REASONING_CONTENT,
            reasoningContent.boundedText(
                if (compactForDrain) MAX_DRAIN_REASONING_CHARS else MAX_RESULT_REASONING_CHARS
            ),
        )
        putString(KEY_ERROR, error?.boundedText(MAX_DRAIN_CONTENT_CHARS))
        if (compactForDrain) {
            putString(KEY_TRANSCRIPT_JSON, AgentConversationCodec.encodeTranscriptForDrain(transcript))
        } else if (transcriptDescriptor != null) {
            putParcelable(KEY_TRANSCRIPT_FD, transcriptDescriptor)
        } else {
            putString(KEY_TRANSCRIPT_JSON, AgentConversationCodec.encodeTranscriptForIpc(transcript))
        }
    }

    fun runResultFromBundle(bundle: Bundle): RunResult =
        RunResult(
            runId = bundle.getString(KEY_RUN_ID).orEmpty(),
            ok = bundle.getBoolean(KEY_OK),
            content = bundle.getString(KEY_CONTENT).orEmpty(),
            error = bundle.getString(KEY_ERROR),
            reasoningContent = bundle.getString(KEY_REASONING_CONTENT).orEmpty(),
            transcript = AgentRuntimeTranscriptTransfer.readFromBundle(bundle),
        )

    fun toBundle(completedRun: CompletedRun): Bundle = completedRun.toBundle(compactForDrain = false)

    private fun CompletedRun.toBundle(compactForDrain: Boolean): Bundle = Bundle().apply {
        putBundle(KEY_HANDOFF, toBundle(handoff))
        putBundle(KEY_RESULT, result.toBundle(compactForDrain))
        putLong(KEY_CREATED_AT, createdAt)
    }

    fun completedRunFromBundle(bundle: Bundle): CompletedRun =
        CompletedRun(
            handoff = entryHandoffFromBundle(bundle.getBundle(KEY_HANDOFF) ?: Bundle()),
            result = runResultFromBundle(bundle.getBundle(KEY_RESULT) ?: Bundle()),
            createdAt = bundle.getLong(KEY_CREATED_AT)
        )

    fun completedRunsToBundle(results: List<CompletedRun>): Bundle = Bundle().apply {
        putParcelableArrayList(
            KEY_RESULTS,
            ArrayList(results.map { it.toBundle(compactForDrain = true) }),
        )
    }

    fun completedRunsFromBundle(bundle: Bundle): List<CompletedRun> =
        bundle.getParcelableArrayList(KEY_RESULTS, Bundle::class.java)
            .orEmpty()
            .map(::completedRunFromBundle)

    fun ackBundle(runId: String): Bundle = Bundle().apply {
        putString(KEY_RUN_ID, runId)
    }

    fun activeRunsBundle(runIds: Collection<String>): Bundle = Bundle().apply {
        val ids = runIds.map { it.trim() }.filter { it.isNotEmpty() }
        putString(KEY_RUN_ID, ids.firstOrNull().orEmpty())
        putStringArrayList(KEY_RUN_IDS, ArrayList(ids))
    }

    fun runIdsFromBundle(bundle: Bundle): Set<String> {
        val listed = bundle.getStringArrayList(KEY_RUN_IDS)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (listed.isNotEmpty()) return listed.toSet()
        return bundle.getString(KEY_RUN_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { setOf(it) }
            .orEmpty()
    }

    fun steerBundle(runId: String, text: String, requestId: String = "", imagesJson: String = "[]"): Bundle = Bundle().apply {
        putString(KEY_RUN_ID, runId)
        putString(KEY_STEER_TEXT, text)
        putString("request_id", requestId)
        putString("images_json", imagesJson)
    }

    fun steerTextFromBundle(bundle: Bundle): String =
        bundle.getString(KEY_STEER_TEXT).orEmpty()

    fun compactBundle(
        runId: String,
        keepRecent: Int? = null,
        compressModelConfig: AgentModelClient.ModelConfig? = null,
    ): Bundle = Bundle().apply {
        putString(KEY_RUN_ID, runId)
        keepRecent?.let { putInt("compact_keep_recent", it) }
        compressModelConfig?.let { putString("compact_model_config", json.encodeToString(it)) }
    }

    fun compactModelConfigFromBundle(bundle: Bundle): AgentModelClient.ModelConfig? =
        bundle.getString("compact_model_config")?.let { json.decodeFromString<AgentModelClient.ModelConfig>(it) }

    fun compactKeepRecentFromBundle(bundle: Bundle): Int =
        bundle.getInt(KEY_COMPACT_KEEP_RECENT, AgentContextCompactor.DEFAULT_KEEP_RECENT)


    fun attachRunResponseBundle(runId: String, attached: Boolean): Bundle = Bundle().apply {
        putString(KEY_RUN_ID, runId)
        putBoolean(KEY_OK, attached)
    }

    fun attachRunSucceeded(bundle: Bundle): Boolean = bundle.getBoolean(KEY_OK)

    fun runIdFromBundle(bundle: Bundle): String =
        bundle.getString(KEY_RUN_ID).orEmpty()

    private fun String.boundedText(maxChars: Int): String =
        if (length <= maxChars) this else take((maxChars - TRUNCATED_SUFFIX.length).coerceAtLeast(0)) + TRUNCATED_SUFFIX

    private fun requireStartRequestWithinBinderBudget(bundle: Bundle) {
        val parcel = Parcel.obtain()
        val sizeBytes = try {
            parcel.writeBundle(bundle)
            parcel.dataSize()
        } finally {
            parcel.recycle()
        }
        if (sizeBytes > MAX_START_REQUEST_PARCEL_BYTES) {
            throw PayloadTooLargeException(sizeBytes)
        }
    }

    /** Packs [AgentEvent] into a [Bundle] that can be passed across processes. */
    fun eventToBundle(event: AgentEvent, historyDescriptor: ParcelFileDescriptor? = null): Bundle = Bundle().apply {
        when (event) {
            is AgentEvent.RunStarted -> {
                putString(KEY_TYPE, "run_started")
                putInt("initial_images", event.initialImages)
                putInt("initial_image_bytes", event.initialImageBytes)
                putInt("tool_count", event.toolCount)
                putBoolean("terminal_tools", event.terminalTools)
            }

            is AgentEvent.RoundStarted -> {
                putString(KEY_TYPE, "round_started")
                putInt("round", event.round)
                putInt("message_count", event.messageCount)
            }

            is AgentEvent.ModelRetryScheduled -> {
                putString(KEY_TYPE, "model_retry_scheduled")
                putInt("round", event.round)
                putInt("attempt", event.attempt)
                putInt("max_attempts", event.maxAttempts)
                putInt("delay_ms", event.delayMs)
                putString("reason_code", event.reasonCode)
                putString("reason_detail", event.reasonDetail)
            }

            is AgentEvent.ProviderRequestStarted -> {
                putString(KEY_TYPE, "provider_request_started")
                putInt("round", event.round)
            }

            is AgentEvent.ProviderResponseStarted -> {
                putString(KEY_TYPE, "provider_response_started")
                putInt("round", event.round)
                putInt("http_code", event.httpCode)
            }

            is AgentEvent.AssistantBlockStart -> {
                putString(KEY_TYPE, "assistant_block_start")
                putInt("round", event.round)
                putString("kind", event.kind.name)
                putInt("index", event.index)
                event.blockId?.let { putString("block_id", it) }
                event.name?.let { putString("name", it) }
            }

            is AgentEvent.AssistantBlockDelta -> {
                putString(KEY_TYPE, "assistant_block_delta")
                putInt("round", event.round)
                putString("kind", event.kind.name)
                putInt("index", event.index)
                putInt("delta_chars", event.deltaChars)
                putString("delta", event.delta)
            }

            is AgentEvent.AssistantBlockEnd -> {
                putString(KEY_TYPE, "assistant_block_end")
                putInt("round", event.round)
                putString("kind", event.kind.name)
                putInt("index", event.index)
                event.blockId?.let { putString("block_id", it) }
                event.name?.let { putString("name", it) }
                putInt("content_chars", event.contentChars)
                event.replacementContent?.let { putString("replacement_content", it) }
            }

            is AgentEvent.AssistantReceived -> {
                putString(KEY_TYPE, "assistant_received")
                putInt("round", event.round)
                putInt("content_chars", event.contentChars)
                putString("reasoning_content", event.reasoningContent)
                putStringArrayList("tool_names", ArrayList(event.toolNames))
            }

            is AgentEvent.ChildContextUpdated -> {
                putString(KEY_TYPE, "child_context_updated")
                putString("child_context_json", event.stats.toJson().toString())
            }

            is AgentEvent.UsageReceived -> {
                putString(KEY_TYPE, "usage_received")
                putInt("round", event.round)
                putBoolean("projected", event.projected)
                putTokenUsage(event.usage)
            }

            is AgentEvent.UserSupplementReceived -> {
                putString(KEY_TYPE, "user_supplement_received")
                putInt("index", event.index)
                putString("text", event.text)
                putString("request_id", event.requestId)
                putString("images_json", event.imagesJson)
            }

            is AgentEvent.ToolStarted -> {
                putString(KEY_TYPE, "tool_started")
                putInt("round", event.round)
                putString("tool_call_id", event.toolCallId)
                putString("name", event.name)
                putString("args_preview", event.argsPreview)
                event.command?.let { putString("command", it) }
            }

            is AgentEvent.ToolFinished -> {
                putString(KEY_TYPE, "tool_finished")
                putInt("round", event.round)
                putString("tool_call_id", event.toolCallId)
                putString("name", event.name)
                putString("result_summary", event.resultSummary)
                putInt("image_count", event.imageCount)
                putInt("image_bytes", event.imageBytes)
                event.success?.let { putBoolean("success", it) }
            }

            is AgentEvent.HostedToolStarted -> {
                putString(KEY_TYPE, "hosted_tool_started")
                putInt("round", event.round)
                putString("tool_call_id", event.toolCallId)
                putString("name", event.name)
            }

            is AgentEvent.HostedToolFinished -> {
                putString(KEY_TYPE, "hosted_tool_finished")
                putInt("round", event.round)
                putString("tool_call_id", event.toolCallId)
                putString("name", event.name)
                putBoolean("success", event.success)
            }

            is AgentEvent.ToolImagesAttached -> {
                putString(KEY_TYPE, "tool_images_attached")
                putInt("round", event.round)
                putString("tool_name", event.toolName)
                putInt("image_count", event.imageCount)
                putInt("image_bytes", event.imageBytes)
            }

            is AgentEvent.ContextCompactionStarted -> {
                putString(KEY_TYPE, "context_compaction_started")
                putString("model_name", event.modelName)
                putInt("round", event.round)
            }

            is AgentEvent.ContextCompacted -> {
                putString(KEY_TYPE, "context_compacted")
                putInt("round", event.round)
                putBoolean("applied", event.applied)
                putInt("original_count", event.originalCount)
                putInt("compacted_count", event.compactedCount)
                putString("compressor_label", event.compressorLabel)
                putBoolean("context_blocked", event.blocked)
                putString("context_reason", event.reason)
                if (historyDescriptor == null) putString("history_json", encodeConversationHistory(event.history))
                else putParcelable(KEY_TRANSCRIPT_FD, historyDescriptor)
            }

            is AgentEvent.RunFinished -> {
                putString(KEY_TYPE, "run_finished")
                putInt("round", event.round)
                putInt("content_chars", event.contentChars)
                event.generatedAtMillis?.let { putLong("generated_at_millis", it) }
            }

            is AgentEvent.RunFailed -> {
                putString(KEY_TYPE, "run_failed")
                putString("reason", event.reason)
            }
        }
    }

    /** Restores [Bundle] to [AgentEvent], returning null when it cannot be recognized. */
    fun eventFromBundle(bundle: Bundle): AgentEvent? = when (bundle.getString(KEY_TYPE)) {
        "run_started" -> AgentEvent.RunStarted(
            initialImages = bundle.getInt("initial_images"),
            initialImageBytes = bundle.getInt("initial_image_bytes"),
            toolCount = bundle.getInt("tool_count"),
            terminalTools = bundle.getBoolean("terminal_tools"),
        )

        "round_started" -> AgentEvent.RoundStarted(
            round = bundle.getInt("round"),
            messageCount = bundle.getInt("message_count"),
        )

        "model_retry_scheduled" -> AgentEvent.ModelRetryScheduled(
            round = bundle.getInt("round"),
            attempt = bundle.getInt("attempt", 1),
            maxAttempts = bundle.getInt("max_attempts", 3),
            delayMs = bundle.getInt("delay_ms", 2_000),
            reasonCode = bundle.getString("reason_code").orEmpty(),
            reasonDetail = bundle.getString("reason_detail").orEmpty(),
        )

        "provider_request_started" -> AgentEvent.ProviderRequestStarted(
            round = bundle.getInt("round"),
        )

        "provider_response_started" -> AgentEvent.ProviderResponseStarted(
            round = bundle.getInt("round"),
            httpCode = bundle.getInt("http_code"),
        )

        "assistant_block_start" -> AgentEvent.AssistantBlockStart(
            round = bundle.getInt("round"),
            kind = AgentEvent.AssistantBlockKind.valueOf(
                bundle.getString("kind").orEmpty()
            ),
            index = bundle.getInt("index"),
            blockId = bundle.getString("block_id"),
            name = bundle.getString("name"),
        )

        "assistant_block_delta" -> AgentEvent.AssistantBlockDelta(
            round = bundle.getInt("round"),
            kind = AgentEvent.AssistantBlockKind.valueOf(
                bundle.getString("kind").orEmpty()
            ),
            index = bundle.getInt("index"),
            deltaChars = bundle.getInt("delta_chars"),
            delta = bundle.getString("delta").orEmpty(),
        )

        "assistant_block_end" -> AgentEvent.AssistantBlockEnd(
            round = bundle.getInt("round"),
            kind = AgentEvent.AssistantBlockKind.valueOf(
                bundle.getString("kind").orEmpty()
            ),
            index = bundle.getInt("index"),
            blockId = bundle.getString("block_id"),
            name = bundle.getString("name"),
            contentChars = bundle.getInt("content_chars"),
            replacementContent = bundle.getString("replacement_content"),
        )

        "assistant_received" -> AgentEvent.AssistantReceived(
            round = bundle.getInt("round"),
            contentChars = bundle.getInt("content_chars"),
            reasoningContent = bundle.getString("reasoning_content").orEmpty(),
            toolNames = bundle.getStringArrayList("tool_names").orEmpty(),
        )

        "child_context_updated" -> AgentEvent.ChildContextUpdated(
            io.github.mangi.eta.agent.delegation.SubAgentContextStats.fromJson(org.json.JSONObject(bundle.getString("child_context_json").orEmpty())))
        "usage_received" -> AgentEvent.UsageReceived(
            round = bundle.getInt("round"),
            usage = bundle.getTokenUsage(),
            projected = bundle.getBoolean("projected", false),
        )

        "user_supplement_received" -> AgentEvent.UserSupplementReceived(
            index = bundle.getInt("index"),
            text = bundle.getString("text").orEmpty(),
            requestId = bundle.getString("request_id").orEmpty(),
            imagesJson = bundle.getString("images_json") ?: "[]",
        )

        "tool_started" -> AgentEvent.ToolStarted(
            round = bundle.getInt("round"),
            toolCallId = bundle.getString("tool_call_id").orEmpty(),
            name = bundle.getString("name").orEmpty(),
            argsPreview = bundle.getString("args_preview").orEmpty(),
            command = bundle.getString("command"),
        )

        "tool_finished" -> AgentEvent.ToolFinished(
            round = bundle.getInt("round"),
            toolCallId = bundle.getString("tool_call_id").orEmpty(),
            name = bundle.getString("name").orEmpty(),
            resultSummary = bundle.getString("result_summary").orEmpty(),
            imageCount = bundle.getInt("image_count"),
            imageBytes = bundle.getInt("image_bytes"),
            success = if (bundle.containsKey("success")) bundle.getBoolean("success") else null,
        )

        "hosted_tool_started" -> AgentEvent.HostedToolStarted(
            round = bundle.getInt("round"),
            toolCallId = bundle.getString("tool_call_id").orEmpty(),
            name = bundle.getString("name").orEmpty(),
        )

        "hosted_tool_finished" -> AgentEvent.HostedToolFinished(
            round = bundle.getInt("round"),
            toolCallId = bundle.getString("tool_call_id").orEmpty(),
            name = bundle.getString("name").orEmpty(),
            success = bundle.getBoolean("success"),
        )

        "tool_images_attached" -> AgentEvent.ToolImagesAttached(
            round = bundle.getInt("round"),
            toolName = bundle.getString("tool_name").orEmpty(),
            imageCount = bundle.getInt("image_count"),
            imageBytes = bundle.getInt("image_bytes"),
        )

        "context_compaction_started" -> AgentEvent.ContextCompactionStarted(
            round = bundle.getInt("round"),
            modelName = bundle.getString("model_name").orEmpty(),
        )

        "context_compacted" -> AgentEvent.ContextCompacted(
            round = bundle.getInt("round"),
            applied = bundle.getBoolean("applied"),
            originalCount = bundle.getInt("original_count"),
            compactedCount = bundle.getInt("compacted_count"),
            history = if (bundle.containsKey(KEY_TRANSCRIPT_FD)) AgentRuntimeTranscriptTransfer.readFromBundle(bundle)
                else decodeConversationHistory(bundle.getString("history_json")),
            compressorLabel = bundle.getString("compressor_label").orEmpty(),
            blocked = bundle.getBoolean("context_blocked", false),
            reason = bundle.getString("context_reason").orEmpty(),
        )

        "run_finished" -> AgentEvent.RunFinished(
            round = bundle.getInt("round"),
            contentChars = bundle.getInt("content_chars"),
            generatedAtMillis = bundle.getLong("generated_at_millis", 0L).takeIf { it > 0L },
        )

        "run_failed" -> AgentEvent.RunFailed(
            reason = bundle.getString("reason").orEmpty(),
        )

        else -> null
    }

    private fun encodeConversationHistory(
        history: List<AgentModelClient.ConversationMessage>,
    ): String {
        val array = org.json.JSONArray()
        history.forEach { message ->
            array.put(io.github.mangi.eta.agent.model.AgentConversationCodec.toJsonObject(message))
        }
        return array.toString()
    }

    private fun decodeConversationHistory(raw: String?): List<AgentModelClient.ConversationMessage> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = org.json.JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(io.github.mangi.eta.agent.model.AgentConversationCodec.fromJsonObject(item))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun Bundle.putTokenUsage(usage: AgentTokenUsage) {
        usage.contextTokens?.let { putInt("usage_context_tokens", it) }
        usage.inputTokens?.let { putInt("usage_input_tokens", it) }
        usage.outputTokens?.let { putInt("usage_output_tokens", it) }
        usage.reasoningTokens?.let { putInt("usage_reasoning_tokens", it) }
        usage.cachedTokens?.let { putInt("usage_cached_tokens", it) }
    }

    private fun Bundle.getTokenUsage(): AgentTokenUsage = AgentTokenUsage(
        contextTokens = if (containsKey("usage_context_tokens")) getInt("usage_context_tokens") else null,
        inputTokens = if (containsKey("usage_input_tokens")) getInt("usage_input_tokens") else null,
        outputTokens = if (containsKey("usage_output_tokens")) getInt("usage_output_tokens") else null,
        reasoningTokens = if (containsKey("usage_reasoning_tokens")) getInt("usage_reasoning_tokens") else null,
        cachedTokens = if (containsKey("usage_cached_tokens")) getInt("usage_cached_tokens") else null,
    )

    private fun decodeReasoningCapabilities(raw: String?): ModelReasoningCapabilities? =
        runCatching {
            raw?.let { json.decodeFromString<ModelReasoningCapabilities>(it) }
        }.getOrNull()

    private fun decodeCustomHeaders(raw: String?): List<CustomHeader> =
        runCatching {
            raw?.let { json.decodeFromString<List<CustomHeader>>(it) } ?: emptyList()
        }.getOrDefault(emptyList())

    private fun decodeCustomBody(raw: String?): List<CustomBody> =
        runCatching {
            raw?.let { json.decodeFromString<List<CustomBody>>(it) } ?: emptyList()
        }.getOrDefault(emptyList())

    private fun Bundle.optionalInt(key: String): Int? =
        if (containsKey(key)) getInt(key) else null
}
