package io.github.mangi.eta.agent.terminal

import java.io.File

/** Normal mode is independent of the legacy Root-created terminal parent directory; it never changes the chroot path or ownership. */
internal object TerminalPrivateStorage {
    fun workspace(filesDir: File): File = directory(filesDir, "workspace")

    fun prootEnvironment(filesDir: File, distribution: LinuxDistribution): File =
        directory(filesDir, "proot/${distribution.wireName}")

    private fun directory(filesDir: File, relative: String): File {
        val independent = File(filesDir, "terminal-user/$relative")
        val legacy = File(filesDir, "terminal/$relative")
        // An existing normal environment keeps its location; path selection never reads Root grants and never moves existing data.
        return if (!independent.exists() && legacy.exists()) legacy else independent
    }

    fun isProotPath(path: String?): Boolean =
        path?.let { "/terminal-user/proot/" in it || "/terminal/proot/" in it } == true
}
