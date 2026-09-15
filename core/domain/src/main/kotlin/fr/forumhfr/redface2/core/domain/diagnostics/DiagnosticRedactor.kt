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
     * Reduces an Android picker URI to a redacted source. File and Android resource URIs never
     * expose their local authority. Content policy: « autorités hors liste blanche caviardées ».
     * The path, query and fragment are always discarded.
     */
    fun redactUriSource(uri: String): String {
        val separatorIndex = uri.indexOf(SCHEME_SEPARATOR)
        return if (separatorIndex <= 0) {
            REDACTED_VALUE
        } else {
            val normalizedScheme = uri.substring(0, separatorIndex).lowercase()
            when (normalizedScheme) {
                FILE_SCHEME -> "$FILE_SCHEME$SCHEME_SEPARATOR$REDACTED_LOCAL_AUTHORITY"
                CONTENT_SCHEME -> {
                    val authority = uri
                        .substring(separatorIndex + SCHEME_SEPARATOR.length)
                        .substringBefore('/')
                        .substringBefore('?')
                        .substringBefore('#')
                    val redactedAuthority = if (isAllowedProviderAuthority(authority)) {
                        authority
                    } else {
                        REDACTED_AUTHORITY
                    }
                    "$CONTENT_SCHEME$SCHEME_SEPARATOR$redactedAuthority"
                }
                ANDROID_RESOURCE_SCHEME ->
                    "$ANDROID_RESOURCE_SCHEME$SCHEME_SEPARATOR$REDACTED_LOCAL_AUTHORITY"
                else -> REDACTED_VALUE
            }
        }
    }

    private fun isAllowedProviderAuthority(authority: String): Boolean =
        authority in PROVIDER_AUTHORITY_ALLOWLIST &&
            URI_AUTHORITY_PATTERN.matches(authority) &&
            !LONG_NUMBER_PATTERN.containsMatchIn(authority)

    private val URL_PATTERN = Regex("""[a-z][a-z0-9+.-]*://[^\s"'<>]+""")
    private val ABSOLUTE_PATH_PATTERN = Regex("""(/storage|/data|/sdcard|/mnt|/proc)[^\s"'<>]*""")
    private val LONG_NUMBER_PATTERN = Regex("""\d{5,}""")
    private val IDENTIFYING_JSON_FIELD_PATTERN = Regex(
        """"(?:picURL|picID|deletehash|link|name|title|description|error)"\s*:\s*""" +
            """(?:"(?:\\.|[^"\\])*"|\{[^{}]*}|\[[^\[\]]*\]|[^,\s}\]]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val URI_AUTHORITY_PATTERN = Regex("[A-Za-z0-9._-]+")
    private val PROVIDER_AUTHORITY_ALLOWLIST = setOf(
        "media",
        "downloads",
        "com.android.providers.media.documents",
        "com.android.providers.downloads.documents",
        "com.android.externalstorage.documents",
        "com.android.providers.media",
        "com.google.android.apps.photos.contentprovider",
        "com.google.android.apps.photos.content",
        "com.google.android.apps.docs.storage",
        "com.google.android.apps.docs.storage.legacy",
        "com.android.providers.media.photopicker",
    )

    private const val DEFAULT_MAX_LENGTH = 300
    private const val SCHEME_SEPARATOR = "://"
    private const val FILE_SCHEME = "file"
    private const val CONTENT_SCHEME = "content"
    private const val ANDROID_RESOURCE_SCHEME = "android.resource"
    private const val REDACTED_URL = "<url>"
    private const val REDACTED_PATH = "<path>"
    private const val REDACTED_NUMBER = "<n>"
    private const val REDACTED_VALUE = "<redacted>"
    private const val REDACTED_AUTHORITY = "<redacted-authority>"
    private const val REDACTED_LOCAL_AUTHORITY = "<local>"
}
