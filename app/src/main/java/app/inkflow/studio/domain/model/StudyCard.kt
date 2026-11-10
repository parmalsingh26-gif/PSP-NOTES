package app.inkflow.studio.domain.model

enum class ReviewRating {
    AGAIN,
    HARD,
    GOOD,
    EASY
}

data class StudyCard(
    val id: String,
    val documentId: String,
    val front: String,
    val back: String,
    val intervalDays: Int = 0,
    val repetitionCount: Int = 0,
    val easeFactor: Float = 2.5f,
    val nextReviewTimestamp: Long = System.currentTimeMillis(),
    val lastReviewTimestamp: Long? = null
)

