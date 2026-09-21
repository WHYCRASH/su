package io.github.mangi.eta.agent.device

/** Falls back to root replay only once a gesture is confirmed never dispatched to the system. */
internal object GestureFallbackPolicy {
    fun mayFallbackToRoot(errorCode: String): Boolean =
        errorCode == "GESTURE_NOT_DISPATCHED"
}
