package com.minibrain.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTextTest {

    @Test
    fun testParseHeadings() {
        val blocks = parse("# Heading 1\n## Heading 2\n### Heading 3")
        assertEquals(3, blocks.size)
        assertEquals(MdBlock.Heading(1, "Heading 1"), blocks[0])
        assertEquals(MdBlock.Heading(2, "Heading 2"), blocks[1])
        assertEquals(MdBlock.Heading(3, "Heading 3"), blocks[2])
    }

    @Test
    fun testParseParagraphs() {
        val blocks = parse("This is a paragraph.\nIt has multiple lines.\n\nAnother paragraph.")
        assertEquals(2, blocks.size)
        assertEquals(MdBlock.Paragraph("This is a paragraph.\nIt has multiple lines."), blocks[0])
        assertEquals(MdBlock.Paragraph("Another paragraph."), blocks[1])
    }

    @Test
    fun testParseCodeBlock() {
        val blocks = parse("```kotlin\nval x = 1\n```")
        assertEquals(1, blocks.size)
        assertEquals(MdBlock.CodeBlock("kotlin", "val x = 1"), blocks[0])
    }

    @Test
    fun testParseUnclosedCodeBlock() {
        val blocks = parse("```\nval x = 1\nval y = 2")
        assertEquals(1, blocks.size)
        assertEquals(MdBlock.CodeBlock("", "val x = 1\nval y = 2"), blocks[0])
    }

    @Test
    fun testParseLists() {
        val blocks = parse("- Item 1\n* Item 2\n+ Item 3\n1. Numbered 1\n2. Numbered 2")
        assertEquals(5, blocks.size)
        assertEquals(MdBlock.BulletItem("Item 1"), blocks[0])
        assertEquals(MdBlock.BulletItem("Item 2"), blocks[1])
        assertEquals(MdBlock.BulletItem("Item 3"), blocks[2])
        assertEquals(MdBlock.NumberedItem(1, "Numbered 1"), blocks[3])
        assertEquals(MdBlock.NumberedItem(2, "Numbered 2"), blocks[4])
    }

    @Test
    fun testParseHRule() {
        val blocks = parse("---\n***\n___")
        assertEquals(3, blocks.size)
        assertEquals(MdBlock.HRule, blocks[0])
        assertEquals(MdBlock.HRule, blocks[1])
        assertEquals(MdBlock.HRule, blocks[2])
    }

    @Test
    fun testBuildInlineBoldItalic() {
        val result = buildInline("Some ***bold italic*** text", Color.Black)
        assertEquals("Some bold italic text", result.text)

        val span = result.spanStyles.first { it.start == 5 && it.end == 16 }
        assertEquals(FontWeight.Bold, span.item.fontWeight)
        assertEquals(FontStyle.Italic, span.item.fontStyle)
    }

    @Test
    fun testBuildInlineBold() {
        val result = buildInline("Some **bold** text and __more__", Color.Black)
        assertEquals("Some bold text and more", result.text)

        val span1 = result.spanStyles.first { it.start == 5 && it.end == 9 }
        assertEquals(FontWeight.Bold, span1.item.fontWeight)

        val span2 = result.spanStyles.first { it.start == 19 && it.end == 23 }
        assertEquals(FontWeight.Bold, span2.item.fontWeight)
    }

    @Test
    fun testBuildInlineItalic() {
        val result = buildInline("Some *italic* text and _more_", Color.Black)
        assertEquals("Some italic text and more", result.text)

        val span1 = result.spanStyles.first { it.start == 5 && it.end == 11 }
        assertEquals(FontStyle.Italic, span1.item.fontStyle)

        val span2 = result.spanStyles.first { it.start == 21 && it.end == 25 }
        assertEquals(FontStyle.Italic, span2.item.fontStyle)
    }

    @Test
    fun testBuildInlineCode() {
        val codeColor = Color.Blue
        val result = buildInline("Some `inline code` here", codeColor)
        assertEquals("Some inline code here", result.text)

        val span = result.spanStyles.first { it.start == 5 && it.end == 16 }
        assertEquals(FontFamily.Monospace, span.item.fontFamily)
        assertEquals(codeColor, span.item.background)
    }

    @Test
    fun testBuildInlineUnmatched() {
        val result = buildInline("Some **bold text", Color.Black)
        assertEquals("Some **bold text", result.text)
        assertEquals(0, result.spanStyles.size)
    }

    @Test
    fun testBuildInlineEmpty() {
        val result = buildInline("****", Color.Black)
        assertEquals("****", result.text)
    }
}
