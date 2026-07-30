package com.minibrain.data.md

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.ByteArrayInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

class MdFileReaderTest {

    @MockK
    private lateinit var context: Context

    @MockK
    private lateinit var contentResolver: ContentResolver

    @MockK
    private lateinit var treeUri: Uri

    private val loggedMessages = mutableListOf<String>()
    private val fakeTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            loggedMessages.add(message)
        }
    }

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        Timber.plant(fakeTree)
        mockkStatic(DocumentFile::class)

        every { context.contentResolver } returns contentResolver
    }

    @After
    fun tearDown() {
        Timber.uproot(fakeTree)
        loggedMessages.clear()
        unmockkStatic(DocumentFile::class)
    }

    @Test
    fun `listMdFiles returns empty list when root is null`() {
        every { DocumentFile.fromTreeUri(context, treeUri) } returns null

        val result = MdFileReader.listMdFiles(context, treeUri)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `listMdFiles collects md files and traverses directories`() {
        val rootDir = mockk<DocumentFile>()
        every { DocumentFile.fromTreeUri(context, treeUri) } returns rootDir

        val subDir = mockk<DocumentFile>()
        every { subDir.isDirectory } returns true
        every { subDir.isFile } returns false
        every { subDir.name } returns "SubFolder"

        val mdFile1 = mockk<DocumentFile>()
        every { mdFile1.isDirectory } returns false
        every { mdFile1.isFile } returns true
        every { mdFile1.name } returns "file1.md"
        every { mdFile1.length() } returns 1024L
        val uri1 = mockk<Uri>()
        every { mdFile1.uri } returns uri1
        every { mdFile1.lastModified() } returns 12345L

        val mdFile2 = mockk<DocumentFile>()
        every { mdFile2.isDirectory } returns false
        every { mdFile2.isFile } returns true
        every { mdFile2.name } returns "file2.MD"
        every { mdFile2.length() } returns 2048L
        val uri2 = mockk<Uri>()
        every { mdFile2.uri } returns uri2
        every { mdFile2.lastModified() } returns 67890L

        every { rootDir.listFiles() } returns arrayOf(subDir, mdFile1)
        every { subDir.listFiles() } returns arrayOf(mdFile2)

        val content1 = "Hello World"
        val content2 = "Markdown Content"
        every { contentResolver.openInputStream(uri1) } returns ByteArrayInputStream(content1.toByteArray())
        every { contentResolver.openInputStream(uri2) } returns ByteArrayInputStream(content2.toByteArray())

        val result = MdFileReader.listMdFiles(context, treeUri)

        assertEquals(2, result.size)

        // Sort results to ensure deterministic ordering since listFiles order might vary depending on MockK behavior
        val sortedResult = result.sortedBy { it.name }

        // file1.md (Root)
        assertEquals("file1.md", sortedResult[0].name)
        assertEquals("file1.md", sortedResult[0].relativePath)
        assertEquals(content1, sortedResult[0].content)
        assertEquals(12345L, sortedResult[0].lastModified)
        assertEquals("a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e", sortedResult[0].contentHash) // sha256("Hello World")

        // file2.MD (SubFolder)
        assertEquals("file2.MD", sortedResult[1].name)
        assertEquals("SubFolder/file2.MD", sortedResult[1].relativePath)
        assertEquals(content2, sortedResult[1].content)
        assertEquals(67890L, sortedResult[1].lastModified)
    }

    @Test
    fun `listMdFiles ignores non-md files`() {
        val rootDir = mockk<DocumentFile>()
        every { DocumentFile.fromTreeUri(context, treeUri) } returns rootDir

        val txtFile = mockk<DocumentFile>()
        every { txtFile.isDirectory } returns false
        every { txtFile.isFile } returns true
        every { txtFile.name } returns "note.txt"

        val noExtFile = mockk<DocumentFile>()
        every { noExtFile.isDirectory } returns false
        every { noExtFile.isFile } returns true
        every { noExtFile.name } returns "README"

        every { rootDir.listFiles() } returns arrayOf(txtFile, noExtFile)

        val result = MdFileReader.listMdFiles(context, treeUri)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `listMdFiles skips files larger than 10MB`() {
        val rootDir = mockk<DocumentFile>()
        every { DocumentFile.fromTreeUri(context, treeUri) } returns rootDir

        val largeFile = mockk<DocumentFile>()
        every { largeFile.isDirectory } returns false
        every { largeFile.isFile } returns true
        every { largeFile.name } returns "huge.md"
        // > 10MB
        every { largeFile.length() } returns 10 * 1024 * 1024L + 1

        every { rootDir.listFiles() } returns arrayOf(largeFile)

        val result = MdFileReader.listMdFiles(context, treeUri)

        assertTrue(result.isEmpty())
        assertTrue(loggedMessages.any { it.contains("Skipping huge.md: file too large") })
    }

    @Test
    fun `listMdFiles skips file if readText fails`() {
        val rootDir = mockk<DocumentFile>()
        every { DocumentFile.fromTreeUri(context, treeUri) } returns rootDir

        val mdFile = mockk<DocumentFile>()
        every { mdFile.isDirectory } returns false
        every { mdFile.isFile } returns true
        every { mdFile.name } returns "error.md"
        every { mdFile.length() } returns 100L
        val uri = mockk<Uri>()
        every { mdFile.uri } returns uri

        every { rootDir.listFiles() } returns arrayOf(mdFile)

        // Simulate read failure by returning null
        every { contentResolver.openInputStream(uri) } returns null

        val result = MdFileReader.listMdFiles(context, treeUri)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `listMdFiles skips file if readText throws exception`() {
        val rootDir = mockk<DocumentFile>()
        every { DocumentFile.fromTreeUri(context, treeUri) } returns rootDir

        val mdFile = mockk<DocumentFile>()
        every { mdFile.isDirectory } returns false
        every { mdFile.isFile } returns true
        every { mdFile.name } returns "error.md"
        every { mdFile.length() } returns 100L
        val uri = mockk<Uri>()
        every { mdFile.uri } returns uri

        every { rootDir.listFiles() } returns arrayOf(mdFile)

        // Simulate read failure by throwing IOException
        every { contentResolver.openInputStream(uri) } throws java.io.IOException("Disk read error")

        val result = MdFileReader.listMdFiles(context, treeUri)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `listMdFiles skips file if name is null`() {
        val rootDir = mockk<DocumentFile>()
        every { DocumentFile.fromTreeUri(context, treeUri) } returns rootDir

        val nullNameFile = mockk<DocumentFile>()
        every { nullNameFile.name } returns null

        every { rootDir.listFiles() } returns arrayOf(nullNameFile)

        val result = MdFileReader.listMdFiles(context, treeUri)

        assertTrue(result.isEmpty())
    }
}
