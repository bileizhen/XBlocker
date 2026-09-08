package io.github.xblocker.ui

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Test

/** The update dialog renders release notes through inlineMarkdown; keep its subset honest. */
class InlineMarkdownTest {
    private val linkStyle = SpanStyle(textDecoration = TextDecoration.Underline)
    private fun render(text: String) = inlineMarkdown(text, linkStyle) {}

    @Test fun plainTextPassesThroughUntouched() {
        assertEquals("普通文本保持原样", render("普通文本保持原样").text)
        assertEquals("a * b 且 ** 未闭合", render("a * b 且 ** 未闭合").text)
    }

    @Test fun boldSpansCoverOnlyTheMarkedRange() {
        val out = render("前缀**加粗**后缀")
        assertEquals("前缀加粗后缀", out.text)
        val style = out.spanStyles.single()
        assertEquals(2..3, style.start..style.end - 1)
        assertEquals(FontWeight.SemiBold, style.item.fontWeight)
    }

    @Test fun linksKeepTheirLabelAndCarryTheTarget() {
        val out = render("见 [Telegram 频道](https://t.me/bileizhen_XBlocker) 获取帮助")
        assertEquals("见 Telegram 频道 获取帮助", out.text)
        val style = out.spanStyles.single()
        assertEquals(2..12, style.start..style.end - 1)
        assertEquals(linkStyle, style.item)
        val link = out.getLinkAnnotations(0, out.length).single().item as LinkAnnotation.Clickable
        assertEquals("https://t.me/bileizhen_XBlocker", link.tag)
    }

    @Test fun boldMarkersInsideALinkLabelStayLiteral() {
        val out = render("[**重要**说明](https://example.com/a)")
        assertEquals("**重要**说明", out.text)
        val style = out.spanStyles.single()
        assertEquals(linkStyle, style.item)
        val link = out.getLinkAnnotations(0, out.length).single().item as LinkAnnotation.Clickable
        assertEquals("https://example.com/a", link.tag)
    }
}
