package fr.forumhfr.redface2.core.domain.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadFailureDiagnosticsTest {

    @Test
    fun `describe marks an unknown throwable as unmapped and truncates its message`() {
        val error = UnknownUploadFailure("x".repeat(170), IllegalStateException("root"))

        val description = UploadFailureDiagnostics.describe(error, mappedError = "Network")

        assertTrue(description.startsWith("upload KO: Network ex="))
        assertTrue(description.contains("UnknownUploadFailure: ${"x".repeat(160)}"))
        assertTrue(description.contains("cause=java.lang.IllegalStateException"))
        assertTrue(description.endsWith("unmapped=true"))
        assertEquals(160, description.substringAfter("UnknownUploadFailure: ").substringBefore(" cause=").length)
    }

    private class UnknownUploadFailure(message: String, cause: Throwable) : Exception(message, cause)
}
