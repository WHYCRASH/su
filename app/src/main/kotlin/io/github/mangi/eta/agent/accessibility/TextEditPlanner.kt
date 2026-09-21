package io.github.mangi.eta.agent.accessibility

/** Generate the resulting content and cursor position after one real "typing" action based on the Android text selection (UTF-16 offset). */
internal object TextEditPlanner {
    data class Plan(
        val text: String,
        val cursor: Int,
    )

    fun canSafelyReconstruct(
        password: Boolean,
        textAvailable: Boolean,
        textLength: Int,
        selectionStart: Int,
        selectionEnd: Int,
    ): Boolean {
        if (password || !textAvailable || textLength < 0) return false
        if (selectionStart in 0..textLength && selectionEnd in 0..textLength) return true
        // Some empty input controls use -1 to indicate no cursor has been created yet; empty text still has only insertion point 0.
        return textLength == 0 && selectionStart <= 0 && selectionEnd <= 0
    }

    fun insertAtSelection(
        currentText: String,
        insertedText: String,
        selectionStart: Int,
        selectionEnd: Int,
    ): Plan? {
        val selectionIsValid =
            selectionStart in 0..currentText.length && selectionEnd in 0..currentText.length
        if (!selectionIsValid && currentText.isNotEmpty()) return null
        val start = if (selectionIsValid) selectionStart else 0
        val end = if (selectionIsValid) selectionEnd else 0
        val lower = minOf(start, end)
        val upper = maxOf(start, end)
        return Plan(
            text = currentText.substring(0, lower) + insertedText + currentText.substring(upper),
            cursor = lower + insertedText.length,
        )
    }
}
