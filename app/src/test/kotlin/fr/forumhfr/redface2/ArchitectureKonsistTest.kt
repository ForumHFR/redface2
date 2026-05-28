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

    @Test
    fun `feature topic does not depend on feature profile`() {
        // Phase 2 finish (#208) — the « ouvrir le profil » affordance is hoisted to `:app`
        // as a callback `onOpenProfile(userId, pseudo, avatarUrl)` so `:feature:topic` can
        // emit the request without importing `:feature:profile`. A direct import would
        // break the architecture boundary documented in
        // `docs/specs/navigation.md` § Profil utilisateur and bring the profile module
        // into every screen that already depends on topic (search results, flag opens,
        // category drilldowns…). This Konsist guard catches such regressions at build
        // time (review feedback I9).
        val topicFiles = Konsist
            .scopeFromProject()
            .slice { file ->
                file.path.contains("/feature/topic/") &&
                    !file.path.contains("/build/") &&
                    !file.path.contains("/src/test/")
            }
            .files

        assertTrue("Konsist must scan :feature:topic production files", topicFiles.isNotEmpty())

        topicFiles.assertFalse { file ->
            file.imports.any { imported ->
                imported.name.orEmpty().startsWith(FEATURE_PROFILE_PACKAGE)
            }
        }
    }

    @Test
    fun `prefetch call sites use the prefetch entry points only`() {
        // Phase 1D PR 4 — anonymous prefetch must funnel through the dedicated
        // repository methods (`TopicRepository.prefetch`, `ForumRepository.prefetchTopicList`),
        // which both go to the @AnonymousClient. A ViewModel that calls
        // `refreshTopicPage()` or `refreshTopicList()` from a "prefetch" context
        // would fire an authenticated request and silently mark drapeaux as read
        // (cf. ADR-003 § Prefetch).
        //
        // We analyse at **function** granularity, not line-by-line. A naive
        // line search misses the natural shape of the bug : a function named
        // `maybeSchedulePrefetch(...)` calls `refreshTopicPage(...)` two lines
        // below — the line search would not flag it because `prefetch` and
        // `refreshTopicPage` live on different lines. Looking at the function
        // text catches both same-line and multi-line forms.
        val viewModelFiles = Konsist
            .scopeFromProject()
            .slice { file ->
                file.path.contains("/feature/") &&
                    file.path.endsWith("ViewModel.kt") &&
                    !file.path.contains("/src/test/") &&
                    !file.path.contains("/build/")
            }
            .files

        assertTrue("Konsist must scan feature ViewModels", viewModelFiles.isNotEmpty())

        viewModelFiles.forEach { file ->
            file.functions(includeNested = true, includeLocal = true).forEach { function ->
                val nameSuggestsPrefetch = "prefetch" in function.name.lowercase()
                val bodyMentionsPrefetch = "prefetch" in function.text.lowercase()
                if (!nameSuggestsPrefetch && !bodyMentionsPrefetch) return@forEach
                val body = function.text.lowercase()
                // Explicit allow-list marker: a function that needs to legitimately mix prefetch
                // bookkeeping (e.g. cancelling an inflight warmup) with an authenticated refresh
                // can opt out of this guard by carrying the literal token below in its KDoc /
                // body. Each use should be reviewed manually — the token is intentionally ugly
                // so it stands out on grep.
                val isExplicitlyExempt = "konsist:bypass-prefetch-guard" in body
                if (isExplicitlyExempt) return@forEach
                val callsRefresh = "refreshtopicpage" in body || "refreshtopiclist" in body
                org.junit.Assert.assertFalse(
                    "Function ${file.path}:${function.name} mixes prefetch context " +
                        "with an authenticated refresh* call. Anonymous prefetch must use " +
                        "TopicRepository.prefetch / ForumRepository.prefetchTopicList only. " +
                        "If this is intentional (deliberate authenticated refetch that also " +
                        "needs to manage prefetch state), add the marker " +
                        "`konsist:bypass-prefetch-guard` to the function and document why.",
                    callsRefresh,
                )
            }
        }
    }

    private companion object {
        const val ANONYMOUS_CLIENT_PACKAGE =
            "fr.forumhfr.redface2.core.network.qualifiers"
        const val ANONYMOUS_CLIENT_QUALIFIER =
            "$ANONYMOUS_CLIENT_PACKAGE.AnonymousClient"
        const val FEATURE_PROFILE_PACKAGE =
            "fr.forumhfr.redface2.feature.profile."
        val AUTH_DIR_TOKENS = listOf("/auth/", "/messages/")
    }
}
