package io.github.mangi.eta.agent.device

import java.util.UUID

/** Confirm root before running the pinned command, so the command's own nonzero exit is not mistaken for revoked authorization. */
internal class RootCommandEnvelope(command: String, token: String = UUID.randomUUID().toString()) {
    private val marker = "ETA_ROOT_GRANTED_$token\n"
    val markerBytes: Int get() = marker.length
    val script: String = "[ \"\$(id -u)\" = 0 ] || exit 77; " +
        "printf '%s\\n' ${quote(marker.trimEnd())} >&2; " +
        "exec /system/bin/sh -c ${quote(command)}"

    fun inspect(stderr: String): Output {
        val index = stderr.indexOf(marker)
        return if (index < 0) Output(granted = false, stderr = stderr) else {
            Output(granted = true, stderr = stderr.removeRange(index, index + marker.length))
        }
    }

    data class Output(val granted: Boolean, val stderr: String) {
        fun denied(completed: Boolean): Boolean = completed && !granted
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
