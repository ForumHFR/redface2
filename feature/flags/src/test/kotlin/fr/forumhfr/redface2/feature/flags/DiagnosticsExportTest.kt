package fr.forumhfr.redface2.feature.flags

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsExportTest {
    @Test
    fun `copy export starts with app build channel and allowed device fields followed by readable entries`() {
        val entry = DiagnosticsLog.Entry(1, DiagnosticsLog.Level.INFO, "FlagsRefresh", "refresh=1 outcome=success", 0)
        val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val environment = DiagnosticsEnvironment("0.62.0", 72, "dev", 33, "Google", "Pixel 7")
        val export = formatDiagnosticsExport(listOf(entry), format, environment)
        assertEquals(listOf(
            "Redface 2 version=0.62.0 build=72 channel=dev",
            "Android API=33 manufacturer=Google model=Pixel 7",
            "00:00:00.000  I  FlagsRefresh  refresh=1 outcome=success",
            "0 entrées locales non exportées",
        ), export.lines())
        assertTrue(formatDiagnosticsExport(emptyList(), format, environment).contains("build=72 channel=dev"))
    }

    @Test
    fun `copy exports only the closed set of network tags and counts excluded local entries`() {
        val messages = listOf(
            "FlagRepository" to "delflag OK topic=35395",
            "ReplyRepository" to "redirect=https://forum.hardware.fr/?hash_check=SecretHash " +
                "body=<html>SecretBody</html>",
            "ReplyRepository" to "IOException: SecretMessage Cookie=SecretCookie",
            "FlagsRefreshExtra" to "SecretUnknownProducer",
            "FlagsRefresh" to "refresh=1 outcome=success",
            "HfrCall" to "refresh=1 rest:categories totalMs=1200",
            "AppForeground" to "backgroundMs=4000",
        )
        val entries = messages.mapIndexed { index, (tag, message) ->
            DiagnosticsLog.Entry(index.toLong(), DiagnosticsLog.Level.INFO, tag, message, 0)
        }
        val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val environment = DiagnosticsEnvironment("0.62.0", 72, "dev", 33, "Google", "Pixel 7")
        val export = formatDiagnosticsExport(entries, format, environment)

        assertEquals(6, export.lines().size)
        assertEquals("4 entrées locales non exportées", export.lines().last())
        listOf("topic=", "35395", "https://", "hash_check", "Secret", "<html>", "IOException").forEach {
            assertFalse(export, export.contains(it))
        }
        messages.takeLast(3).forEach { (tag, message) -> assertTrue(export.contains("$tag  $message")) }
        // Filtering never mutates the local viewer's complete snapshot.
        assertEquals(7, entries.size)
        assertEquals(
            "0 entrées locales non exportées",
            formatDiagnosticsExport(emptyList(), format, environment).lines().last(),
        )
    }
}
