package network.ght.pocketshell

/** Bulk equivalent of the terminal's unmodified code-point input, without per-character UI callbacks. */
internal object ImeTextCodec {
    fun normalize(text: CharSequence): String {
        val original = text.toString()
        val chars = original.toCharArray()
        var changed = false
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            val replacement = when (c) {
                '\n' -> '\r'
                '\u02DC' -> '~'
                '\u02CB' -> '`'
                '\u02C6' -> '^'
                else -> c
            }
            when {
                c in '\uD800'..'\uDBFF' -> {
                    if (i + 1 < chars.size && chars[i + 1] in '\uDC00'..'\uDFFF') i++
                    else { chars[i] = '\uFFFD'; changed = true }
                }
                c in '\uDC00'..'\uDFFF' -> { chars[i] = '\uFFFD'; changed = true }
                replacement != c -> { chars[i] = replacement; changed = true }
            }
            i++
        }
        return if (changed) String(chars) else original
    }
}
