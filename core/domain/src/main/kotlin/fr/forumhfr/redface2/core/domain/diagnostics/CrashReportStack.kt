package fr.forumhfr.redface2.core.domain.diagnostics

/** Writes only allowlisted crash fields. The sink returns false when its byte budget is exhausted. */
object CrashReportStack {
    fun write(thread: Thread, mainThread: Thread, error: Throwable, line: (String) -> Boolean) {
        val threadKind = if (thread === mainThread) "main" else "other"
        if (!line("thread=$threadKind")) return

        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_THROWABLES) {
            val type = current.javaClass.name
            val prefix = if (depth == 0) "" else "Caused by: "
            val exceptionFits = !type.isReportField(CLASS_NAME) || line("$prefix$type${messageSuffix(current.message)}")
            if (!exceptionFits || !writeFrames(current.stackTrace, line)) return
            current = current.cause
            depth++
        }
        if (current != null) line("... further causes omitted")
    }

    private fun writeFrames(frames: Array<StackTraceElement>, line: (String) -> Boolean): Boolean {
        for (index in 0 until minOf(frames.size, MAX_FRAMES)) {
            val frame = frames[index].reportLine() ?: continue
            if (!line(frame)) return false
        }
        return frames.size <= MAX_FRAMES || line("\t... ${frames.size - MAX_FRAMES} frames omitted")
    }

    private fun StackTraceElement.reportLine(): String? {
        val file = fileName
        val validFile = file == null || file.isReportField(FILE_NAME)
        if (!className.isReportField(CLASS_NAME) || !methodName.isReportField(METHOD_NAME) || !validFile) return null
        val location = when {
            isNativeMethod -> "Native Method"
            file == null -> "Unknown Source"
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

    private const val MAX_FIELD_CHARS = 512
    private const val MAX_THROWABLES = 8
    private const val MAX_FRAMES = 40
    private const val IDENTIFIER = """[\p{L}_$][\p{L}\p{Nd}_$]*"""
    private val CLASS_NAME = Regex("""$IDENTIFIER(?:\.$IDENTIFIER)*""")
    // Kotlin emits hyphens in JVM method names mangled for value classes.
    private val METHOD_NAME = Regex("""(?:<init>|<clinit>|[\p{L}_$][\p{L}\p{Nd}_$-]*)""")
    private val FILE_NAME = Regex("""[\p{L}\p{Nd}_$-]+(?:\.[\p{L}\p{Nd}_$-]+)*""")
}
