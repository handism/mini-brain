package com.minibrain.ai.embed

import java.text.BreakIterator

/**
 * SentencePiece の precompiled_charsmap（Darts double-array trie + 正規化文字列 blob）による
 * テキスト正規化。HuggingFace spm_precompiled (Rust) の移植。
 */
class PrecompiledCharsMap private constructor(
    private val trie: IntArray,
    private val normalized: ByteArray,
) {

    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        val iter = BreakIterator.getCharacterInstance()
        iter.setText(text)
        var start = iter.first()
        var end = iter.next()
        while (end != BreakIterator.DONE) {
            val grapheme = text.substring(start, end)
            val bytes = grapheme.toByteArray(Charsets.UTF_8)
            var handled = false
            // spm_precompiled と同じく 6 バイト未満の grapheme はまず全体一致を試す
            if (bytes.size < 6) {
                val t = transform(bytes)
                if (t != null) {
                    sb.append(t)
                    handled = true
                }
            }
            if (!handled) {
                var i = 0
                while (i < grapheme.length) {
                    val cp = grapheme.codePointAt(i)
                    val cpLen = Character.charCount(cp)
                    val t = transform(grapheme.substring(i, i + cpLen).toByteArray(Charsets.UTF_8))
                    if (t != null) sb.append(t) else sb.appendCodePoint(cp)
                    i += cpLen
                }
            }
            start = end
            end = iter.next()
        }
        return sb.toString()
    }

    private fun transform(key: ByteArray): String? {
        val index = commonPrefixSearchFirst(key) ?: return null
        var blobEnd = index
        while (blobEnd < normalized.size && normalized[blobEnd] != 0.toByte()) blobEnd++
        return String(normalized, index, blobEnd - index, Charsets.UTF_8)
    }

    /** Darts double-array の common prefix search。最初に見つかった葉の value を返す。 */
    private fun commonPrefixSearchFirst(key: ByteArray): Int? {
        var nodePos = 0
        var unit = unitAt(0)
        nodePos = nodePos xor offset(unit)
        for (b in key) {
            val c = b.toInt() and 0xFF
            if (c == 0) break
            nodePos = nodePos xor c
            unit = unitAt(nodePos)
            if (label(unit) != c.toLong()) return null
            nodePos = nodePos xor offset(unit)
            if (hasLeaf(unit)) {
                return value(unitAt(nodePos))
            }
        }
        return null
    }

    private fun unitAt(pos: Int): Long = trie[pos].toLong() and 0xFFFFFFFFL

    private fun hasLeaf(unit: Long): Boolean = (unit shr 8) and 1L == 1L

    private fun value(unit: Long): Int = (unit and 0x7FFFFFFFL).toInt()

    private fun label(unit: Long): Long = unit and 0x800000FFL

    private fun offset(unit: Long): Int = ((unit shr 10) shl (((unit and 0x200L) shr 6).toInt())).toInt()

    companion object {
        fun parse(bytes: ByteArray): PrecompiledCharsMap {
            require(bytes.size >= 4) { "precompiled_charsmap が短すぎます" }
            val trieSize = ((bytes[0].toInt() and 0xFF)) or
                ((bytes[1].toInt() and 0xFF) shl 8) or
                ((bytes[2].toInt() and 0xFF) shl 16) or
                ((bytes[3].toInt() and 0xFF) shl 24)
            require(trieSize in 0..bytes.size - 4) { "precompiled_charsmap の trie サイズが不正: $trieSize" }
            val unitCount = trieSize / 4
            val trie = IntArray(unitCount)
            var off = 4
            for (i in 0 until unitCount) {
                trie[i] = ((bytes[off].toInt() and 0xFF)) or
                    ((bytes[off + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[off + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[off + 3].toInt() and 0xFF) shl 24)
                off += 4
            }
            val normalized = bytes.copyOfRange(off, bytes.size)
            return PrecompiledCharsMap(trie, normalized)
        }
    }
}
