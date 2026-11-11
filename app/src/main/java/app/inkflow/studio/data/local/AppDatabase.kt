package app.inkflow.studio.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.inkflow.studio.data.local.dao.DocumentDao
import app.inkflow.studio.data.local.dao.FolderDao
import app.inkflow.studio.data.local.dao.PageDao
import app.inkflow.studio.data.local.dao.StudyDao
import app.inkflow.studio.data.local.entity.DocumentEntity
import app.inkflow.studio.data.local.entity.FolderEntity
import app.inkflow.studio.data.local.entity.PageEntity
import app.inkflow.studio.data.local.entity.StudyCardEntity

@Database(
    entities = [
        DocumentEntity::class,
        FolderEntity::class,
        PageEntity::class,
        StudyCardEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun folderDao(): FolderDao
    abstract fun pageDao(): PageDao
    abstract fun studyDao(): StudyDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pages ADD COLUMN pdfPageIndex INTEGER DEFAULT NULL")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "inkflow_studio.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
