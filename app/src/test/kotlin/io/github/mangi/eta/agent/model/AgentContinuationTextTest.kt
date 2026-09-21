package io.github.mangi.eta.agent.model

import org.junit.Assert.*
import org.junit.Test

class AgentContinuationTextTest {
    @Test fun repeatedSentenceIsRemovedEvenAcrossSingleCharacterDeltas() {
        val filter = AgentContinuationText("The report has come in. He opens his inbox. ")
        val resumed = "He opens his inbox. He clicks open the attachment."
        val visible = resumed.map { filter.append(it.toString()) }.joinToString("") + filter.finish()
        assertEquals("He clicks open the attachment.", visible)
        assertEquals("He clicks open the attachment.", filter.normalize(resumed))
    }

    @Test fun longestExactOverlapWinsAndBodyRepetitionIsNotTouched() {
        val filter = AgentContinuationText("The beginning. He opens his inbox. He opens his inbox. ")
        assertEquals(
            "He clicks open the attachment. He opens his inbox.",
            filter.append("He opens his inbox. He opens his inbox. He clicks open the attachment. He opens his inbox.") + filter.finish(),
        )
    }

    @Test fun mismatchFlushesPendingTextAndShortCommonFragmentsArePreserved() {
        val filter = AgentContinuationText("He opens his inbox. ")
        assertEquals("", filter.append("He opens"))
        assertEquals("He opens the window.", filter.append(" the window."))
        assertEquals("", filter.finish())
        val short = AgentContinuationText("ok")
        assertEquals("ok! No problem.", short.append("ok! No problem.") + short.finish())
    }

    @Test fun sentenceSeamAcrossTwoWordsIsRemoved() {
        val filter = AgentContinuationText("He waves at me through the car window. ")
        val resumed = "He waves at me through the car window. That was the first time I felt so far from home."
        val visible = resumed.map { filter.append(it.toString()) }.joinToString("") + filter.finish()
        assertEquals("That was the first time I felt so far from home.", visible)
        assertEquals("That was the first time I felt so far from home.", filter.normalize(resumed))
    }

    @Test fun partialWordSeamWithoutPunctuationIsRemoved() {
        val filter = AgentContinuationText("My old clothes, my life. All that warmth ")
        val resumed = "All that warmth was sewn into my shoe soles, stitch by stitch."
        val visible = resumed.map { filter.append(it.toString()) }.joinToString("") + filter.finish()
        assertEquals("was sewn into my shoe soles, stitch by stitch.", visible)
        assertEquals("was sewn into my shoe soles, stitch by stitch.", filter.normalize(resumed))
    }

    @Test fun singleWordSeamIsRemovedWithoutEatingShortAcknowledgements() {
        val filter = AgentContinuationText("In itself, it is all about the ")
        val resumed = "the heart of the matter. There is no place like home."
        assertEquals("heart of the matter. There is no place like home.", filter.normalize(resumed))
        val ack = AgentContinuationText("ok")
        assertEquals("ok! No problem.", ack.append("ok! No problem.") + ack.finish())
    }

    @Test fun singleIdeographSeamIsRemovedWithoutEatingShortAcknowledgements() {
        val filter = AgentContinuationText("本身都是在跟这")
        val resumed = "这这世上对得起“还有个你可回”这句话。"
        val visible = resumed.map { filter.append(it.toString()) }.joinToString("") + filter.finish()
        assertEquals("这世上对得起“还有个你可回”这句话。", visible)
        assertEquals("这世上对得起“还有个你可回”这句话。", filter.normalize(resumed))
        val ack = AgentContinuationText("好。")
        assertEquals("好。没问题。", ack.append("好。没问题。") + ack.finish())
    }

    @Test fun pausedBeforeOverlapDecisionNeverLosesIncompleteNewText() {
        val filter = AgentContinuationText("He opens his inbox. ")
        assertEquals("", filter.append("He opens"))
        assertEquals("He opens", filter.finish())
        assertEquals("He opens", filter.normalize("He opens"))
    }

    @Test fun endReplacementAndFinalResponseUseSameSeamAndOnlyFirstTextBlockIsFiltered() {
        val filter = AgentContinuationTextEvents("He opens his inbox. ")
        filter.map(ProviderEvent.BlockStart(AssistantBlockKind.TEXT, 8))
        assertTrue(filter.map(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 8, "He opens")).isEmpty())
        val end = filter.map(
            ProviderEvent.BlockEnd(
                AssistantBlockKind.TEXT,
                8,
                content = "He opens his inbox. He clicks open the attachment.",
                replaceContent = true,
            ),
        )
        assertEquals("He clicks open the attachment.", (end.last() as ProviderEvent.BlockEnd).content)
        assertEquals("He clicks open the attachment.", filter.normalize("He opens his inbox. He clicks open the attachment."))
        val later = ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 9, "He opens his inbox.")
        assertEquals(listOf(later), filter.map(later))
    }

    @Test fun ordinaryRequestIsNotDeduplicatedAndTransportRetryResetsOpening() {
        val plain = AgentContinuationTextEvents("")
        assertEquals("He opens his inbox. He opens his inbox.", plain.normalize("He opens his inbox. He opens his inbox."))
        val filter = AgentContinuationTextEvents("He opens his inbox. ")
        filter.map(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "He opens"))
        filter.map(ProviderEvent.RequestStarted)
        val event = filter.map(
            ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "He opens his inbox. He taps the attachment to open it."),
        ).single() as ProviderEvent.BlockDelta
        assertEquals("He taps the attachment to open it.", event.delta)
    }
}
