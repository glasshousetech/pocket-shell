package network.ght.pocketshell

/**
 * Keyboards such as SwiftKey re-capitalize a word as they commit it (on space), even when the
 * editor asks for no capitalization: `claude` becomes `Claude`. A command line must keep exactly what
 * was typed, so a keyboard edit may add, remove or replace characters but never change the case
 * of letters the user already entered.
 */
object TypedCase {
    /**
     * Returns [proposed] with its leading run of characters that equal [typed] apart from case
     * put back in [typed]'s case. From the first real difference on, the keyboard's text is kept
     * as it is, so genuine edits (backspace, a dictation revision, new letters) still apply.
     */
    @JvmStatic
    fun keep(typed: CharSequence, proposed: CharSequence): String {
        var t = 0
        var p = 0
        var recased = false
        while (t < typed.length && p < proposed.length) {
            val typedPoint = Character.codePointAt(typed, t)
            val proposedPoint = Character.codePointAt(proposed, p)
            if (typedPoint != proposedPoint) {
                if (!sameLetter(typedPoint, proposedPoint)) break
                recased = true
            }
            t += Character.charCount(typedPoint)
            p += Character.charCount(proposedPoint)
        }
        if (!recased) return proposed.toString()
        return typed.subSequence(0, t).toString() + proposed.subSequence(p, proposed.length)
    }

    private fun sameLetter(a: Int, b: Int) =
        Character.toLowerCase(a) == Character.toLowerCase(b) || Character.toUpperCase(a) == Character.toUpperCase(b)
}
