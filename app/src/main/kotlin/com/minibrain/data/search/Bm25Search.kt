package com.minibrain.data.search

import com.minibrain.data.db.daos.ChunkDao
import com.minibrain.data.db.entities.ChunkEntity
import com.minibrain.util.runCatchingCancellable
import timber.log.Timber

/**
 * クエリを bigram の FTS MATCH 式に変換して treeUri 内を BM25 検索する。
 * クエリが短すぎて MATCH 式を作れない場合や、FTS 構文エラーなどで失敗した場合は空リストを返す。
 * SearchPipeline と RagPipeline で同じ処理を持っていたものを 1 箇所にまとめた。
 */
suspend fun ChunkDao.bm25SearchOrEmpty(query: String, treeUri: String, limit: Int): List<ChunkEntity> {
    val matchQuery = NGramTokenizer.toFtsMatchQuery(query) ?: return emptyList()
    return runCatchingCancellable { bm25SearchByTree(matchQuery, treeUri, limit) }
        .getOrElse { e ->
            Timber.tag("Bm25Search").w("BM25 search failed for '$query': ${e.message}")
            emptyList()
        }
}
