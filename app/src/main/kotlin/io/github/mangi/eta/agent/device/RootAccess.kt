package io.github.mangi.eta.agent.device

import android.content.Context
import android.content.SharedPreferences
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

internal enum class RootAccessStatus { UNKNOWN, UNAVAILABLE, NOT_GRANTED, GRANTED, DENIED, TIMED_OUT }

internal data class RootAccessState(
    val status: RootAccessStatus = RootAccessStatus.UNKNOWN,
    val suPresent: Boolean = false,
    val isChecking: Boolean = false,
) {
    val isGranted: Boolean get() = status == RootAccessStatus.GRANTED
}

/** Root probing belongs to the app process; reading capability never executes su or pops up an authorization prompt. */
internal object RootAccess {
    private const val PREFERENCES = "eta_root_access"
    private const val AUTOMATIC_REQUEST_ATTEMPTED = "automatic_request_attempted"
    private const val LAST_GRANTED = "last_granted"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(RootAccessState())
    val state: StateFlow<RootAccessState> = mutableState.asStateFlow()
    val isGranted: Boolean get() = mutableState.value.isGranted
    private var preferences: SharedPreferences? = null
    private var probeJob: Job? = null

    fun initialize(context: Context) {
        synchronized(this) {
            if (preferences != null) return
            preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        }
        refresh(context)
    }

    /** Request once when su is first discovered; after a denial only an explicit request applies again. */
    fun refresh(context: Context): Job = startProbe(context, explicit = false)

    fun request(context: Context): Job = startProbe(context, explicit = true)

    /** Called only after the executor confirms an su denial; an ordinary command failure does not mean authorization was revoked. */
    fun markDenied() {
        preferences?.edit()?.putBoolean(LAST_GRANTED, false)?.apply()
        mutableState.value = mutableState.value.copy(status = RootAccessStatus.DENIED)
    }

    private fun startProbe(context: Context, explicit: Boolean): Job = synchronized(this) {
        probeJob?.takeIf { it.isActive }?.let { return@synchronized it }
        val prefs = preferences ?: context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .also { preferences = it }
        scope.launch {
            mutableState.value = mutableState.value.copy(isChecking = true)
            try {
                val present = suExists()
                if (!present) {
                    mutableState.value = RootAccessState(RootAccessStatus.UNAVAILABLE)
                    return@launch
                }
                val attempted = prefs.getBoolean(AUTOMATIC_REQUEST_ATTEMPTED, false)
                val wasGranted = prefs.getBoolean(LAST_GRANTED, false)
                if (!shouldRequestRoot(explicit, attempted, wasGranted)) {
                    mutableState.value = mutableState.value.copy(
                        status = if (mutableState.value.status == RootAccessStatus.UNKNOWN) {
                            RootAccessStatus.NOT_GRANTED
                        } else {
                            mutableState.value.status
                        },
                        suPresent = true,
                    )
                    return@launch
                }
                // Record the attempt first, so quitting mid-prompt will not bother the user again on next launch.
                if (!prefs.edit().putBoolean(AUTOMATIC_REQUEST_ATTEMPTED, true).commit()) {
                    mutableState.value = RootAccessState(RootAccessStatus.NOT_GRANTED, suPresent = true)
                    AndroidAgentLogger.warn("Root access probe outcome=preferences_unavailable")
                    return@launch
                }
                val result = runInterruptible {
                    BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = { true }).use {
                        it.execute("id -u", timeoutMillis = 30_000L, maxOutputBytes = 256)
                    }
                }
                val granted = result.ok && result.stdout.trim() == "0"
                prefs.edit().putBoolean(LAST_GRANTED, granted).apply()
                mutableState.value = RootAccessState(
                    status = when {
                        granted -> RootAccessStatus.GRANTED
                        result.timedOut -> RootAccessStatus.TIMED_OUT
                        else -> RootAccessStatus.DENIED
                    },
                    suPresent = true,
                )
            } finally {
                mutableState.value = mutableState.value.copy(isChecking = false)
            }
        }.also { probeJob = it }
    }

    private fun suExists(): Boolean = System.getenv("PATH").orEmpty()
        .split(File.pathSeparatorChar)
        .filter(String::isNotBlank)
        .any { path -> File(path, "su").let { it.isFile && it.canExecute() } }
}

internal fun shouldRequestRoot(explicit: Boolean, attempted: Boolean, wasGranted: Boolean): Boolean =
    explicit || !attempted || wasGranted
