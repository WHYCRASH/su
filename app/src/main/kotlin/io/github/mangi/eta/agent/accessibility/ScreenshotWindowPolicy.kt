package io.github.mangi.eta.agent.accessibility

/** Screenshot window filtering must keep "what the model sees" consistent with the window actually receiving touch. */
internal object ScreenshotWindowPolicy {
    enum class Decision {
        CAPTURE,
        EXCLUDE,
        BLOCK_UNKNOWN,
    }

    fun decide(
        isAccessibilityOverlay: Boolean,
        isApplicationWindow: Boolean,
        active: Boolean,
        focused: Boolean,
        resolvedPackage: String?,
        ownPackage: String,
        excludedPackages: Set<String>,
    ): Decision {
        if (isAccessibilityOverlay && resolvedPackage == ownPackage) {
            return Decision.EXCLUDE
        }
        if (resolvedPackage in excludedPackages) return Decision.EXCLUDE
        if (
            excludedPackages.isNotEmpty() &&
            resolvedPackage.isNullOrBlank() &&
            (isApplicationWindow || active || focused)
        ) {
            return Decision.BLOCK_UNKNOWN
        }
        return Decision.CAPTURE
    }
}
