package fr.forumhfr.redface2.feature.flags

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import java.text.SimpleDateFormat
import java.util.Date

// Six explicitly allowed environment fields; no package name, device ID or account metadata.
@Suppress("LongParameterList")
internal data class DiagnosticsEnvironment(
    val versionName: String,
    val versionCode: Int,
    val channel: String,
    val apiLevel: Int,
    val manufacturer: String,
    val model: String,
)

internal fun formatDiagnosticsExport(
    entries: List<DiagnosticsLog.Entry>,
    timeFormat: SimpleDateFormat,
    environment: DiagnosticsEnvironment,
): String = buildString {
    appendLine("Redface 2 version=${environment.versionName.singleLine()} build=${environment.versionCode} " +
        "channel=${environment.channel.singleLine()}")
    appendLine("Android API=${environment.apiLevel} manufacturer=${environment.manufacturer.singleLine()} " +
        "model=${environment.model.singleLine()}")
    entries.forEach { entry ->
        val level = when (entry.level) {
            DiagnosticsLog.Level.INFO -> "I"
            DiagnosticsLog.Level.DEBUG -> "D"
            DiagnosticsLog.Level.WARN -> "W"
            DiagnosticsLog.Level.ERROR -> "E"
        }
        appendLine("${timeFormat.format(Date(entry.timestampMillis))}  $level  ${entry.tag}  ${entry.message}")
    }
}.trimEnd()

private fun String.singleLine(): String = replace('\n', ' ').replace('\r', ' ')
