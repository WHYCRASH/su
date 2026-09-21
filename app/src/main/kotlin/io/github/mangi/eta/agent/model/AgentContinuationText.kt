package io.github.mangi.eta.agent.model

/** Only removes an exact suffix/prefix overlap at an interrupted request's seam.
 * Buffers only an ambiguous opening, never scans or deduplicates the body.
 */
internal class AgentContinuationText(prefix: String) {
    private val tail = prefix.takeLast(2048)
    private val candidates = tail.indices.map { tail.substring(it) }.filter(::isSeamCandidate)
    private var opening = StringBuilder()
    private var decided = candidates.isEmpty()

    fun append(delta: String): String {
        if (decided) return delta
        opening.append(delta)
        val text = opening.toString()
        if (candidates.any { it.length > text.length && it.startsWith(text) }) return ""
        return resolve(text)
    }

    fun finish(): String = if (decided) "" else resolve(opening.toString())

    private fun resolve(text: String): String {
        val removed = candidates.filter { text.startsWith(it) }.maxOfOrNull { it.length } ?: 0
        decided = true
        opening.setLength(0)
        return text.drop(removed)
    }

    /** The provider's authoritative replacement/final response is still request-local. */
    fun normalize(text: String): String {
        val overlap = candidates.filter { text.startsWith(it) }.maxOfOrNull { it.length } ?: 0
        return text.drop(overlap)
    }

    private fun isSeamCandidate(candidate: String): Boolean {
        val visibleChars = candidate.filterNot { it.isWhitespace() }
        if (visibleChars.isEmpty()) return false
        val ideographs = visibleChars.filter { it.isIdeograph() }
        // A single ideograph at the pause point is a seam; a two-character acknowledgement (an
        // ideograph plus its terminator) is not: the terminator counts as a visible character but
        // it is not a repeated clause.
        if (ideographs.length <= 1 && visibleChars.length <= 2) {
            return candidate.length == 1 && ideographs.length == 1
        }
        return true
    }

    private fun Char.isIdeograph(): Boolean =
        this in '\u4e00'..'\u9fff' || this in '\u3400'..'\u4dbf'
}

internal class AgentContinuationTextEvents(private val prefix: String) {
    private var filter = AgentContinuationText(prefix)
    private var firstIndex: Int? = null
    private var firstEnded = false

    fun map(event: ProviderEvent): List<ProviderEvent> {
        if (event is ProviderEvent.RequestStarted) {
            filter = AgentContinuationText(prefix)
            firstIndex = null
            firstEnded = false
        }
        val kind = when (event) {
            is ProviderEvent.BlockStart -> event.kind
            is ProviderEvent.BlockDelta -> event.kind
            is ProviderEvent.BlockEnd -> event.kind
            else -> null
        }
        val index = when (event) {
            is ProviderEvent.BlockStart -> event.index
            is ProviderEvent.BlockDelta -> event.index
            is ProviderEvent.BlockEnd -> event.index
            else -> null
        }
        if (kind != AssistantBlockKind.TEXT || firstEnded) return listOf(event)
        if (firstIndex == null) firstIndex = index
        if (index != firstIndex) return finish() + event
        return when (event) {
            is ProviderEvent.BlockDelta -> filter.append(event.delta).takeIf { it.isNotEmpty() }
                ?.let { listOf(event.copy(delta = it)) }.orEmpty()
            is ProviderEvent.BlockEnd -> {
                val pending = finish().takeUnless { event.replaceContent }.orEmpty()
                pending + event.copy(content = filter.normalize(event.content))
            }
            else -> listOf(event)
        }
    }

    fun finish(): List<ProviderEvent> {
        if (firstEnded) return emptyList()
        firstEnded = true
        val delta = filter.finish()
        return if (delta.isEmpty() || firstIndex == null) emptyList()
        else listOf(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, firstIndex!!, delta))
    }

    fun normalize(text: String): String = filter.normalize(text)
}
