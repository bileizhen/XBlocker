package io.github.xblocker.core

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.*

class RuleEngineTest {
    @Test fun `parses upstream categories without losing hashtags`() {
        val rules = RuleParser.parse("\uFEFF# [常规屏蔽词]\n垃圾\n\n#\n#hashtag\n# 用户名\n测试\n")
        assertEquals(listOf("垃圾", "#hashtag", "测试"), rules.map { it.text })
        assertEquals("用户名", rules.last().category)
    }
    @Test fun `ignorable characters and case cannot evade literals`() {
        assertNotNull(engine("VPN推广").match(reply("v\u200Bp\uFE0Fn推广")))
    }
    @Test fun `literal regex punctuation stays literal`() {
        assertNull(engine("a.b").match(reply("axb")))
        assertNotNull(engine("a.b").match(reply("a.b")))
    }
    @Test fun `regex flags preserve case and multiline semantics`() {
        assertNull(engine("/ABC/").match(reply("abc")))
        assertNotNull(engine("/^ABC/im").match(reply("hello\nabc")))
    }
    @Test fun `whitelist precedes text and name rules`() {
        val engine = RuleEngine(FilterSettings(whitelist = setOf("@Alice")), "垃圾")
        assertNull(engine.match(Tweet("1", "垃圾", "垃圾", "ALICE", true)))
    }
    @Test fun `reply scope excludes top-level tweets`() {
        assertNull(engine("spam").match(Tweet("1", "spam")))
        assertNotNull(RuleEngine(FilterSettings(onlyReplies = false), "spam").match(Tweet("1", "spam")))
    }
    @Test fun `disabled cloud still keeps custom rules`() {
        val engine = RuleEngine(FilterSettings(cloudEnabled = false, customRules = "custom"), "cloud")
        assertNull(engine.match(reply("cloud")))
        assertNotNull(engine.match(reply("custom")))
    }
    @Test fun `category toggles apply only to cloud rules`() {
        val engine = RuleEngine(FilterSettings(disabledCategories = setOf("用户名"), customRules = "custom"), "# [用户名]\ncloud")
        assertNull(engine.match(reply("cloud")))
        assertNotNull(engine.match(reply("custom")))
    }
    @Test fun `name matching can be disabled`() {
        val tweet = Tweet("1", "normal", "spam", "alice", true)
        assertEquals("昵称", engine("spam").match(tweet)?.reason)
        assertNull(RuleEngine(FilterSettings(checkNames = false), "spam").match(tweet))
    }
    @Test fun `master switch disables all matching`() {
        assertNull(RuleEngine(FilterSettings(enabled = false), "spam").match(reply("spam")))
    }
    @Test fun `unsupported rules reported and valid rules survive`() {
        val engine = engine("/[a-/\n/\\p{Emoji}/u\nvalid")
        assertEquals(2, engine.rejected.size)
        assertNotNull(engine.match(reply("valid")))
    }
    @Test fun `pathological regular expression has bounded runtime`() {
        val start = System.nanoTime()
        assertNull(engine("/(a+)+b/").match(reply("a".repeat(12000))))
        assertTrue(System.nanoTime() - start < 1_000_000_000L)
    }
    @Test fun `configuration survives JSON roundtrip`() {
        val settings = FilterSettings(enabled = false, cloudEnabled = false, customRules = "line\n/abc/i", whitelist = setOf("a", "b"), disabledCategories = emptySet())
        assertEquals(settings, ConfigCodec.decode(ConfigCodec.encode(settings)))
    }
    @Test fun `bundled upstream snapshot is usable`() {
        val source = File("../app/src/main/assets/keywords.txt").readText()
        val engine = engine(source)
        assertTrue(engine.count > 500)
        assertTrue(engine.categories.keys.containsAll(listOf("常规屏蔽词", "用户名")))
        assertTrue(engine.rejected.size <= 2, engine.rejected.toString())
    }
    private fun engine(text: String) = RuleEngine(FilterSettings(), text)
    private fun reply(text: String) = Tweet("1", text, isReply = true)
}
