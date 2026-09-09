package com.minibrain.data.db.daos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.minibrain.data.db.AppDatabase
import com.minibrain.data.db.entities.FolderEmbeddingEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FolderEmbeddingDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: FolderEmbeddingDao

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.folderEmbeddingDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun upsertAndGetAllByTree() = runBlocking {
        val entity1 = FolderEmbeddingEntity(
            path = "folder1",
            treeUri = "tree1",
            embedding = byteArrayOf(1, 2, 3)
        )
        val entity2 = FolderEmbeddingEntity(
            path = "folder2",
            treeUri = "tree1",
            embedding = byteArrayOf(4, 5, 6)
        )
        val entity3 = FolderEmbeddingEntity(
            path = "folder3",
            treeUri = "tree2",
            embedding = byteArrayOf(7, 8, 9)
        )

        dao.upsert(entity1)
        dao.upsert(entity2)
        dao.upsert(entity3)

        val resultsTree1 = dao.getAllByTree("tree1")
        assertEquals(2, resultsTree1.size)
        assertTrue(resultsTree1.any { it.path == "folder1" })
        assertTrue(resultsTree1.any { it.path == "folder2" })

        val resultsTree2 = dao.getAllByTree("tree2")
        assertEquals(1, resultsTree2.size)
        assertEquals("folder3", resultsTree2[0].path)
    }

    @Test
    fun upsertReplacesOnConflict() = runBlocking {
        val entity1 = FolderEmbeddingEntity(
            path = "folder1",
            treeUri = "tree1",
            embedding = byteArrayOf(1, 2, 3)
        )
        dao.upsert(entity1)

        val entity2 = FolderEmbeddingEntity(
            path = "folder1",
            treeUri = "tree1",
            embedding = byteArrayOf(9, 9, 9)
        )
        dao.upsert(entity2)

        val results = dao.getAllByTree("tree1")
        assertEquals(1, results.size)
        assertEquals((9).toByte(), results[0].embedding[0])
    }

    @Test
    fun upsertAllAndGetAllByTree() = runBlocking {
        val entities = listOf(
            FolderEmbeddingEntity(path = "folder1", treeUri = "tree1", embedding = byteArrayOf(1)),
            FolderEmbeddingEntity(path = "folder2", treeUri = "tree1", embedding = byteArrayOf(2)),
            FolderEmbeddingEntity(path = "folder3", treeUri = "tree2", embedding = byteArrayOf(3))
        )

        dao.upsertAll(entities)

        val resultsTree1 = dao.getAllByTree("tree1")
        assertEquals(2, resultsTree1.size)
    }

    @Test
    fun deleteAllByTree() = runBlocking {
        val entities = listOf(
            FolderEmbeddingEntity(path = "folder1", treeUri = "tree1", embedding = byteArrayOf(1)),
            FolderEmbeddingEntity(path = "folder2", treeUri = "tree1", embedding = byteArrayOf(2)),
            FolderEmbeddingEntity(path = "folder3", treeUri = "tree2", embedding = byteArrayOf(3))
        )

        dao.upsertAll(entities)

        dao.deleteAllByTree("tree1")

        val resultsTree1 = dao.getAllByTree("tree1")
        assertEquals(0, resultsTree1.size)

        val resultsTree2 = dao.getAllByTree("tree2")
        assertEquals(1, resultsTree2.size)
    }
}
