package fr.forumhfr.redface2.core.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactorPatternSyntaxTest {

    @Test
    fun `pattern sources only contain escaped literal braces`() {
        DiagnosticRedactor.patternSources.forEach { (name, source) ->
            val violations = findUnescapedLiteralBraces(source)

            assertTrue("$name contains unescaped literal braces at $violations", violations.isEmpty())
        }
    }

    @Test
    fun `escaped braces preserve diagnostic redaction semantics`() {
        assertEquals(
            """{"status":"error",<redacted>}""",
            DiagnosticRedactor.redact("""{"status":"error","error":{"message":"upload refused"}}"""),
        )
        assertEquals(
            "content://media",
            DiagnosticRedactor.redactUriSource("content://media/external/images/media/12345"),
        )
        assertEquals(
            "java.io.IOException: read failed: <path>",
            DiagnosticRedactor.redact(
                "java.io.IOException: read failed: /storage/emulated/0/DCIM/IMG_123456.jpg",
            ),
        )
    }

    private fun findUnescapedLiteralBraces(source: String): List<Int> {
        val violations = mutableListOf<Int>()
        var index = 0
        while (index < source.length) {
            when (source[index]) {
                '\\' -> index += 2
                '{' -> {
                    val quantifier = QUANTIFIER_PATTERN.matchAt(source, index)
                    if (quantifier == null) {
                        violations += index
                        index++
                    } else {
                        index = quantifier.range.last + 1
                    }
                }
                '}' -> {
                    violations += index
                    index++
                }
                else -> index++
            }
        }
        return violations
    }

    private companion object {
        val QUANTIFIER_PATTERN = Regex("""\{\d+(?:,\d*)?\}""")
    }
}
