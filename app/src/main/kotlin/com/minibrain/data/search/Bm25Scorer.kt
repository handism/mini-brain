package com.minibrain.data.search

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln

/**
 * FTS4 の `matchinfo(chunks_fts, 'pcnalx')` から Okapi BM25 を計算する。
 * FTS4 には順位付けの関数が無く、以前は MATCH 結果を rowid 順に LIMIT していた（関連度と無関係だった、ADR-040）。
 *
 * 'pcnalx' のレイアウト（32bit 符号なし整数、端末のバイト順）:
 *   p: フレーズ数, c: 列数, n: 全行数, a[c]: 列ごとの平均トークン数, l[c]: この行の列ごとのトークン数,
 *   x[p][c][3]: (この行での出現数, 全行での出現数, 出現する行数)
 */
object Bm25Scorer {

    const val MATCHINFO_FORMAT = "pcnalx"

    private const val K1 = 1.2
    private const val B = 0.75

    // chunks_fts の列順（text_bigram, heading_bigram）。見出しは「パス > 見出し」で全チャンクに重複して入るので軽くする
    private val COLUMN_WEIGHTS = doubleArrayOf(1.0, 0.5)

    fun score(matchinfo: ByteArray): Double {
        val ints = ByteBuffer.wrap(matchinfo).order(ByteOrder.nativeOrder()).asIntBuffer()
        if (ints.remaining() < 3) return 0.0
        val p = ints.get(0)
        val c = ints.get(1)
        val n = ints.get(2).toDouble()
        val aOffset = 3
        val lOffset = aOffset + c
        val xOffset = lOffset + c
        if (ints.remaining() < xOffset + 3 * p * c) return 0.0

        var total = 0.0
        for (col in 0 until c) {
            val weight = COLUMN_WEIGHTS.getOrElse(col) { 1.0 }
            val avgLen = ints.get(aOffset + col).toDouble().coerceAtLeast(1.0)
            val len = ints.get(lOffset + col).toDouble()
            val norm = K1 * (1 - B + B * len / avgLen)
            for (phrase in 0 until p) {
                val base = xOffset + 3 * (phrase * c + col)
                val tf = ints.get(base).toDouble()
                if (tf <= 0) continue
                val df = ints.get(base + 2).toDouble()
                val idf = ln(1 + (n - df + 0.5) / (df + 0.5))
                total += weight * idf * tf * (K1 + 1) / (tf + norm)
            }
        }
        return total
    }

    /** スコアの高い順に chunk id を最大 limit 件返す。同点は id の小さい順。 */
    fun rank(matches: List<FtsMatchInfo>, limit: Int): List<Long> =
        matches.asSequence()
            .map { it.chunkId to score(it.info) }
            .sortedWith(compareByDescending<Pair<Long, Double>> { it.second }.thenBy { it.first })
            .take(limit)
            .map { it.first }
            .toList()
}

/** `ChunkDao._ftsMatchInfoByTree` の 1 行。 */
class FtsMatchInfo(val chunkId: Long, val info: ByteArray)
