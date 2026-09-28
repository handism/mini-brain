package com.minibrain.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
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

internal sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class CodeBlock(val language: String, val code: String) : MdBlock()
    data class BulletItem(val text: String) : MdBlock()
    data class NumberedItem(val index: Int, val text: String) : MdBlock()
    data object HRule : MdBlock()
}

private fun isSpecialBlock(line: String): Boolean {
    val nt = line.trimStart()
    if (nt.isEmpty()) return true
    if (nt.startsWith("#")) return true
    if (nt.startsWith("```")) return true

    val c0 = nt[0]
    if ((c0 == '-' || c0 == '*' || c0 == '+') && nt.length > 1 && nt[1] == ' ') return true

    if (c0.isDigit()) {
        var idx = 1
        while (idx < nt.length && nt[idx].isDigit()) {
            idx++
        }
        if (idx < nt.length - 1 && nt[idx] == '.' && nt[idx+1] == ' ') return true
    }

    if (nt.length >= 3 && (c0 == '-' || c0 == '*' || c0 == '_')) {
        var isHrule = true
        for (i in 1 until nt.length) {
            val c = nt[i]
            if (c != '-' && c != '*' && c != '_') {
                isHrule = false
                break
            }
        }
        if (isHrule) return true
    }
    return false
}

internal fun parse(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = text.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()

        if (trimmed.isEmpty()) {
            i++
            continue
        }

        if (trimmed.startsWith("```")) {
            val lang = trimmed.removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            blocks.add(MdBlock.CodeBlock(lang, codeLines.joinToString("\n")))
            i++
            continue
        }

        if (line.startsWith("### ")) { blocks.add(MdBlock.Heading(3, line.removePrefix("### "))); i++; continue }
        if (line.startsWith("## ")) { blocks.add(MdBlock.Heading(2, line.removePrefix("## "))); i++; continue }
        if (line.startsWith("# ")) { blocks.add(MdBlock.Heading(1, line.removePrefix("# "))); i++; continue }

        val c0 = trimmed[0]

        var isBullet = false
        if ((c0 == '-' || c0 == '*' || c0 == '+') && trimmed.length > 1 && trimmed[1] == ' ') {
            blocks.add(MdBlock.BulletItem(trimmed.substring(2)))
            isBullet = true
            i++
            continue
        }

        var isHrule = false
        if (trimmed.length >= 3 && (c0 == '-' || c0 == '*' || c0 == '_')) {
            isHrule = true
            for (j in 1 until trimmed.length) {
                val c = trimmed[j]
                if (c != '-' && c != '*' && c != '_') {
                    isHrule = false
                    break
                }
            }
            if (isHrule) {
                blocks.add(MdBlock.HRule)
                i++
                continue
            }
        }

        var isNumbered = false
        if (c0.isDigit()) {
            var idx = 1
            while (idx < trimmed.length && trimmed[idx].isDigit()) {
                idx++
            }
            if (idx < trimmed.length - 1 && trimmed[idx] == '.' && trimmed[idx+1] == ' ') {
                blocks.add(MdBlock.NumberedItem(trimmed.substring(0, idx).toInt(), trimmed.substring(idx + 2)))
                isNumbered = true
                i++
                continue
            }
        }

        val para = mutableListOf(line)
        while (i + 1 < lines.size) {
            val next = lines[i + 1]
            if (isSpecialBlock(next)) break
            i++
            para.add(lines[i])
        }
        blocks.add(MdBlock.Paragraph(para.joinToString("\n")))
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
            }
        }
    }
}
