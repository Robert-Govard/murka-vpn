package org.olcbox.app.util

/**
 * Parses a raw string to extract a leading emoji and the remaining text.
 * If no leading emoji is found, returns a default emoji and the original text.
 */
fun parseEmojiAndName(rawName: String, defaultEmoji: String = ""): Pair<String, String> {
    if (rawName.isBlank()) return defaultEmoji to ""

    val it = rawName.iterator()
    if (!it.hasNext()) return defaultEmoji to rawName

    val firstChar = it.next()

    // Check for emoji ranges or surrogate pairs
    return if (firstChar.isHighSurrogate() || firstChar.code in 0x2000..0x32FF || firstChar.code > 0x1F000) {
        var emoji = if (firstChar.isHighSurrogate() && it.hasNext()) {
            "$firstChar${it.next()}"
        } else {
            firstChar.toString()
        }
        // A flag is two regional indicator symbols (e.g. 🇳 + 🇱 = 🇳🇱).
        if (emoji.isRegionalIndicator()) {
            val next = rawName.substring(emoji.length).take(2)
            if (next.isRegionalIndicator()) emoji += next
        }
        emoji to rawName.substring(emoji.length).trim()
    } else {
        defaultEmoji to rawName
    }
}

private fun String.isRegionalIndicator(): Boolean {
    if (length != 2 || !this[0].isHighSurrogate() || !this[1].isLowSurrogate()) return false
    val codePoint = 0x10000 + ((this[0].code - 0xD800) shl 10) + (this[1].code - 0xDC00)
    return codePoint in 0x1F1E6..0x1F1FF
}
