package com.minibrain.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.minibrain.data.db.entities.MessageRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {

    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun testDaosAreProvided() {
        assertNotNull(db.documentDao())
        assertNotNull(db.chunkDao())
        assertNotNull(db.chatSessionDao())
        assertNotNull(db.chatMessageDao())
        assertNotNull(db.folderEmbeddingDao())
    }

    @Test
    fun testConverters() {
        val converters = Converters()
        assertEquals("USER", converters.fromRole(MessageRole.USER))
        assertEquals("ASSISTANT", converters.fromRole(MessageRole.ASSISTANT))
        assertEquals(MessageRole.USER, converters.toRole("USER"))
        assertEquals(MessageRole.ASSISTANT, converters.toRole("ASSISTANT"))
    }

    @Test
    fun testGetInstance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instance1 = AppDatabase.getInstance(context)
        val instance2 = AppDatabase.getInstance(context)

        assertNotNull(instance1)
        assertSame(instance1, instance2)

        // Clean up
        instance1.close()
    }
}
