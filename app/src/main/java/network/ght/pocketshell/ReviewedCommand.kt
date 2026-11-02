package network.ght.pocketshell

/** Route reviewed AI text to its originating live tab, with atomic input acceptance. */
object ReviewedCommand {
    fun insert(target: TermSession, originId: Int?, command: String, run: Boolean): Result<Unit> = runCatching {
        check(target.id == originId && target.alive.value && target.session.emulator != null) {
            "Select the original live tab before inserting this suggestion."
        }
        val text = command.trim()
        require(text.isNotBlank()) { "There is no command to insert." }
        require(text.none { Character.isISOControl(it) || it == '\u2028' || it == '\u2029' }) {
            "This suggestion contains multiple lines or control characters. Ask for a single-line command before inserting or running it. The original suggestion was kept."
        }
        target.session.beginInputBatch()
        val accepted: Boolean
        try {
            target.session.tryPaste(text)
            // Never change shell semantics by flattening multiline provider output.
            // Insert alone never sends Enter.
            if (run) target.session.write("\r")
        } finally { accepted = target.session.endInputBatch() }
        check(accepted) { "The terminal could not accept this command. Your suggestion has been kept." }
    }
}
