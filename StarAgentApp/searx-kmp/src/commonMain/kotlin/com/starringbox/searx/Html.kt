package com.starringbox.searx

/**
 * 解析引擎返回的 HTML 用的几个小工具。
 *
 * 这个库不想引入 HTML 解析库（那是又一份依赖、又不跨平台），
 * 四个引擎的页面结构都很规整，够用的字符串扫描就写在这。
 * **只追求"对这几个引擎的当前页面有效"**，不追求通用——页面一变就得回来改。
 */

internal const val SEARX_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36"

/** 一个带某个 class 的元素：开标签原文、内部 HTML、在整段 HTML 里的位置。 */
internal data class ClassTag(
    val tag: String,
    val open: String,
    val inner: String,
    val start: Int,
    val end: Int,
)

/**
 * 找出所有 class 里**含有 [marker] 这个词**的元素。
 *
 * 按整个 class 词匹配，不是子串——`marker = "s"` 不应该命中 `class="results-standard"`。
 * 命中后从开标签往后深度扫描，找配对的闭合标签（处理同一个标签的嵌套）。
 */
internal fun classTags(html: String, marker: String): List<ClassTag> {
    val openRe = Regex("""<([a-zA-Z][a-zA-Z0-9]*)\b[^>]*\bclass\s*=\s*"([^"]*)"[^>]*>""")
    val out = ArrayList<ClassTag>()
    var searchFrom = 0
    for (m in openRe.findAll(html)) {
        if (m.range.first < searchFrom) continue
        val classes = m.groupValues[2].split(' ', '\t', '\n', '\r')
        if (marker !in classes) continue
        val tag = m.groupValues[1].lowercase()
        val openEnd = m.range.last
        val closeStart = findClose(html, openEnd + 1, tag)
        if (closeStart < 0) continue
        val closeEnd = html.indexOf('>', closeStart).let { if (it < 0) html.length - 1 else it }
        out += ClassTag(
            tag = tag,
            open = html.substring(m.range.first, openEnd + 1),
            inner = html.substring(openEnd + 1, closeStart),
            start = m.range.first,
            end = closeEnd,
        )
        // 跳到这个元素之后，避免重复匹配它内部的同名 class
        searchFrom = closeEnd + 1
    }
    return out
}

/** 从开标签里取属性值，支持双引号、单引号、无引号三种写法。 */
internal fun attr(tag: String, name: String): String? {
    val re = Regex("""\b$name\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
    val m = re.find(tag) ?: return null
    return m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } }.takeIf { it.isNotBlank() }
}

/** 去掉标签、还原实体、把连续空白压成一个空格。 */
internal fun cleanText(html: String): String =
    decodeEntities(html.replace(Regex("""(?is)<(script|style)\b.*?</\1>"""), " ").replace(Regex("<[^>]+>"), " "))
        .replace(Regex("\\s+"), " ")
        .trim()

/** 从 URL 里取查询参数的值。够用就好，不做完整的 URL 解析。 */
internal fun queryParam(url: String, name: String): String? {
    val query = url.substringAfter('?', missingDelimiterValue = "").substringBefore('#')
    if (query.isBlank()) return null
    return query.split('&').firstNotNullOfOrNull { pair ->
        val key = pair.substringBefore('=')
        if (key == name) pair.substringAfter('=', missingDelimiterValue = "") else null
    }
}

/** 还原 `%XX` 和 `+`。 */
internal fun percentDecode(value: String): String {
    if ('%' !in value && '+' !in value) return value
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        when {
            c == '%' && i + 3 <= value.length -> {
                val v = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) { out.append(v.toChar()); i += 3 } else { out.append(c); i++ }
            }
            c == '+' -> { out.append(' '); i++ }
            else -> { out.append(c); i++ }
        }
    }
    return out.toString()
}

/** 把字符串里的非安全字符按 UTF-8 逐字节百分号编码，用于拼 URL 的路径段或查询串。 */
internal fun urlEncode(value: String): String {
    val hex = "0123456789ABCDEF"
    val out = StringBuilder()
    for (byte in value.encodeToByteArray()) {
        val v = byte.toInt() and 0xFF
        val c = v.toChar()
        val safe = (v < 128) && (c.isLetterOrDigit() || c in "-_.~")
        if (safe) out.append(c)
        else out.append('%').append(hex[v shr 4]).append(hex[v and 15])
    }
    return out.toString()
}

/**
 * 解开跳转链：DuckDuckGo 是 `//duckduckgo.com/l/?uddg=<encoded>`，
 * Bing 是 `/ck/a?…&u=a1<base64url>`。解不开就原样返回。
 */
internal fun unwrapRedirect(href: String): String {
    if (!href.startsWith("http") && !href.startsWith("//")) return href
    val absolute = if (href.startsWith("//")) "https:$href" else href
    queryParam(absolute, "uddg")?.let { target ->
        val decoded = percentDecode(target)
        if (decoded.startsWith("http")) return decoded
    }
    return absolute
}

/** 解开 Bing 的 `/ck/a?…&u=a1<base64url>` 跳转链，拿到真实地址。 */
internal fun unwrapBingUrl(href: String): String {
    if ("bing.com/ck/a" !in href) return href
    val encoded = queryParam(href, "u") ?: return href
    if (!encoded.startsWith("a1")) return href
    val decoded = decodeBase64Url(encoded.substring(2))
    return if (decoded.startsWith("http")) decoded else href
}

private fun decodeBase64Url(value: String): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val cleaned = value.substringBefore('&').trimEnd('=')
    val out = ArrayList<Byte>(cleaned.length * 3 / 4)
    var buffer = 0
    var bits = 0
    for (ch in cleaned) {
        val index = alphabet.indexOf(ch)
        if (index < 0) continue
        buffer = (buffer shl 6) or index
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out += ((buffer shr bits) and 0xFF).toByte()
        }
    }
    return out.toByteArray().decodeToString()
}

private fun decodeEntities(value: String): String {
    if ('&' !in value) return value
    return Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);""").replace(value) { m ->
        when (val token = m.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> " "
            "hellip" -> "…"
            "mdash" -> "—"
            "ndash" -> "–"
            else -> when {
                token.startsWith("#x") || token.startsWith("#X") ->
                    token.drop(2).toIntOrNull(16)?.let { it.toChar().toString() } ?: m.value
                token.startsWith("#") ->
                    token.drop(1).toIntOrNull()?.let { it.toChar().toString() } ?: m.value
                else -> m.value
            }
        }
    }
}

private fun findClose(html: String, from: Int, tag: String): Int {
    var depth = 0
    var i = from
    while (i < html.length) {
        val open = indexOfOpen(html, tag, i)
        val close = indexOfClose(html, tag, i)
        if (close < 0) return -1
        if (open in 0 until close) {
            depth++
            i = open + tag.length + 1
        } else {
            if (depth == 0) return close
            depth--
            i = close + tag.length + 2
        }
    }
    return -1
}

private fun indexOfOpen(html: String, tag: String, from: Int): Int {
    var i = html.indexOf("<$tag", from)
    while (i >= 0) {
        val c = html.getOrNull(i + tag.length + 1)
        if (c == null || c == '>' || c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '/') return i
        i = html.indexOf("<$tag", i + 1)
    }
    return -1
}

private fun indexOfClose(html: String, tag: String, from: Int): Int {
    var i = html.indexOf("</$tag", from)
    while (i >= 0) {
        val c = html.getOrNull(i + tag.length + 2)
        if (c == null || c == '>' || c == ' ' || c == '\t' || c == '\n' || c == '\r') return i
        i = html.indexOf("</$tag", i + 1)
    }
    return -1
}
