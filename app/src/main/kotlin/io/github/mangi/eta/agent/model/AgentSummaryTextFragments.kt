package io.github.mangi.eta.agent.model

/** Lossless ranges in a summary-only text projection, never edits to live history or archived JSON. */
internal object AgentSummaryTextFragments {
    data class Range(val start: Int, val end: Int)

    fun split(
        source: String,
        maxFragments: Int,
        checkCancellation: () -> Unit,
        fits: (Range) -> Boolean,
    ): List<Range> {
        require(maxFragments > 0) { "Too many summary fragments; use a summary model with a larger window; original history unchanged" }
        require(source.isNotEmpty()) { "No history text available to split" }
        val result = mutableListOf<Range>()
        var start = 0
        while (start < source.length) {
            checkCancellation()
            require(result.size < maxFragments) { "Too many summary fragments; use a summary model with a larger window; original history unchanged" }
            val remainder = Range(start, source.length)
            if (fits(remainder)) {
                result += remainder
                break
            }
            var low = 1
            var high = source.codePointCount(start, source.length)
            var acceptedEnd = start
            while (low <= high) {
                checkCancellation()
                val mid = low + (high - low) / 2
                val end = source.offsetByCodePoints(start, mid)
                if (fits(Range(start, end))) {
                    acceptedEnd = end
                    low = mid + 1
                } else high = mid - 1
            }
            require(acceptedEnd > start) { "Summary model input budget cannot fit a history text fragment; original history unchanged" }
            val range = Range(start, acceptedEnd)
            // The caller's budget may include structured projection and metadata. Recheck the final candidate.
            require(fits(range)) { "Summary text fragment still exceeds the input budget; original history unchanged" }
            result += range
            start = acceptedEnd
        }
        return result
    }
}
