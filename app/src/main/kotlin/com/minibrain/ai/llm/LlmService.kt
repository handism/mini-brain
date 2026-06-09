package com.minibrain.ai.llm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class LlmService(private val context: Context) {

    private var engine: Engine? = null
    private val mutex = Mutex()

    suspend fun initialize(modelFile: File, forceCpu: Boolean = false) = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (!modelFile.exists() || modelFile.length() < 100_000_000L) {
                throw IllegalArgumentException("モデルファイルが不完全または存在しません (現在のサイズ: ${modelFile.length()} bytes)")
            }
            engine?.close()
            
            var lastError: Throwable? = null
            
            // 1. GPU で試行 (forceCpu が false の場合のみ)
            if (!forceCpu) {
                try {
                    val config = buildConfig(modelFile, useGpu = true)
                    val eng = Engine(config)
                    eng.initialize()
                    engine = eng
                    return@withLock
                } catch (e: Throwable) {
                    lastError = e
                    android.util.Log.e("LlmService", "GPU initialization failed, falling back to CPU", e)
                }
            }

            // 2. CPU でリトライ (または forceCpu が true の場合)
            try {
                val cpuConfig = buildConfig(modelFile, useGpu = false)
                val eng = Engine(cpuConfig)
                eng.initialize()
                engine = eng
            } catch (e: Throwable) {
                android.util.Log.e("LlmService", "CPU initialization failed", e)
                val msg = if (forceCpu) "CPUモードでの初期化に失敗しました" else "GPU/CPU 両方で失敗しました"
                throw Exception("$msg: ${e.localizedMessage}", e)
            }
        }
    }

    fun generateStream(prompt: String): Flow<String> = flow {
        val eng = requireNotNull(engine) { "LlmService not initialized. Call initialize() first." }
        eng.createConversation().use { conversation ->
            conversation.sendMessageAsync(prompt).collect { message ->
                val text = message.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
                emit(text)
            }
        }
    }.flowOn(Dispatchers.Default)

    fun isReady(): Boolean = engine != null

    fun close() {
        engine?.close()
        engine = null
    }

    private fun buildConfig(modelFile: File, useGpu: Boolean = true): EngineConfig {
        val backend = if (useGpu) Backend.GPU() else Backend.CPU()
        return EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = backend,
        )
    }
}
