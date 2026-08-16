package com.minibrain.data.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

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
    fun noDateReturnsNull() {
        assertNull(extract("# タイトル\n\n本文だけ"))
    }

    @Test
    fun testSafeDate_valid() {
        val today = LocalDate.of(2025, 1, 1)
        assertEquals("2024-12-15", MarkdownMetaExtractor.safeDate("2024", "12", "15", today))
    }

    @Test
    fun testSafeDate_futureDate() {
        val today = LocalDate.of(2024, 12, 1)
        assertNull("Future date should return null", MarkdownMetaExtractor.safeDate("2024", "12", "15", today))
    }

    @Test
    fun testSafeDate_before1990() {
        val today = LocalDate.of(2025, 1, 1)
        assertNull("Dates before 1990 should return null", MarkdownMetaExtractor.safeDate("1989", "12", "31", today))
    }

    @Test
    fun testSafeDate_invalidMonth() {
        val today = LocalDate.of(2025, 1, 1)
        assertNull("Invalid month should return null (runCatching catches exception)", MarkdownMetaExtractor.safeDate("2024", "13", "1", today))
    }

    @Test
    fun testSafeDate_invalidDay() {
        val today = LocalDate.of(2025, 1, 1)
        assertNull("Invalid day should return null (runCatching catches exception)", MarkdownMetaExtractor.safeDate("2024", "2", "30", today))
    }

    @Test
    fun testSafeDate_invalidFormat() {
        val today = LocalDate.of(2025, 1, 1)
        assertNull("Invalid string format should return null", MarkdownMetaExtractor.safeDate("YYYY", "MM", "DD", today))
    }

    @Test
    fun testSafeMonth_valid() {
        val today = LocalDate.of(2025, 1, 1)
        assertEquals("2024-12-01", MarkdownMetaExtractor.safeMonth("2024", "12", today))
    }

    @Test
    fun testSafeMonth_futureDate() {
        val today = LocalDate.of(2024, 11, 1)
        assertNull("Future month should return null", MarkdownMetaExtractor.safeMonth("2024", "12", today))
    }

    @Test
    fun testSafeMonth_before1990() {
        val today = LocalDate.of(2025, 1, 1)
        assertNull("Month before 1990 should return null", MarkdownMetaExtractor.safeMonth("1989", "12", today))
    }

    @Test
    fun testSafeMonth_invalidMonth() {
        val today = LocalDate.of(2025, 1, 1)
        assertNull("Invalid month should return null", MarkdownMetaExtractor.safeMonth("2024", "13", today))
    }

    @Test
    fun testSafeMonth_invalidFormat() {
        val today = LocalDate.of(2025, 1, 1)
        assertNull("Invalid string format should return null", MarkdownMetaExtractor.safeMonth("YYYY", "MM", today))
    }
}
