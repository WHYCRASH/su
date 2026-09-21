package io.github.mangi.eta.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** Resume-animation boundaries are decided by already-laid-out content; layout callbacks from the old page must not lift a new pause round. */
internal class StreamingMarkdownRestoreState {
    var generation by mutableIntStateOf(0)
        private set
    private var baseline by mutableStateOf<String?>(null)
    private var foreground by mutableStateOf(false)

    fun animationsAllowed(paused: Boolean): Boolean = foreground && baseline == null && !paused

    fun begin(content: String) {
        generation += 1
        baseline = content
        foreground = true
    }

    fun pause() {
        generation += 1
        baseline = null
        foreground = false
    }

    fun completeLayout(generation: Int, renderedContent: String, currentContent: String): Boolean {
        val pending = baseline ?: return false
        if (generation != this.generation) return false
        val caughtUp = renderedContent == currentContent ||
            (renderedContent.startsWith(pending) && currentContent.startsWith(renderedContent))
        if (!caughtUp) return false
        baseline = null
        return true
    }
}

internal fun isStreamingMarkdownTargetComplete(
    content: String,
    isStreaming: Boolean,
    snapshotContent: String?,
    snapshotComplete: Boolean,
): Boolean = !isStreaming && snapshotComplete && snapshotContent == content
