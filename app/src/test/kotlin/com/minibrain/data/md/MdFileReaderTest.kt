package com.minibrain.data.md

import android.net.Uri
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import timber.log.Timber

class MdFileReaderTest {

    private val loggedMessages = mutableListOf<String>()
    private val fakeTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            loggedMessages.add(message)
        }
    }

    /** documentId をパスとして扱う簡易ツリー。 */
    private class FakeDocumentTree : DocumentTree {
        override val rootDocumentId = "root"
        val children = mutableMapOf<String, List<DocumentEntry>>()
        val contents = mutableMapOf<String, String>()
        val failingDirs = mutableSetOf<String>()
        val throwingFiles = mutableSetOf<String>()
        private val uris = mutableMapOf<String, Uri>()
        private val idsByUri = mutableMapOf<Uri, String>()

        fun dir(parent: String, name: String): String {
            val id = "$parent/$name"
            add(parent, DocumentEntry(id, name, isDirectory = true, lastModified = 0L, size = null))
            return id
        }

        fun file(parent: String, name: String, content: String, lastModified: Long = 0L, size: Long? = null) {
            val id = "$parent/$name"
            add(parent, DocumentEntry(id, name, isDirectory = false, lastModified, size ?: content.length.toLong()))
            contents[id] = content
        }

        private fun add(parent: String, entry: DocumentEntry) {
            children[parent] = children[parent].orEmpty() + entry
        }

        override fun listChildren(parentDocumentId: String): List<DocumentEntry> {
            if (parentDocumentId in failingDirs) throw IllegalStateException("query failed")
            return children[parentDocumentId].orEmpty()
        }

        // 読み込みは並列に呼ばれるので、Map への出し入れは同期する
        @Synchronized
        override fun documentUri(documentId: String): Uri = uris.getOrPut(documentId) {
            mockk<Uri>().also { idsByUri[it] = documentId }
        }

        @Synchronized
        override fun readText(uri: Uri): String? {
            val id = idsByUri.getValue(uri)
            if (id in throwingFiles) throw IOException("Disk read error")
            return contents[id]
        }
    }

    private val tree = FakeDocumentTree()

    @Before
    fun setUp() {
        Timber.plant(fakeTree)
    }

    @After
    fun tearDown() {
        Timber.uproot(fakeTree)
        loggedMessages.clear()
    }

    @Test
    fun `listMdFiles returns empty list for empty folder`() = runTest {
        assertTrue(MdFileReader.listMdFiles(tree).isEmpty())
    }

    @Test
    fun `listMdFiles collects md files and traverses directories`() = runTest {
        val sub = tree.dir("root", "SubFolder")
        tree.file("root", "file1.md", "Hello World", lastModified = 12345L)
        tree.file(sub, "file2.MD", "Markdown Content", lastModified = 67890L)

        val result = MdFileReader.listMdFiles(tree).sortedBy { it.name }

        assertEquals(2, result.size)
        assertEquals("file1.md", result[0].relativePath)
        assertEquals("Hello World", result[0].content)
        assertEquals(12345L, result[0].lastModified)
        assertEquals("a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e", result[0].contentHash) // sha256("Hello World")
        assertEquals("file2.MD", result[1].name)
        assertEquals("SubFolder/file2.MD", result[1].relativePath)
        assertEquals("Markdown Content", result[1].content)
        assertEquals(67890L, result[1].lastModified)
    }

    @Test
    fun `listMdFiles reaches notes nested several levels deep`() = runTest {
        val tech = tree.dir("root", "tech")
        val android = tree.dir(tech, "Android")
        val deep = tree.dir(android, "深い")
        tree.file(tech, "Git.md", "git")
        tree.file(android, "R8とProGuard.md", "r8")
        tree.file(deep, "メモ.md", "deep")

        val paths = MdFileReader.listMdFiles(tree).map { it.relativePath }.sorted()

        assertEquals(listOf("tech/Android/R8とProGuard.md", "tech/Android/深い/メモ.md", "tech/Git.md"), paths)
    }

    @Test
    fun `listMdFiles fails instead of silently dropping an unreadable folder`() = runTest {
        val tech = tree.dir("root", "tech")
        val android = tree.dir(tech, "Android")
        tree.file(tech, "Git.md", "git")
        tree.failingDirs.add(android)

        try {
            MdFileReader.listMdFiles(tree)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("tech/Android"))
        }
    }

    @Test
    fun `listMdFiles ignores non-md files`() = runTest {
        tree.file("root", "note.txt", "txt")
        tree.file("root", "README", "readme")

        assertTrue(MdFileReader.listMdFiles(tree).isEmpty())
    }

    @Test
    fun `listMdFiles skips files larger than 10MB`() = runTest {
        tree.file("root", "huge.md", "x", size = 10 * 1024 * 1024L + 1)

        assertTrue(MdFileReader.listMdFiles(tree).isEmpty())
        assertTrue(loggedMessages.any { it.contains("Skipping huge.md: file too large") })
    }

    @Test
    fun `listMdFiles reads file when size is unknown`() = runTest {
        tree.file("root", "a.md", "body")
        tree.children["root"] = tree.children.getValue("root").map { it.copy(size = null) }

        assertEquals(listOf("body"), MdFileReader.listMdFiles(tree).map { it.content })
    }

    @Test
    fun `listMdFiles skips file if readText returns null`() = runTest {
        tree.file("root", "error.md", "x")
        tree.contents.remove("root/error.md")

        assertTrue(MdFileReader.listMdFiles(tree).isEmpty())
    }

    @Test
    fun `listMdFiles skips file if readText throws exception`() = runTest {
        tree.file("root", "error.md", "x")
        tree.file("root", "ok.md", "ok")
        tree.throwingFiles.add("root/error.md")

        assertEquals(listOf("ok.md"), MdFileReader.listMdFiles(tree).map { it.name })
        assertTrue(loggedMessages.any { it.contains("read failed: error.md") })
    }
}
