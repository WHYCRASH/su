package io.github.mangi.eta.agent.device

/**
 * KernelSU / Magisk keep `su` in the caller's isolated mount namespace by default.
 * That namespace swaps `/data/data` and `/data/user` for a tmpfs containing only this app and GMS,
 * so terminal and file tools cannot see other apps' private directories even as root.
 *
 * `-M` / `--mount-master` enters init's global mount namespace.
 */
internal object RootSu {
    fun args(script: String): Array<String> = arrayOf("su", "-M", "-c", script)

    fun process(script: String): ProcessBuilder = ProcessBuilder(*args(script))
}
