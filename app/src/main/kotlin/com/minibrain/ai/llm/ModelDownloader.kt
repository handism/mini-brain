package com.minibrain.ai.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import timber.log.Timber

data class DownloadProgress(
    val fileName: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
) {
    val fraction: Float get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
    val isDone: Boolean get() = bytesDownloaded >= totalBytes && totalBytes > 0
}

sealed class DownloadResult {
    data class Progress(val progress: DownloadProgress) : DownloadResult()
    data class Done(val file: File) : DownloadResult()
    data class Error(val message: String) : DownloadResult()
}

class ModelDownloader(private val context: Context) {

    private val modelsDir: File = File(context.filesDir, "models").also { it.mkdirs() }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // ストリームDLなので timeout なし
        .build()

    val llmModelFile: File get() = File(modelsDir, LLM_FILE_NAME)
    val embedderModelFile: File get() = File(modelsDir, EMBEDDER_FILE_NAME)
    val tokenizerModelFile: File get() = File(modelsDir, TOKENIZER_FILE_NAME)

    fun isLlmReady(): Boolean = llmModelFile.exists() && llmModelFile.length() >= MIN_LLM_SIZE && verifyHash(llmModelFile, LLM_SHA256)
    fun isEmbedderReady(): Boolean = embedderModelFile.exists() && embedderModelFile.length() >= MIN_EMBEDDER_SIZE && verifyHash(embedderModelFile, EMBEDDER_SHA256)
    fun isTokenizerReady(): Boolean = tokenizerModelFile.exists() && tokenizerModelFile.length() >= MIN_TOKENIZER_SIZE && verifyHash(tokenizerModelFile, TOKENIZER_SHA256)
    fun isAllReady(): Boolean = isLlmReady() && isEmbedderReady() && isTokenizerReady()

    private fun verifyHash(file: File, expectedHash: String): Boolean {
        if (!file.exists()) return false
        val actualHash = calculateSha256(file)
        return actualHash.equals(expectedHash, ignoreCase = true)
    }

    private fun calculateSha256(file: File): String = try {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "Failed to calculate SHA-256 for ${file.name}")
        ""
    }

    fun downloadAll(): Flow<DownloadResult> = flow {
        try {
            if (!isEmbedderReady()) {
                val tempFile = File(modelsDir, "$EMBEDDER_FILE_NAME.download")
                var errorOccurred = false
                downloadFile(EMBEDDER_URL, tempFile, EMBEDDER_FILE_NAME).collect { result ->
                    when {
                        result is DownloadResult.Done -> {
                            if (verifyHash(tempFile, EMBEDDER_SHA256)) {
                                val err = moveFile(tempFile, embedderModelFile)
                                if (err != null) {
                                    Timber.tag(TAG).e("Failed to move embedder temp file: $err")
                                    tempFile.delete()
                                    emit(DownloadResult.Error(err))
                                    errorOccurred = true
                                }
                            } else {
                                Timber.tag(TAG).e("Embedder hash verification failed")
                                tempFile.delete()
                                emit(DownloadResult.Error("Embedder のハッシュ検証に失敗しました。再試行してください。"))
                                errorOccurred = true
                            }
                        }
                        result is DownloadResult.Error -> { emit(result); errorOccurred = true }
                        else -> emit(result)
                    }
                }
                if (errorOccurred) return@flow
            }
            if (!isTokenizerReady()) {
                val tempFile = File(modelsDir, "$TOKENIZER_FILE_NAME.download")
                var errorOccurred = false
                downloadFile(TOKENIZER_URL, tempFile, TOKENIZER_FILE_NAME).collect { result ->
                    when {
                        result is DownloadResult.Done -> {
                            if (verifyHash(tempFile, TOKENIZER_SHA256)) {
                                val err = moveFile(tempFile, tokenizerModelFile)
                                if (err != null) {
                                    Timber.tag(TAG).e("Failed to move tokenizer temp file: $err")
                                    tempFile.delete()
                                    emit(DownloadResult.Error(err))
                                    errorOccurred = true
                                }
                            } else {
                                Timber.tag(TAG).e("Tokenizer hash verification failed")
                                tempFile.delete()
                                emit(DownloadResult.Error("Tokenizer のハッシュ検証に失敗しました。再試行してください。"))
                                errorOccurred = true
                            }
                        }
                        result is DownloadResult.Error -> { emit(result); errorOccurred = true }
                        else -> emit(result)
                    }
                }
                if (errorOccurred) return@flow
            }
            if (!isLlmReady()) {
                val tempFile = File(modelsDir, "$LLM_FILE_NAME.download")
                var errorOccurred = false
                downloadFile(LLM_URL, tempFile, LLM_FILE_NAME).collect { result ->
                    when {
                        result is DownloadResult.Done -> {
                            if (verifyHash(tempFile, LLM_SHA256)) {
                                val err = moveFile(tempFile, llmModelFile)
                                if (err != null) {
                                    Timber.tag(TAG).e("Failed to move LLM temp file: $err")
                                    tempFile.delete()
                                    emit(DownloadResult.Error(err))
                                    errorOccurred = true
                                }
                            } else {
                                Timber.tag(TAG).e("LLM hash verification failed")
                                tempFile.delete()
                                emit(DownloadResult.Error("LLM のハッシュ検証に失敗しました。再試行してください。"))
                                errorOccurred = true
                            }
                        }
                        result is DownloadResult.Error -> { emit(result); errorOccurred = true }
                        else -> emit(result)
                    }
                }
                if (errorOccurred) return@flow
            }
            if (isAllReady()) {
                emit(DownloadResult.Done(llmModelFile))
            } else {
                val msg = "準備失敗: LLM=${llmModelFile.length()}/$MIN_LLM_SIZE, Embedder=${embedderModelFile.length()}/$MIN_EMBEDDER_SIZE, Tokenizer=${tokenizerModelFile.length()}/$MIN_TOKENIZER_SIZE"
                emit(DownloadResult.Error("ダウンロードが完了しましたが、ファイルが準備できていません。($msg)"))
            }
        } catch (e: Exception) {
            emit(DownloadResult.Error("エラー: ${e.localizedMessage}"))
        }
    }.flowOn(Dispatchers.IO)

    private fun moveFile(src: File, dst: File): String? = try {
        java.nio.file.Files.move(
            src.toPath(), dst.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
        null
    } catch (e: java.io.IOException) {
        "ファイルの移動に失敗しました: ${e.localizedMessage}"
    }

    private fun downloadFile(url: String, dest: File, label: String): Flow<DownloadResult> = flow {
        val alreadyDownloaded = if (dest.exists()) dest.length() else 0L
        val requestBuilder = Request.Builder().url(url)
        if (alreadyDownloaded > 0) {
            requestBuilder.header("Range", "bytes=$alreadyDownloaded-")
        }

        val response = try {
            client.newCall(requestBuilder.build()).execute()
        } catch (e: Exception) {
            emit(DownloadResult.Error("接続失敗: ${e.localizedMessage}"))
            return@flow
        }

        if (!response.isSuccessful) {
            if (response.code == 416 && dest.exists()) {
                emit(DownloadResult.Done(dest))
                return@flow
            }
            emit(DownloadResult.Error("HTTP ${response.code}: ${response.message}"))
            return@flow
        }

        val body = response.body ?: run {
            emit(DownloadResult.Error("レスポンスボディが空です"))
            return@flow
        }

        val contentLength = body.contentLength()
        val totalBytes = if (response.code == 206 && alreadyDownloaded > 0 && contentLength > 0) {
            alreadyDownloaded + contentLength
        } else {
            contentLength
        }

        val outputStream = if (alreadyDownloaded > 0 && response.code == 206) {
            java.io.FileOutputStream(dest, true)
        } else {
            java.io.FileOutputStream(dest, false)
        }

        body.byteStream().use { input ->
            outputStream.use { output ->
                val buffer = ByteArray(64 * 1024)
                var downloaded = if (response.code == 206) alreadyDownloaded else 0L
                var bytes: Int
                while (input.read(buffer).also { bytes = it } != -1) {
                    output.write(buffer, 0, bytes)
                    downloaded += bytes
                    emit(DownloadResult.Progress(DownloadProgress(label, downloaded, totalBytes)))
                }

                if (totalBytes > 0 && downloaded < totalBytes) {
                    emit(DownloadResult.Error("中断されました: $downloaded / $totalBytes bytes"))
                    return@flow
                }
            }
        }
        emit(DownloadResult.Done(dest))
    }

    companion object {
        private const val TAG = "ModelDownloader"
        const val LLM_FILE_NAME = "gemma-4-E2B-it.litertlm"
        const val EMBEDDER_FILE_NAME = "multilingual-e5-small-q.onnx"
        const val TOKENIZER_FILE_NAME = "e5-tokenizer.json"

        // HuggingFace litert-community/gemma-4-E2B-it-litert-lm 配布 URL
        private const val LLM_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"

        // Xenova/multilingual-e5-small INT8 量子化版 ONNX
        private const val EMBEDDER_URL =
            "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/onnx/model_quantized.onnx"

        // 同リポジトリの XLM-RoBERTa SentencePiece tokenizer (HuggingFace tokenizers.json 形式)
        private const val TOKENIZER_URL =
            "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/tokenizer.json"

        private const val MIN_LLM_SIZE = 2_000_000_000L    // 2GB (実際は約2.5GB)
        private const val MIN_EMBEDDER_SIZE = 50_000_000L  // 50MB (実際は約118MB)
        private const val MIN_TOKENIZER_SIZE = 1_000_000L  // 1MB (実際は約17MB)

        private const val LLM_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
        private const val EMBEDDER_SHA256 = "f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193"
        private const val TOKENIZER_SHA256 = "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39"
    }
}
