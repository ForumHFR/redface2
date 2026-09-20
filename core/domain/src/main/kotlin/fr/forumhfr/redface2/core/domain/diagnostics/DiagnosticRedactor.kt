package fr.forumhfr.redface2.core.domain.diagnostics

/** Removes user-identifying values from diagnostics intended to be shared publicly. */
object DiagnosticRedactor {

    fun redact(text: String, maxLength: Int = DEFAULT_MAX_LENGTH): String {
        val redacted = REDACT_PATTERNS?.redact(text)?.take(maxLength)
        return if (redacted == null || redacted.contains(SCHEME_SEPARATOR)) REDACTED_VALUE else redacted
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
            URI_AUTHORITY_PATTERN?.matches(authority) == true &&
            REDACT_PATTERNS?.longNumber?.containsMatchIn(authority) == false

    /**
     * Android compiles regular expressions with ICU, which is stricter than java.util.regex;
     * JVM tests do not detect every syntax difference between the two engines.
     */
    internal val patternSources: Map<String, String> = mapOf(
        "url" to URL_PATTERN_SOURCE,
        "absolutePath" to ABSOLUTE_PATH_PATTERN_SOURCE,
        "longNumber" to LONG_NUMBER_PATTERN_SOURCE,
        "identifyingJsonField" to IDENTIFYING_JSON_FIELD_PATTERN_SOURCE,
        "uriAuthority" to URI_AUTHORITY_PATTERN_SOURCE,
    )

    private val REDACT_PATTERNS = compileRedactPatterns()
    private val URI_AUTHORITY_PATTERN = compilePattern(URI_AUTHORITY_PATTERN_SOURCE)
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

    private data class RedactPatterns(
        val url: Regex,
        val absolutePath: Regex,
        val longNumber: Regex,
        val identifyingJsonField: Regex,
    ) {
        fun redact(text: String): String = identifyingJsonField.replace(
            longNumber.replace(
                absolutePath.replace(
                    url.replace(text, REDACTED_URL),
                    REDACTED_PATH,
                ),
                REDACTED_NUMBER,
            ),
            REDACTED_VALUE,
        )
    }

    private fun compileRedactPatterns(): RedactPatterns? {
        val patterns = listOfNotNull(
            compilePattern(URL_PATTERN_SOURCE),
            compilePattern(ABSOLUTE_PATH_PATTERN_SOURCE),
            compilePattern(LONG_NUMBER_PATTERN_SOURCE),
            compilePattern(IDENTIFYING_JSON_FIELD_PATTERN_SOURCE, setOf(RegexOption.IGNORE_CASE)),
        )
        return if (patterns.size == REDACT_PATTERN_COUNT) {
            RedactPatterns(
                url = patterns[0],
                absolutePath = patterns[1],
                longNumber = patterns[2],
                identifyingJsonField = patterns[3],
            )
        } else {
            null
        }
    }

    @Suppress("SwallowedException")
    private fun compilePattern(
        source: String,
        options: Set<RegexOption> = emptySet(),
    ): Regex? = try {
        Regex(source, options)
    } catch (_: IllegalArgumentException) {
        null
    }

    private const val DEFAULT_MAX_LENGTH = 300
    private const val REDACT_PATTERN_COUNT = 4
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
    private const val URL_PATTERN_SOURCE = """[a-z][a-z0-9+.-]*://[^\s"'<>]+"""
    private const val ABSOLUTE_PATH_PATTERN_SOURCE = """(/storage|/data|/sdcard|/mnt|/proc)[^\s"'<>]*"""
    private const val LONG_NUMBER_PATTERN_SOURCE = """\d{5,}"""
    private const val IDENTIFYING_JSON_FIELD_PATTERN_SOURCE =
        """"(?:picURL|picID|deletehash|link|name|title|description|error)"\s*:\s*""" +
            """(?:"(?:\\.|[^"\\])*"|\{[^\{\}]*\}|\[[^\[\]]*\]|[^,\s\}\]]+)"""
    private const val URI_AUTHORITY_PATTERN_SOURCE = "[A-Za-z0-9._-]+"
}
