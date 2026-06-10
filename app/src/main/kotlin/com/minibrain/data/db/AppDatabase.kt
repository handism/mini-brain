package com.minibrain.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.minibrain.data.db.daos.ChatMessageDao
import com.minibrain.data.db.daos.ChatSessionDao
import com.minibrain.data.db.daos.ChunkDao
import com.minibrain.data.db.daos.DocumentDao
import com.minibrain.data.db.daos.FolderEmbeddingDao
import com.minibrain.data.db.entities.ChatMessageEntity
import com.minibrain.data.db.entities.ChatSessionEntity
import com.minibrain.data.db.entities.ChunkEntity
import com.minibrain.data.db.entities.DocumentEntity
import com.minibrain.data.db.entities.FolderEmbeddingEntity
import com.minibrain.data.db.entities.MessageRole

class Converters {
    @TypeConverter
    fun fromRole(role: MessageRole): String = role.name

    @TypeConverter
    fun toRole(name: String): MessageRole = MessageRole.valueOf(name)
}

private const val CREATE_CHUNKS_FTS = """
    CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts4(
        text_bigram,
        heading_bigram,
        tokenize=unicode61
    )
"""

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(CREATE_CHUNKS_FTS)
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE documents ADD COLUMN headings TEXT")
        db.execSQL("ALTER TABLE documents ADD COLUMN first_para TEXT")
        db.execSQL("ALTER TABLE documents ADD COLUMN tags TEXT")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE documents ADD COLUMN documentDate TEXT")
    }
}

private const val CREATE_FOLDER_EMBEDDINGS = """
    CREATE TABLE IF NOT EXISTS folder_embeddings (
        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        path TEXT NOT NULL,
        treeUri TEXT NOT NULL,
        embedding BLOB NOT NULL
    )
"""

private const val CREATE_FOLDER_EMBEDDINGS_INDEX = """
    CREATE UNIQUE INDEX IF NOT EXISTS index_folder_embeddings_path_treeUri
        ON folder_embeddings(path, treeUri)
"""

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(CREATE_FOLDER_EMBEDDINGS)
        db.execSQL(CREATE_FOLDER_EMBEDDINGS_INDEX)
    }
}

@Database(
    entities = [DocumentEntity::class, ChunkEntity::class, ChatSessionEntity::class, ChatMessageEntity::class, FolderEmbeddingEntity::class],
    version = 5,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun chunkDao(): ChunkDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun folderEmbeddingDao(): FolderEmbeddingDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "minibrain.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            db.execSQL(CREATE_CHUNKS_FTS)
                            db.execSQL(CREATE_FOLDER_EMBEDDINGS)
                            db.execSQL(CREATE_FOLDER_EMBEDDINGS_INDEX)
                        }
                    })
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
