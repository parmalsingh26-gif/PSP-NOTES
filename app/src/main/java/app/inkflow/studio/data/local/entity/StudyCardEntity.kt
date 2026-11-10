package app.inkflow.studio.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.inkflow.studio.domain.model.StudyCard

@Entity(tableName = "study_cards")
data class StudyCardEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val front: String,
    val back: String,
    val intervalDays: Int,
    val repetitionCount: Int,
    val easeFactor: Float,
    val nextReviewTimestamp: Long,
    val lastReviewTimestamp: Long?
) {
    fun toDomain(): StudyCard = StudyCard(
        id = id,
        documentId = documentId,
        front = front,
        back = back,
        intervalDays = intervalDays,
        repetitionCount = repetitionCount,
        easeFactor = easeFactor,
        nextReviewTimestamp = nextReviewTimestamp,
        lastReviewTimestamp = lastReviewTimestamp
    )

    companion object {
        fun fromDomain(card: StudyCard): StudyCardEntity = StudyCardEntity(
            id = card.id,
            documentId = card.documentId,
            front = card.front,
            back = card.back,
            intervalDays = card.intervalDays,
            repetitionCount = card.repetitionCount,
            easeFactor = card.easeFactor,
            nextReviewTimestamp = card.nextReviewTimestamp,
            lastReviewTimestamp = card.lastReviewTimestamp
        )
    }
}

