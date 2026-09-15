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

    /**
     * Reduces an Android picker URI to a redacted source. File URIs never expose their local name;
     * other authorities are retained only when they look like Android content-provider names. The
     * path, query and fragment are always discarded.
     */
    fun redactUriSource(uri: String): String {
        val separatorIndex = uri.indexOf(SCHEME_SEPARATOR)
        if (separatorIndex <= 0) return REDACTED_VALUE
        val scheme = uri.substring(0, separatorIndex)
        val normalizedScheme = scheme.lowercase()
        val authority = uri
            .substring(separatorIndex + SCHEME_SEPARATOR.length)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        val redactedAuthority = when {
            normalizedScheme == FILE_SCHEME -> REDACTED_LOCAL_AUTHORITY
            isProviderAuthority(authority) -> authority
            else -> REDACTED_AUTHORITY
        }
        val schemeIsSafe = URI_SCHEME_PATTERN.matches(scheme) && scheme.length <= MAX_URI_COMPONENT_LENGTH
        return if (schemeIsSafe) {
            "$normalizedScheme$SCHEME_SEPARATOR$redactedAuthority"
        } else {
            REDACTED_VALUE
        }
    }

    private fun isProviderAuthority(authority: String): Boolean {
        val formatIsSafe = URI_AUTHORITY_PATTERN.matches(authority) && authority.length <= MAX_URI_COMPONENT_LENGTH
        if (!formatIsSafe) return false
        val looksLikeProvider = authority.contains('.') || authority.lowercase() in PROVIDER_AUTHORITY_ALLOWLIST
        return looksLikeProvider && !LONG_NUMBER_PATTERN.containsMatchIn(authority)
    }

    private val URL_PATTERN = Regex("""[a-z][a-z0-9+.-]*://[^\s"'<>]+""")
    private val ABSOLUTE_PATH_PATTERN = Regex("""(/storage|/data|/sdcard|/mnt|/proc)[^\s"'<>]*""")
    private val LONG_NUMBER_PATTERN = Regex("""\d{5,}""")
    private val IDENTIFYING_JSON_FIELD_PATTERN = Regex(
        """"(?:picURL|picID|deletehash|link|name|title|description|error)"\s*:\s*""" +
            """(?:"(?:\\.|[^"\\])*"|\{[^{}]*}|\[[^\[\]]*\]|[^,\s}\]]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val URI_SCHEME_PATTERN = Regex("[A-Za-z][A-Za-z0-9+.-]*")
    private val URI_AUTHORITY_PATTERN = Regex("[A-Za-z0-9._-]+")
    private val PROVIDER_AUTHORITY_ALLOWLIST = setOf(
        "media",
        "com.android.providers.media.documents",
        "com.android.providers.downloads.documents",
        "com.android.externalstorage.documents",
        "com.google.android.apps.photos.contentprovider",
        "com.google.android.apps.docs.storage",
    )

    private const val DEFAULT_MAX_LENGTH = 300
    private const val MAX_URI_COMPONENT_LENGTH = 120
    private const val SCHEME_SEPARATOR = "://"
    private const val FILE_SCHEME = "file"
    private const val REDACTED_URL = "<url>"
    private const val REDACTED_PATH = "<path>"
    private const val REDACTED_NUMBER = "<n>"
    private const val REDACTED_VALUE = "<redacted>"
    private const val REDACTED_AUTHORITY = "<redacted-authority>"
    private const val REDACTED_LOCAL_AUTHORITY = "<local>"
}
