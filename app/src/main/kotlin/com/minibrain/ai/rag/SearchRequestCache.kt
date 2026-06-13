package com.minibrain.ai.rag

import com.minibrain.ai.embed.EmbedderService
import com.minibrain.data.db.daos.ChunkDao
import com.minibrain.data.db.daos.DocumentDao
import com.minibrain.data.db.entities.ChunkEntity
import com.minibrain.data.db.entities.DocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 1 リクエスト分の Document/Chunk ロードと FloatArray デコードを memoize する軽量キャッシュ。
 *
 * AgentPipeline.run の冒頭で 1 つ生成し、SearchPipeline / RagPipeline / buildPlannerHint で使い回す。
 * リクエスト終了時に破棄するため、書き込み(インデックス更新)との整合性を考えなくて済む。
 *
 * ADR-024: multiVectorSearch が同一 treeUri に対して embed クエリ N 件分の
 * chunks ロード + bytesToFloatArray を繰り返していた問題を解消する。
 */
class SearchRequestCache(
    val treeUri: String,
    private val chunkDao: ChunkDao,
    private val documentDao: DocumentDao,
) {
    private val docMutex = Mutex()
    private val chunkMutex = Mutex()

    @Volatile private var cachedDocs: List<DocumentEntity>? = null
    @Volatile private var cachedChunks: List<ChunkEntity>? = null
    @Volatile private var cachedVectors: Array<FloatArray>? = null

    suspend fun documents(): List<DocumentEntity> {
        cachedDocs?.let { return it }
        return docMutex.withLock {
            cachedDocs ?: withContext(Dispatchers.IO) {
                documentDao.getAllByTree(treeUri)
            }.also { cachedDocs = it }
        }
    }

    suspend fun chunkVectors(): Pair<List<ChunkEntity>, Array<FloatArray>> {
        val c = cachedChunks
        val v = cachedVectors
        if (c != null && v != null) return c to v
        return chunkMutex.withLock {
            val cc = cachedChunks
            val vv = cachedVectors
            if (cc != null && vv != null) return cc to vv
            val chunks = withContext(Dispatchers.IO) { chunkDao.getAllByTree(treeUri) }
            val vectors = withContext(Dispatchers.Default) {
                Array(chunks.size) { EmbedderService.bytesToFloatArray(chunks[it].embedding) }
            }
            cachedChunks = chunks
            cachedVectors = vectors
            chunks to vectors
        }
    }

    /** queryVec に対する cosine topK。L2 正規化済みのためドット積で算出。 */
    suspend fun cosineTopK(queryVec: FloatArray, k: Int): List<Pair<Float, ChunkEntity>> {
        val (chunks, vectors) = chunkVectors()
        if (chunks.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<Float, ChunkEntity>>(chunks.size)
        for (i in chunks.indices) {
            scored.add(CosineSimilarity.compute(queryVec, vectors[i]) to chunks[i])
        }
        scored.sortByDescending { it.first }
        return if (scored.size > k) scored.subList(0, k) else scored
    }
}
