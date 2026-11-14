package inego.zungjunggame.render.fonts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LineBreakerTest {

    @Test
    fun emptyStringTokenizesToEmptyList() {
        assertEquals(LineBreaker.tokenize(""), emptyList())
    }

    @Test
    fun westernWordsSplitOnWhitespace() {
        val tokens = LineBreaker.tokenize("Hello World! This is a test.")
        assertEquals(
            listOf("Hello", " ", "World!", " ", "This", " ", "is", " ", "a", " ", "test."),
            tokens,
        )
    }

    @Test
    fun cjkCharactersSplitIndividually() {
        val tokens = LineBreaker.tokenize("平和門前清")
        assertEquals(listOf("平", "和", "門", "前", "清"), tokens)
    }

    @Test
    fun cjkClosingPunctuationGluesToPrecedingCharacter() {
        val tokens = LineBreaker.tokenize("與門前清不同，平和允許開吃。")
        assertEquals(
            listOf(
                "與", "門", "前", "清", "不", "同，",
                "平", "和", "允", "許", "開", "吃。",
            ),
            tokens,
        )
    }

    @Test
    fun cjkOpeningPunctuationGluesToFollowingCharacter() {
        val tokens = LineBreaker.tokenize("「平和」與（門前清）")
        assertEquals(
            listOf(
                "「平", "和」", "與", "（門", "前", "清）",
            ),
            tokens,
        )
    }

    @Test
    fun multiplePunctuationGluesTogether() {
        val tokens = LineBreaker.tokenize("真的嗎？！")
        assertEquals(listOf("真", "的", "嗎？！"), tokens)
    }

    @Test
    fun japaneseSmallKanaAndProlongedSoundGluesToPreceding() {
        val tokens = LineBreaker.tokenize("ラーメンちょっと")
        assertEquals(
            listOf(
                "ラー", "メ", "ン", "ちょっ", "と",
            ),
            tokens,
        )
    }

    @Test
    fun mixedWesternAndCjkWithNumbers() {
        val tokens = LineBreaker.tokenize("獲得5分 100 pts")
        assertEquals(
            listOf("獲", "得", "5", "分", " ", "100", " ", "pts"),
            tokens,
        )
    }

    @Test
    fun wrapInsertsNewlinesAtBoundaries() {
        // Each character/token measured by length * 10
        val text = "與門前清不同，平和允許開吃。"
        // 7 characters per line * 10 = 70
        val wrapped = LineBreaker.wrap(text, maxWidth = 70f) { token ->
            token.length * 10f
        }
        val lines = wrapped.split('\n')
        // Line 1: 與 門 前 清 不 同， (6 tokens / 7 chars, width = 70)
        // Line 2: 平 和 允 許 開 吃。 (6 tokens / 7 chars, width = 70)
        assertEquals(listOf("與門前清不同，", "平和允許開吃。"), lines)
        for (line in lines) {
            assertFalse(LineBreaker.isNoLineStart(line.first()))
            assertFalse(LineBreaker.isNoLineEnd(line.last()))
        }
    }

    @Test
    fun wrapPreservesExistingHardNewlines() {
        val text = "第一行\n第二行"
        val wrapped = LineBreaker.wrap(text, maxWidth = 1000f) { 10f }
        assertEquals("第一行\n第二行", wrapped)
    }
}
