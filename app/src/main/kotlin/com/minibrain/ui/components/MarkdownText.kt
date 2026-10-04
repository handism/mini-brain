package com.minibrain.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val hRuleRegex = Regex("[-*_]{3,}")
private val numberedMatchRegex = Regex("\\d+\\.\\s.*")
private val numberedItemRegex = Regex("^(\\d+)\\.\\s(.*)")
// | --- | :---: | のような表の区切り行
private val tableSeparatorRegex = Regex("^\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?$")

internal sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class CodeBlock(val language: String, val code: String) : MdBlock()
    data class BulletItem(val text: String) : MdBlock()
    data class NumberedItem(val index: Int, val text: String) : MdBlock()
    data object HRule : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock()
}

private fun isTableRow(trimmed: String) = trimmed.startsWith("|")

/** 先頭・末尾の `|` を落としてセルに分ける。 */
internal fun splitTableRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

/** ヘッダー行の直後に区切り行がある箇所だけを表とみなす（`|` で始まる普通の文を表にしない）。 */
private fun isTableStart(lines: List<String>, i: Int): Boolean =
    isTableRow(lines[i].trimStart()) &&
        i + 1 < lines.size && lines[i + 1].trim().matches(tableSeparatorRegex)

internal fun parse(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = text.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()

        when {
            trimmed.startsWith("```") -> {
                val lang = trimmed.removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                blocks.add(MdBlock.CodeBlock(lang, codeLines.joinToString("\n")))
            }
            isTableStart(lines, i) -> {
                val header = splitTableRow(line)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && isTableRow(lines[i].trimStart())) {
                    // 列数が足りない行は空セルで埋め、多い行は切って崩れないようにする
                    val cells = splitTableRow(lines[i])
                    rows.add(List(header.size) { cells.getOrElse(it) { "" } })
                    i++
                }
                blocks.add(MdBlock.Table(header, rows))
                continue
            }
            trimmed.startsWith(">") -> {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quoteLines.add(lines[i].trimStart().removePrefix(">").removePrefix(" "))
                    i++
                }
                blocks.add(MdBlock.Quote(quoteLines.joinToString("\n")))
                continue
            }
            line.startsWith("### ") -> blocks.add(MdBlock.Heading(3, line.removePrefix("### ")))
            line.startsWith("## ") -> blocks.add(MdBlock.Heading(2, line.removePrefix("## ")))
            line.startsWith("# ") -> blocks.add(MdBlock.Heading(1, line.removePrefix("# ")))
            trimmed.matches(hRuleRegex) -> blocks.add(MdBlock.HRule)
            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") ->
                blocks.add(MdBlock.BulletItem(trimmed.substring(2)))
            trimmed.matches(numberedMatchRegex) -> {
                val m = numberedItemRegex.find(trimmed)
                if (m != null) blocks.add(MdBlock.NumberedItem(m.groupValues[1].toInt(), m.groupValues[2]))
            }
            line.isBlank() -> { /* ブロック間の空行はスキップ */ }
            else -> {
                val para = mutableListOf(line)
                while (i + 1 < lines.size) {
                    val next = lines[i + 1]
                    val nt = next.trimStart()
                    if (next.isBlank() || nt.startsWith("#") || nt.startsWith("```") ||
                        nt.startsWith("- ") || nt.startsWith("* ") || nt.startsWith("+ ") ||
                        nt.startsWith(">") || isTableStart(lines, i + 1) ||
                        nt.matches(numberedMatchRegex) || nt.matches(hRuleRegex)) break
                    i++
                    para.add(lines[i])
                }
                blocks.add(MdBlock.Paragraph(para.joinToString("\n")))
            }
        }
        i++
    }
    return blocks
}

internal fun buildInline(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    var remaining = text
    while (remaining.isNotEmpty()) {
        when {
            remaining.startsWith("***") || remaining.startsWith("___") -> {
                val delim = remaining.substring(0, 3)
                val end = remaining.indexOf(delim, 3)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                        append(buildInline(remaining.substring(3, end), codeBackground))
                    }
                    remaining = remaining.substring(end + 3)
                } else {
                    append(remaining[0]); remaining = remaining.substring(1)
                }
            }
            remaining.startsWith("**") || remaining.startsWith("__") -> {
                val delim = remaining.substring(0, 2)
                val end = remaining.indexOf(delim, 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(buildInline(remaining.substring(2, end), codeBackground))
                    }
                    remaining = remaining.substring(end + 2)
                } else {
                    append(remaining[0]); remaining = remaining.substring(1)
                }
            }
            (remaining.startsWith("*") || remaining.startsWith("_")) && remaining.length > 1 -> {
                val d = remaining[0]
                val end = remaining.indexOf(d, 1)
                if (end > 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(buildInline(remaining.substring(1, end), codeBackground))
                    }
                    remaining = remaining.substring(end + 1)
                } else {
                    append(remaining[0]); remaining = remaining.substring(1)
                }
            }
            remaining.startsWith("`") -> {
                val end = remaining.indexOf('`', 1)
                if (end != -1) {
                    withStyle(SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBackground,
                        fontSize = 13.sp,
                    )) { append(remaining.substring(1, end)) }
                    remaining = remaining.substring(end + 1)
                } else {
                    append(remaining[0]); remaining = remaining.substring(1)
                }
            }
            else -> { append(remaining[0]); remaining = remaining.substring(1) }
        }
    }
}

@Composable
fun MarkdownText(
    text: String,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parse(text).forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    Text(
                        text = buildInline(block.text, codeBackground),
                        style = style,
                        color = textColor,
                    )
                }
                is MdBlock.Paragraph -> Text(
                    text = buildInline(block.text, codeBackground),
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                )
                is MdBlock.CodeBlock -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(codeBackground, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                ) {
                    Text(
                        text = block.code,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = textColor,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }
                is MdBlock.BulletItem -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("•", style = MaterialTheme.typography.bodyMedium, color = textColor)
                    Text(
                        text = buildInline(block.text, codeBackground),
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                    )
                }
                is MdBlock.NumberedItem -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${block.index}.", style = MaterialTheme.typography.bodyMedium, color = textColor)
                    Text(
                        text = buildInline(block.text, codeBackground),
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                    )
                }
                MdBlock.HRule -> HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                is MdBlock.Quote -> Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
                    )
                    Text(
                        text = buildInline(block.text, codeBackground),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                is MdBlock.Table -> MarkdownTable(block, textColor, codeBackground)
            }
        }
    }
}

// 1 セルが長文で表が画面外まで伸びないよう、列幅に上限を設ける
private val TABLE_CELL_MAX_WIDTH = 200.dp

/**
 * 列幅は列内でいちばん長いセル（上限あり）、行の高さは行内でいちばん高いセルに揃える。
 * 幅が足りなければ横スクロールさせる。
 */
@Composable
private fun MarkdownTable(table: MdBlock.Table, textColor: Color, codeBackground: Color) {
    val shape = RoundedCornerShape(8.dp)
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val headerBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val columnCount = table.header.size
    val allRows = listOf(table.header) + table.rows
    Box(
        modifier = Modifier
            .clip(shape)
            .border(1.dp, borderColor, shape)
            .horizontalScroll(rememberScrollState()),
    ) {
        Layout(
            content = {
                allRows.forEachIndexed { r, row ->
                    row.forEachIndexed { c, cell ->
                        TableCell(
                            text = cell,
                            style = if (r == 0) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall,
                            textColor = textColor,
                            codeBackground = codeBackground,
                            modifier = Modifier
                                .then(if (r == 0) Modifier.background(headerBackground) else Modifier)
                                .drawBehind {
                                    val stroke = 1.dp.toPx()
                                    if (r > 0) drawLine(borderColor, Offset.Zero, Offset(size.width, 0f), stroke)
                                    if (c > 0) drawLine(borderColor, Offset.Zero, Offset(0f, size.height), stroke)
                                },
                        )
                    }
                }
            },
        ) { measurables, _ ->
            val maxCellWidth = TABLE_CELL_MAX_WIDTH.roundToPx()
            val rowCount = allRows.size
            val colWidths = IntArray(columnCount) { c ->
                (0 until rowCount).maxOf { r ->
                    measurables[r * columnCount + c].maxIntrinsicWidth(Constraints.Infinity)
                }.coerceAtMost(maxCellWidth)
            }
            val rowHeights = IntArray(rowCount) { r ->
                (0 until columnCount).maxOf { c ->
                    measurables[r * columnCount + c].minIntrinsicHeight(colWidths[c])
                }
            }
            val placeables = measurables.mapIndexed { i, m ->
                m.measure(Constraints.fixed(colWidths[i % columnCount], rowHeights[i / columnCount]))
            }
            layout(colWidths.sum(), rowHeights.sum()) {
                var y = 0
                for (r in 0 until rowCount) {
                    var x = 0
                    for (c in 0 until columnCount) {
                        placeables[r * columnCount + c].placeRelative(x, y)
                        x += colWidths[c]
                    }
                    y += rowHeights[r]
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    style: TextStyle,
    textColor: Color,
    codeBackground: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = buildInline(text, codeBackground),
        style = style,
        color = textColor,
        modifier = modifier.padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
