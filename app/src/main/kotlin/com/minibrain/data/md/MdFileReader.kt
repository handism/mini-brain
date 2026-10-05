package com.minibrain.data.md

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    suspend fun listMdFiles(context: Context, treeUri: Uri): List<MdFile> =
        listMdFiles(ContentResolverDocumentTree(context.contentResolver, treeUri))

    // DocumentFile は query の失敗を握りつぶして空や false を返すため、
    // 深い階層のノートが黙って欠けていた。DocumentsContract で直接列挙し、失敗は例外にする。
    internal suspend fun listMdFiles(tree: DocumentTree): List<MdFile> = withContext(Dispatchers.IO) {
        val targets = collectMdEntries(tree)
        val semaphore = Semaphore(READ_PARALLELISM)
        targets.map { (entry, relativePath) ->
            async { semaphore.withPermit { readMdFile(tree, entry, relativePath) } }
        }.awaitAll().filterNotNull()
    }

    private fun collectMdEntries(tree: DocumentTree): List<Pair<DocumentEntry, String>> {
        val result = mutableListOf<Pair<DocumentEntry, String>>()
        val pending = ArrayDeque<Pair<String, String>>() // documentId, pathPrefix
        pending.addLast(tree.rootDocumentId to "")
        while (pending.isNotEmpty()) {
            val (dirId, prefix) = pending.removeFirst()
            val children = try {
                tree.listChildren(dirId)
            } catch (e: Exception) {
                val where = prefix.ifEmpty { "(ルート)" }
                throw IOException("フォルダを読み込めませんでした: $where", e)
            }
            for (child in children) {
                val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                when {
                    child.isDirectory -> pending.addLast(child.documentId to path)
                    child.name.endsWith(".md", ignoreCase = true) -> result.add(child to path)
                }
            }
        }
        return result
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
