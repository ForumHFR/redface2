package fr.forumhfr.redface2.core.data.write

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the redaction contract of [redactHashCheckForDiagnostics]. Coverage matrix mirrors
 * the patterns the helper KDoc promises to mask :
 *
 *  - URL query / form-encoded KV (`hash_check=…`)
 *  - JS literal (`var hash_check = "…"`) — falls back on the KV path once the literal opens
 *  - HTML hidden input with `name` before `value`
 *  - HTML hidden input with `value` before `name`
 *  - mixed payload (KV + HTML inputs in the same blob, as observed when the alpha tester
 *    dumps the first 600 chars of HFR's `message.php` response)
 *
 * Each test asserts both that the literal `hash_check` token stays in place (the redactor
 * must not erase the field name — diagnostic value comes from seeing *that* HFR returned
 * a `hash_check`) AND that the secret value never makes it through.
 */
class RedactHashCheckForDiagnosticsTest {

    private val secret = "AbCdEfGhIjKlMnOp1234567890"

    @Test
    fun `KV form hash_check=value is masked`() {
        val redacted = redactHashCheckForDiagnostics("hash_check=$secret&other=1")
        assertEquals("hash_check=<REDACTED>&other=1", redacted)
        assertFalse(secret in redacted)
    }

    @Test
    fun `KV form hash_check colon value is masked`() {
        val redacted = redactHashCheckForDiagnostics("hash_check:$secret")
        assertEquals("hash_check:<REDACTED>", redacted)
        assertFalse(secret in redacted)
    }

    @Test
    fun `JS literal var hash_check is masked via the KV path`() {
        val input = """var hash_check = "&hash_check=$secret";"""
        val redacted = redactHashCheckForDiagnostics(input)
        assertFalse("JS literal secret must not survive", secret in redacted)
        assertTrue("hash_check label remains for diagnostic context", "hash_check" in redacted)
    }

    @Test
    fun `HTML input name then value is masked`() {
        val input = """<input type="hidden" name="hash_check" value="$secret" />"""
        val redacted = redactHashCheckForDiagnostics(input)
        assertFalse("HTML input value must not survive", secret in redacted)
        assertTrue("redaction marker present", "<REDACTED>" in redacted)
        assertTrue("input still recognisable as hash_check", """name="hash_check"""" in redacted)
    }

    @Test
    fun `HTML input value then name is masked`() {
        val input = """<input type="hidden" value="$secret" name="hash_check" />"""
        val redacted = redactHashCheckForDiagnostics(input)
        assertFalse("HTML input value must not survive when value comes first", secret in redacted)
        assertTrue("redaction marker present", "<REDACTED>" in redacted)
    }

    @Test
    fun `HTML input with single quotes is masked`() {
        val input = """<input type='hidden' name='hash_check' value='$secret' />"""
        val redacted = redactHashCheckForDiagnostics(input)
        assertFalse("single-quoted attribute value must not survive", secret in redacted)
    }

    @Test
    fun `mixed payload KV plus HTML input masks both sites`() {
        val input = buildString {
            append("Status: 200 OK\n")
            append("Set-Cookie: PHPSESSID=zzz\n")
            append("...hash_check=$secret&other=1...")
            append("""<form action="bddpost.php?cat=23&page=1">""")
            append("""<input type="hidden" name="hash_check" value="$secret" />""")
            append("</form>")
        }
        val redacted = redactHashCheckForDiagnostics(input)
        assertFalse(
            "Neither carrier of the secret must survive the redactor",
            secret in redacted,
        )
        // Sanity : we did not nuke the rest of the diagnostic context.
        assertTrue("status line preserved", "200 OK" in redacted)
        assertTrue("form tag preserved", """<form action="bddpost.php""" in redacted)
    }

    @Test
    fun `payload without hash_check is left untouched`() {
        val input = """<form action="bdd.php"><input name="content_form" value="hello"/></form>"""
        val redacted = redactHashCheckForDiagnostics(input)
        assertEquals(input, redacted)
    }

    @Test
    fun `redactor never echoes the secret in its output`() {
        val variants = listOf(
            "hash_check=$secret",
            "hash_check:$secret",
            """<input name="hash_check" value="$secret">""",
            """<input value="$secret" name="hash_check">""",
            """<input type="hidden" name="hash_check"  value="$secret"   class="x">""",
        )
        variants.forEach { input ->
            assertFalse(
                "Secret leaked through variant: $input",
                secret in redactHashCheckForDiagnostics(input),
            )
        }
    }
}
