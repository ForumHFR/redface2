package fr.forumhfr.redface2.core.domain.diagnostics

/** Filters the free text in a crash report before it can be retained or shared. */
object CrashReportRedactor {
    fun redact(details: String): String = details.lineSequence().joinToString("\n", transform = ::redactLine)

    private fun redactLine(line: String): String {
        FRAME.matchEntire(line)?.let { frame ->
            val (method, source) = frame.destructured
            // Source line numbers are structural, even when they exceed the generic digit limit.
            val location = SOURCE_LINE.matchEntire(source)
            val safeSource = if (location == null) {
                redactField(source)
            } else {
                val (file, number) = location.destructured
                redactField(file) + number
            }
            return "\tat $method($safeSource)"
        }
        val exception = EXCEPTION_MESSAGE.matchEntire(line)
        return if (exception == null || PRIVATE_TEXT.containsMatchIn(exception.groupValues[1])) {
            redactField(line)
        } else {
            val (type, message) = exception.destructured
            type + redactField(message)
        }
    }

    private fun redactField(field: String): String {
        val redacted = DiagnosticRedactor.redact(field, maxLength = Int.MAX_VALUE)
        // A header or assignment can contain multiple values, including quoted or folded text.
        return if (PRIVATE_TEXT.containsMatchIn(redacted)) "<redacted>" else redacted
    }

    private const val IDENTIFIER = """[\p{L}\p{N}_$<>-]+"""
    private val FRAME = Regex("""\tat ($IDENTIFIER(?:\.$IDENTIFIER)+)\((.*)\)""")
    // Include redaction markers so rereading a retained filename preserves its line number.
    private val SOURCE_LINE = Regex("""([\p{L}\p{N}_.$<>-]+)(:-?\d+)""")
    private val EXCEPTION_MESSAGE = Regex("""((?:Caused by: )?$IDENTIFIER(?:\.$IDENTIFIER)*: )(.*)""")
    private val PRIVATE_TEXT = Regex(
        """\b(?:set-cookie|cookie|hash_check|md_user|md_id|md_passs)\b|""" +
            """\b(?:pseudo|password)\b["']?\s*[:=]|\?\S|(?:^|[\s("'=])//|""" +
            """\b(?:https?|file|content|mailto|tel|data):\S""",
        RegexOption.IGNORE_CASE,
    )
}
