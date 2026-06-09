package com.minibrain.ai.embed

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder.TextEmbedderOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EmbedderService(private val context: Context) {

    private var embedder: TextEmbedder? = null
    private val mutex = Mutex()

    suspend fun initialize(modelFile: File) = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (!modelFile.exists() || modelFile.length() < 1_000_000L) {
                throw IllegalArgumentException("Embedderモデルファイルが不完全または存在しません")
            }
            embedder?.close()
            val options = TextEmbedderOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(modelFile.absolutePath)
                        .build()
                )
                .setL2Normalize(true)
                .build()
            embedder = TextEmbedder.createFromOptions(context, options)
        }
    }

    suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        val emb = requireNotNull(embedder) { "EmbedderService not initialized. Call initialize() first." }
        mutex.withLock {
            val result = emb.embed(text)
            // MediaPipe 0.10.x: TextEmbedderResult -> embeddingResult() -> embeddings() -> floatEmbedding()
            val floatList = result.embeddingResult().embeddings().first().floatEmbedding()
                ?: error("floatEmbedding is null — quantized embeddings are not supported")
            FloatArray(floatList.size) { floatList[it] }
        }
    }

    fun isReady(): Boolean = embedder != null

    fun close() {
        embedder?.close()
        embedder = null
    }

    companion object {
        fun floatArrayToBytes(array: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(array.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            buf.asFloatBuffer().put(array)
            return buf.array()
        }

        fun bytesToFloatArray(bytes: ByteArray): FloatArray {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val floats = FloatArray(bytes.size / 4)
            buf.asFloatBuffer().get(floats)
            return floats
        }
    }
}
