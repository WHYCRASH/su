package io.github.mangi.eta.agent.terminal

internal const val SELECTED_LINUX_WIRE_NAME = "linux"

/** Linux user-space distributions supported by su. The kernel is still provided by Android; the distribution only replaces the rootfs. */
internal enum class LinuxDistribution(val wireName: String) {
    ALPINE("alpine"),
    DEBIAN("debian"),
}

internal enum class TerminalEnvironment(
    val wireName: String,
    val linuxDistribution: LinuxDistribution? = null,
) {
    ANDROID("android"),
    ALPINE("alpine", LinuxDistribution.ALPINE),
    DEBIAN("debian", LinuxDistribution.DEBIAN),
}

internal val TerminalEnvironment.isLinux: Boolean
    get() = linuxDistribution != null

internal val LinuxDistribution.terminalEnvironment: TerminalEnvironment
    get() = when (this) {
        LinuxDistribution.ALPINE -> TerminalEnvironment.ALPINE
        LinuxDistribution.DEBIAN -> TerminalEnvironment.DEBIAN
    }

/**
 * Returns a human-readable name for UI display.
 */
internal fun TerminalEnvironment.label(): String = when (this) {
    TerminalEnvironment.ANDROID -> "Android"
    TerminalEnvironment.ALPINE -> "Alpine"
    TerminalEnvironment.DEBIAN -> "Debian"
}
