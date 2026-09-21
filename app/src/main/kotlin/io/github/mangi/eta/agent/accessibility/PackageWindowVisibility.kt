package io.github.mangi.eta.agent.accessibility

/** A main-thread query timeout must not treat UNKNOWN as the window already being gone. */
internal enum class PackageWindowVisibility {
    VISIBLE,
    GONE,
    UNKNOWN,
}
