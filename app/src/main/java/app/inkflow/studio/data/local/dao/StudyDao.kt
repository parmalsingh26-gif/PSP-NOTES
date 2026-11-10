package app.inkflow.studio.data.local.dao

import androidx.room.*
import app.inkflow.studio.data.local.entity.StudyCardEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StudyDao {
    @Query("SELECT * FROM study_cards WHERE documentId = :documentId ORDER BY nextReviewTimestamp ASC")
    fun getCardsForDocument(documentId: String): Flow<List<StudyCardEntity>>

    @Query("SELECT * FROM study_cards ORDER BY nextReviewTimestamp ASC")
    fun getAllCards(): Flow<List<StudyCardEntity>>

    @Query("SELECT * FROM study_cards WHERE nextReviewTimestamp <= :currentTimestamp ORDER BY nextReviewTimestamp ASC")
    suspend fun getDueCards(currentTimestamp: Long = System.currentTimeMillis()): List<StudyCardEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(card: StudyCardEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cards: List<StudyCardEntity>)

    @Query("DELETE FROM study_cards WHERE id = :cardId")
    suspend fun deleteCard(cardId: String)

    @Query("DELETE FROM study_cards WHERE documentId = :documentId")
    suspend fun deleteCardsForDocument(documentId: String)
}

