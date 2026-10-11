package com.minibrain.data.md

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

data class MdFile(
    val uri: Uri,
    val name: String,
    val relativePath: String,
    val lastModified: Long,
    val contentHash: String,
    val content: String,
)

/** SAF ツリー内の 1 エントリ。フォルダの列挙 1 回で必要な列をまとめて取る。 */
internal data class DocumentEntry(
    val documentId: String,
    val name: String,
    val isDirectory: Boolean,
    val lastModified: Long,
    val size: Long?,
)

/** SAF ツリーへのアクセス。JVM テストで差し替えるために切り出している。 */
internal interface DocumentTree {
    val rootDocumentId: String

    /** 子の一覧。列挙できなかったときは例外を投げる（空で返すと、そのフォルダのノートが消えたように見えるため）。 */
    fun listChildren(parentDocumentId: String): List<DocumentEntry>

    fun documentUri(documentId: String): Uri

    fun readText(uri: Uri): String?
}

private class ContentResolverDocumentTree(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : DocumentTree {

    override val rootDocumentId: String = DocumentsContract.getTreeDocumentId(treeUri)

    override fun listChildren(parentDocumentId: String): List<DocumentEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val cursor = resolver.query(childrenUri, PROJECTION, null, null, null)
            ?: throw IOException("query returned null: $parentDocumentId")
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    add(
                        DocumentEntry(
                            documentId = c.getString(0),
                            name = name,
                            isDirectory = c.getString(2) == Document.MIME_TYPE_DIR,
                            lastModified = if (c.isNull(3)) 0L else c.getLong(3),
                            size = if (c.isNull(4)) null else c.getLong(4),
                        )
                    )
                }
            }
        }
    }

    // DocumentFile.listFiles() と同じ組み立て方なので、既存の documents.fileUri とも一致する
    override fun documentUri(documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    override fun readText(uri: Uri): String? =
        resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }

    private companion object {
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
        )
    }
}

object MdFileReader {

    private const val TAG = "MdFileReader"
    private const val MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024L // 10MB
    private const val READ_PARALLELISM = 4
    private const val LIST_PARALLELISM = 8

    /**
     * 列挙と読み込みが終わるまで返らないので、進み具合をコールバックで知らせる。
     * クラウドの提供元だと 1 件ずつ取りに行くため、ここが長く止まって見えることがある。
     * - [onListing]: 列挙中。これまでに見つかった .md の数
     * - [onRead]: 読み込み中。読み終えた数・総数・読み終えたファイル名（並列に呼ばれる）
     */
    suspend fun listMdFiles(
        context: Context,
        treeUri: Uri,
        onListing: (found: Int) -> Unit = {},
        onRead: (done: Int, total: Int, fileName: String) -> Unit = { _, _, _ -> },
    ): List<MdFile> =
        listMdFiles(ContentResolverDocumentTree(context.contentResolver, treeUri), onListing, onRead)

    // DocumentFile は query の失敗を握りつぶして空や false を返すため、
    // 深い階層のノートが黙って欠けていた。DocumentsContract で直接列挙し、失敗は例外にする。
    internal suspend fun listMdFiles(
        tree: DocumentTree,
        onListing: (found: Int) -> Unit = {},
        onRead: (done: Int, total: Int, fileName: String) -> Unit = { _, _, _ -> },
    ): List<MdFile> = withContext(Dispatchers.IO) {
        val targets = collectMdEntries(tree, onListing)
        val semaphore = Semaphore(READ_PARALLELISM)
        val done = AtomicInteger(0)
        targets.map { (entry, relativePath) ->
            async {
                semaphore.withPermit {
                    readMdFile(tree, entry, relativePath).also {
                        onRead(done.incrementAndGet(), targets.size, entry.name)
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun collectMdEntries(
        tree: DocumentTree,
        onListing: (found: Int) -> Unit,
    ): List<Pair<DocumentEntry, String>> = kotlinx.coroutines.coroutineScope {
        val result = mutableListOf<Pair<DocumentEntry, String>>()
        val mutex = kotlinx.coroutines.sync.Mutex()
        val semaphore = kotlinx.coroutines.sync.Semaphore(LIST_PARALLELISM)

        suspend fun traverse(dirId: String, prefix: String) {
            val children = try {
                semaphore.withPermit {
                    tree.listChildren(dirId)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val where = prefix.ifEmpty { "(ルート)" }
                throw java.io.IOException("フォルダを読み込めませんでした: $where", e)
            }

            val dirs = mutableListOf<Pair<String, String>>()
            val files = mutableListOf<Pair<DocumentEntry, String>>()

            for (child in children) {
                val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                when {
                    child.isDirectory -> dirs.add(child.documentId to path)
                    child.name.endsWith(".md", ignoreCase = true) -> files.add(child to path)
                }
            }

            val currentSize = mutex.withLock {
                if (files.isNotEmpty()) {
                    result.addAll(files)
                }
                result.size
            }
            onListing(currentSize)

            dirs.map { (childDirId, childPath) ->
                async { traverse(childDirId, childPath) }
            }.awaitAll()
        }

        traverse(tree.rootDocumentId, "")
        result
    }

    private fun readMdFile(tree: DocumentTree, entry: DocumentEntry, relativePath: String): MdFile? {
        val size = entry.size
        if (size != null && size > MAX_FILE_SIZE_BYTES) {
            Timber.tag(TAG).w("Skipping ${entry.name}: file too large ($size bytes)")
            return null
        }
        val uri = tree.documentUri(entry.documentId)
        val content = try {
            tree.readText(uri)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "read failed: $relativePath")
            null
        } ?: return null
        return MdFile(
            uri = uri,
            name = entry.name,
            relativePath = relativePath,
            lastModified = entry.lastModified,
            contentHash = sha256(content),
            content = content,
        )
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
