package com.minibrain.ai.embed

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import kotlin.math.sqrt

enum class EmbedType { QUERY, PASSAGE }

class EmbedderService {

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var tokenizer: E5Tokenizer? = null
    private val mutex = Mutex()

    suspend fun initialize(modelFile: File, tokenizerFile: File) = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (!modelFile.exists() || modelFile.length() < MIN_MODEL_SIZE) {
                throw IllegalArgumentException("Embedder モデルファイルが不完全または存在しません: ${modelFile.absolutePath}")
            }
            if (!tokenizerFile.exists() || tokenizerFile.length() < MIN_TOKENIZER_SIZE) {
                throw IllegalArgumentException("Tokenizer ファイルが不完全または存在しません: ${tokenizerFile.absolutePath}")
            }
            ortSession?.close()

            val env = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }
            ortEnv = env
            ortSession = env.createSession(modelFile.absolutePath, sessionOptions)
            tokenizer = E5Tokenizer.load(tokenizerFile)
        }
    }

    suspend fun embed(text: String, type: EmbedType = EmbedType.PASSAGE): FloatArray = withContext(Dispatchers.Default) {
        mutex.withLock {
            val env = requireNotNull(ortEnv) { "EmbedderService not initialized. Call initialize() first." }
            val session = requireNotNull(ortSession) { "EmbedderService not initialized. Call initialize() first." }
            val tok = requireNotNull(tokenizer) { "EmbedderService not initialized. Call initialize() first." }

            val prefixed = when (type) {
                EmbedType.QUERY -> "query: $text"
                EmbedType.PASSAGE -> "passage: $text"
            }

            val encoding = tok.encode(prefixed, MAX_SEQ_LEN)
            val inputIds = encoding.ids
            val attentionMask = encoding.attentionMask
            val tokenTypeIds = encoding.tokenTypeIds // Tokenizer から取得
            val seqLen = inputIds.size

            val inputIdsBuffer = LongBuffer.allocate(seqLen).apply { put(inputIds); rewind() }
            val attentionMaskBuffer = LongBuffer.allocate(seqLen).apply { put(attentionMask); rewind() }
            val tokenTypeIdsBuffer = LongBuffer.allocate(seqLen).apply { put(tokenTypeIds); rewind() } // 追加
            val shape = longArrayOf(1, seqLen.toLong())

            OnnxTensor.createTensor(env, inputIdsBuffer, shape).use { inputIdsTensor ->
                OnnxTensor.createTensor(env, attentionMaskBuffer, shape).use { attentionMaskTensor ->
                    OnnxTensor.createTensor(env, tokenTypeIdsBuffer, shape).use { tokenTypeIdsTensor -> // 追加
                        val inputs = mapOf(
                            "input_ids" to inputIdsTensor,
                            "attention_mask" to attentionMaskTensor,
                            "token_type_ids" to tokenTypeIdsTensor, // モデルに渡す
                        )
                        session.run(inputs).use { results ->
                            @Suppress("UNCHECKED_CAST")
                            val hidden = results[0].value as Array<Array<FloatArray>>
                            meanPoolAndNormalize(hidden[0], attentionMask)
                        }
                    }
                }
            }
        }
    }

    private fun meanPoolAndNormalize(hidden: Array<FloatArray>, mask: LongArray): FloatArray {
        val seqLen = hidden.size
        val dim = hidden[0].size
        val pooled = FloatArray(dim)
        var sumMask = 0f
        for (i in 0 until seqLen) {
            val m = mask[i].toFloat()
            if (m == 0f) continue
            sumMask += m
            val vec = hidden[i]
            for (j in 0 until dim) {
                pooled[j] += vec[j] * m
            }
        }
        if (sumMask > 0f) {
            for (j in 0 until dim) pooled[j] /= sumMask
        }
        var norm = 0f
        for (j in 0 until dim) norm += pooled[j] * pooled[j]
        norm = sqrt(norm)
        if (norm > 0f) {
            for (j in 0 until dim) pooled[j] /= norm
        }
        return pooled
    }

    fun isReady(): Boolean = ortSession != null && tokenizer != null

    fun close() {
        ortSession?.close()
        ortSession = null
        tokenizer = null
        ortEnv = null
    }

    companion object {
        const val EMBEDDING_DIM = 384
        const val MAX_SEQ_LEN = 512
        private const val MIN_MODEL_SIZE = 50_000_000L
        private const val MIN_TOKENIZER_SIZE = 1_000_000L

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