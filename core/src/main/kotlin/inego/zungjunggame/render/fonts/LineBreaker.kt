package inego.zungjunggame.render.fonts

/**
 * Text segmentation and line breaking following Unicode Standard Annex #14 (UAX #14)
 * with CJK Kinsoku Shori (禁則処理) rules.
 *
 * Breaks are allowed:
 * - Between whitespace-separated Western words.
 * - Between CJK ideographs, Hiragana, Katakana, and Hangul syllables.
 *
 * Breaks are prohibited:
 * - Before closing punctuation (fullwidth commas, periods, brackets, small kana).
 * - After opening punctuation (fullwidth brackets, quotes, currency symbols).
 * - Inside numbers and Western words.
 *
 * Zero-dependency and pure Kotlin: safe across desktop JVM and Web (TeaVM/WebGL).
 */
object LineBreaker {

    /** True when [c] must not appear at the start of a wrapped line. */
    fun isNoLineStart(c: Char): Boolean = when (c) {
        // CJK and fullwidth closing punctuation
        '）', '〕', '】', '〉', '》', '」', '』', '｝', '］',
        '、', '，', '。', '．', '！', '？', '：', '；',
        '…', '‥', '—', '–', '～', '·', '・',
        '”', '’',
        // ASCII closing punctuation
        ')', ']', '}', '>', ',', '.', '!', '?', ':', ';', '%',
        // Japanese prolonged sound mark and small kana
        'ー',
        'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ', 'っ', 'ゃ', 'ゅ', 'ょ', 'ゎ', 'ゕ', 'ゖ',
        'ァ', 'ィ', 'ゥ', 'ェ', 'ォ', 'ッ', 'ャ', 'ュ', 'ョ', 'ヮ', 'ヵ', 'ヶ',
        'ㇰ', 'ㇱ', 'ㇲ', 'ㇳ', 'ㇴ', 'ㇵ', 'ㇶ', 'ㇷ', 'ㇸ', 'ㇹ', 'ㇺ', 'ㇻ', 'ㇼ', 'ㇽ', 'ㇾ', 'ㇿ' -> true
        else -> false
    }

    /** True when [c] must not appear at the end of a line. */
    fun isNoLineEnd(c: Char): Boolean = when (c) {
        // CJK and fullwidth opening punctuation
        '（', '〔', '【', '〈', '《', '「', '『', '｛', '［',
        '“', '‘',
        // ASCII opening punctuation
        '(', '[', '{', '<',
        // Currency and prefix symbols
        '$', '¥', '£', '€', '￥', '₩' -> true
        else -> false
    }

    /**
     * True for CJK ideographs, kana, hangul, and ideographic symbols where line breaks
     * are naturally permitted between individual characters.
     */
    fun isCjk(c: Char): Boolean = when (c) {
        in '\u4E00'..'\u9FFF', // CJK Unified Ideographs
        in '\u3400'..'\u4DBF', // CJK Extension A
        in '\uF900'..'\uFAFF', // CJK Compatibility Ideographs
        in '\u3040'..'\u309F', // Hiragana
        in '\u30A0'..'\u30FF', // Katakana
        in '\u31F0'..'\u31FF', // Katakana Phonetic Extensions
        in '\uAC00'..'\uD7AF', // Hangul Syllables
        in '\u1100'..'\u11FF', // Hangul Jamo
        in '\u3130'..'\u318F', // Hangul Compatibility Jamo
        in '\u3100'..'\u312F', // Bopomofo
        in '\u31A0'..'\u31BF', // Bopomofo Extended
        in '\u2E80'..'\u2EFF', // CJK Radicals Supplement
        in '\u2F00'..'\u2FDF', // Kangxi Radicals
        '\u3005', '\u3007' -> true // 々, 〇
        in '\u3021'..'\u3029' -> true // Hangzhou numerals
        else -> false
    }

    /**
     * Splits [text] into unbreakable tokens.
     * Line breaks are valid between tokens, but prohibited within a token.
     * Whitespace runs are collapsed into single `" "` tokens.
     */
    fun tokenize(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val tokens = ArrayList<String>()
        var i = 0
        val len = text.length
        while (i < len) {
            val c = text[i]
            if (c.isWhitespace()) {
                while (i < len && text[i].isWhitespace()) i++
                tokens.add(" ")
                continue
            }

            val start = i
            // 1. Consume any leading opening punctuation (glues to the following token)
            while (i < len && isNoLineEnd(text[i])) {
                i++
            }

            if (i < len) {
                val curr = text[i]
                if (isCjk(curr)) {
                    // Consume single CJK character (or surrogate pair)
                    if (curr.isHighSurrogate() && i + 1 < len && text[i + 1].isLowSurrogate()) {
                        i += 2
                    } else {
                        i++
                    }
                } else if (isNoLineStart(curr)) {
                    // Stray closing punctuation without a preceding character: consume it
                    i++
                } else {
                    // Alphanumeric / Western word run
                    while (i < len) {
                        val ch = text[i]
                        if (ch.isWhitespace() || isNoLineEnd(ch) || isNoLineStart(ch) || isCjk(ch)) {
                            break
                        }
                        if (ch.isHighSurrogate() && i + 1 < len && text[i + 1].isLowSurrogate()) {
                            i += 2
                        } else {
                            i++
                            // Allow soft break after hyphen in hyphenated word when followed by text
                            if (ch == '-' && i < len && !text[i].isWhitespace() && !isNoLineStart(text[i])) {
                                break
                            }
                        }
                    }
                }
            }

            // 2. Consume any trailing closing punctuation (glues to the preceding token)
            while (i < len && isNoLineStart(text[i])) {
                i++
            }

            if (i > start) {
                tokens.add(text.substring(start, i))
            } else {
                tokens.add(text.substring(i, i + 1))
                i++
            }
        }
        return tokens
    }

    /**
     * Soft-wraps [text] at [maxWidth] by inserting newline (`\n`) characters at valid
     * break opportunities according to [measure]. Existing hard newlines are preserved.
     */
    fun wrap(
        text: String,
        maxWidth: Float,
        measure: (String) -> Float,
    ): String {
        if (text.isEmpty() || maxWidth <= 0f) return text
        val rawLines = text.split('\n')
        val sb = StringBuilder(text.length + 16)
        for ((lineIdx, rawLine) in rawLines.withIndex()) {
            if (lineIdx > 0) sb.append('\n')
            if (rawLine.isEmpty()) continue

            val tokens = tokenize(rawLine)
            var currentLineWidth = 0f
            var lineHasContent = false

            for (token in tokens) {
                val isSpace = token == " " || token.isBlank()
                val w = measure(token)

                if (lineHasContent && currentLineWidth + w > maxWidth && !isSpace) {
                    sb.append('\n')
                    currentLineWidth = 0f
                    lineHasContent = false
                }

                if (isSpace && !lineHasContent) {
                    // Discard leading space on wrapped line
                    continue
                }

                sb.append(token)
                currentLineWidth += w
                lineHasContent = true
            }
        }
        return sb.toString()
    }
}
