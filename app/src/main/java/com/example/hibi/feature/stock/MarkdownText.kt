package com.example.hibi.feature.stock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * GeminiのMarkdown形式の回答をMaterial 3スタイルで美しくレンダリングするコンポーネント
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val lines = markdown.lines()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        var inCodeBlock = false
        val codeBlockLines = mutableListOf<String>()

        lines.forEach { line ->
            val trimmed = line.trim()

            // コードブロック ``` の処理
            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    // コードブロック終了
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
                    codeBlockLines.clear()
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                }
                return@forEach
            }

            if (inCodeBlock) {
                codeBlockLines.add(line)
                return@forEach
            }

            // 空行
            if (trimmed.isEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                return@forEach
            }

            // 水平線 (--- or ***)
            if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                )
                return@forEach
            }

            // 門番判定バッジ 【🛑 ...】 / 【⚠️ ...】 / 【✅ ...】
            if (trimmed.contains("【🛑") || trimmed.contains("【待った") ||
                trimmed.contains("【⚠️") || trimmed.contains("【慎重") ||
                trimmed.contains("【✅") || trimmed.contains("【シナリオ合致")
            ) {
                DecisionBadgeView(trimmed)
                return@forEach
            }

            // 見出し (#, ##, ###)
            when {
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
                // リスト項目 (*, -, 数字リスト)
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
                // 番号付きリスト (1. 2. など)
                trimmed.matches(Regex("""^\d+\.\s+.*""")) -> {
                    val number = trimmed.substringBefore(".")
                    val content = trimmed.substringAfter(".").trim()
                    NumberedItem(
                        number = number,
                        content = parseInlineMarkdown(content),
                        textColor = textColor,
                    )
                }
                // 通常の本文段落
                else -> {
                    Text(
                        text = parseInlineMarkdown(trimmed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                        lineHeight = 22.sp,
                    )
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
    indent: androidx.compose.ui.unit.Dp,
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
                // **太字**
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
                // `インラインコード`
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
                // 通常の文字
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}
