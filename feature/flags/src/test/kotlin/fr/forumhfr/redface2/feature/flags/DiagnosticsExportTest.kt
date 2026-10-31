package fr.forumhfr.redface2.feature.flags

import fr.forumhfr.redface2.core.domain.diagnostics.AppForegroundDiagnostics
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsClock
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshDiagnostics
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshTrigger
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkDiagnosticsContext
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkDiagnosticsContextProvider
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkTransport
import fr.forumhfr.redface2.core.model.FlagType
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsExportTest {
    @Test
    fun `export retains closed network snapshots and capture times even when copied after resume`() {
        val contexts = listOf(
            NetworkDiagnosticsContext(setOf(NetworkTransport.WIFI), false, true, true) to
                "transport=wifi metered=0 validated=1 privateDns=1",
            NetworkDiagnosticsContext(setOf(NetworkTransport.CELLULAR), true, false, false) to
                "transport=cellular metered=1 validated=0 privateDns=0",
            NetworkDiagnosticsContext(setOf(NetworkTransport.VPN)) to
                "transport=vpn metered=? validated=? privateDns=?",
            NetworkDiagnosticsContext(setOf(NetworkTransport.WIFI, NetworkTransport.VPN), false, true) to
                "transport=vpn+wifi metered=0 validated=1 privateDns=?",
            NetworkDiagnosticsContext(emptySet()) to "transport=none metered=? validated=? privateDns=?",
        )
        contexts.forEach { (context, expected) ->
            assertCapturedExport(NetworkDiagnosticsContextProvider { context }, expected)
        }
        assertCapturedExport(
            NetworkDiagnosticsContextProvider { error("SecretSsid SecretDns SecretOperator 192.0.2.1") },
            "transport=unknown metered=? validated=? privateDns=?",
        )
    }

    private fun assertCapturedExport(provider: NetworkDiagnosticsContextProvider, expected: String) {
        val log = DiagnosticsLog()
        var now = 10_000_000_000L
        val foreground = AppForegroundDiagnostics(log, DiagnosticsClock { now })
        foreground.foregrounded()
        now = 16_900_000_000L
        val trace = FlagRefreshDiagnostics(log, provider, foreground)
            .begin(FlagRefreshTrigger.PULL, listOf(FlagType.CYAN))
        now = 60_000_000_000L
        trace.finish()
        foreground.backgrounded()
        now = 100_000_000_000L
        foreground.foregrounded()
        val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val environment = DiagnosticsEnvironment("0.63.1", 72, "dev", 36, "Xiaomi", "Test")
        val exported = formatDiagnosticsExport(log.entries.value, format, environment)
        val refreshLines = exported.lines().filter { it.contains("  FlagsRefresh  ") }
        assertEquals(2, refreshLines.size)
        refreshLines.forEach { assertTrue(it, it.endsWith("$expected uptimeS=16 foregroundS=6")) }
        listOf("Secret", "192.0.2.1").forEach { assertFalse(exported, exported.contains(it)) }
        assertEquals("Redface 2 version=0.63.1 build=72 channel=dev", exported.lines().first())
        assertEquals("0 entrées locales non exportées", exported.lines().last())
    }

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
    fun `copy exports network and smiley picker tags and counts excluded local entries`() {
        val messages = listOf(
            "FlagRepository" to "delflag OK topic=35395",
            "ReplyRepository" to "redirect=https://forum.hardware.fr/?hash_check=SecretHash " +
                "body=<html>SecretBody</html>",
            "ReplyRepository" to "IOException: SecretMessage Cookie=SecretCookie",
            "FlagsRefreshExtra" to "SecretUnknownProducer",
            "SmileyPickerExtra" to "SecretUnknownPicker",
            "FlagsRefresh" to "refresh=1 outcome=success",
            "HfrCall" to "refresh=1 rest:categories totalMs=1200 ip=v6",
            "AppForeground" to "backgroundMs=4000",
            "SmileyPicker" to "sheet_close cause=unknown",
        )
        val entries = messages.mapIndexed { index, (tag, message) ->
            DiagnosticsLog.Entry(index.toLong(), DiagnosticsLog.Level.INFO, tag, message, 0)
        }
        val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val environment = DiagnosticsEnvironment("0.62.0", 72, "dev", 33, "Google", "Pixel 7")
        val export = formatDiagnosticsExport(entries, format, environment)

        assertEquals(7, export.lines().size)
        assertEquals("5 entrées locales non exportées", export.lines().last())
        listOf("topic=", "35395", "https://", "hash_check", "Secret", "<html>", "IOException").forEach {
            assertFalse(export, export.contains(it))
        }
        messages.takeLast(4).forEach { (tag, message) -> assertTrue(export.contains("$tag  $message")) }
        // Filtering never mutates the local viewer's complete snapshot.
        assertEquals(9, entries.size)
        assertEquals(
            "0 entrées locales non exportées",
            formatDiagnosticsExport(emptyList(), format, environment).lines().last(),
        )
    }
}
