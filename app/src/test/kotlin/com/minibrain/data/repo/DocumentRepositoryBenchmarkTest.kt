package com.minibrain.data.repo

import org.junit.Test
import org.junit.Assert.*
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import kotlin.system.measureTimeMillis
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DocumentRepositoryBenchmarkTest {

    @Test
    fun benchmarkFtsInsert() {
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

        // Simulate insertFts without transaction
        val sql = "INSERT OR REPLACE INTO chunks_fts(rowid, text_bigram, heading_bigram) VALUES (?, ?, ?)"

        val time1 = measureTimeMillis {
            for (file in 1..100) {
                // compile inside loop
                val stmt = db.compileStatement(sql)
                try {
                    for (i in 1..10) {
                        stmt.bindLong(1, (file * 100 + i).toLong())
                        stmt.bindString(2, "text")
                        stmt.bindString(3, "heading")
                        stmt.executeInsert()
                        stmt.clearBindings()
                    }
                } finally {
                    stmt.close()
                }
            }
        }

        db.execSQL("DELETE FROM chunks_fts")

        val time2 = measureTimeMillis {
            val stmt = db.compileStatement(sql)
            try {
                for (file in 1..100) {
                    for (i in 1..10) {
                        stmt.bindLong(1, (file * 100 + i).toLong())
                        stmt.bindString(2, "text")
                        stmt.bindString(3, "heading")
                        stmt.executeInsert()
                        stmt.clearBindings()
                    }
                }
            } finally {
                stmt.close()
            }
        }

        db.execSQL("DELETE FROM chunks_fts")

        val time3 = measureTimeMillis {
            val stmt = db.compileStatement(sql)
            try {
                for (file in 1..100) {
                    db.beginTransaction()
                    try {
                        for (i in 1..10) {
                            stmt.bindLong(1, (file * 100 + i).toLong())
                            stmt.bindString(2, "text")
                            stmt.bindString(3, "heading")
                            stmt.executeInsert()
                            stmt.clearBindings()
                        }
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                }
            } finally {
                stmt.close()
            }
        }

        println("Baseline (compiling every time, no transaction): $time1 ms")
        println("Reusing stmt (no transaction): $time2 ms")
        println("Reusing stmt (with transaction): $time3 ms")
    }
}
