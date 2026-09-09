package com.minibrain.data.db.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentEntityTest {

    @Test
    fun `instantiation with all fields explicitly set`() {
        val entity = DocumentEntity(
            id = 1L,
            treeUri = "content://tree/uri",
            fileUri = "content://file/uri",
            fileName = "document.txt",
            relativePath = "path/to/document.txt",
            lastModified = 1234567890L,
            contentHash = "hash123",
            headings = "Heading 1, Heading 2",
            firstParagraph = "This is the first paragraph.",
            tags = "tag1, tag2",
            documentDate = "2023-10-27"
        )

        assertEquals(1L, entity.id)
        assertEquals("content://tree/uri", entity.treeUri)
        assertEquals("content://file/uri", entity.fileUri)
        assertEquals("document.txt", entity.fileName)
        assertEquals("path/to/document.txt", entity.relativePath)
        assertEquals(1234567890L, entity.lastModified)
        assertEquals("hash123", entity.contentHash)
        assertEquals("Heading 1, Heading 2", entity.headings)
        assertEquals("This is the first paragraph.", entity.firstParagraph)
        assertEquals("tag1, tag2", entity.tags)
        assertEquals("2023-10-27", entity.documentDate)
    }

    @Test
    fun `instantiation with default values for optional fields`() {
        val entity = DocumentEntity(
            treeUri = "content://tree/uri",
            fileUri = "content://file/uri",
            fileName = "document.txt",
            relativePath = "path/to/document.txt",
            lastModified = 1234567890L,
            contentHash = "hash123"
        )

        assertEquals(0L, entity.id)
        assertEquals("content://tree/uri", entity.treeUri)
        assertEquals("content://file/uri", entity.fileUri)
        assertEquals("document.txt", entity.fileName)
        assertEquals("path/to/document.txt", entity.relativePath)
        assertEquals(1234567890L, entity.lastModified)
        assertEquals("hash123", entity.contentHash)
        assertNull(entity.headings)
        assertNull(entity.firstParagraph)
        assertNull(entity.tags)
        assertNull(entity.documentDate)
    }
}
