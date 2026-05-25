package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

sealed class MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
    data class BulletItem(val text: String) : MarkdownBlock()
    data class CodeBlock(val code: String) : MarkdownBlock()
    data class Image(val alt: String, val url: String) : MarkdownBlock()
    object HorizontalRule : MarkdownBlock()
}

fun parseMarkdown(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = markdown.replace("\r\n", "\n").split("\n")
    var inCodeBlock = false
    val currentCodeLines = mutableListOf<String>()

    for (line in lines) {
        if (line.trim().startsWith("```")) {
            if (inCodeBlock) {
                blocks.add(MarkdownBlock.CodeBlock(currentCodeLines.joinToString("\n")))
                currentCodeLines.clear()
                inCodeBlock = false
            } else {
                inCodeBlock = true
            }
            continue
        }

        if (inCodeBlock) {
            currentCodeLines.add(line)
            continue
        }

        val trimmed = line.trim()

        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            blocks.add(MarkdownBlock.HorizontalRule)
            continue
        }

        if (trimmed.startsWith("#")) {
            val level = trimmed.takeWhile { it == '#' }.length
            if (level in 1..6 && trimmed.getOrNull(level) == ' ') {
                val headingText = trimmed.substring(level).trim()
                blocks.add(MarkdownBlock.Heading(level, headingText))
                continue
            }
        }

        val imageRegex = """^!\[(.*?)\]\((.*?)\)$""".toRegex()
        val match = imageRegex.matchEntire(trimmed)
        if (match != null) {
            val alt = match.groupValues[1]
            val url = match.groupValues[2]
            blocks.add(MarkdownBlock.Image(alt, url))
            continue
        }

        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
            blocks.add(MarkdownBlock.BulletItem(trimmed.substring(2)))
            continue
        }

        val textWithImages = splitTextAndImages(line)
        blocks.addAll(textWithImages)
    }

    if (inCodeBlock && currentCodeLines.isNotEmpty()) {
        blocks.add(MarkdownBlock.CodeBlock(currentCodeLines.joinToString("\n")))
    }

    return blocks
}

fun splitTextAndImages(line: String): List<MarkdownBlock> {
    val result = mutableListOf<MarkdownBlock>()
    val regex = """!\[(.*?)\]\((.*?)\)""".toRegex()
    var lastIndex = 0
    val matches = regex.findAll(line)
    
    for (match in matches) {
        val range = match.range
        if (range.start > lastIndex) {
            val beforeText = line.substring(lastIndex, range.start)
            if (beforeText.trim().isNotEmpty() || beforeText.contains("\n")) {
                result.add(MarkdownBlock.Paragraph(beforeText))
            }
        }
        val alt = match.groupValues[1]
        val url = match.groupValues[2]
        result.add(MarkdownBlock.Image(alt, url))
        lastIndex = range.endInclusive + 1
    }
    
    if (lastIndex < line.length) {
        val remainingText = line.substring(lastIndex)
        if (remainingText.trim().isNotEmpty() || remainingText.contains("\n")) {
            result.add(MarkdownBlock.Paragraph(remainingText))
        }
    }
    
    if (result.isEmpty()) {
        result.add(MarkdownBlock.Paragraph(line))
    }
    return result
}

fun parseInlineMarkdown(text: String, primaryColor: Color): AnnotatedString {
    return buildAnnotatedString {
        val regex = """(\*\*|__)(.+?)\1|(\*|_)(.+?)\3|`(.+?)`|\[(.+?)\]\((.+?)\)""".toRegex()
        var lastInsertedIndex = 0
        
        regex.findAll(text).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            
            if (start > lastInsertedIndex) {
                append(text.substring(lastInsertedIndex, start))
            }
            
            val boldText = match.groups[2]?.value
            val italicText = match.groups[4]?.value
            val codeText = match.groups[5]?.value
            val linkText = match.groups[6]?.value
            val linkUrl = match.groups[7]?.value
            
            when {
                boldText != null -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(boldText)
                    pop()
                }
                italicText != null -> {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(italicText)
                    pop()
                }
                codeText != null -> {
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color.LightGray.copy(alpha = 0.3f),
                            color = Color(0xFFC7254E)
                        )
                    )
                    append(codeText)
                    pop()
                }
                linkText != null && linkUrl != null -> {
                    pushStyle(
                        SpanStyle(
                            color = primaryColor,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    pushStringAnnotation(tag = "URL", annotation = linkUrl)
                    append(linkText)
                    pop()
                    pop()
                }
                else -> {
                    append(match.value)
                }
            }
            
            lastInsertedIndex = end
        }
        
        if (lastInsertedIndex < text.length) {
            append(text.substring(lastInsertedIndex))
        }
    }
}

@Composable
fun MarkdownRenderer(
    markdown: String,
    modifier: Modifier = Modifier,
    primaryColor: Color = Color(0xFF3B82F6)
) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> {
                    val fontSize = when (block.level) {
                        1 -> 20.sp
                        2 -> 18.sp
                        3 -> 16.sp
                        else -> 14.sp
                    }
                    val fontWeight = FontWeight.Bold
                    Text(
                        text = parseInlineMarkdown(block.text, primaryColor),
                        fontSize = fontSize,
                        fontWeight = fontWeight,
                        color = Color(0xFF1E293B),
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }
                is MarkdownBlock.Paragraph -> {
                    val annotatedString = parseInlineMarkdown(block.text, primaryColor)
                    ClickableText(
                        text = annotatedString,
                        style = TextStyle(
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = Color(0xFF334155)
                        ),
                        onClick = { offset ->
                            annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                                .firstOrNull()?.let { annotation ->
                                    try {
                                        uriHandler.openUri(annotation.item)
                                    } catch (e: Exception) {
                                        // Ignore
                                    }
                                }
                        }
                    )
                }
                is MarkdownBlock.BulletItem -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "•",
                            fontSize = 14.sp,
                            color = primaryColor,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        val annotatedString = parseInlineMarkdown(block.text, primaryColor)
                        ClickableText(
                            text = annotatedString,
                            modifier = Modifier.weight(1f),
                            style = TextStyle(
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = Color(0xFF334155)
                            ),
                            onClick = { offset ->
                                annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                                    .firstOrNull()?.let { annotation ->
                                        try {
                                            uriHandler.openUri(annotation.item)
                                        } catch (e: Exception) {
                                            // Ignore
                                        }
                                    }
                            }
                        )
                    }
                }
                is MarkdownBlock.CodeBlock -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFF1F5F9))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = block.code,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = Color(0xFF0F172A),
                            lineHeight = 16.sp,
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        )
                    }
                }
                is MarkdownBlock.HorizontalRule -> {
                    HorizontalDivider(
                        color = Color(0xFFE2E8F0),
                        thickness = 1.dp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                is MarkdownBlock.Image -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(block.url)
                                .crossfade(true)
                                .build(),
                            contentDescription = block.alt.takeIf { it.isNotEmpty() } ?: "Imagen de changelog",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        }
    }
}
