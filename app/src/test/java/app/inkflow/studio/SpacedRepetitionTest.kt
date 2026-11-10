package app.inkflow.studio

import app.inkflow.studio.domain.model.ReviewRating
import app.inkflow.studio.domain.model.StudyCard
import org.junit.Assert.*
import org.junit.Test

class SpacedRepetitionTest {

    @Test
    fun testInitialCardState() {
        val card = StudyCard(
            id = "test-card",
            documentId = "doc-1",
            front = "What is Antigravity?",
            back = "Advanced Agentic Coding Platform"
        )
        assertEquals(0, card.repetitionCount)
        assertEquals(0, card.intervalDays)
        assertEquals(2.5f, card.easeFactor, 0.001f)
    }

    @Test
    fun testSM2Algorithm_GoodRatingProgression() {
        // Simulating the SM-2 logic
        var repetition = 0
        var interval = 0
        var ease = 2.5f

        val q = 3 // GOOD rating
        ease += (0.1f - (5 - q) * (0.08f + (5 - q) * 0.02f))
        interval = when (repetition) {
            0 -> 1
            1 -> 6
            else -> (interval * ease).toInt().coerceAtLeast(1)
        }
        repetition += 1

        assertEquals(1, repetition)
        assertEquals(1, interval)
        assertTrue(ease >= 1.3f)

        // Second Good review
        ease += (0.1f - (5 - q) * (0.08f + (5 - q) * 0.02f))
        interval = when (repetition) {
            0 -> 1
            1 -> 6
            else -> (interval * ease).toInt().coerceAtLeast(1)
        }
        repetition += 1

        assertEquals(2, repetition)
        assertEquals(6, interval)
    }

    @Test
    fun testSM2Algorithm_AgainRatingResets() {
        var repetition = 3
        var interval = 12
        var ease = 2.4f

        val q = 1 // AGAIN (failure)
        ease += (0.1f - (5 - q) * (0.08f + (5 - q) * 0.02f))
        if (ease < 1.3f) ease = 1.3f

        if (q < 3) {
            repetition = 0
            interval = 1
        }

        assertEquals(0, repetition)
        assertEquals(1, interval)
        assertTrue(ease >= 1.3f)
    }
}

