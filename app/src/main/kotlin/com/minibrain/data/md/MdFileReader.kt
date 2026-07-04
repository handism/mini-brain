package com.minibrain.data.md

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.security.MessageDigest
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

    fun listMdFiles(context: Context, treeUri: Uri): List<MdFile> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val results = mutableListOf<MdFile>()
        collectMd(context, root, "", results)
        return results
    }

    private fun collectMd(
        context: Context,
        dir: DocumentFile,
        pathPrefix: String,
        results: MutableList<MdFile>,
    ) {
        for (file in dir.listFiles()) {
            val name = file.name ?: continue
            when {
                file.isDirectory -> {
                    collectMd(context, file, if (pathPrefix.isEmpty()) name else "$pathPrefix/$name", results)
                }
                file.isFile && name.endsWith(".md", ignoreCase = true) -> {
                    val fileSize = file.length()
                    if (fileSize > MAX_FILE_SIZE_BYTES) {
                        Timber.tag(TAG).w("Skipping $name: file too large ($fileSize bytes)")
                        continue
                    }
                    val content = readText(context, file.uri) ?: continue
                    val hash = sha256(content)
                    val relativePath = if (pathPrefix.isEmpty()) name else "$pathPrefix/$name"
                    results.add(
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
        }
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
