package fr.forumhfr.redface2

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.Test

class ArchitectureKonsistTest {
    private val forbiddenImplementationImports = listOf(
        "fr.forumhfr.redface2.core.data.",
        "fr.forumhfr.redface2.core.network.",
        "fr.forumhfr.redface2.core.parser.",
        "fr.forumhfr.redface2.core.database.",
    )

    @Test
    fun `feature modules do not import implementation layers`() {
        Konsist
            .scopeFromProject()
            .slice { it.path.contains("/feature/") }
            .files
            .assertFalse {
                it.imports.any { imported ->
                    forbiddenImplementationImports.any(imported.name::startsWith)
                }
            }
    }

    @Test
    fun `only topic and editor may import core extension`() {
        Konsist
            .scopeFromProject()
            .slice {
                it.path.contains("/feature/") &&
                    !it.path.contains("/feature/topic/") &&
                    !it.path.contains("/feature/editor/")
            }
            .files
            .assertFalse {
                it.imports.any { imported ->
                    imported.name.startsWith("fr.forumhfr.redface2.core.extension.")
                }
            }
    }
}
