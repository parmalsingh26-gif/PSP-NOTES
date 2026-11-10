package app.inkflow.studio.data.repository

import app.inkflow.studio.data.local.AppDatabase
import app.inkflow.studio.data.local.entity.StudyCardEntity
import app.inkflow.studio.domain.model.ReviewRating
import app.inkflow.studio.domain.model.StudyCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

class StudyRepository(database: AppDatabase) {
    private val studyDao = database.studyDao()

    fun getCardsForDocument(documentId: String): Flow<List<StudyCard>> =
        studyDao.getCardsForDocument(documentId).map { list -> list.map { it.toDomain() } }

    fun getAllCards(): Flow<List<StudyCard>> =
        studyDao.getAllCards().map { list -> list.map { it.toDomain() } }

    suspend fun getDueCards(): List<StudyCard> = withContext(Dispatchers.IO) {
        studyDao.getDueCards().map { it.toDomain() }
    }

    suspend fun saveCard(documentId: String, front: String, back: String): StudyCard = withContext(Dispatchers.IO) {
        val card = StudyCard(
            id = UUID.randomUUID().toString(),
            documentId = documentId,
            front = front,
            back = back
        )
        studyDao.insertOrUpdate(StudyCardEntity.fromDomain(card))
        card
    }

    suspend fun saveCards(cards: List<StudyCard>) = withContext(Dispatchers.IO) {
        studyDao.insertAll(cards.map { StudyCardEntity.fromDomain(it) })
    }

    suspend fun updateCard(card: StudyCard) = withContext(Dispatchers.IO) {
        studyDao.insertOrUpdate(StudyCardEntity.fromDomain(card))
    }

    suspend fun deleteCard(cardId: String) = withContext(Dispatchers.IO) {
        studyDao.deleteCard(cardId)
    }

    suspend fun processReview(card: StudyCard, rating: ReviewRating): StudyCard = withContext(Dispatchers.IO) {
        // SuperMemo SM-2 Algorithm
        val q = when (rating) {
            ReviewRating.AGAIN -> 1
            ReviewRating.HARD -> 2
            ReviewRating.GOOD -> 3
            ReviewRating.EASY -> 5
        }

        var repetition = card.repetitionCount
        var interval = card.intervalDays
        var ease = card.easeFactor

        // Calculate new Ease Factor: EF' = EF + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02))
        ease += (0.1f - (5 - q) * (0.08f + (5 - q) * 0.02f))
        if (ease < 1.3f) ease = 1.3f

        if (q < 3) {
            // Failure (Again or Hard): reset repetitions
            repetition = 0
            interval = 1
        } else {
            // Success (Good or Easy)
            interval = when (repetition) {
                0 -> 1
                1 -> 6
                else -> (interval * ease).toInt().coerceAtLeast(1)
            }
            repetition += 1
        }

        val now = System.currentTimeMillis()
        val nextDue = now + interval.toLong() * 24 * 60 * 60 * 1000

        val updatedCard = card.copy(
            repetitionCount = repetition,
            intervalDays = interval,
            easeFactor = ease,
            nextReviewTimestamp = nextDue,
            lastReviewTimestamp = now
        )
        studyDao.insertOrUpdate(StudyCardEntity.fromDomain(updatedCard))
        updatedCard
    }
}

