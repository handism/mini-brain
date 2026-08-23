package com.minibrain.data.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownMetaExtractorTest {

    private fun extract(content: String): String? =
        MarkdownMetaExtractor.extractDateFromContent(content)

    private fun extractFirstParagraph(content: String, maxChars: Int = 200): String =
        MarkdownMetaExtractor.extractFirstParagraph(content, maxChars)

    @Test
    fun testExtractFirstParagraph_basic() {
        val md = """

            This is the first paragraph.
            It has two lines.

            This is the second paragraph.
        """.trimIndent()
        assertEquals("This is the first paragraph. It has two lines.", extractFirstParagraph(md))
    }

    @Test
    fun testExtractFirstParagraph_skipYaml() {
        val md = """
            ---
            title: Hello
            date: 2024-01-01
            ---
            This is the first paragraph after YAML.
        """.trimIndent()
        assertEquals("This is the first paragraph after YAML.", extractFirstParagraph(md))
    }

    @Test
    fun testExtractFirstParagraph_skipHeadingsAndImages() {
        val md = """
            # Heading 1
            ## Heading 2
            ![Image](image.jpg)

            This is the actual first paragraph.
        """.trimIndent()
        assertEquals("This is the actual first paragraph.", extractFirstParagraph(md))
    }

    @Test
    fun testExtractFirstParagraph_emptyLinesBeforeParagraph() {
        val md = """



            Paragraph text.
        """.trimIndent()
        assertEquals("Paragraph text.", extractFirstParagraph(md))
    }

    @Test
    fun testExtractFirstParagraph_maxChars() {
        val md = """
            This is a very long paragraph that will definitely exceed the twenty character limit we set for this test.
        """.trimIndent()
        assertEquals("This is a very long ", extractFirstParagraph(md, 20))
    }

    @Test
    fun testExtractFirstParagraph_emptyString() {
        assertEquals("", extractFirstParagraph(""))
    }

    @Test
    fun testExtractFirstParagraph_onlyYamlAndHeadings() {
        val md = """
            ---
            title: Title
            ---
            # Heading
        """.trimIndent()
        assertEquals("", extractFirstParagraph(md))
    }

    @Test
    fun yamlDateLabel() {
        val md = """
            ---
            date: 2024-12-15
            ---
            本文
        """.trimIndent()
        assertEquals("2024-12-15", extract(md))
    }

    @Test
    fun yamlCreatedLabel() {
        val md = """
            ---
            created: 2024-12-15
            ---
            本文
        """.trimIndent()
        assertEquals("2024-12-15", extract(md))
    }

    @Test
    fun yamlJpLabel() {
        val md = """
            ---
            日付: 2024/12/15
            ---
            本文
        """.trimIndent()
        assertEquals("2024-12-15", extract(md))
    }

    @Test
    fun yamlQuotedDate() {
        val md = """
            ---
            published: '2024-12-15'
            ---
            本文
        """.trimIndent()
        assertEquals("2024-12-15", extract(md))
    }

    @Test
    fun labeledLineWithJpLabel() {
        val md = """
            # タイトル

            初回訪問日: 2024-12-15
        """.trimIndent()
        assertEquals("2024-12-15", extract(md))
    }

    @Test
    fun bodyHyphenDate() {
        val md = """
            # メモ

            2024-12-15 に書いた
        """.trimIndent()
        assertEquals("2024-12-15", extract(md))
    }

    @Test
    fun headingJpFullDate() {
        val md = """
            # 2024年12月15日（日）

            今日は寒い
        """.trimIndent()
        assertEquals("2024-12-15", extract(md))
    }

    @Test
    fun headingJpMonthOnly() {
        val md = """
            # 2024年12月

            振り返り
        """.trimIndent()
        assertEquals("2024-12-01", extract(md))
    }

    @Test
    fun casualBodyJpDateDoesNotExtract() {
        // 本文中の「2024年5月にXXした」のようなカジュアル言及は documentDate にしない
        // （日記でないノートに誤って [日付:] プレフィックスを付け、Reranker の競合候補を
        //  増やして無関係なファイルが押し出される regression を防ぐ）
        val md = """
            # サウナしきじ

            2024年5月に行ってきた。とても良かった。
        """.trimIndent()
        assertNull(extract(md))
    }

    @Test
    fun yamlPriorityOverBody() {
        // frontmatter の date が本文より優先される
        val md = """
            ---
            date: 2024-12-15
            ---
            2020-01-01 の話
        """.trimIndent()
        assertEquals("2024-12-15", extract(md))
    }

    @Test
    fun outOfRangeYearReturnsNull() {
        val md = """
            # 古文書

            1850-01-01
        """.trimIndent()
        assertNull(extract(md))
    }

    @Test
    fun invalidMonthReturnsNull() {
        val md = """
            ---
            date: 2024-13-15
            ---
            本文
        """.trimIndent()
        assertNull(extract(md))
    }

    @Test
    fun invalidDayReturnsNull() {
        val md = """
            ---
            date: 2024-02-30
            ---
            本文
        """.trimIndent()
        assertNull(extract(md))
    }

    @Test
    fun invalidHeadingDateReturnsNull() {
        val md = """
            # 2024年02月30日

            本文
        """.trimIndent()
        assertNull(extract(md))
    }

    @Test
    fun noDateReturnsNull() {
        assertNull(extract("# タイトル\n\n本文だけ"))
    }

    @Test
    fun testSafeDate_valid() {
        val today = java.time.LocalDate.of(2024, 5, 1)
        assertEquals("2024-04-15", MarkdownMetaExtractor.safeDate("2024", "4", "15", today))
    }

    @Test
    fun testSafeDate_futureReturnsNull() {
        val today = java.time.LocalDate.of(2024, 5, 1)
        assertNull(MarkdownMetaExtractor.safeDate("2024", "5", "2", today))
    }

    @Test
    fun testSafeDate_invalidDateReturnsNull() {
        val today = java.time.LocalDate.of(2024, 5, 1)
        assertNull(MarkdownMetaExtractor.safeDate("2024", "2", "30", today))
    }

    @Test
    fun testSafeDate_defaultDay() {
        val today = java.time.LocalDate.of(2024, 5, 1)
        assertEquals("2024-04-01", MarkdownMetaExtractor.safeDate("2024", "4", today = today))
    }
}
