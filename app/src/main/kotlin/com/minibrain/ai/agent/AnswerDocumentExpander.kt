package com.minibrain.ai.agent

import com.minibrain.ai.rag.Citation
import com.minibrain.ai.rag.SearchRequestCache
import com.minibrain.data.db.entities.ChunkEntity
import com.minibrain.util.DatePrefix

/**
 * 回答 LLM に渡す上位の引用を、1 チャンクから文書全体（長ければ一致したチャンクの周辺）に広げる（ADR-049）。
 *
 * 検索は 1 ファイル 1 チャンクで並べる（ADR-047）が、答えが同じファイルの別の節にあると
 * 回答 LLM はそれを見られない（「山本さん.md」が 1 位なのに「気をつけること」の節が渡らない）。
 * 個人のメモは 1 ファイルが短いので、上位数件は全文を渡す。画面に出す引用元は変えない。
 */
object AnswerDocumentExpander {
    /** 全文に広げる上位の文書数 */
    const val EXPAND_DOCS = 3

    /** 広げた後の本文の上限（文字）。超える文書は一致したチャンクから前後に広げてここで止める */
    const val MAX_DOC_CHARS = 1500

    // 隣のチャンクと重なる文字数の上限（MarkdownChunker の OVERLAP_CHARS / SECTION_TAIL_CARRY より大きく）
    private const val MAX_OVERLAP_CHARS = 200
    // これより短い一致は偶然（行頭の「- 」など）とみなす
    private const val MIN_OVERLAP_CHARS = 10
    private const val ANCHOR_PROBE_CHARS = 40
    private const val GAP_MARK = "…"
    // プロンプトでは「### path > 全文」と出る
    const val FULL_TEXT_HEADING = "全文"

    suspend fun expand(citations: List<Citation>, cache: SearchRequestCache): List<Citation> {
        val byDoc = cache.chunksByDoc()
        val expanded = HashSet<Long>()
        return citations.mapNotNull { c ->
            val docId = c.docId ?: return@mapNotNull c
            if (docId in expanded) return@mapNotNull null // 全文に含まれるので同じファイルの 2 件目は外す
            if (expanded.size >= EXPAND_DOCS) return@mapNotNull c
            val chunks = byDoc[docId].orEmpty()
            if (chunks.isEmpty()) return@mapNotNull c
            expanded += docId
            val (date, body) = DatePrefix.split(c.snippet)
            val text = documentText(chunks, anchorIndex(chunks, body), MAX_DOC_CHARS)
            c.copy(headingPath = FULL_TEXT_HEADING, snippet = DatePrefix.build(date, text))
        }
    }

    /** snippet の書き出しを含むチャンク。見つからなければ先頭 */
    internal fun anchorIndex(chunks: List<ChunkEntity>, snippet: String): Int {
        val probe = snippet.trim().take(ANCHOR_PROBE_CHARS)
        if (probe.isEmpty()) return 0
        return chunks.indexOfFirst { it.text.contains(probe) }.coerceAtLeast(0)
    }

    /**
     * チャンクを文書の順に並べ直す。見出しが変わるところに「## 見出し」を入れ、隣と重なる部分は 1 回だけにする。
     * maxChars を超えるときは anchor から後ろ・前の順に交互に足していき、飛ばした所には「…」を置く。
     */
    internal fun documentText(chunks: List<ChunkEntity>, anchor: Int, maxChars: Int): String {
        val pieces = chunks.mapIndexed { i, chunk ->
            val prev = chunks.getOrNull(i - 1)
            val body = if (prev == null) chunk.text else chunk.text.drop(overlap(prev.text, chunk.text)).trimStart()
            val heading = chunk.headingPath.substringAfterLast(" > ", "")
            if (heading.isNotEmpty() && chunk.headingPath != prev?.headingPath && chunk.headingPath.contains(" > ")) {
                "## $heading\n$body"
            } else {
                body
            }
        }
        val chosen = sortedSetOf(anchor.coerceIn(pieces.indices))
        var length = pieces[chosen.first()].length
        var after = chosen.first() + 1
        var before = chosen.first() - 1
        var turnAfter = true
        while (after < pieces.size || before >= 0) {
            val next = when {
                after >= pieces.size -> before--
                before < 0 -> after++
                turnAfter -> after++
                else -> before--
            }
            turnAfter = !turnAfter
            if (length + pieces[next].length > maxChars) break
            chosen += next
            length += pieces[next].length
        }
        return buildString {
            if (chosen.first() > 0) append(GAP_MARK).append("\n\n")
            chosen.forEachIndexed { k, i ->
                if (k > 0) append("\n\n")
                append(pieces[i])
            }
            if (chosen.last() < pieces.lastIndex) append("\n\n").append(GAP_MARK)
        }.take(maxChars)
    }

    // prev の末尾と cur の先頭が一致する最長の文字数
    private fun overlap(prev: String, cur: String): Int {
        for (k in minOf(MAX_OVERLAP_CHARS, prev.length, cur.length) downTo MIN_OVERLAP_CHARS) {
            if (prev.endsWith(cur.substring(0, k))) return k
        }
        return 0
    }
}
