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
    fun `authenticated-only code does not depend on AnonymousClient`() {
        // Any code under /auth/ or /messages/ speaks to HFR with cookies attached. Wiring
        // @AnonymousClient (CookieJar.NO_COOKIES) here would silently break the session
        // since OkHttp would never replay md_user / md_pass — this rule catches the
        // mistake at build time instead of runtime. The check is layered so a contributor
        // can't bypass it with a star-import (`import …qualifiers.*`) or a fully-qualified
        // annotation usage (`@…qualifiers.AnonymousClient`).
        val authenticatedOnlyFiles = Konsist
            .scopeFromProject()
            .slice { file ->
                AUTH_DIR_TOKENS.any { file.path.contains(it) } &&
                    !file.path.contains("/src/test/") &&
                    !file.path.contains("/build/")
            }
            .files

        assertTrue(
            "Konsist must scan authenticated-only production files",
            authenticatedOnlyFiles.isNotEmpty(),
        )

        authenticatedOnlyFiles.assertFalse { file ->
            file.imports.any { imported ->
                val name = imported.name.orEmpty()
                name == ANONYMOUS_CLIENT_QUALIFIER ||
                    // Star-import of the qualifiers package would silently allow @AnonymousClient.
                    name == "$ANONYMOUS_CLIENT_PACKAGE.*"
            } ||
                // Catches @fully.qualified.AnonymousClient usage that bypasses the imports list.
                file.text.contains(ANONYMOUS_CLIENT_QUALIFIER)
        }
    }

    private companion object {
        const val ANONYMOUS_CLIENT_PACKAGE =
            "fr.forumhfr.redface2.core.network.qualifiers"
        const val ANONYMOUS_CLIENT_QUALIFIER =
            "$ANONYMOUS_CLIENT_PACKAGE.AnonymousClient"
        val AUTH_DIR_TOKENS = listOf("/auth/", "/messages/")
    }
}
