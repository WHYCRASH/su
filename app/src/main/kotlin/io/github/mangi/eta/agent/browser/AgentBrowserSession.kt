package io.github.mangi.eta.agent.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Looper
import android.util.Base64
import io.github.mangi.eta.agent.browser.ported.browser.BrowserActionInput
import io.github.mangi.eta.agent.browser.ported.browser.BrowserActionResult
import io.github.mangi.eta.agent.browser.ported.browser.BrowserTabPool
import io.github.mangi.eta.agent.browser.ported.browser.UserAgentProfile
import io.github.mangi.eta.agent.terminal.LinuxGuestPathResolver
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal data class BrowserSessionSnapshot(
    val tabId: Int? = null,
    val tabCount: Int = 0,
    val available: Boolean = false,
    val url: String = "",
    val displayUrl: String = "",
    val host: String = "",
    val title: String = "",
    val isLoading: Boolean = false,
    val isPageVisible: Boolean = false,
    val hasCommittedPage: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val error: String? = null,
    val isUserControlling: Boolean = false,
    val lastAgentRunId: String? = null,
    val lastAgentToolCallId: String? = null,
    val desktopMode: Boolean = true,
    val userAgent: String = BrowserUserAgent.DEFAULT.wireName,
)

internal data class BrowserImage(
    val dataUrl: String,
    val mimeType: String,
    val bytes: Int,
    val width: Int,
    val height: Int,
)

internal data class BrowserToolResult(
    val content: String,
    val images: List<BrowserImage> = emptyList(),
)

/** Host adapter: UI and tools use exactly the same OpenMinis-derived tab pool. */
internal object AgentBrowserSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = Any()
    private var active: Deferred<BrowserToolResult>? = null
    private var activeRunId: String? = null
    private var userOwner: Any? = null
    @Volatile private var context: Context? = null
    private var pool: BrowserTabPool? = null // main-thread owned
    @Volatile var isUserControlling: Boolean = false
        private set
    private var lastRunId: String? = null
    private var lastCallId: String? = null
    private val mutableSnapshots = MutableStateFlow(BrowserSessionSnapshot())
    val snapshots: StateFlow<BrowserSessionSnapshot> = mutableSnapshots.asStateFlow()

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    private suspend fun ensurePool(): BrowserTabPool = withContext(Dispatchers.Main.immediate) {
        pool ?: run {
            val app = requireNotNull(context)
            val prefs = app.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
            if (!prefs.contains("user_agent_profile")) {
                val old = BrowserUserAgent.load()
                prefs.edit().putString("user_agent_profile", if (old.desktop) "DESKTOP_CHROME" else "MOBILE_CHROME").apply()
            }
            BrowserTabPool(app)
        }.also { created ->
            pool = created
            // Eta historically shares one browser across tool calls and chat sessions.
            created.setSession("eta-shared")
            scope.launch {
                while (isActive) {
                    publishSnapshot()
                    delay(if (created.isAgentBusy || isUserControlling) 250 else 1000)
                }
            }
        }
    }

    /** Ownership is reserved before cancellation, so no queued tool can race the UI. */
    suspend fun acquireUserControl(context: Context, owner: Any): BrowserTabPool {
        initialize(context)
        val pending = synchronized(gate) {
            userOwner = owner
            isUserControlling = true
            active?.also { it.cancel() }
        }
        pending?.join()
        synchronized(gate) {
            if (userOwner !== owner) throw CancellationException("Browser page disposed")
        }
        return withContext(Dispatchers.Main.immediate) {
            ensurePool().also { browser ->
                browser.tabs.value.forEach { it.manager.stopLoading() }
                browser.releaseAllTabs()
                browser.ensureTabForUI()
                publishSnapshot()
            }
        }
    }

    fun releaseUserControl(owner: Any) {
        synchronized(gate) {
            if (userOwner !== owner) return
            userOwner = null
        }
        scope.launch {
            yield() // AndroidView removes the hosted WebView before restoring off-screen bounds.
            synchronized(gate) { if (userOwner != null) return@launch }
            // A visible WebView has phone-sized bounds. Restore agent viewport after unmount.
            pool?.let { browser ->
                val (width, height) = browser.resolvedViewportSize()
                browser.tabs.value.forEach { tab ->
                    if (tab.manager.webView.parent == null) tab.manager.applyViewport(width, height)
                }
            }
            synchronized(gate) { isUserControlling = false }
            publishSnapshot()
        }
    }

    fun execute(context: Context, args: JSONObject, runId: String?, toolCallId: String?): BrowserToolResult {
        initialize(context)
        val action = args.optString("action").trim().lowercase()
        if (Looper.myLooper() == Looper.getMainLooper()) return error(action, "MAIN_THREAD_CALL", "The browser tool cannot block the main thread")
        val job = synchronized(gate) {
            if (isUserControlling) return error(action, "USER_CONTROL_ACTIVE", "The user is taking over the browser. Please wait for the user to leave the browser page")
            if (active?.isCompleted == false) return error(action, "BROWSER_BUSY", "The browser is performing another operation. Please wait for it to finish")
            scope.async(start = CoroutineStart.LAZY) {
                if (isUserControlling) return@async error(action, "USER_CONTROL_ACTIVE", "The user is taking over the browser")
                lastRunId = runId
                lastCallId = toolCallId
                val normalized = JSONObject(args.toString()).put("action", action)
                val input = BrowserActionInput.parse(normalized.toString())
                    ?: return@async error(action, "INVALID_ARGUMENT", "The browser parameter or action is invalid")
                val browser = ensurePool()
                try {
                    val timeout = normalized.optLong("timeout_ms", normalized.optLong("timeout", 30000L)).coerceIn(500, 60000)
                    val result = withTimeout(if (action == "navigate" || action == "wait_for_selector") timeout + 1000 else 90000L) {
                        // Preserve old serial semantics: tab-less navigation follows the current tab.
                        browser.execute(input, singleTab = true)
                    }
                    toToolResult(action, args, result)
                } finally {
                    if (!isActive) {
                        withContext(NonCancellable + Dispatchers.Main.immediate) {
                            browser.tabs.value.forEach { it.manager.stopLoading() }
                            browser.releaseAllTabs()
                        }
                    }
                    publishSnapshot()
                }
            }.also { active = it; activeRunId = runId }
        }
        return try {
            runBlocking { job.await() }
        } catch (_: TimeoutCancellationException) {
            error(action, "BROWSER_TIMEOUT", "The browser operation timed out; the result of an operation with side effects is unconfirmed. Please observe the page first")
        } catch (_: CancellationException) {
            error(action, "CANCELLED", "The browser operation was interrupted, and the result is unconfirmed. Please observe the page first")
        } catch (_: Exception) {
            error(action, "BROWSER_ERROR", "The browser operation failed. Please check the page and try again")
        } finally {
            synchronized(gate) {
                if (active === job) { active = null; activeRunId = null }
            }
        }
    }

    fun interruptAgentAction(runId: String? = null) {
        synchronized(gate) {
            if (runId == null || runId == activeRunId) active?.cancel()
        }
    }

    private fun publishSnapshot() {
        val browser = pool ?: return
        val manager = browser.activeManager
        val url = manager?.currentURL?.value.orEmpty()
        val loading = manager?.isLoading?.value == true
        val profile = browser.currentUserAgentProfile.value
        mutableSnapshots.value = BrowserSessionSnapshot(
            tabId = browser.selectedTabId.value.takeIf { manager != null },
            tabCount = browser.tabs.value.size,
            available = manager != null && url.isNotBlank(),
            url = url, displayUrl = url, host = Uri.parse(url).host.orEmpty(),
            title = manager?.pageTitle?.value.orEmpty().take(160),
            isLoading = loading, isPageVisible = !loading && url.isNotBlank(),
            hasCommittedPage = url.isNotBlank(), progress = if (loading) 0 else 100,
            canGoBack = manager?.canGoBack?.value == true,
            canGoForward = manager?.canGoForward?.value == true,
            isUserControlling = isUserControlling,
            lastAgentRunId = lastRunId, lastAgentToolCallId = lastCallId,
            desktopMode = profile == UserAgentProfile.DESKTOP_CHROME,
            userAgent = profile.value,
        )
    }

    private suspend fun toToolResult(action: String, args: JSONObject, result: BrowserActionResult): BrowserToolResult {
        val body = if (action in setOf("get_text", "get_readable", "get_cookies")) {
            runCatching { JSONObject(result.text.substringBeforeLast("\n  tab_id:", result.text)) }.getOrNull()
        } else null
        val envelope = body ?: JSONObject().put("text", result.text)
        val target = result.tabId?.let { id -> pool?.tabs?.value?.firstOrNull { it.id == id }?.manager }
            ?: pool?.activeManager
        envelope.put("ok", result.success).put("tool", "browser_use").put("action", action)
            .put("status", if (result.success) "ok" else "error")
            .put("tab_id", result.tabId ?: JSONObject.NULL)
            .put("url", result.pageURL ?: target?.currentURL?.value.orEmpty()).put("content_source", "web_page")
        val images = withContext(Dispatchers.IO) {
            if (action == "screenshot" && !args.optBoolean("read_image", true)) emptyList()
            else loadImage(result)?.let(::listOf).orEmpty()
        }
        if (images.isNotEmpty()) envelope.put("snapshot_attached", true)
        result.imageFilePath?.let { envelope.put("image_path", it) }
        result.fetchedFileData?.let { data ->
            require(data.size <= 8 * 1024 * 1024) { "Download exceeds limit" }
            val file = withContext(Dispatchers.IO) {
                val dir = File(LinuxGuestPathResolver.resolveForApp(requireNotNull(context), "/var/minis/browser"))
                check(dir.isDirectory || dir.mkdirs())
                File.createTempFile("fetch_", "." + File(result.fetchedFileName.orEmpty()).extension.take(12).ifBlank { "bin" }, dir)
                    .also { it.writeBytes(data) }
            }
            envelope.put("path", file.absolutePath).put("bytes", data.size)
                .put("minis_path", "/var/minis/browser/${file.name}")
        }
        return BrowserToolResult(BrowserPayloadLimiter.serialize(envelope), images)
    }

    private fun loadImage(result: BrowserActionResult): BrowserImage? {
        val data = result.imageFilePath?.let { path ->
            val file = File(path)
            if (file.isFile && file.length() <= 8 * 1024 * 1024) file.readBytes() else null
        } ?: result.base64Image?.takeIf { it.length <= 12 * 1024 * 1024 }?.let { Base64.decode(it, Base64.DEFAULT) }
            ?: return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return BrowserImage("data:image/jpeg;base64," + Base64.encodeToString(data, Base64.NO_WRAP),
            "image/jpeg", data.size, options.outWidth, options.outHeight)
    }

    fun capturePreview(): BrowserImage? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        return runCatching {
            runBlocking {
                withTimeoutOrNull(2000) {
                    withContext(Dispatchers.Main) {
                        val bitmap = pool?.activeManager?.captureLiveSnapshot() ?: return@withContext null
                        try {
                            val out = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, out)
                            val data = out.toByteArray()
                            BrowserImage("data:image/jpeg;base64," + Base64.encodeToString(data, Base64.NO_WRAP),
                                "image/jpeg", data.size, bitmap.width, bitmap.height)
                        } finally { bitmap.recycle() }
                    }
                }
            }
        }.getOrNull()
    }

    private fun error(action: String, code: String, message: String) = BrowserToolResult(
        JSONObject().put("ok", false).put("tool", "browser_use").put("action", action)
            .put("status", "error").put("code", code).put("message", message).toString(),
    )
}
