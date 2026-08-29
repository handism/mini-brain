package com.minibrain.data.db.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderEmbeddingEntityTest {

    @Test
    fun `equals returns true for same instance`() {
        val entity = FolderEmbeddingEntity(
            id = 1L,
            path = "path/to/folder",
            treeUri = "content://tree/uri",
            embedding = byteArrayOf(1, 2, 3)
        )
        assertTrue(entity == entity)
    }

    @Test
    fun `equals returns true when all fields including embedding match`() {
        val entity1 = FolderEmbeddingEntity(
            id = 1L,
            path = "path/to/folder",
            treeUri = "content://tree/uri",
            embedding = byteArrayOf(1, 2, 3)
        )
        val entity2 = FolderEmbeddingEntity(
            id = 1L,
            path = "path/to/folder",
            treeUri = "content://tree/uri",
            embedding = byteArrayOf(1, 2, 3)
        )
        assertTrue(entity1 == entity2)
        assertTrue(entity2 == entity1)
    }

    @Test
    fun `equals returns false when embedding is different`() {
        val entity1 = FolderEmbeddingEntity(
            id = 1L,
            path = "path/to/folder",
            treeUri = "content://tree/uri",
            embedding = byteArrayOf(1, 2, 3)
        )
        val entity2 = FolderEmbeddingEntity(
            id = 1L,
            path = "path/to/folder",
            treeUri = "content://tree/uri",
            embedding = byteArrayOf(4, 5, 6)
        )
        assertFalse(entity1 == entity2)
        assertFalse(entity2 == entity1)
    }

    @Test
    fun `equals returns false for null`() {
        val entity = FolderEmbeddingEntity(
            id = 1L,
            path = "path/to/folder",
            treeUri = "content://tree/uri",
            embedding = byteArrayOf(1, 2, 3)
        )
        assertFalse(entity.equals(null))
    }

    @Test
    fun `equals returns false for different type`() {
        val entity = FolderEmbeddingEntity(
            id = 1L,
            path = "path/to/folder",
            treeUri = "content://tree/uri",
            embedding = byteArrayOf(1, 2, 3)
        )
        assertFalse(entity.equals("some string"))
    }

    @Test
    fun `equals returns false for different id`() {
        val entity1 = FolderEmbeddingEntity(
            id = 1L,
            path = "path/to/folder",
            treeUri = "content://tree/uri",
            embedding = byteArrayOf(1, 2, 3)
        )
        val entity2 = FolderEmbeddingEntity(
            id = 2L,
            path = "path/to/folder",
            treeUri = "content://tree/uri",
            embedding = byteArrayOf(1, 2, 3)
        )
        assertFalse(entity1 == entity2)
    }

    @Test
    fun `equals returns false for different path`() {
        val entity1 = FolderEmbeddingEntity(
            id = 1L,
            path = "path/to/folder1",
            treeUri = "content://tree/uri",
            embedding = byteArrayOf(1, 2, 3)
        )
        val entity2 = FolderEmbeddingEntity(
            id = 1L,
            path = "path/to/folder2",
            treeUri = "content://tree/uri",
            embedding = byteArrayOf(1, 2, 3)
        )
        assertFalse(entity1 == entity2)
    }

    @Test
    fun `equals returns false for different treeUri`() {
        val entity1 = FolderEmbeddingEntity(
            id = 1L,
            path = "path/to/folder",
            treeUri = "content://tree/uri1",
            embedding = byteArrayOf(1, 2, 3)
        )
        val entity2 = FolderEmbeddingEntity(
            id = 1L,
            path = "path/to/folder",
            treeUri = "content://tree/uri2",
            embedding = byteArrayOf(1, 2, 3)
        )
        assertFalse(entity1 == entity2)
    }

    @Test
    fun `hashCode is consistent with equals`() {
        val entity1 = FolderEmbeddingEntity(
            id = 1L,
            path = "path/to/folder",
            treeUri = "content://tree/uri",
            embedding = byteArrayOf(1, 2, 3)
        )
        val entity2 = FolderEmbeddingEntity(
            id = 1L,
            path = "path/to/folder",
            treeUri = "content://tree/uri",
            embedding = byteArrayOf(1, 2, 3)
        )
        assertEquals(entity1.hashCode(), entity2.hashCode())
    }
}
