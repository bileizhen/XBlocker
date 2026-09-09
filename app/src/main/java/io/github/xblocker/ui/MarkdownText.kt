package io.github.xblocker.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Ordered-list items: the number is kept so the rendering stays readable when copied. */
private val orderedItem = Regex("""^(\d{1,3})[.)]\s+(.+)$""")

/**
 * Renders the markdown subset that release notes actually use: ##/### headings,
 * "- " bullets, "1. " ordered items, --- rules, **bold**, `code` spans and
 * [label](url) links. Anything else stays plain text, so unknown syntax degrades
 * to readable content instead of breaking layout.
 */
@Composable
internal fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val linkStyle = SpanStyle(color = MiuixTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        markdown.replace("\r\n", "\n").lines().forEach { raw ->
            val line = raw.trim()
            val ordered = orderedItem.find(line)
            when {
                line.isEmpty() -> Unit
                line.length >= 3 && line.all { it == '-' || it == '*' || it == '_' } ->
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                line.startsWith("### ") -> Text(inlineMarkdown(line.removePrefix("### "), linkStyle) { openLink(context, it) },
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                line.startsWith("## ") -> Text(inlineMarkdown(line.removePrefix("## "), linkStyle) { openLink(context, it) },
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                line.startsWith("- ") || line.startsWith("* ") -> Row {
                    Text("•  ")
                    Text(inlineMarkdown(line.substring(2), linkStyle) { openLink(context, it) }, modifier = Modifier.weight(1f))
                }
                ordered != null -> Row {
                    Text("${ordered.groupValues[1]}.  ")
                    Text(inlineMarkdown(ordered.groupValues[2], linkStyle) { openLink(context, it) }, modifier = Modifier.weight(1f))
                }
                else -> Text(inlineMarkdown(line, linkStyle) { openLink(context, it) })
            }
        }
    }
}

private val inlineSyntax = Regex("""\*\*(.+?)\*\*|\[([^]]+)]\(([^)]+)\)|`([^`]+)`""")

/** Inline parser kept free of composition so the supported subset can be unit tested. */
internal fun inlineMarkdown(text: String, linkStyle: SpanStyle, onLinkClick: (String) -> Unit): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var cursor = 0
    while (cursor < text.length) {
        val match = inlineSyntax.find(text, cursor) ?: run { builder.append(text.substring(cursor)); return builder.toAnnotatedString() }
        builder.append(text.substring(cursor, match.range.first))
        val bold = match.groups[1]
        val code = match.groups[4]
        when {
            bold != null -> {
                val start = builder.length
                builder.append(bold.value)
                builder.addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), start, builder.length)
            }
            code != null -> {
                val start = builder.length
                builder.append(code.value)
                builder.addStyle(SpanStyle(fontFamily = FontFamily.Monospace), start, builder.length)
            }
            else -> {
                val start = builder.length
                builder.append(match.groupValues[2])
                builder.addStyle(linkStyle, start, builder.length)
                val url = match.groupValues[3]
                builder.addLink(LinkAnnotation.Clickable(tag = url, styles = TextLinkStyles(linkStyle),
                    linkInteractionListener = { onLinkClick(url) }), start, builder.length)
            }
        }
        cursor = match.range.last + 1
    }
    return builder.toAnnotatedString()
}

private fun openLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
