package fr.forumhfr.redface2.feature.flags

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
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
        ), export.lines())
        assertTrue(formatDiagnosticsExport(emptyList(), format, environment).contains("build=72 channel=dev"))
    }
}
