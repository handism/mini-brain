package com.minibrain.data.db.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentEntityTest {

    @Test
    fun `can instantiate DocumentEntity with required fields`() {
        val entity = DocumentEntity(
            treeUri = "content://tree",
            fileUri = "content://file",
            fileName = "test.txt",
            relativePath = "documents/test.txt",
            lastModified = 123456789L,
            contentHash = "hash123"
        )

        assertEquals(0L, entity.id)
        assertEquals("content://tree", entity.treeUri)
        assertEquals("content://file", entity.fileUri)
        assertEquals("test.txt", entity.fileName)
        assertEquals("documents/test.txt", entity.relativePath)
        assertEquals(123456789L, entity.lastModified)
        assertEquals("hash123", entity.contentHash)
        assertNull(entity.headings)
        assertNull(entity.firstParagraph)
        assertNull(entity.tags)
        assertNull(entity.documentDate)
    }

    @Test
    fun `can instantiate DocumentEntity with all fields`() {
        val entity = DocumentEntity(
            id = 1L,
            treeUri = "content://tree",
            fileUri = "content://file",
            fileName = "test.txt",
            relativePath = "documents/test.txt",
            lastModified = 123456789L,
            contentHash = "hash123",
            headings = "heading1",
            firstParagraph = "first paragraph",
            tags = "tag1,tag2",
            documentDate = "2023-01-01"
        )

        assertEquals(1L, entity.id)
        assertEquals("content://tree", entity.treeUri)
        assertEquals("content://file", entity.fileUri)
        assertEquals("test.txt", entity.fileName)
        assertEquals("documents/test.txt", entity.relativePath)
        assertEquals(123456789L, entity.lastModified)
        assertEquals("hash123", entity.contentHash)
        assertEquals("heading1", entity.headings)
        assertEquals("first paragraph", entity.firstParagraph)
        assertEquals("tag1,tag2", entity.tags)
        assertEquals("2023-01-01", entity.documentDate)
    }
}
