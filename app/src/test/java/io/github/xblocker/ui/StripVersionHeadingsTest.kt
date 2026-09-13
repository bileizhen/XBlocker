package io.github.xblocker.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dialog title already shows the version; the notes must not repeat it as a heading. */
class StripVersionHeadingsTest {
    private val bilingual = """
        ## 0.3.1

        ### 新功能

        - 屏蔽时间线转帖。

        ---

        ## 0.3.1

        ### New features

        - Optional timeline repost blocking.
    """.trimIndent()

    @Test fun versionHeadingsDropFromBothLanguages() {
        val out = stripVersionHeadings("0.3.1", bilingual)
        assertFalse(out.contains("## 0.3.1"))
        assertFalse(out.contains("0.3.1"))
    }

    @Test fun otherHeadingsAndContentStay() {
        val out = stripVersionHeadings("0.3.1", bilingual)
        assertTrue(out.startsWith("### 新功能"))
        assertTrue(out.contains("### New features"))
        assertTrue(out.contains("屏蔽时间线转帖"))
    }

    @Test fun blankVersionKeepsNotesUntouched() {
        assertEquals(bilingual, stripVersionHeadings("", bilingual))
    }

    @Test fun partialVersionMatchesStayLiteral() {
        val out = stripVersionHeadings("0.3.1", "## 0.3.1\n内容\n## 0.3.10 变更")
        assertTrue(out.contains("## 0.3.10 变更"))
        assertTrue(out.contains("内容"))
    }
}
