package network.ght.pocketshell

/**
 * Pure (Compose-free) rules behind the tab long-press menu: the color
 * palette, how a typed name becomes a tab name, and how much output fits on
 * the clipboard. Kept separate from the UI so it runs in plain JVM tests.
 */

/**
 * One tab color. [id] is persisted in [SessionStore], so ids never change.
 * [argb] is the tone for dark themes; [argbLight] is a deeper tone of the same
 * hue that still reads as a dot and underline on the light themes.
 */
data class TabColor(val id: String, val label: String, val argb: Long, val argbLight: Long) {
    fun argbFor(darkTheme: Boolean): Long = if (darkTheme) argb else argbLight
}

object TabColors {
    // Dark-theme tones sit at similar brightness so no color shouts over the
    // others; light-theme tones are the same hues deepened for white surfaces.
    // The default (null) is the theme's own accent.
    val ALL = listOf(
        TabColor("red", "Red", 0xFFFF6B6B, 0xFFDC2626),
        TabColor("orange", "Orange", 0xFFFF9F43, 0xFFEA580C),
        TabColor("yellow", "Yellow", 0xFFF7D154, 0xFFCA8A04),
        TabColor("green", "Green", 0xFF4ADE80, 0xFF16A34A),
        TabColor("teal", "Teal", 0xFF2DD4BF, 0xFF0D9488),
        TabColor("sky", "Sky", 0xFF38BDF8, 0xFF0284C7),
        TabColor("violet", "Violet", 0xFFA78BFA, 0xFF7C3AED),
        TabColor("pink", "Pink", 0xFFF472B6, 0xFFDB2777),
        TabColor("gray", "Gray", 0xFF94A3B8, 0xFF64748B),
    )

    fun byId(id: String?): TabColor? = if (id == null) null else ALL.firstOrNull { it.id == id }
}

object TabNames {
    const val MAX_LENGTH = 32

    /**
     * Trims, turns line breaks and control characters into spaces, collapses
     * whitespace runs, and caps the name at [MAX_LENGTH] characters without
     * splitting an emoji. Blank means "go back to the automatic title" (null).
     */
    fun normalize(raw: String?): String? {
        if (raw == null) return null
        val flat = buildString(raw.length) {
            raw.forEach { c -> append(if (Character.isISOControl(c) || c == '\u2028' || c == '\u2029') ' ' else c) }
        }
        val cleaned = flat.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return null
        return capCodePoints(cleaned, MAX_LENGTH).trimEnd()
    }

    /** True when [text] is still within the limit, so the editor can stop input at the cap. */
    fun fits(text: String): Boolean = text.codePointCount(0, text.length) <= MAX_LENGTH

    private fun capCodePoints(text: String, max: Int): String {
        val count = text.codePointCount(0, text.length)
        return if (count <= max) text else text.substring(0, text.offsetByCodePoints(0, max))
    }
}

object TabOutput {
    /**
     * Android passes clipboard data over Binder (about 1 MB per transaction),
     * and a full 20,000-line scrollback can exceed that and crash the copy.
     * Keep the most recent output that safely fits, starting on a whole line.
     */
    const val MAX_CLIPBOARD_CHARS = 100_000

    fun forClipboard(transcript: String?, max: Int = MAX_CLIPBOARD_CHARS): String {
        val text = transcript.orEmpty().trimEnd()
        if (text.length <= max) return text
        val tail = text.takeLast(max)
        val newline = tail.indexOf('\n')
        return if (newline in 0 until tail.length - 1) tail.substring(newline + 1) else tail
    }
}
