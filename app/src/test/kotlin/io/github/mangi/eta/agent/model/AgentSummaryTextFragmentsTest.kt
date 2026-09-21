package io.github.mangi.eta.agent.model

import org.junit.Assert.*
import org.junit.Test

class AgentSummaryTextFragmentsTest {
    private fun split(source: String, capacity: Int, max: Int = 32) = AgentSummaryTextFragments.split(
        source, max, checkCancellation = {}, fits = { source.codePointCount(it.start, it.end) <= capacity },
    )

    @Test fun fittingTextIsOneRange() {
        assertEquals(listOf(AgentSummaryTextFragments.Range(0, 3)), split("abc", 3))
    }

    @Test fun allWhitespaceAndBoundaryMarkersSurvive() {
        val text = "  start\n\n</history-fragment>\n```code```\tend  "
        val ranges = split(text, 4)
        assertEquals(text, ranges.joinToString("") { text.substring(it.start, it.end) })
        assertEquals(0, ranges.first().start)
        assertEquals(text.length, ranges.last().end)
        ranges.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
    }

    @Test fun unicodeSurrogatePairsNeverSplit() {
        val text = "😀A𠀀B😀"
        val ranges = split(text, 1)
        assertEquals(5, ranges.size)
        assertEquals(text, ranges.joinToString("") { text.substring(it.start, it.end) })
        assertTrue(ranges.all { text.codePointCount(it.start, it.end) == 1 })
    }

    @Test fun noRoomFailsInsteadOfEmptyInfiniteLoop() {
        val e = assertThrows(IllegalArgumentException::class.java) { split("a", 0) }
        assertTrue(e.message!!.contains("input budget"))
    }

    @Test fun excessiveFragmentsFailRatherThanSilentlyTruncating() {
        val e = assertThrows(IllegalArgumentException::class.java) { split("abcdef", 1, 5) }
        assertTrue(e.message!!.contains("Too many summary fragments"))
    }

    @Test fun cancellationIsCheckedInsideBinarySearch() {
        var checks = 0
        assertThrows(InterruptedException::class.java) {
            AgentSummaryTextFragments.split("a".repeat(1000), 32,
                checkCancellation = { if (++checks == 3) throw InterruptedException("cancel") },
                fits = { it.end - it.start <= 50 },
            )
        }
        assertEquals(3, checks)
    }

    @Test fun utf16OffsetsCanBeIncludedInRealBudget() {
        val source = "Middle😀".repeat(200)
        val ranges = AgentSummaryTextFragments.split(source, 32, {}, {
            AgentContextBudget.countTokens("offset=${it.start}..${it.end}\n" + source.substring(it.start, it.end)) <= 50
        })
        assertEquals(source, ranges.joinToString("") { source.substring(it.start, it.end) })
        assertTrue(ranges.all {
            AgentContextBudget.countTokens("offset=${it.start}..${it.end}\n" + source.substring(it.start, it.end)) <= 50
        })
    }
}
