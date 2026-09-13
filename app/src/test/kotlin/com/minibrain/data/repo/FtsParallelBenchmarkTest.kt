package com.minibrain.data.repo

import org.junit.Test
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import kotlin.system.measureTimeMillis
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.minibrain.data.db.entities.ChunkEntity
import com.minibrain.data.search.NGramTokenizer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FtsParallelBenchmarkTest {

    @Test
    fun benchmarkFtsInsertParallel() = runBlocking {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            RuntimeEnvironment.getApplication()
        )
        .name(null)
        .callback(object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE chunks_fts(rowid INTEGER PRIMARY KEY, text_bigram TEXT, heading_bigram TEXT)")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        })
        .build()

        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(config)
        val db = helper.writableDatabase

        val sql = "INSERT OR REPLACE INTO chunks_fts(rowid, text_bigram, heading_bigram) VALUES (?, ?, ?)"

        // Generate dummy entities
        val entities = (1..50000).map {
            ChunkEntity(docId = 1L, text = "This is a dummy text for chunk $it. It has some more words to make it slightly longer and more realistic for a search bigram test. Some japanese here 検索.", headingPath = "Heading 1 > Heading 2", embedding = ByteArray(0))
        }
        val ids = (1..50000).map { it.toLong() }

        val timeSequential = measureTimeMillis {
            val stmt = db.compileStatement(sql)
            db.beginTransaction()
            try {
                ids.zip(entities).forEach { (id, entity) ->
                    stmt.bindLong(1, id)
                    val textBigrams = NGramTokenizer.toBigrams(entity.text)
                    stmt.bindString(2, textBigrams)

                    val headingBigrams = NGramTokenizer.toBigrams(entity.headingPath)
                    stmt.bindString(3, headingBigrams)
                    stmt.executeInsert()
                    stmt.clearBindings()
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
                stmt.close()
            }
        }

        db.execSQL("DELETE FROM chunks_fts")

        val timeParallel = measureTimeMillis {
            val bigrams = coroutineScope {
                entities.map { entity ->
                    async(Dispatchers.Default) {
                        NGramTokenizer.toBigrams(entity.text) to NGramTokenizer.toBigrams(entity.headingPath)
                    }
                }.awaitAll()
            }
            val stmt = db.compileStatement(sql)
            db.beginTransaction()
            try {
                ids.zip(bigrams).forEach { (id, bg) ->
                    stmt.bindLong(1, id)
                    stmt.bindString(2, bg.first)
                    stmt.bindString(3, bg.second)
                    stmt.executeInsert()
                    stmt.clearBindings()
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
                stmt.close()
            }
        }

        println("Sequential: $timeSequential ms")
        println("Parallel: $timeParallel ms")
    }
}
