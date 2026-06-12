package com.minibrain.ai.embed

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * 純 Kotlin E5Tokenizer と HuggingFace tokenizers（DJL JVM 版 = Rust 実装バインディング）の
 * トークン列一致を検証する parity テスト。
 *
 * 実 tokenizer.json が必要。/tmp/e5-tokenizer.json（環境変数 E5_TOKENIZER_JSON で上書き可）に
 * 無ければ HuggingFace から自動ダウンロードし、それも失敗した場合はスキップする。
 */
class E5TokenizerParityTest {

    companion object {
        private val tokenizerFile = File(System.getenv("E5_TOKENIZER_JSON") ?: "/tmp/e5-tokenizer.json")
        private var reference: HuggingFaceTokenizer? = null
        private var subject: E5Tokenizer? = null

        @JvmStatic
        @BeforeClass
        fun setUp() {
            if (!tokenizerFile.exists()) {
                runCatching {
                    val url = java.net.URL(
                        "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/tokenizer.json"
                    )
                    val tmp = File(tokenizerFile.absolutePath + ".part")
                    url.openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    tmp.renameTo(tokenizerFile)
                }
            }
            if (!tokenizerFile.exists()) return
            reference = HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokenizerFile.toPath())
                .optAddSpecialTokens(true)
                .optTruncation(true)
                .optMaxLength(512)
                .build()
            subject = E5Tokenizer.load(tokenizerFile)
        }
    }

    private fun assertParity(text: String) {
        assumeTrue("tokenizer.json が無いためスキップ: $tokenizerFile", tokenizerFile.exists())
        val expected = reference!!.encode(text).ids
        val actual = subject!!.encode(text, 512).ids
        assertArrayEquals("mismatch for: $text", expected, actual)
    }

    @Test
    fun `英語`() = assertParity("query: what did I do last week?")

    @Test
    fun `日本語`() = assertParity("passage: 今日は東京で打ち合わせをした。明日の予定はまだ決まっていない。")

    @Test
    fun `日本語クエリ`() = assertParity("query: 沖縄旅行はいつだった？")

    @Test
    fun `日英混在`() = assertParity("passage: Android の 16KB page size 対応について調査した。ELF alignment が必要。")

    @Test
    fun `日付とパス`() = assertParity("passage: 2026-06-13 のメモ。notes/2026/06/13.md に保存。documentDate=20260613")

    @Test
    fun `記号と絵文字`() = assertParity("passage: 完了🎉 リスト: ①あれ ②これ → 残り 50%！《重要》")

    @Test
    fun `連続スペースとタブ改行`() = assertParity("passage: foo   bar\tbaz\nqux  quux")

    @Test
    fun `全角英数と半角カナ`() = assertParity("passage: ＡＢＣ１２３ ｱｲｳｴｵ ﾃﾞｰﾀ")

    @Test
    fun `先頭末尾スペース`() = assertParity("  passage: 前後に空白  ")

    @Test
    fun `空文字列`() = assertParity("")

    @Test
    fun `長文トランケート`() {
        val long = (1..200).joinToString(" ") { "これは長い文章のテストです。セグメント$it" }
        assertParity("passage: $long")
    }

    @Test
    fun `マークダウン`() = assertParity(
        "passage: # 見出し\n\n- リスト項目1\n- リスト項目2\n\n```kotlin\nval x = 1\n```\n\n**強調** と [リンク](https://example.com)"
    )
}
