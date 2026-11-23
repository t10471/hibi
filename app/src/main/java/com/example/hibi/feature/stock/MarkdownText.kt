package com.example.hibi.feature.stock

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val TABLE_SEP_REGEX = Regex("""^\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)+\|?$""")

private fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    return t.contains("-") && (TABLE_SEP_REGEX.matches(t) || Regex("""^\|?\s*:?-{2,}:?\s*\|?$""").matches(t))
}

private fun splitTableCells(line: String): List<String> {
    var t = line.trim()
    if (t.startsWith("|")) t = t.drop(1)
    if (t.endsWith("|")) t = t.dropLast(1)
    return t.split("|").map { it.trim() }
}

/**
 * GeminiのMarkdown形式の回答をMaterial 3スタイルで美しくレンダリングするコンポーネント
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val lines = markdown.replace("\r\n", "\n").lines()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // コードブロック ```
            if (trimmed.startsWith("```")) {
                val codeBlockLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    codeBlockLines.add(lines[i])
                    i++
                }
                if (i < lines.size && lines[i].trim().startsWith("```")) {
                    i++
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        text = codeBlockLines.joinToString("\n"),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(10.dp),
                        color = textColor,
                    )
                }
                continue
            }

            // 空行
            if (trimmed.isEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                i++
                continue
            }

            // 水平線 (--- or *** or ___)
            if (trimmed.matches(Regex("""^(-{3,}|\*{3,}|_{3,})$"""))) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                )
                i++
                continue
            }

            // テーブル
            if (trimmed.contains("|")) {
                var sepIdx = i + 1
                while (sepIdx < lines.size && lines[sepIdx].trim().isEmpty()) {
                    sepIdx++
                }
                if (sepIdx < lines.size && isTableSeparator(lines[sepIdx])) {
                    val headers = splitTableCells(trimmed)
                    val rows = mutableListOf<List<String>>()
                    var j = sepIdx + 1
                    while (j < lines.size) {
                        val rt = lines[j].trim()
                        if (rt.isEmpty()) {
                            var lookAhead = j + 1
                            while (lookAhead < lines.size && lines[lookAhead].trim().isEmpty()) {
                                lookAhead++
                            }
                            if (lookAhead < lines.size && lines[lookAhead].trim().startsWith("|")) {
                                j = lookAhead
                                continue
                            }
                            break
                        }
                        if (!rt.contains("|")) break
                        rows.add(splitTableCells(rt))
                        j++
                    }
                    MarkdownTableView(headers = headers, rows = rows, textColor = textColor)
                    i = j
                    continue
                }
            }

            // 引用 (>)
            if (trimmed.startsWith(">")) {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quoteLines.add(lines[i].trim().removePrefix(">").trim())
                    i++
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        quoteLines.forEach { q ->
                            Text(
                                text = parseInlineMarkdown(q),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                continue
            }

            // 門番判定バッジ 【🛑 ...】 / 【⚠️ ...】 / 【✅ ...】
            if (trimmed.contains("【🛑") || trimmed.contains("【待った") ||
                trimmed.contains("【⚠️") || trimmed.contains("【慎重") ||
                trimmed.contains("【✅") || trimmed.contains("【シナリオ合致")
            ) {
                DecisionBadgeView(trimmed)
                i++
                continue
            }

            // 見出し・リスト・段落
            when {
                trimmed.startsWith("#### ") -> {
                    Text(
                        text = parseInlineMarkdown(trimmed.removePrefix("#### ")),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                }
                trimmed.startsWith("### ") -> {
                    Text(
                        text = parseInlineMarkdown(trimmed.removePrefix("### ")),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                trimmed.startsWith("## ") -> {
                    Text(
                        text = parseInlineMarkdown(trimmed.removePrefix("## ")),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                    )
                }
                trimmed.startsWith("# ") -> {
                    Text(
                        text = parseInlineMarkdown(trimmed.removePrefix("# ")),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                    )
                }
                line.startsWith("    * ") || line.startsWith("    - ") || line.startsWith("\t* ") -> {
                    BulletItem(
                        content = parseInlineMarkdown(trimmed.removePrefix("* ").removePrefix("- ")),
                        indent = 24.dp,
                        textColor = textColor,
                    )
                }
                line.startsWith("  * ") || line.startsWith("  - ") || line.startsWith(" * ") -> {
                    BulletItem(
                        content = parseInlineMarkdown(trimmed.removePrefix("* ").removePrefix("- ")),
                        indent = 12.dp,
                        textColor = textColor,
                    )
                }
                trimmed.startsWith("* ") || trimmed.startsWith("- ") -> {
                    BulletItem(
                        content = parseInlineMarkdown(trimmed.removePrefix("* ").removePrefix("- ")),
                        indent = 0.dp,
                        textColor = textColor,
                    )
                }
                trimmed.matches(Regex("""^\d+\.\s+.*""")) -> {
                    val number = trimmed.substringBefore(".")
                    val content = trimmed.substringAfter(".").trim()
                    NumberedItem(
                        number = number,
                        content = parseInlineMarkdown(content),
                        textColor = textColor,
                    )
                }
                else -> {
                    Text(
                        text = parseInlineMarkdown(trimmed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                        lineHeight = 22.sp,
                    )
                }
            }
            i++
        }
    }
}

@Composable
private fun MarkdownTableView(
    headers: List<String>,
    rows: List<List<String>>,
    textColor: Color,
) {
    val colCount = headers.size
    val colWidths: List<Dp> = (0 until colCount).map { col ->
        val headerLen = headers.getOrNull(col)?.length ?: 4
        val maxBodyLen = rows.maxOfOrNull { it.getOrNull(col)?.length ?: 0 } ?: 0
        val maxLen = maxOf(headerLen, maxBodyLen)
        when {
            maxLen <= 4 -> 58.dp
            maxLen <= 12 -> 120.dp
            else -> 230.dp
        }
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier
                    .height(IntrinsicSize.Min)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
            ) {
                headers.forEachIndexed { colIdx, cell ->
                    Text(
                        text = parseInlineMarkdown(cell),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        modifier = Modifier
                            .width(colWidths[colIdx])
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                    if (colIdx < colCount - 1) {
                        VerticalDivider(
                            modifier = Modifier.fillMaxHeight(),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }

            rows.forEachIndexed { rowIdx, row ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                Row(
                    modifier = Modifier
                        .height(IntrinsicSize.Min)
                        .background(
                            if (rowIdx % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                            else MaterialTheme.colorScheme.surface,
                        ),
                ) {
                    (0 until colCount).forEach { colIdx ->
                        Text(
                            text = parseInlineMarkdown(row.getOrNull(colIdx).orEmpty()),
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor,
                            lineHeight = 18.sp,
                            modifier = Modifier
                                .width(colWidths[colIdx])
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                        )
                        if (colIdx < colCount - 1) {
                            VerticalDivider(
                                modifier = Modifier.fillMaxHeight(),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 判定バッジ表示（赤: 待った / 黄: 慎重 / 緑: 合致）
 */
@Composable
private fun DecisionBadgeView(text: String) {
    val isStop = text.contains("🛑") || text.contains("待った") || text.contains("見送り")
    val isCaution = text.contains("⚠️") || text.contains("慎重")
    val isGo = text.contains("✅") || text.contains("合致")

    val containerColor = when {
        isStop -> MaterialTheme.colorScheme.errorContainer
        isCaution -> MaterialTheme.colorScheme.tertiaryContainer
        isGo -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }

    val contentColor = when {
        isStop -> MaterialTheme.colorScheme.onErrorContainer
        isCaution -> MaterialTheme.colorScheme.onTertiaryContainer
        isGo -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = parseInlineMarkdown(text),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = contentColor,
            )
        }
    }
}

@Composable
private fun BulletItem(
    content: AnnotatedString,
    indent: Dp,
    textColor: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indent, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            lineHeight = 21.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun NumberedItem(
    number: String,
    content: AnnotatedString,
    textColor: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            lineHeight = 21.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * インラインMarkdown構文（**太字**, `コード` など）を AnnotatedString に変換
 */
fun parseInlineMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                        append(text.substring(i + 2, end))
                        pop()
                        i = end + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }
                text.startsWith("`", i) -> {
                    val end = text.indexOf("`", i + 1)
                    if (end != -1) {
                        pushStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                        append(text.substring(i + 1, end))
                        pop()
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}
