package fr.forumhfr.redface2.core.data.upload

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadProviderDiagnosticsTest {

    @Test
    fun `response redacts the failure body at the shared formatting boundary`() {
        val response = UploadProviderDiagnostics.response(
            trace = ProviderResponseTrace(
                code = 422,
                contentType = "application/json",
                durationMs = 12,
                result = "http_error",
            ),
            failureBody = """{"picURL":"https://host/private/543526"}""",
        )

        assertTrue(response.contains("body={<redacted>}"))
        assertFalse(response.contains("picURL"))
        assertFalse(response.contains("://"))
        assertFalse(response.contains("543526"))
    }
}
