package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive

/**
 * One answer uses a single reveal clock so multiple Markdown blocks never "type" in the same frame.
 *
 * Parsing and text layout only happen when the target text changes; per-frame advancement only updates plain fields and calls
 * [invalidateDraw]. An extra measurement is requested only when the reveal crosses into a new line to grow the message height,
 * and Compose State is never written throughout, so character frames trigger neither recomposition nor relayout.
 */
@Stable
internal class SmoothTextRevealCoordinator {
    private val records = sortedMapOf<RevealBlockKey, RevealRecord>()
    private val wakeups = Channel<Unit>(capacity = Channel.CONFLATED)
    private val drainedState = MutableStateFlow(true)
    private val startedState = MutableStateFlow<Set<RevealBlockKey>>(emptySet())
    private var animationsPaused = false
    private var restoredSourceLength = 0

    /** Layout nodes may attach after the parent's restore callback; those old blocks
     * must also start complete rather than replaying from zero. */
    fun restoreHistoryThrough(sourceLength: Int) {
        restoredSourceLength = sourceLength.coerceAtLeast(0)
        pauseAnimationsAndCatchUp()
    }

    private var onRevealAdvanced: ((Float) -> Unit)? = null

    fun setOnRevealAdvanced(listener: ((Float) -> Unit)?) {
        onRevealAdvanced = listener
    }

    val drained: StateFlow<Boolean> = drainedState
    /** Blocks that have started revealing, so list markers share the body text's lifetime. */
    val started: StateFlow<Set<RevealBlockKey>> = startedState

    val isAnimationPaused: Boolean
        get() = animationsPaused

    /**
     * The frame clock stops while the page is invisible, but the runtime may keep appending text. Catch up to the current target directly,
     * and complete subsequent layout results immediately, so returning to the page does not replay backlogged reveal animations.
     */
    fun pauseAnimationsAndCatchUp() {
        animationsPaused = true
        records.values.forEach(::completeRecord)
        updateDrainedState()
        wakeups.trySend(Unit)
    }

    fun resumeAnimationsAfterCatchUp() {
        records.values.forEach(::completeRecord)
        updateDrainedState()
        animationsPaused = false
        wakeups.trySend(Unit)
    }

    /** Pause subsequent session writes: shown text stays caught up, and newly arriving text restarts the typewriter. */
    fun resumeAnimationsWithoutCatchingUp() {
        animationsPaused = false
        updateDrainedState()
        wakeups.trySend(Unit)
    }

    fun retainBlocks(activeBlocks: Set<RevealBlockKey>) {
        val iterator = records.iterator()
        var removedPendingBlock = false
        while (iterator.hasNext()) {
            val (_, record) = iterator.next()
            if (record.key !in activeBlocks) {
                removedPendingBlock = removedPendingBlock || record.progress < record.targetCount
                iterator.remove()
            }
        }
        val retainedStarted = startedState.value.intersect(activeBlocks)
        if (retainedStarted != startedState.value) {
            startedState.value = retainedStarted
        }
        if (removedPendingBlock || records.none { (_, record) -> record.progress < record.targetCount }) {
            updateDrainedState()
        }
        wakeups.trySend(Unit)
    }

    fun attach(
        key: RevealBlockKey,
        node: SmoothTextRevealNode,
        text: String?,
        layoutResult: TextLayoutResult?,
    ) {
        val record = records.getOrPut(key) { RevealRecord(key) }
        record.node = node
        if (text != null && layoutResult != null) {
            updateRecord(record, text, layoutResult)
        }
        wakeups.trySend(Unit)
    }

    fun detach(key: RevealBlockKey, node: SmoothTextRevealNode) {
        val record = records[key]?.takeIf { it.node === node } ?: return
        record.node = null
        // Blocks that left composition no longer consume the frame clock; completed progress is kept, and only newly added text reveals on remount.
        completeRecord(record)
        updateDrainedState()
        wakeups.trySend(Unit)
    }

    fun updateLayout(
        key: RevealBlockKey,
        node: SmoothTextRevealNode?,
        text: String,
        layoutResult: TextLayoutResult,
    ) {
        val record = records.getOrPut(key) { RevealRecord(key) }
        if (node != null) record.node = node
        updateRecord(record, text, layoutResult)
        wakeups.trySend(Unit)
    }

    fun drawSnapshot(key: RevealBlockKey): RevealDrawSnapshot? {
        val record = records[key] ?: return null
        if (record.layoutResult == null) return null
        return record.drawSnapshot
    }

    suspend fun runFrameClock() {
        while (currentCoroutineContext().isActive) {
            val active = firstPendingRecord()
            if (active == null) {
                updateDrainedState()
                wakeups.receive()
                continue
            }

            drainedState.value = false
            var previousFrameNanos = withFrameNanos { it }
            while (currentCoroutineContext().isActive) {
                val record = firstPendingRecord() ?: break
                val frameNanos = withFrameNanos { it }
                StreamPerformanceDiagnostics.record("reveal.frameGap", frameNanos - previousFrameNanos)
                val elapsedSeconds = ((frameNanos - previousFrameNanos) / NANOS_PER_SECOND)
                    .coerceIn(0f, MAX_FRAME_DELTA_SECONDS)
                previousFrameNanos = frameNanos

                val totalBacklog = records.values.sumOf { candidate ->
                    max(0.0, (candidate.targetCount - candidate.progress).toDouble())
                }.toFloat()
                StreamPerformanceDiagnostics.record("reveal.backlog", value = totalBacklog.toLong())
                val previous = record.progress
                record.progress = advanceSmoothReveal(
                    current = record.progress,
                    target = record.targetCount,
                    elapsedSeconds = elapsedSeconds,
                    totalBacklog = totalBacklog,
                )
                val delta = record.progress - previous
                if (delta > 0f) onRevealAdvanced?.invoke(delta)
                if (record.progress > 0f && record.key !in startedState.value) {
                    startedState.value = startedState.value + record.key
                }
                record.node?.onRevealDataChanged()
            }
        }
    }

    private fun updateRecord(
        record: RevealRecord,
        text: String,
        layoutResult: TextLayoutResult,
    ) {
        if (record.text == text && record.layoutResult === layoutResult) return
        val firstLayoutOfRestoredBlock = record.layoutResult == null && record.key.sourceOffset < restoredSourceLength
        if (text != record.text) {
            // Streaming text is only appended, never edited, but inline-syntax closures (**bold**, `code`, collapsed links, and the like) can make
            // the rendered text shorter or shifted by dropping markup characters. Progress must then only move forward monotonically: on any regression,
            // revealed text would vanish and retype, flickering the output.
            record.boundaries = updateGraphemeBoundaries(
                previousText = record.text,
                previousBoundaries = record.boundaries,
                text = text,
            )
            record.text = text
            record.targetCount = record.boundaries.lastIndex.toFloat()
            record.progress = record.progress.coerceAtMost(record.targetCount)
        }
        if (record.layoutResult !== layoutResult) {
            record.layoutResult = layoutResult
        }
        if (animationsPaused || record.node == null || firstLayoutOfRestoredBlock) completeRecord(record)
        updateDrainedState()
        record.node?.onRevealDataChanged()
    }

    private fun completeRecord(record: RevealRecord) {
        record.progress = record.targetCount
        if (record.targetCount > 0f && record.key !in startedState.value) {
            startedState.value = startedState.value + record.key
        }
        record.node?.onRevealDataChanged()
    }

    private fun firstPendingRecord(): RevealRecord? = records.values.firstOrNull { record ->
        record.progress < record.targetCount && record.node != null && record.layoutResult != null
    }

    private fun updateDrainedState() {
        drainedState.value = records.values.none { record -> record.progress < record.targetCount }
    }
}

@JvmInline
internal value class RevealBlockKey(val sourceOffset: Int) : Comparable<RevealBlockKey> {
    override fun compareTo(other: RevealBlockKey): Int = sourceOffset.compareTo(other.sourceOffset)
}

@Stable
internal class SmoothTextRevealState(
    val key: RevealBlockKey,
    private val coordinator: SmoothTextRevealCoordinator,
) {
    private var node: SmoothTextRevealNode? = null
    private var text: String? = null
    private var layoutResult: TextLayoutResult? = null

    fun onTextLayout(text: String, layoutResult: TextLayoutResult) {
        this.text = text
        this.layoutResult = layoutResult
        coordinator.updateLayout(key, node, text, layoutResult)
    }

    internal fun attach(node: SmoothTextRevealNode) {
        this.node = node
        coordinator.attach(key, node, text, layoutResult)
    }

    internal fun detach(node: SmoothTextRevealNode) {
        if (this.node === node) this.node = null
        coordinator.detach(key, node)
    }

    internal fun drawSnapshot(): RevealDrawSnapshot? = coordinator.drawSnapshot(key)
}

@Composable
internal fun rememberSmoothTextRevealState(
    key: RevealBlockKey,
    coordinator: SmoothTextRevealCoordinator,
): SmoothTextRevealState = remember(key, coordinator) {
    SmoothTextRevealState(key, coordinator)
}

internal fun Modifier.smoothTextReveal(state: SmoothTextRevealState): Modifier =
    this then SmoothTextRevealElement(state)

private data class SmoothTextRevealElement(
    val state: SmoothTextRevealState,
) : ModifierNodeElement<SmoothTextRevealNode>() {
    override fun create(): SmoothTextRevealNode = SmoothTextRevealNode(state)

    override fun update(node: SmoothTextRevealNode) {
        node.updateState(state)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "smoothTextReveal"
    }
}

internal class SmoothTextRevealNode(
    private var state: SmoothTextRevealState,
) : Modifier.Node(), DrawModifierNode, LayoutModifierNode {
    private val alphaPaint = Paint()
    private var cachedLayoutResult: TextLayoutResult? = null
    private var cachedFullCount = -1
    private var cachedFullPath: Path? = null
    private var cachedNextPath: Path? = null
    private var cachedVisibleHeight = -1

    override fun onAttach() {
        state.attach(this)
        onRevealDataChanged()
    }

    override fun onDetach() {
        state.detach(this)
        clearPathCache()
        cachedVisibleHeight = -1
    }

    fun updateState(next: SmoothTextRevealState) {
        if (state === next) return
        if (isAttached) state.detach(this)
        state = next
        clearPathCache()
        cachedVisibleHeight = -1
        if (isAttached) {
            state.attach(this)
            onRevealDataChanged()
        }
    }

    fun onRevealDataChanged() {
        val visibleHeight = state.visibleHeightPx()
        if (visibleHeight != cachedVisibleHeight) {
            cachedVisibleHeight = visibleHeight
            if (isAttached) {
                StreamPerformanceDiagnostics.record("reveal.remeasure")
                invalidateMeasurement()
            }
        }
        if (isAttached) invalidateDraw()
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        // Reattached/replaced Markdown nodes may not have delivered onTextLayout yet.
        // Missing reveal metadata must not collapse an already measured paragraph to zero.
        val visibleHeight = if (state.drawSnapshot() == null) placeable.height
            else state.visibleHeightPx().coerceAtMost(placeable.height)
        cachedVisibleHeight = visibleHeight
        val measuredHeight = visibleHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
        return layout(placeable.width, measuredHeight) {
            placeable.place(0, 0)
        }
    }

    override fun ContentDrawScope.draw() {
        val snapshot = state.drawSnapshot()
        if (snapshot == null) {
            drawContent()
            return
        }
        val contentScope = this
        val targetCount = snapshot.boundaries.lastIndex
        if (targetCount <= 0 || snapshot.progress >= targetCount) {
            drawContent()
            return
        }

        val fullCount = floor(snapshot.progress).toInt().coerceIn(0, targetCount)
        ensurePaths(snapshot, fullCount)
        cachedFullPath?.let { path ->
            clipPath(path) { contentScope.drawContent() }
        }

        val partialAlpha = (snapshot.progress - fullCount).coerceIn(0f, 1f)
        if (partialAlpha > 0f) {
            cachedNextPath?.let { path ->
                clipPath(path) {
                    alphaPaint.alpha = partialAlpha
                    drawContext.canvas.saveLayer(
                        // Only the fading grapheme needs an offscreen alpha layer.
                        // A paragraph-sized layer grows with the answer on every frame.
                        path.getBounds(),
                        alphaPaint,
                    )
                    try {
                        contentScope.drawContent()
                    } finally {
                        drawContext.canvas.restore()
                    }
                }
            }
        }
    }

    private fun ensurePaths(snapshot: RevealDrawSnapshot, fullCount: Int) {
        val sameLayout = cachedLayoutResult === snapshot.layoutResult
        if (sameLayout && cachedFullCount == fullCount) return

        if (canAppendRevealPath(sameLayout, cachedFullCount, fullCount)) {
            // Fast output commonly reveals several graphemes per frame. Append only
            // that range, not a fresh path for the entire already-visible prefix.
            val textLength = snapshot.layoutResult.layoutInput.text.length
            val start = snapshot.boundaries[cachedFullCount].coerceIn(0, textLength)
            val end = snapshot.boundaries[fullCount].coerceIn(start, textLength)
            val addedPath = if (fullCount == cachedFullCount + 1) {
                cachedNextPath
            } else if (end > start) {
                snapshot.layoutResult.getPathForRange(start, end)
            } else null
            if (addedPath != null) {
                val accumulatedPath = cachedFullPath ?: Path()
                accumulatedPath.addPath(addedPath)
                cachedFullPath = accumulatedPath
            }
            cachedFullCount = fullCount
            cachedNextPath = nextGraphemePath(snapshot, fullCount)
            return
        }

        cachedLayoutResult = snapshot.layoutResult
        cachedFullCount = fullCount
        val textLength = snapshot.layoutResult.layoutInput.text.length
        val fullEnd = snapshot.boundaries[fullCount].coerceIn(0, textLength)
        cachedFullPath = if (fullEnd > 0) {
            snapshot.layoutResult.getPathForRange(0, fullEnd)
        } else {
            null
        }
        cachedNextPath = nextGraphemePath(snapshot, fullCount)
    }

    private fun nextGraphemePath(
        snapshot: RevealDrawSnapshot,
        fullCount: Int,
    ): Path? {
        val textLength = snapshot.layoutResult.layoutInput.text.length
        val start = snapshot.boundaries.getOrNull(fullCount)?.coerceIn(0, textLength)
            ?: return null
        val end = snapshot.boundaries.getOrNull(fullCount + 1)?.coerceIn(start, textLength)
            ?: return null
        return if (end > start) {
            snapshot.layoutResult.getPathForRange(start, end)
        } else {
            null
        }
    }

    private fun clearPathCache() {
        cachedLayoutResult = null
        cachedFullCount = -1
        cachedFullPath = null
        cachedNextPath = null
    }
}

internal class RevealDrawSnapshot(
    private val record: RevealRecord,
) {
    val layoutResult: TextLayoutResult
        get() = checkNotNull(record.layoutResult)
    val boundaries: IntArray
        get() = record.boundaries
    val progress: Float
        get() = record.progress
}

internal class RevealRecord(
    val key: RevealBlockKey,
) {
    val drawSnapshot = RevealDrawSnapshot(this)
    var node: SmoothTextRevealNode? = null
    var text: String = ""
    var layoutResult: TextLayoutResult? = null
    var boundaries: IntArray = intArrayOf(0)
    var progress: Float = 0f
    var targetCount: Float = 0f
}

private fun SmoothTextRevealState.visibleHeightPx(): Int {
    val snapshot = drawSnapshot() ?: return 0
    val layoutResult = snapshot.layoutResult
    val targetCount = snapshot.boundaries.lastIndex
    if (targetCount <= 0 || snapshot.progress >= targetCount) {
        return layoutResult.size.height
    }

    val visibleCount = ceil(snapshot.progress).toInt().coerceIn(0, targetCount)
    if (visibleCount == 0) return 0
    val textLength = layoutResult.layoutInput.text.length
    val visibleEnd = snapshot.boundaries[visibleCount].coerceIn(0, textLength)
    if (visibleEnd == 0 || layoutResult.lineCount == 0) return 0
    val line = layoutResult.getLineForOffset((visibleEnd - 1).coerceAtMost(textLength - 1))
    return ceil(layoutResult.getLineBottom(line)).toInt()
        .coerceIn(0, layoutResult.size.height)
}

internal fun graphemeBoundaries(text: String): IntArray {
    if (text.isEmpty()) return intArrayOf(0)

    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    val result = ArrayList<Int>(text.length + 1)
    var boundary = iterator.first()
    while (boundary != BreakIterator.DONE) {
        result += boundary
        boundary = iterator.next()
    }
    if (result.lastOrNull() != text.length) result += text.length
    return result.toIntArray()
}

/**
 * Maintain grapheme boundaries for append-only text increments.
 *
 * New content may extend the last grapheme of the old text, e.g. combining marks, ZWJ emoji, flags, and CRLF.
 * So results before the second-to-last boundary are kept, recomputing only the last old grapheme and the added suffix instead of
 * rescanning the whole answer for every streaming chunk.
 */
internal fun updateGraphemeBoundaries(
    previousText: String,
    previousBoundaries: IntArray,
    text: String,
): IntArray {
    if (
        previousText.isEmpty() ||
        !text.startsWith(previousText) ||
        previousBoundaries.isEmpty() ||
        previousBoundaries.first() != 0 ||
        previousBoundaries.last() != previousText.length
    ) {
        return graphemeBoundaries(text)
    }
    if (text == previousText) return previousBoundaries

    val restartBoundaryIndex = (previousBoundaries.lastIndex - 1).coerceAtLeast(0)
    val restartOffset = previousBoundaries[restartBoundaryIndex]
    val suffixBoundaries = graphemeBoundaries(text.substring(restartOffset))
    return IntArray(restartBoundaryIndex + suffixBoundaries.size).also { merged ->
        for (index in 0 until restartBoundaryIndex) {
            merged[index] = previousBoundaries[index]
        }
        suffixBoundaries.forEachIndexed { index, boundary ->
            merged[restartBoundaryIndex + index] = restartOffset + boundary
        }
    }
}

internal class AppendOnlyGraphemeIndex {
    private var indexedText = ""
    private var boundaries = intArrayOf(0)

    fun update(text: String) {
        boundaries = updateGraphemeBoundaries(
            previousText = indexedText,
            previousBoundaries = boundaries,
            text = text,
        )
        indexedText = text
    }

    fun endAfter(start: Int, maxGraphemes: Int): Int {
        val clampedStart = start.coerceIn(0, indexedText.length)
        if (clampedStart == indexedText.length || maxGraphemes <= 0) return clampedStart

        val foundIndex = boundaries.binarySearch(clampedStart)
        val firstEndIndex = if (foundIndex >= 0) foundIndex + 1 else -foundIndex - 1
        val endIndex = (firstEndIndex + maxGraphemes - 1).coerceAtMost(boundaries.lastIndex)
        return boundaries[endIndex]
    }
}

internal fun commonUtf16PrefixLength(first: String, second: String): Int {
    val limit = minOf(first.length, second.length)
    var index = 0
    while (index < limit && first[index] == second[index]) index += 1
    if (
        index in 1 until limit &&
        first[index - 1].isHighSurrogate() &&
        first[index].isLowSurrogate()
    ) {
        index -= 1
    }
    return index
}

internal fun smoothRevealSpeed(totalBacklog: Float): Float =
    max(BASE_REVEAL_GRAPHEMES_PER_SECOND, totalBacklog / TARGET_CATCH_UP_SECONDS)
        .coerceAtMost(MAX_REVEAL_GRAPHEMES_PER_SECOND)

internal fun advanceSmoothReveal(
    current: Float,
    target: Float,
    elapsedSeconds: Float,
    totalBacklog: Float,
): Float {
    if (current >= target) return target
    // The frame interval is already capped to MAX_FRAME_DELTA_SECONDS by the caller; the per-frame advance comes from the adaptive speed.
    // No additional hard cap of 1 grapheme per frame, or catch-up would stall under backlog and the output would lag the model permanently.
    val advance = (smoothRevealSpeed(totalBacklog) * elapsedSeconds).coerceAtLeast(0f)
    return (current + advance).coerceAtMost(target)
}

private const val NANOS_PER_SECOND = 1_000_000_000f
private const val MAX_FRAME_DELTA_SECONDS = 0.05f
private const val BASE_REVEAL_GRAPHEMES_PER_SECOND = 36f
private const val MAX_REVEAL_GRAPHEMES_PER_SECOND = 240f
private const val TARGET_CATCH_UP_SECONDS = 0.20f

/** Layout changes and progress corrections require rebuilding; forward batches do not. */
internal fun canAppendRevealPath(sameLayout: Boolean, cachedCount: Int, nextCount: Int): Boolean =
    sameLayout && cachedCount >= 0 && nextCount > cachedCount
