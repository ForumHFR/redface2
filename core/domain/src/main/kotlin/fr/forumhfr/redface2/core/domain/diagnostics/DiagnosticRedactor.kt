package fr.forumhfr.redface2.core.domain.diagnostics

/** Removes user-identifying values from diagnostics intended to be shared publicly. */
object DiagnosticRedactor {

    fun redact(text: String, maxLength: Int = DEFAULT_MAX_LENGTH): String {
        val redacted = IDENTIFYING_JSON_FIELD_PATTERN.replace(
            LONG_NUMBER_PATTERN.replace(
                ABSOLUTE_PATH_PATTERN.replace(
                    URL_PATTERN.replace(text, REDACTED_URL),
                    REDACTED_PATH,
                ),
                REDACTED_NUMBER,
            ),
            REDACTED_VALUE,
        ).take(maxLength)
        return if (redacted.contains(SCHEME_SEPARATOR)) REDACTED_VALUE else redacted
    }

    private val URL_PATTERN = Regex("""[a-z][a-z0-9+.-]*://[^\s"'<>]+""")
    private val ABSOLUTE_PATH_PATTERN = Regex("""(/storage|/data|/sdcard|/mnt|/proc)[^\s"'<>]*""")
    private val LONG_NUMBER_PATTERN = Regex("""\d{5,}""")
    private val IDENTIFYING_JSON_FIELD_PATTERN = Regex(
        """"(?:picURL|picID|deletehash|link|name|title|description|error)"\s*:\s*""" +
            """(?:"(?:\\.|[^"\\])*"|\{[^{}]*}|\[[^\[\]]*\]|[^,\s}\]]+)""",
        RegexOption.IGNORE_CASE,
    )

    private const val DEFAULT_MAX_LENGTH = 300
    private const val SCHEME_SEPARATOR = "://"
    private const val REDACTED_URL = "<url>"
    private const val REDACTED_PATH = "<path>"
    private const val REDACTED_NUMBER = "<n>"
    private const val REDACTED_VALUE = "<redacted>"
}
