package com.minibrain.ai.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

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

    fun isLlmReady(): Boolean = llmModelFile.exists() && llmModelFile.length() >= MIN_LLM_SIZE
    fun isEmbedderReady(): Boolean = embedderModelFile.exists() && embedderModelFile.length() >= MIN_EMBEDDER_SIZE

    fun downloadAll(): Flow<DownloadResult> = flow {
        try {
            if (!isEmbedderReady()) {
                val tempFile = File(modelsDir, "$EMBEDDER_FILE_NAME.download")
                var errorOccurred = false
                downloadFile(EMBEDDER_URL, tempFile, EMBEDDER_FILE_NAME).collect { result ->
                    when {
                        result is DownloadResult.Done -> {
                            val err = moveFile(tempFile, embedderModelFile)
                            if (err != null) {
                                // move 失敗時は temp ファイルを削除して stuck 状態を防ぐ
                                Log.e(TAG, "Failed to move embedder temp file: $err")
                                tempFile.delete()
                                emit(DownloadResult.Error(err))
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
                            val err = moveFile(tempFile, llmModelFile)
                            if (err != null) {
                                // move 失敗時は temp ファイルを削除して stuck 状態を防ぐ
                                Log.e(TAG, "Failed to move LLM temp file: $err")
                                tempFile.delete()
                                emit(DownloadResult.Error(err))
                                errorOccurred = true
                            }
                        }
                        result is DownloadResult.Error -> { emit(result); errorOccurred = true }
                        else -> emit(result)
                    }
                }
                if (errorOccurred) return@flow
            }
            if (isLlmReady() && isEmbedderReady()) {
                emit(DownloadResult.Done(llmModelFile))
            } else {
                val msg = "準備失敗: LLM=${llmModelFile.length()}/$MIN_LLM_SIZE, Embedder=${embedderModelFile.length()}/$MIN_EMBEDDER_SIZE"
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
    } catch (e: Exception) {
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
        const val EMBEDDER_FILE_NAME = "universal_sentence_encoder_multilingual.tflite"

        // HuggingFace litert-community/gemma-4-E2B-it-litert-lm 配布 URL
        // 実際の URL は HuggingFace ページで確認してください
        private const val LLM_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"

        // MediaPipe multilingual USE model
        private const val EMBEDDER_URL =
            "https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/latest/universal_sentence_encoder.tflite"

        private const val MIN_LLM_SIZE = 2_000_000_000L    // 2GB (実際は約2.5GB)
        private const val MIN_EMBEDDER_SIZE = 1_000_000L   // 1MB (ユーザーの環境で約6MBであることを確認)
    }
}
