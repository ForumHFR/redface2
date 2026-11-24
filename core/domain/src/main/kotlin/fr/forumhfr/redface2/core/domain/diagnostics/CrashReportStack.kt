package fr.forumhfr.redface2.core.domain.diagnostics

/**
 * Writes only allowlisted crash fields: class, method and file names are kept for known packages
 * and anonymised otherwise. The sink returns false when its byte budget is exhausted.
 */
object CrashReportStack {
    fun write(thread: Thread, mainThread: Thread, error: Throwable, line: (String) -> Boolean) {
        val threadKind = if (thread === mainThread) "main" else "other"
        if (!line("thread=$threadKind")) return

        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_THROWABLES) {
            val type = current.javaClass.name.takeIf { it.isKnownClass() } ?: OMITTED_EXCEPTION_CLASS
            val prefix = if (depth == 0) "" else "Caused by: "
            val headerFits = line("$prefix$type${messageSuffix(current.message)}")
            if (!headerFits || !writeFrames(current.stackTrace, line)) return
            current = current.cause
            depth++
        }
        if (current != null) line("... further causes omitted")
    }

    private fun writeFrames(frames: Array<StackTraceElement>, line: (String) -> Boolean): Boolean =
        frameLines(frames).all(line)

    /** Consecutive frames of unknown origin share one anonymous line. */
    private fun frameLines(frames: Array<StackTraceElement>): List<String> {
        val lines = mutableListOf<String>()
        var omitted = 0
        fun flushOmitted() {
            if (omitted == 1) lines += "\tat $OMITTED_FRAME"
            if (omitted > 1) lines += "\tat $OMITTED_FRAME (x$omitted)"
            omitted = 0
        }
        for (index in 0 until minOf(frames.size, MAX_FRAMES)) {
            val frame = frames[index].reportLine()
            if (frame == null) {
                omitted++
            } else {
                flushOmitted()
                lines += frame
            }
        }
        flushOmitted()
        if (frames.size > MAX_FRAMES) lines += "\t... ${frames.size - MAX_FRAMES} frames omitted"
        return lines
    }

    /** A frame keeps its symbols only when its class, method and source file all look build-made. */
    private fun StackTraceElement.reportLine(): String? {
        val file = fileName?.takeIf { it.isReportField(FILE_NAME) && SOURCE_EXTENSIONS.any(it::endsWith) }
        if (file == null || !className.isKnownClass() || !methodName.isReportField(METHOD_NAME)) return null
        val location = when {
            isNativeMethod -> "Native Method"
            lineNumber >= 0 -> "$file:$lineNumber"
            else -> file
        }
        return "\tat $className.$methodName($location)"
    }

    private fun messageSuffix(message: String?): String = when (message) {
        "Failed requirement.", "Check failed.", "Required value was null." -> ": $message"
        else -> ": message omitted (${message?.length ?: 0} chars)"
    }

    private fun String.isReportField(pattern: Regex): Boolean = length <= MAX_FIELD_CHARS && pattern.matches(this)

    private fun String.isKnownClass(): Boolean = isReportField(CLASS_NAME) && KNOWN_PACKAGES.any(::startsWith)

    private const val MAX_FIELD_CHARS = 512
    private const val MAX_THROWABLES = 8
    private const val MAX_FRAMES = 40
    const val OMITTED_FRAME = "<omitted frame>"
    const val OMITTED_EXCEPTION_CLASS = "<omitted exception class>"
    private val SOURCE_EXTENSIONS = listOf(".kt", ".java")

    // Closed list: the app, the platform, the JDK and the runtime dependencies of gradle/libs.versions.toml.
    // Symbols from anywhere else (generated, synthetic or attacker-shaped names) are never written.
    private val KNOWN_PACKAGES = listOf(
        "fr.forumhfr.redface2.",
        "android.", "androidx.", "com.android.", "dalvik.", "libcore.",
        "java.", "javax.", "jdk.", "sun.",
        "kotlin.", "kotlinx.",
        "okhttp3.", "okio.", "coil3.", "dagger.", "com.google.", "org.jsoup.",
        "me.saket.telephoto.", "com.materialkolor.",
    )
    private const val IDENTIFIER = """[\p{L}_$][\p{L}\p{Nd}_$]*"""
    private val CLASS_NAME = Regex("""$IDENTIFIER(?:\.$IDENTIFIER)*""")
    // Kotlin emits hyphens in JVM method names mangled for value classes.
    private val METHOD_NAME = Regex("""(?:<init>|<clinit>|[\p{L}_$][\p{L}\p{Nd}_$-]*)""")
    private val FILE_NAME = Regex("""[\p{L}\p{Nd}_$-]+(?:\.[\p{L}\p{Nd}_$-]+)*""")
}
