package app.localizeme.sdk

/**
 * Checks on an OTA string before it replaces the shipped one: its format
 * placeholders, and which of its angle brackets are styling.
 *
 * Pure Kotlin so it is unit-tested without Android; [OtaResources] caches the
 * verdicts per resource id.
 */
internal object Formats {
    /**
     * One `java.util.Formatter` specifier: an optional explicit index, flags,
     * width, precision, the `t`/`T` date prefix and the conversion character.
     */
    private val SPECIFIER = Regex("%(\\d+\\$)?([-#+ 0,(<]*)\\d*(\\.\\d+)?([tT])?([a-zA-Z%])")

    /**
     * The inline tags Android's resource compiler turns into styled spans in a
     * `<string>` (`android.content.res.StringBlock`). In a LocalizeMe value
     * these eleven are markup and anything else in angle brackets is text. The
     * LocalizeMe server writes exported strings.xml files by the same rule, so
     * a value looks the same in an exported build and over the air.
     */
    val STYLE_TAGS: Set<String> = setOf(
        "b", "i", "u", "font", "a", "big", "small", "sup", "sub", "strike", "tt",
    )

    // Explicit ASCII classes rather than \s and \w, which mean different
    // things on the JVM the tests run on and on Android's ICU regex engine.
    private const val SPACE = "[ \\t\\n\\r\\f\\u000B]"
    private const val NOT_VALUE = "[^ \\t\\n\\r\\f\\u000B\"'<>=`]"
    private const val ATTRIBUTE =
        "(?:$SPACE+[A-Za-z_:][-A-Za-z0-9_:.]*(?:$SPACE*=$SPACE*(?:\"[^\"]*\"|'[^']*'|$NOT_VALUE+))?)"
    private val TAG_NAMES = STYLE_TAGS.sortedByDescending { it.length }.joinToString("|")

    /** An open tag (group 1, attributes in 2, `/` of a self-closing tag in 3) or a close tag (4). */
    private val TAG = Regex(
        "<($TAG_NAMES)($ATTRIBUTE*)$SPACE*(/)?>|</($TAG_NAMES)$SPACE*>",
        RegexOption.IGNORE_CASE,
    )

    private enum class Kind { TEXT, OPEN, EMPTY, CLOSE }

    private class Token(var kind: Kind, val name: String, val source: String)

    /**
     * [value] split into runs of text and recognised tags. A tag is markup only
     * when it pairs up with its close, properly nested, or closes itself; an
     * unpaired one is text like any other character. Mirrors the server's
     * strings.xml exporter token for token.
     */
    private fun tokens(value: String): List<Token> {
        val out = ArrayList<Token>()
        var position = 0
        for (match in TAG.findAll(value)) {
            if (match.range.first > position) {
                out += Token(Kind.TEXT, "", value.substring(position, match.range.first))
            }
            val close = match.groups[4]
            out += if (close != null) {
                Token(Kind.CLOSE, close.value.lowercase(), match.value)
            } else {
                val kind = if (match.groups[3] != null) Kind.EMPTY else Kind.OPEN
                Token(kind, match.groups[1]!!.value.lowercase(), match.value)
            }
            position = match.range.last + 1
        }
        if (position < value.length) out += Token(Kind.TEXT, "", value.substring(position))

        val open = ArrayList<Int>()
        for ((index, token) in out.withIndex()) {
            when (token.kind) {
                Kind.OPEN -> open += index
                Kind.CLOSE ->
                    if (open.isNotEmpty() && out[open[open.lastIndex]].name == token.name) {
                        open.removeAt(open.lastIndex)
                    } else {
                        token.kind = Kind.TEXT
                    }
                else -> Unit
            }
        }
        for (index in open) out[index].kind = Kind.TEXT
        return out
    }

    /**
     * Whether [ota] can be formatted with the arguments the app passes for
     * [shipped]: the same set of (argument index, conversion) pairs, in any
     * order. `%%` and `%n` take no argument and are ignored; the conversion
     * character is compared case-insensitively, so `%S` and `%s` agree.
     */
    fun compatible(shipped: String, ota: String): Boolean {
        if ('%' !in shipped && '%' !in ota) return true
        return specifiers(shipped) == specifiers(ota)
    }

    private fun specifiers(text: String): List<String> {
        val out = ArrayList<String>()
        var ordinal = 0
        var last = 0
        for (match in SPECIFIER.findAll(text)) {
            val conversion = match.groupValues[5]
            if (conversion == "%" || conversion == "n") continue
            val explicit = match.groupValues[1]
            val index = when {
                explicit.isNotEmpty() -> explicit.dropLast(1).toIntOrNull() ?: continue
                '<' in match.groupValues[2] -> last.takeIf { it > 0 } ?: continue
                else -> ++ordinal
            }
            last = index
            out += "$index:${match.groupValues[4].lowercase()}${conversion.lowercase()}"
        }
        out.sort()
        return out
    }

    /**
     * Whether [value] holds at least one of the [STYLE_TAGS], paired. A bare
     * `<` in prose (`5 < 6`), a tag outside the eleven (`<em>`, `<br>`) or an
     * unpaired `<b>` is not markup.
     */
    fun hasMarkup(value: String): Boolean =
        '<' in value && tokens(value).any { it.kind == Kind.OPEN || it.kind == Kind.EMPTY }

    /**
     * [value] as input for `Html.fromHtml`: the recognised tags passed through
     * as written, every other `&`, `<` and `>` escaped, so `fromHtml` draws the
     * eleven tags and shows anything else, `<em>` or `&amp;` included, exactly
     * as an exported strings.xml would. A newline becomes `<br>`, because
     * `fromHtml` folds a plain one into a space.
     */
    fun toHtml(value: String): String {
        val out = StringBuilder(value.length + 16)
        for (token in tokens(value)) {
            if (token.kind != Kind.TEXT) {
                out.append(token.source)
                continue
            }
            for (char in token.source) {
                when (char) {
                    '&' -> out.append("&amp;")
                    '<' -> out.append("&lt;")
                    '>' -> out.append("&gt;")
                    '\n' -> out.append("<br>")
                    else -> out.append(char)
                }
            }
        }
        return out.toString()
    }
}
