package fr.forumhfr.redface2

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocsConsistencyTest {
    private val root: Path = findRepoRoot()

    @Test
    fun `docs do not reference obsolete Navigation 3 APIs as current guidance`() {
        val docsLines = docsMarkdownFiles().flatMap { file ->
            file.readText().lines().mapIndexed { index, line ->
                DocLine(file = root.relativize(file).toString(), number = index + 1, text = line)
            }
        }
        val forbiddenApis = listOf(
            "rememberDecoratedNavEntries",
            "rememberSceneState",
            "NavigationBackHandler",
            "SinglePaneSceneStrategy",
        )

        forbiddenApis.forEach { api ->
            val suspiciousLines = docsLines
                .filter { line -> line.text.contains(api) }
                .filterNot(DocLine::isAllowedObsoleteApiMention)

            assertTrue(
                buildString {
                    append("Documentation must not use obsolete Navigation 3 API as current guidance: $api")
                    suspiciousLines.forEach { line ->
                        append("\n")
                        append(line.file)
                        append(":")
                        append(line.number)
                        append(": ")
                        append(line.text)
                    }
                },
                suspiciousLines.isEmpty(),
            )
        }
    }

    @Test
    fun `editor Phase 2 split is documented without reviving NewTopic as current EditorMode`() {
        val mvi = doc("docs/specs/mvi.md")
        val changelog = doc("CHANGELOG.md")

        assertTrue(mvi.contains("PostEditorMode { Reply, Edit }"))
        assertTrue(mvi.contains("TopicFormMode { New, EditFirstPost }"))
        assertTrue(changelog.contains("TopicFormMode { New, EditFirstPost }"))
        assertFalse(
            "Do not describe NewTopic as a finalized current EditorMode decision.",
            changelog.contains("création de topic est un écran distinct, pas un mode de l'éditeur"),
        )
    }

    @Test
    fun `BBCode parsing responsibility stays out of core domain docs`() {
        val mvi = doc("docs/specs/mvi.md")
        val architecture = doc("docs/specs/architecture.md")

        assertTrue(architecture.contains("fun parsePostContentFromBbcode(bbcode: String): PostContent"))
        assertTrue(mvi.contains("responsabilité `:core:parser` (`parsePostContentFromBbcode`)"))
        assertFalse(
            "parsePostContentFromBbcode must not be documented as a :core:domain use case.",
            mvi.contains("use cases `:core:domain` (`parsePostContentFromBbcode`"),
        )
    }

    @Test
    fun `feature dependency exception for core extension is explicit`() {
        val architecture = doc("docs/specs/architecture.md")

        assertTrue(architecture.contains("Exception volontaire"))
        assertTrue(architecture.contains("`:feature:topic` et `:feature:editor`"))
        assertTrue(architecture.contains("`:core:extension`"))
    }

    @Test
    fun `future auth route is not documented as an existing route`() {
        val productionNavigation = doc("app/src/main/kotlin/fr/forumhfr/redface2/navigation/RedfaceNavigation.kt")
        val docsText = docsText()
        val docsLower = docsText.lowercase()

        if (!productionNavigation.contains("AuthRoute")) {
            assertFalse(
                "Docs must not imply a current AuthRoute exists before :feature:auth is implemented.",
                docsText.contains("route `Auth`"),
            )
            assertTrue(
                "Docs must explicitly state that no AuthRoute exists yet (case-insensitive match across all docs).",
                docsLower.contains("aucune `authroute` n'existe encore"),
            )
        }
    }

    @Test
    fun `feature request construction is attributed to entryProvider not MainActivity`() {
        val contributing = doc("docs/guides/contributing.md")

        assertFalse(contributing.contains("MainActivity` extrait"))
        assertTrue(contributing.contains("construit par le `entryProvider` / `RedfaceNavHost`"))
    }

    private fun docsText(): String =
        docsMarkdownFiles()
            .map { it.readText() }
            .joinToString("\n")

    private fun docsMarkdownFiles(): List<Path> {
        val rootDocs = listOf("AGENTS.md", "CHANGELOG.md", "README.md")
            .map(root::resolve)
            .filter(Files::isRegularFile)
        val siteDocs = Files.walk(root.resolve("docs"))
            .use { paths ->
                paths
                    .filter { path ->
                        Files.isRegularFile(path) &&
                            path.fileName.toString().endsWith(".md")
                    }
                    .sorted()
                    .toList()
            }
        return rootDocs + siteDocs
    }

    private fun doc(relativePath: String): String = root.resolve(relativePath).readText()

    private fun findRepoRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (true) {
            if (current.resolve("settings.gradle.kts").exists() && current.resolve("docs").isDirectory()) {
                return current
            }
            current = current.parent ?: error("Unable to find repo root from ${System.getProperty("user.dir")}")
        }
    }

    private data class DocLine(
        val file: String,
        val number: Int,
        val text: String,
    ) {
        fun isAllowedObsoleteApiMention(): Boolean {
            val lower = text.lowercase()
            return listOf(
                "pas besoin",
                "antérieure",
                "ancienne",
                "obsolète",
                "n'existe pas",
                "corrigé",
                "corrigée",
                "corrigés",
                "interdit",
            ).any(lower::contains)
        }
    }
}
