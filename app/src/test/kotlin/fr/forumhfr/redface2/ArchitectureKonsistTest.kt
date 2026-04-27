package fr.forumhfr.redface2

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectureKonsistTest {
    private val forbiddenImplementationImports = listOf(
        "fr.forumhfr.redface2.core.data.",
        "fr.forumhfr.redface2.core.network.",
        "fr.forumhfr.redface2.core.parser.",
        "fr.forumhfr.redface2.core.database.",
    )
    private val forbiddenMaterialTokenImports = setOf(
        "androidx.compose.material3.ColorScheme",
        "androidx.compose.material3.Typography",
        "androidx.compose.material3.Shapes",
    )

    @Test
    fun `feature modules do not import implementation layers`() {
        val featureFiles = Konsist
            .scopeFromProject()
            .slice { declaration ->
                declaration.path.contains("/feature/")
            }
            .files

        assertTrue("Konsist must scan feature modules", featureFiles.isNotEmpty())

        featureFiles
            .assertFalse {
                it.imports.any { imported ->
                    forbiddenImplementationImports.any(imported.name.orEmpty()::startsWith)
                }
            }
    }

    @Test
    fun `only topic and editor may import core extension`() {
        val nonExtensionFeatureFiles = Konsist
            .scopeFromProject()
            .slice {
                it.path.contains("/feature/") &&
                    !it.path.contains("/feature/topic/") &&
                    !it.path.contains("/feature/editor/")
            }
            .files

        assertTrue(
            "Konsist must scan non-extension feature modules",
            nonExtensionFeatureFiles.isNotEmpty(),
        )

        nonExtensionFeatureFiles
            .assertFalse {
                it.imports.any { imported ->
                    imported.name.orEmpty().startsWith("fr.forumhfr.redface2.core.extension.")
                }
            }
    }

    @Test
    fun `material tokens stay confined to core ui`() {
        val nonCoreUiFiles = Konsist
            .scopeFromProject()
            .slice {
                it.path.contains("/app/src/main/") ||
                    it.path.contains("/feature/") ||
                    (it.path.contains("/core/") && !it.path.contains("/core/ui/"))
            }
            .files

        assertTrue("Konsist must scan production files outside core ui", nonCoreUiFiles.isNotEmpty())

        nonCoreUiFiles.assertFalse { file ->
            file.imports.any { imported ->
                imported.name.orEmpty() in forbiddenMaterialTokenImports
            }
        }
    }

    @Test
    fun `auth code does not depend on AnonymousClient`() {
        // Anything under an /auth/ directory speaks to HFR with cookies attached. Wiring
        // @AnonymousClient (CookieJar.NO_COOKIES) into auth code would silently break the
        // session since OkHttp would never replay md_user / md_pass — this rule catches the
        // mistake at build time instead of runtime.
        val authProductionFiles = Konsist
            .scopeFromProject()
            .slice { file ->
                file.path.contains("/auth/") &&
                    !file.path.contains("/src/test/") &&
                    !file.path.contains("/build/")
            }
            .files

        assertTrue("Konsist must scan auth production files", authProductionFiles.isNotEmpty())

        authProductionFiles.assertFalse { file ->
            file.imports.any { imported ->
                imported.name.orEmpty() == ANONYMOUS_CLIENT_QUALIFIER
            }
        }
    }

    private companion object {
        const val ANONYMOUS_CLIENT_QUALIFIER =
            "fr.forumhfr.redface2.core.network.qualifiers.AnonymousClient"
    }
}
