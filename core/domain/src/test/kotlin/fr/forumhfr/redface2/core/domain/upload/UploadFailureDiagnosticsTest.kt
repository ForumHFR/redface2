package fr.forumhfr.redface2.core.domain.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `describe redacts URLs from the error message`() {
        val error = UnknownUploadFailure(
            "upload failed for https://private.test/image/543526",
            IllegalStateException("root"),
        )

        val description = UploadFailureDiagnostics.describe(error, mappedError = "Network")

        assertTrue(description.contains("UnknownUploadFailure: upload failed for <url>"))
        assertFalse(description.contains("://"))
        assertFalse(description.contains("543526"))
    }

    @Test
    fun `describe redacts URLs from the cause description`() {
        val error = UnknownUploadFailure(
            "outer",
            IllegalStateException("read content://provider/private/12345"),
        )

        val description = UploadFailureDiagnostics.describe(error, mappedError = "Network")

        assertTrue(description.contains("cause=java.lang.IllegalStateException: read <url>"))
        assertFalse(description.contains("://"))
        assertFalse(description.contains("12345"))
    }

    private class UnknownUploadFailure(message: String, cause: Throwable) : Exception(message, cause)
}
