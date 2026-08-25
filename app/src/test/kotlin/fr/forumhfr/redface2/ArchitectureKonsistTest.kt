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
    fun `private message session cache stays memory only and silent`() {
        // #1080 / #316 — private conversation content may live in process memory only. This guard
        // keeps persistence and diagnostics dependencies out of the dedicated cache component;
        // adding one would turn an implementation detail into a private-data disk/log sink.
        val cacheFiles = Konsist
            .scopeFromProject()
            .slice { file ->
                file.path.endsWith("/PrivateMessageThreadSessionCache.kt") &&
                    !file.path.contains("/src/test/") &&
                    !file.path.contains("/build/")
            }
            .files

        assertTrue("Konsist must scan the private-message session cache", cacheFiles.size == 1)

        cacheFiles.assertFalse { file ->
            file.imports.any { imported ->
                val name = imported.name.orEmpty()
                PRIVATE_CACHE_FORBIDDEN_IMPORT_PREFIXES.any(name::startsWith) ||
                    name.endsWith("Dao") ||
                    name.endsWith(".Log")
            } ||
                PRIVATE_CACHE_FORBIDDEN_TEXT.any(file.text::contains) ||
                PRIVATE_CACHE_FORBIDDEN_USAGE.any { it.containsMatchIn(file.text) }
        }
    }

    @Test
    fun `private message cache guard recognizes console and java logging usage shapes`() {
        // #1086 — the production guard scans the WHOLE file text. These shapes pin the holes left
        // by import-only/function-only checks: init blocks, property initializers, default-imported
        // println and fully-qualified calls all have to be recognized.
        val guardedUsages = mapOf(
            "println in init block" to "init { println(thread) }",
            "print in property initializer" to "private val leaked = print(thread)",
            "fully-qualified Kotlin println" to "kotlin.io.println(thread)",
            "System out" to "private val sink = System.out",
            "fully-qualified System err" to "java.lang.System.err.println(thread)",
            "java util logging" to "java.util.logging.Logger.getLogger(\"PrivateCache\")",
            "stack trace" to "error.printStackTrace()",
        )

        guardedUsages.forEach { (shape, source) ->
            assertTrue(
                "Private-message cache guard must recognize $shape",
                PRIVATE_CACHE_FORBIDDEN_USAGE.any { it.containsMatchIn(source) },
            )
        }
    }

    @Test
    fun `private message content database access stays behind its silent facade`() {
        val productionFiles = Konsist
            .scopeFromProject()
            .slice { file ->
                file.path.contains("/src/main/") && !file.path.contains("/build/")
            }
            .files

        assertTrue("Konsist must scan production files", productionFiles.isNotEmpty())

        productionFiles.forEach { file ->
            assertTrue(
                "${file.path} references the private-message content database access outside " +
                    "the database declaration/wiring or its dedicated facade",
                privateMessageDatabaseReferenceIsAllowed(file.path, file.text),
            )
        }

        val facadeFiles = productionFiles.filter { file ->
            file.path.endsWith(PRIVATE_MESSAGE_DISK_FACADE_PATH)
        }
        assertTrue("Konsist must find exactly one private-message disk facade", facadeFiles.size == 1)
        org.junit.Assert.assertFalse(
            "The private-message disk facade must remain silent (#316)",
            privateMessageFacadeLeaksToDiagnostics(facadeFiles.single().text),
        )
    }

    @Test
    fun `private message content database guard rejects repository access and facade logging`() {
        val forbiddenDatabaseAccess = listOf(
            "private val contentDao: PrivateMessageContentDao",
            "database.privateMessageContentDao()",
            "RedfaceDatabase::privateMessageContentDao",
            "database.query(\"SELECT * FROM mp_thread_pages\")",
            "database.execSQL(\"DELETE FROM mp_messages\")",
        )
        forbiddenDatabaseAccess.forEach { source ->
            org.junit.Assert.assertFalse(
                privateMessageDatabaseReferenceIsAllowed(
                    "/core/data/src/main/kotlin/example/OtherRepository.kt",
                    source,
                ),
            )
        }
        assertTrue(
            privateMessageDatabaseReferenceIsAllowed(
                PRIVATE_MESSAGE_DISK_FACADE_PATH,
                "private val contentDao: PrivateMessageContentDao",
            ),
        )

        val forbiddenDiagnostics = listOf(
            "android.util.Log.w(\"PrivateCache\", \"failed\")",
            "diagnostics.record(DiagnosticsLog.Level.WARN, \"tag\", \"failed\")",
            "println(\"failed\")",
            "System.out.println(\"failed\")",
            "Logger.getLogger(\"PrivateCache\")",
            "error.printStackTrace()",
        )
        forbiddenDiagnostics.forEach { source ->
            assertTrue(
                "Private-message facade guard must recognize $source",
                privateMessageFacadeLeaksToDiagnostics(source),
            )
        }
    }

    private fun privateMessageDatabaseReferenceIsAllowed(path: String, source: String): Boolean =
        !PRIVATE_MESSAGE_CONTENT_DATABASE_USAGE.containsMatchIn(source) ||
            PRIVATE_MESSAGE_CONTENT_DATABASE_ALLOWED_PATHS.any(path::endsWith)

    private fun privateMessageFacadeLeaksToDiagnostics(source: String): Boolean =
        PRIVATE_MESSAGE_FACADE_FORBIDDEN_USAGE.any { forbidden ->
            forbidden.containsMatchIn(source)
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

        // #1080 / ADR-013 decision 3 — authenticated MP prefetch is the unique exception to the
        // anonymous rule. Its dedicated entry point is intentionally narrow: every production call
        // site must live in the conversation ViewModel. In particular, MessagesViewModel (the inbox)
        // may never call it because merely fetching a conversation clears both its unread dot and the
        // MultiMP « pas lu par » receipt. Scan the full text of ALL production files so an init block,
        // property initializer, call without an explicit receiver, or callable reference cannot move
        // the forbidden usage outside a function and evade the path check.
        val productionFiles = Konsist
            .scopeFromProject()
            .slice { file ->
                file.path.contains("/src/main/") && !file.path.contains("/build/")
            }
            .files
        var privateMessagePrefetchCallSites = 0
        productionFiles.forEach { file ->
            val usageCount = PRIVATE_MESSAGE_PREFETCH_USAGE.findAll(file.text).count()
            if (usageCount == 0) return@forEach

            assertTrue(
                "${file.path} declares, calls, or references the authenticated private-message " +
                    "prefetch entry point outside the explicit ADR-013 allowlist",
                PRIVATE_MESSAGE_PREFETCH_ALLOWED_PATHS.any(file.path::endsWith),
            )
            if (file.path.endsWith(PRIVATE_MESSAGE_THREAD_VIEW_MODEL_PATH)) {
                privateMessagePrefetchCallSites += usageCount
            }
        }
        assertTrue(
            "Konsist must find the authenticated private-message prefetch call site in " +
                "PrivateMessageThreadViewModel",
            privateMessagePrefetchCallSites > 0,
        )
    }

    @Test
    fun `private message prefetch guard recognizes all usage shapes`() {
        val guardedUsages = mapOf(
            "init block" to
                "init { scope.launch { repository.prefetchPrivateMessageThread(1, 1) } }",
            "property initializer" to
                "private val warmup = scope.launch { repository.prefetchPrivateMessageThread(1, 1) }",
            "call without receiver" to
                "with(repository) { prefetchPrivateMessageThread(1, 1) }",
            "callable reference" to "repository::prefetchPrivateMessageThread",
        )

        guardedUsages.forEach { (shape, source) ->
            assertTrue(
                "Private-message prefetch guard must recognize the $shape shape",
                PRIVATE_MESSAGE_PREFETCH_USAGE.containsMatchIn(source),
            )
        }
    }

    private companion object {
        const val ANONYMOUS_CLIENT_PACKAGE =
            "fr.forumhfr.redface2.core.network.qualifiers"
        const val ANONYMOUS_CLIENT_QUALIFIER =
            "$ANONYMOUS_CLIENT_PACKAGE.AnonymousClient"
        const val FEATURE_PROFILE_PACKAGE =
            "fr.forumhfr.redface2.feature.profile."
        const val MESSAGES_REPOSITORY_PATH =
            "/core/domain/src/main/kotlin/fr/forumhfr/redface2/core/domain/messages/" +
                "MessagesRepository.kt"
        const val DEFAULT_MESSAGES_REPOSITORY_PATH =
            "/core/data/src/main/kotlin/fr/forumhfr/redface2/core/data/messages/" +
                "DefaultMessagesRepository.kt"
        const val PRIVATE_MESSAGE_THREAD_VIEW_MODEL_PATH =
            "/feature/messages/src/main/kotlin/fr/forumhfr/redface2/feature/messages/" +
                "PrivateMessageThreadViewModel.kt"
        const val PRIVATE_MESSAGE_DISK_FACADE_PATH =
            "/core/data/src/main/kotlin/fr/forumhfr/redface2/core/data/messages/" +
                "PrivateMessageThreadDiskCache.kt"
        const val PRIVATE_MESSAGE_CONTENT_DAO_PATH =
            "/core/database/src/main/kotlin/fr/forumhfr/redface2/core/database/dao/" +
                "PrivateMessageContentDao.kt"
        const val REDFACE_DATABASE_PATH =
            "/core/database/src/main/kotlin/fr/forumhfr/redface2/core/database/RedfaceDatabase.kt"
        const val DATABASE_MODULE_PATH =
            "/core/database/src/main/kotlin/fr/forumhfr/redface2/core/database/di/DatabaseModule.kt"
        const val DATABASE_MIGRATIONS_PATH =
            "/core/database/src/main/kotlin/fr/forumhfr/redface2/core/database/migrations/Migrations.kt"
        const val PRIVATE_MESSAGE_PAGE_ENTITY_PATH =
            "/core/database/src/main/kotlin/fr/forumhfr/redface2/core/database/entities/" +
                "PrivateMessageThreadPageEntity.kt"
        const val PRIVATE_MESSAGE_ENTITY_PATH =
            "/core/database/src/main/kotlin/fr/forumhfr/redface2/core/database/entities/" +
                "PrivateMessageEntity.kt"
        val PRIVATE_MESSAGE_CONTENT_DATABASE_ALLOWED_PATHS = setOf(
            PRIVATE_MESSAGE_CONTENT_DAO_PATH,
            REDFACE_DATABASE_PATH,
            DATABASE_MODULE_PATH,
            DATABASE_MIGRATIONS_PATH,
            PRIVATE_MESSAGE_PAGE_ENTITY_PATH,
            PRIVATE_MESSAGE_ENTITY_PATH,
            PRIVATE_MESSAGE_DISK_FACADE_PATH,
        )
        val PRIVATE_MESSAGE_CONTENT_DATABASE_USAGE = Regex(
            """\b(?:PrivateMessageContentDao|privateMessageContentDao|mp_thread_pages|mp_messages)\b""",
        )
        val PRIVATE_MESSAGE_FACADE_FORBIDDEN_USAGE = listOf(
            Regex("""\b(?:android\.util\.)?Log\s*\."""),
            Regex("""\bDiagnosticsLog\b"""),
            Regex("""\b(?:kotlin\.io\.)?print(?:ln)?\s*\("""),
            Regex("""\b(?:java\.lang\.)?System\s*\.\s*(?:out|err)\b"""),
            Regex("""\bjava\.util\.logging\b"""),
            Regex("""\b(?:java\.util\.logging\.)?Logger\s*\."""),
            Regex("""\bprintStackTrace\s*\("""),
        )
        val PRIVATE_MESSAGE_PREFETCH_ALLOWED_PATHS = setOf(
            MESSAGES_REPOSITORY_PATH,
            DEFAULT_MESSAGES_REPOSITORY_PATH,
            PRIVATE_MESSAGE_THREAD_VIEW_MODEL_PATH,
        )
        val PRIVATE_MESSAGE_PREFETCH_USAGE =
            Regex("""\bprefetchPrivateMessageThread\b""")
        val AUTH_DIR_TOKENS = listOf("/auth/", "/messages/")
        val PRIVATE_CACHE_FORBIDDEN_IMPORT_PREFIXES = listOf(
            "android.util.Log",
            "androidx.datastore.",
            "androidx.room.",
            "fr.forumhfr.redface2.core.database.",
            "fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog",
            "java.lang.System",
            "java.util.logging.",
            "kotlin.io.print",
        )
        val PRIVATE_CACHE_FORBIDDEN_TEXT = listOf(
            "android.util.Log",
            "androidx.datastore.",
            "androidx.room.",
            "Dao",
            "DiagnosticsLog",
        )
        val PRIVATE_CACHE_FORBIDDEN_USAGE = listOf(
            Regex("""\b(?:kotlin\.io\.)?print(?:ln)?\s*\("""),
            Regex("""\b(?:java\.lang\.)?System\s*\.\s*(?:out|err)\b"""),
            Regex("""\bjava\.util\.logging\b"""),
            Regex("""\bprintStackTrace\s*\("""),
        )
    }
}
