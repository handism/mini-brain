package com.minibrain.ai.embed

import com.squareup.moshi.JsonReader
import okio.buffer
import okio.source
import java.io.File
import java.util.Base64

/**
 * multilingual-e5-small (XLM-RoBERTa / SentencePiece Unigram) 用の純 Kotlin tokenizer。
 */
class E5Tokenizer private constructor(
    private val charsMap: PrecompiledCharsMap,
    private val replacement: Char,
    private val model: UnigramModel,
) {

    // tokenTypeIds を追加
    class Encoding(val ids: LongArray, val attentionMask: LongArray, val tokenTypeIds: LongArray)

    fun encode(text: String, maxLength: Int = 512): Encoding {
        var s = charsMap.normalize(text)
        s = MULTI_SPACE.replace(s, " ")
        s = s.replace(' ', replacement)
        if (s.isNotEmpty() && s[0] != replacement) s = replacement + s

        val ids = ArrayList<Int>()
        var start = 0
        for (idx in 1 until s.length) {
            if (s[idx] == replacement) {
                ids.addAll(model.tokenize(s.substring(start, idx)))
                start = idx
            }
        }
        if (start < s.length) ids.addAll(model.tokenize(s.substring(start)))

        val bodyLen = minOf(ids.size, maxLength - 2)
        val out = LongArray(bodyLen + 2)
        out[0] = BOS_ID
        for (k in 0 until bodyLen) out[k + 1] = ids[k].toLong()
        out[out.size - 1] = EOS_ID

        // すべて 0 の LongArray を生成
        return Encoding(out, LongArray(out.size) { 1L }, LongArray(out.size) { 0L })
    }

    companion object {
        private const val BOS_ID = 0L
        private const val EOS_ID = 2L
        private val MULTI_SPACE = Regex(" {2,}")

        fun load(file: File): E5Tokenizer {
            return TokenizerJsonParser(file).parse()
        }

        private class TokenizerJsonParser(private val file: File) {
            var charsmapBase64: String? = null
            var replacement = '▁'
            var unkId = 3
            val pieceIds = HashMap<String, Int>(300_000)
            var scores = DoubleArray(280_000)
            var pieceCount = 0

            fun parse(): E5Tokenizer {
                JsonReader.of(file.source().buffer()).use { reader ->
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "normalizer" -> scanStrings(reader) { name, value ->
                                if (name == "precompiled_charsmap") charsmapBase64 = value
                            }
                            "pre_tokenizer" -> scanStrings(reader) { name, value ->
                                if (name == "replacement" && value.isNotEmpty()) replacement = value[0]
                            }
                            "model" -> readModel(reader)
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }

                require(pieceCount > 0) { "tokenizer.json に vocab がありません: ${file.absolutePath}" }
                val base64 = requireNotNull(charsmapBase64) {
                    "tokenizer.json に precompiled_charsmap がありません: ${file.absolutePath}"
                }
                val charsMap = PrecompiledCharsMap.parse(Base64.getDecoder().decode(base64))
                val model = UnigramModel(pieceIds, scores.copyOf(pieceCount), unkId)
                return E5Tokenizer(charsMap, replacement, model)
            }

            private fun readModel(reader: JsonReader) {
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "unk_id" -> unkId = reader.nextInt()
                        "vocab" -> readVocab(reader)
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }

            private fun readVocab(reader: JsonReader) {
                reader.beginArray()
                while (reader.hasNext()) {
                    reader.beginArray()
                    val piece = reader.nextString()
                    val score = reader.nextDouble()
                    reader.endArray()
                    if (pieceCount == scores.size) {
                        scores = scores.copyOf(scores.size * 2)
                    }
                    scores[pieceCount] = score
                    pieceIds[piece] = pieceCount
                    pieceCount++
                }
                reader.endArray()
            }

            private fun scanStrings(reader: JsonReader, onString: (name: String, value: String) -> Unit) {
                scanValue(reader, null, onString)
            }

            private fun scanValue(reader: JsonReader, name: String?, onString: (String, String) -> Unit) {
                when (reader.peek()) {
                    JsonReader.Token.BEGIN_OBJECT -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val childName = reader.nextName()
                            scanValue(reader, childName, onString)
                        }
                        reader.endObject()
                    }
                    JsonReader.Token.BEGIN_ARRAY -> {
                        reader.beginArray()
                        while (reader.hasNext()) scanValue(reader, name, onString)
                        reader.endArray()
                    }
                    JsonReader.Token.STRING -> {
                        val value = reader.nextString()
                        if (name != null) onString(name, value)
                    }
                    else -> reader.skipValue()
                }
            }
        }
    }
}
