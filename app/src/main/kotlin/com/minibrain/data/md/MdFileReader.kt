package com.minibrain.data.md

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

object MdFileReader {

    private const val TAG = "MdFileReader"
    private const val MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024L // 10MB

    suspend fun listMdFiles(context: Context, treeUri: Uri): List<MdFile> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        collectMd(context, root, "")
    }

    private suspend fun collectMd(
        context: Context,
        dir: DocumentFile,
        pathPrefix: String,
    ): List<MdFile> = withContext(Dispatchers.IO) {
        dir.listFiles().mapNotNull { file ->
            val name = file.name ?: return@mapNotNull null
            when {
                file.isDirectory -> {
                    async {
                        collectMd(context, file, if (pathPrefix.isEmpty()) name else "$pathPrefix/$name")
                    }
                }
                file.isFile && name.endsWith(".md", ignoreCase = true) -> {
                    async {
                        val fileSize = file.length()
                        if (fileSize > MAX_FILE_SIZE_BYTES) {
                            Timber.tag(TAG).w("Skipping $name: file too large ($fileSize bytes)")
                            return@async null
                        }
                        val content = readText(context, file.uri) ?: return@async null
                        val hash = sha256(content)
                        val relativePath = if (pathPrefix.isEmpty()) name else "$pathPrefix/$name"
                        listOf(
                            MdFile(
                                uri = file.uri,
                                name = name,
                                relativePath = relativePath,
                                lastModified = file.lastModified(),
                                contentHash = hash,
                                content = content,
                            )
                        )
                    }
                }
                else -> null
            }
        }.awaitAll().filterNotNull().flatten()
    }

    private fun readText(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
    }.getOrNull()

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
