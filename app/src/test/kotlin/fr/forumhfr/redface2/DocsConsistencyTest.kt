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
    fun `login route is documented as delivered once feature auth exists`() {
        val productionNavigation = doc("app/src/main/kotlin/fr/forumhfr/redface2/navigation/RedfaceNavigation.kt")
        val docsText = docsText()
        val docsLower = docsText.lowercase()

        assertTrue(
            "Production navigation must expose the delivered login route.",
            productionNavigation.contains("LoginRoute"),
        )
        assertTrue(
            "Docs must document that the login route is now delivered by :feature:auth.",
            docsText.contains("route de login livrée par `:feature:auth`"),
        )
        assertFalse(
            "Docs must not keep the obsolete pre-1B.1 claim that no auth route exists.",
            docsLower.contains("aucune `authroute` n'existe encore"),
        )
    }

    // Guard B of #1045 (reading-parity maintenance rule, symbol coupling). Lives here rather than
    // in the bash `repo-guards` job because it needs no PR context (property of the tree, not of a
    // diff) and this test also runs locally via `/validate`, so a stale citation fails before the
    // push, not fifteen minutes later in CI. It checks that cited symbols are really DEFINED —
    // comments and documentation echoes never count, so a citation cannot validate itself against
    // prose (not even against this file's own comments). It never checks row counts: the matrix
    // is meant to grow (#1045 is explicit about this).
    @Test
    fun `reading parity cited symbols still exist in the source tree`() {
        val pagePath = "docs/specs/reading-parity.md"
        val cited = readingParityCitedSymbols(doc(pagePath).lines())

        assertTrue(
            "Guard B (#1045) extracted no symbol from $pagePath: the page structure changed under " +
                "the guard (\"## Matrice\" section, Réf. column, or \"## Anomalies\" section). " +
                "Update readingParityCitedSymbols() together with the page, in the same PR.",
            cited.isNotEmpty(),
        )

        val index = sourceTreeIndex()
        val alive = HashMap<List<String>, Boolean>()
        val missing = cited.filterNot { symbol ->
            alive.getOrPut(symbol.parts) { index.defines(symbol.parts) }
        }

        assertTrue(
            buildString {
                append("Guard B (#1045): every symbol cited by $pagePath (Réf. column of the ")
                append("matrix + Anomalies section) must still be DEFINED in the source tree.")
                missing.forEach { symbol ->
                    append("\n  $pagePath:${symbol.line}: `${symbol.token}` — no single */src/** ")
                    append("file defines all of ${symbol.parts}. Counted as a definition: a ")
                    append("Kotlin/Java declaration, a typed name (`x:`), an import, an exact ")
                    append("word in a string literal (outside annotation arguments), a res XML ")
                    append("name=\"…\", or a source file named after the part. Comments never ")
                    append("count, test files only vouch for `*Test` symbols, and a qualified ")
                    append("member needs a declaration or typed name in the same file.")
                }
                append("\nFix: update the citation to the renamed symbol, or move the line per the ")
                append("page's maintenance rule (§ Règle d'entretien). Do not weaken this guard.")
            },
            missing.isEmpty(),
        )
    }

    // Regression demanded by the #1045 gate: a name surviving only in a comment must be dead for
    // guard B — the guard's own comments (or any KDoc quoting matrix symbols) must not be able to
    // self-validate a citation after the real code is gone.
    @Test
    fun `guard B index never counts comments as definitions`() {
        val lexed = lexSource(
            """
            // ghostSymbol lives only here, and GhostClass.ghostMember too
            /* val ghostVal = 1
               fun ghostFun() = Unit */
            val url = "https://example.com//still-code-after-this-string"
            fun realFun(realParam: Int) = realParam
            foo(namedArgOnly = 1)
            """.trimIndent(),
        )
        val file = IndexedFile("SomethingElse", isXml = false, isTest = false, lexed.code, lexed.literals)

        listOf("ghostSymbol", "GhostClass", "ghostMember", "ghostVal", "ghostFun").forEach { ghost ->
            assertFalse(
                "'$ghost' survives only in a comment and must not count as a definition",
                file.defines(ghost, PartMatchers(ghost)),
            )
        }
        // The `//` inside a string literal is not a comment: code after it stays indexed.
        assertTrue(file.defines("realFun", PartMatchers("realFun")))
        // A typed name (parameter/property) is a definition.
        assertTrue(file.defines("realParam", PartMatchers("realParam")))
        // A named-argument USAGE alone is not a definition.
        assertFalse(
            "a named-argument usage alone must not count as a definition",
            file.defines("namedArgOnly", PartMatchers("namedArgOnly")),
        )
    }

    // Regressions demanded by the second #1045 gate: annotation-argument literals, test-file
    // lookalikes and import+literal combinations must not keep a citation alive.
    @Test
    fun `guard B index rejects annotation literals, test helpers and undeclared members`() {
        val mainLexed = lexSource(
            """
            import sample.Post
            @Suppress("DeadSymbolA")
            @DisplayName("DeadSymbolB")
            @kotlin.Suppress("DeadQualifiedA")
            @field:kotlin.Suppress("DeadQualifiedB")
            class Holder(val keep: Int) {
                @Suppress("postIndex")
                fun open() = dataStoreFile("mp_read_positions_demo")
            }
            """.trimIndent(),
        )
        val mainFile = IndexedFile("Holder", isXml = false, isTest = false, mainLexed.code, mainLexed.literals)
        val testLexed = lexSource(
            """
            fun DeadSymbolC() = Unit
            class RealParserTest
            """.trimIndent(),
        )
        val testFile = IndexedFile("HelpersTest", isXml = false, isTest = true, testLexed.code, testLexed.literals)
        val index = DefinitionIndex(listOf(mainFile, testFile))

        assertFalse(
            "an @Suppress argument is invisible semantics, not a definition",
            index.defines(listOf("DeadSymbolA")),
        )
        assertFalse(
            "an @DisplayName argument is display prose, not a definition",
            index.defines(listOf("DeadSymbolB")),
        )
        assertFalse(
            "a QUALIFIED annotation's argument (@kotlin.Suppress) is not a definition either",
            index.defines(listOf("DeadQualifiedA")),
        )
        assertFalse(
            "a use-site target followed by a qualified name (@field:kotlin.Suppress) is covered too",
            index.defines(listOf("DeadQualifiedB")),
        )
        assertFalse(
            "a helper declared in a test file must not vouch for a production symbol",
            index.defines(listOf("DeadSymbolC")),
        )
        assertFalse(
            "a qualified member needs a declaration or typed name — import + stray literal is not enough",
            index.defines(listOf("Post", "postIndex")),
        )
        assertTrue(
            "a code literal outside annotations still defines an external name",
            index.defines(listOf("mp_read_positions_demo")),
        )
        assertTrue(
            "a test file may vouch for a symbol that is itself a test",
            index.defines(listOf("RealParserTest")),
        )
    }

    @Test
    fun `guard B index keeps nested string templates out of code`() {
        val lexed = lexSource(
            """
            import sample.Auth
            val route = "auth:${'$'}{auth.userId ?: "pseudo:${'$'}{auth.pseudo}"}"
            fun realFun() = Unit
            """.trimIndent(),
        )
        val file = IndexedFile("Route", isXml = false, isTest = false, lexed.code, lexed.literals)
        val index = DefinitionIndex(listOf(file))

        assertFalse(
            "the nested literal's `pseudo:` must not leak into code as a typed declaration",
            file.declaresStrongly("pseudo", PartMatchers("pseudo")),
        )
        assertFalse(
            "an imported head plus the leaked `pseudo:` must not keep a qualified citation alive",
            index.defines(listOf("Auth", "pseudo")),
        )
        assertTrue(
            "the nested string content must remain indexed as a literal",
            file.defines("pseudo", PartMatchers("pseudo")),
        )
        assertTrue(
            "code after the outer string must remain indexed",
            file.defines("realFun", PartMatchers("realFun")),
        )
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

    /**
     * Symbols cited by the reading-parity page: every backticked token of the Réf. column inside
     * the "## Matrice" table, plus every backticked token of the "## Anomalies …" section — its
     * entries are former matrix rows meant to return to the table, so their references rot the
     * same way (#1045). Prose tokens that are not identifiers (`cat=prive`, `[quotemsg=…]`) and
     * language literals (`null`) are skipped by construction.
     */
    private fun readingParityCitedSymbols(lines: List<String>): List<CitedSymbol> {
        val cited = mutableListOf<CitedSymbol>()
        var section = ""
        lines.forEachIndexed { index, raw ->
            if (raw.startsWith("## ")) {
                section = raw.removePrefix("## ").trim()
                return@forEachIndexed
            }
            val citedText = when {
                section == "Matrice" -> refColumnOrNull(raw) ?: return@forEachIndexed
                section.startsWith("Anomalies") -> raw
                else -> return@forEachIndexed
            }
            BACKTICKED.findAll(citedText).forEach { match ->
                val token = match.groupValues[1]
                val parts = symbolParts(token)
                if (parts.isNotEmpty()) {
                    cited += CitedSymbol(line = index + 1, token = token, parts = parts)
                }
            }
        }
        return cited
    }

    /** The Réf. cell of a matrix data row, or null for non-table lines, the header and the rule. */
    private fun refColumnOrNull(raw: String): String? {
        val trimmed = raw.trim()
        val cells = if (trimmed.startsWith("|")) trimmed.split("|") else emptyList()
        if (cells.size < REF_COLUMN_MIN_CELLS) return null
        val ref = cells[REF_COLUMN_INDEX].trim()
        val headerOrRule = ref == "Réf." || ref.all { it == '-' || it == ':' }
        return ref.takeUnless { headerOrRule }
    }

    /**
     * Identifier parts of a cited token: strips a trailing parenthesized qualifier
     * (`PostCardShell(flat)`) and a leading annotation `@`, splits dotted names
     * (`Post.postIndex`), and rejects the whole token when any part is not an identifier.
     */
    private fun symbolParts(token: String): List<String> {
        val cleaned = token.trim().replace(TRAILING_PARENS, "").removePrefix("@")
        val parts = cleaned.split(".")
        val isSymbol = cleaned.isNotEmpty() && parts.all(IDENTIFIER::matches)
        return if (isSymbol) parts.filterNot(LITERAL_STOPLIST::contains) else emptyList()
    }

    /**
     * Per-file definition index. A cited symbol part counts as alive only through a REAL
     * definition, never a comment or a documentation echo:
     * - a Kotlin/Java declaration (`class|interface|object|fun|val|var|typealias`, extension
     *   receivers and generic prefixes allowed),
     * - a typed name (`x:` — parameters and properties, the way UI callbacks are defined),
     * - an import ending in the name (external APIs the code still uses),
     * - an exact word inside a string literal OUTSIDE annotation arguments (externally-defined
     *   names: Room tables, migration SQL, HFR form fields — but never `@Suppress`-style echoes),
     * - a res XML `name="…"` resource,
     * - or, as an exact-basename fallback, a source file named after the part.
     * Comments are stripped and string literals are indexed apart from code, so a name that
     * survives only in a comment is dead. Scoping rules (#1045 gate 2): a TEST file may only
     * vouch for a part that is itself a test (ends in `Test`), and a qualified symbol needs all
     * its parts alive in the SAME file with its MEMBER part backed by a declaration or a typed
     * name — an import plus a stray literal cannot keep `Post.postIndex` alive.
     */
    private class DefinitionIndex(private val files: List<IndexedFile>) {
        private val matchers = HashMap<String, PartMatchers>()

        fun defines(parts: List<String>): Boolean {
            val partMatchers = parts.map { matchers.getOrPut(it) { PartMatchers(it) } }
            return files.any { file -> file.definesAll(parts, partMatchers) }
        }
    }

    private class IndexedFile(
        val baseName: String,
        val isXml: Boolean,
        val isTest: Boolean,
        val code: String,
        val literals: String,
    ) {
        fun definesAll(parts: List<String>, matchers: List<PartMatchers>): Boolean {
            if (!parts.all { mayVouchFor(it) }) return false
            val headCount = if (parts.size == 1) 1 else parts.size - 1
            val headsAlive = (0 until headCount).all { defines(parts[it], matchers[it]) }
            val memberAlive = parts.size == 1 || declaresStrongly(parts.last(), matchers.last())
            return headsAlive && memberAlive
        }

        /** Any definition category — single-part symbols and the head parts of qualified ones. */
        fun defines(part: String, matchers: PartMatchers): Boolean = when {
            baseName == part -> true
            isXml -> matchers.xmlResource.containsMatchIn(code)
            part !in code && part !in literals -> false
            else -> declaresStrongly(part, matchers) ||
                matchers.importedName.containsMatchIn(code) ||
                matchers.literalWord.containsMatchIn(literals)
        }

        /** Declaration or typed name only — the bar a qualified symbol's member part must clear. */
        fun declaresStrongly(part: String, matchers: PartMatchers): Boolean =
            !isXml && part in code &&
                (matchers.declaration.containsMatchIn(code) || matchers.typedName.containsMatchIn(code))

        /** A test file may only vouch for symbols that are themselves tests. */
        private fun mayVouchFor(part: String): Boolean = !isTest || part.endsWith("Test")
    }

    private class PartMatchers(part: String) {
        private val quoted = Regex.escape(part)
        val declaration = Regex(
            "\\b(?:class|interface|object|fun|val|var|typealias)\\s+" +
                "(?:<[^>]*>\\s+)?(?:[A-Za-z_][A-Za-z0-9_]*\\.)*$quoted\\b",
        )
        val typedName = Regex("\\b$quoted\\s*:")
        val importedName = Regex("\\bimport\\s+[A-Za-z_][A-Za-z0-9_.]*\\.$quoted\\b")
        val literalWord = Regex("\\b$quoted\\b")
        val xmlResource = Regex("name\\s*=\\s*\"$quoted\"")
    }

    private class LexedSource(val code: String, val literals: String)

    /**
     * Splits Kotlin/Java source into comment-free code and string-literal contents. Lexical only:
     * line/block comments (nesting handled) are dropped, `"…"`/`"""…"""` contents move to
     * [LexedSource.literals], char literals are dropped. A `//` inside a string is NOT a comment.
     * Literals inside ANNOTATION ARGUMENTS (`@Suppress("…")`, qualified `@kotlin.Suppress("…")`,
     * use-site `@field:X("…")` and combinations) are dropped entirely: they name rules and
     * display strings, not domain symbols (#1045 gates 2 and 3).
     * Known imprecisions, all fail-closed (they only ever drop content, never leak a comment): a
     * labeled expression directly followed by parentheses is treated as an annotation; and the
     * `()` of a function type right after a qualified annotation
     * (`@androidx.compose.runtime.Composable () -> Unit`) briefly arms the annotation state —
     * harmless, since a type carries no literals.
     */
    private fun lexSource(text: String): LexedSource = SourceLexer(text).lex()

    /**
     * The scanner behind [lexSource], split along its three responsibilities: comment
     * recognition, literal recognition, and the annotation-argument state machine. Each pass of
     * [lex] consumes exactly one construct and returns its length.
     */
    private class SourceLexer(private val text: String) {
        private val code = StringBuilder()
        private val literals = StringBuilder()
        private var annotationDepth = 0
        private var pendingAnnotationArgs = false

        fun lex(): LexedSource {
            var i = 0
            while (i < text.length) {
                i += consumeCommentAt(i) ?: consumeLiteralAt(i) ?: consumeCodeCharAt(i)
            }
            return LexedSource(code.toString(), literals.toString())
        }

        /** `//` and (nested) block comments become a single space in the code stream. */
        private fun consumeCommentAt(i: Int): Int? = when {
            text.startsWith("//", i) -> lineCommentLength(i)
            text.startsWith("/*", i) -> blockCommentLength(i)
            else -> null
        }?.also { code.append(' ') }

        /** Strings and chars; contents are indexed unless inside annotation arguments. */
        private fun consumeLiteralAt(i: Int): Int? {
            val sink = if (annotationDepth > 0) null else literals
            return when {
                text.startsWith("\"\"\"", i) -> rawStringLength(i, sink)
                text[i] == '"' -> quotedLength(i, sink)
                text[i] == '\'' -> quotedLength(i, null)
                else -> null
            }?.also { code.append(' ') }
        }

        /** One ordinary code char (or `@Name` prefix), driving the annotation state machine. */
        private fun consumeCodeCharAt(i: Int): Int {
            val c = text[i]
            return when {
                c == '@' && annotationDepth == 0 -> {
                    val end = annotationNameEnd(i)
                    code.append(text, i, end)
                    pendingAnnotationArgs = true
                    end - i
                }
                c == '(' && (annotationDepth > 0 || pendingAnnotationArgs) -> {
                    annotationDepth++
                    pendingAnnotationArgs = false
                    code.append(c)
                    1
                }
                c == ')' && annotationDepth > 0 -> {
                    annotationDepth--
                    code.append(c)
                    1
                }
                else -> {
                    if (pendingAnnotationArgs && c != ' ' && c != '\t') pendingAnnotationArgs = false
                    code.append(c)
                    1
                }
            }
        }

        /**
         * End of `@Name`, `@target:Name` or their dotted-qualified forms (`@kotlin.Suppress`,
         * `@field:kotlin.Suppress`), starting at [start], which holds the `@`. Stopping at the
         * first dot would let a qualified annotation's arguments slip past the literal drop —
         * the `.` used to disarm the pending state before the `(` (#1045, third gate). The `::`
         * guard keeps callable references (`this@Outer::method`) out of the use-site branch.
         */
        private fun annotationNameEnd(start: Int): Int {
            var i = qualifiedNameEnd(start + 1)
            if (i < text.length && text[i] == ':' && text.getOrNull(i + 1) != ':') {
                i = qualifiedNameEnd(i + 1)
            }
            return i
        }

        /** End of `Ident(.Ident)*` at [start]; returns [start] when no identifier is there. */
        private fun qualifiedNameEnd(start: Int): Int {
            var i = identifierEnd(start)
            while (i < text.length && text[i] == '.' && isIdentifierStart(text.getOrNull(i + 1))) {
                i = identifierEnd(i + 1)
            }
            return i
        }

        private fun identifierEnd(start: Int): Int {
            var i = start
            while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
            return i
        }

        private fun isIdentifierStart(c: Char?): Boolean = c != null && (c.isLetter() || c == '_')

        /** Length up to (not including) the newline, which stays in the code stream. */
        private fun lineCommentLength(start: Int): Int {
            val end = text.indexOf('\n', start)
            return (if (end == -1) text.length else end) - start
        }

        private fun blockCommentLength(start: Int): Int {
            var depth = 0
            var i = start
            while (i < text.length) {
                when {
                    text.startsWith("/*", i) -> {
                        depth++
                        i += 2
                    }
                    text.startsWith("*/", i) -> {
                        depth--
                        i += 2
                        if (depth == 0) return i - start
                    }
                    else -> i++
                }
            }
            return text.length - start
        }

        private fun rawStringLength(start: Int, sink: StringBuilder?): Int {
            val closing = text.indexOf("\"\"\"", start + 3)
            val end = if (closing == -1) text.length else closing + 3
            sink?.append(text, start + 3, maxOf(start + 3, end - 3))?.append('\n')
            return end - start
        }

        /**
         * `"…"` (contents indexed as literal) or `'…'` (dropped); stops at an unescaped newline.
         * A `${'$'}{…}` expression is consumed through its matching brace so quoted strings and
         * nested templates inside it cannot close the outer literal early.
         */
        private fun quotedLength(start: Int, sink: StringBuilder?): Int {
            val quote = text[start]
            var i = start + 1
            while (i < text.length && text[i] != '\n') {
                when {
                    text[i] == '\\' -> {
                        i++
                        if (i < text.length) {
                            sink?.append(text[i])
                            i++
                        }
                    }
                    text[i] == quote -> break
                    quote == '"' && text.startsWith("\${", i) -> {
                        sink?.append("\${")
                        i += 2 + templateExpressionLength(i + 2, sink)
                    }
                    else -> {
                        sink?.append(text[i])
                        i++
                    }
                }
            }
            sink?.append('\n')
            return (if (i < text.length && text[i] == quote) i + 1 else i) - start
        }

        /** Length of a `${'$'}{…}` body, including its matching `}`, from just after the opener. */
        private fun templateExpressionLength(start: Int, sink: StringBuilder?): Int {
            var depth = 1
            var i = start
            while (i < text.length && text[i] != '\n' && depth > 0) {
                val nestedLength = templateNestedConstructLength(i, sink)
                if (nestedLength != null) {
                    i += nestedLength
                } else {
                    depth += templateBraceDepthDelta(text[i])
                    sink?.append(text[i])
                    i++
                }
            }
            return i - start
        }

        private fun templateNestedConstructLength(start: Int, sink: StringBuilder?): Int? = when {
            text.startsWith("//", start) -> lineCommentLength(start)
            text.startsWith("/*", start) -> blockCommentLength(start)
            text.startsWith("\"\"\"", start) -> rawStringLength(start, sink)
            text[start] == '"' -> quotedLength(start, sink)
            text[start] == '\'' -> quotedLength(start, null)
            else -> null
        }

        private fun templateBraceDepthDelta(char: Char): Int = when (char) {
            '{' -> 1
            '}' -> -1
            else -> 0
        }
    }

    private fun sourceTreeIndex(): DefinitionIndex {
        val files = Files.walk(root).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .filter { path ->
                    val relative = root.relativize(path)
                    val segments = (0 until relative.nameCount).map { relative.getName(it).toString() }
                    "src" in segments &&
                        "build" !in segments &&
                        path.fileName.toString().substringAfterLast('.', "") in SOURCE_EXTENSIONS
                }
                .toList()
        }
        return DefinitionIndex(
            files.map { path ->
                val baseName = path.fileName.toString().substringBeforeLast('.')
                val text = path.readText()
                // The source set is the path segment right after `src`: `test`, `testDebug` and
                // `androidTest` files may only vouch for `*Test` symbols; `main`, `debug` and
                // `release` are production-like (debug tooling such as DebugFlatPostShell ships).
                val segments = root.relativize(path).let { rel ->
                    (0 until rel.nameCount).map { rel.getName(it).toString() }
                }
                val sourceSet = segments.getOrNull(segments.indexOf("src") + 1).orEmpty()
                val isTest = sourceSet.lowercase().contains("test")
                if (path.fileName.toString().endsWith(".xml")) {
                    val commentFree = XML_COMMENT.replace(text, " ")
                    IndexedFile(baseName, isXml = true, isTest = isTest, code = commentFree, literals = "")
                } else {
                    val lexed = lexSource(text)
                    IndexedFile(baseName, isXml = false, isTest = isTest, code = lexed.code, literals = lexed.literals)
                }
            },
        )
    }

    private data class CitedSymbol(
        val line: Int,
        val token: String,
        val parts: List<String>,
    )

    private fun findRepoRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (true) {
            if (current.resolve("settings.gradle.kts").exists() && current.resolve("docs").isDirectory()) {
                return current
            }
            current = current.parent ?: error("Unable to find repo root from ${System.getProperty("user.dir")}")
        }
    }

    private companion object {
        val BACKTICKED = Regex("`([^`]+)`")
        val XML_COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val TRAILING_PARENS = Regex("\\(.*\\)$")
        val LITERAL_STOPLIST = setOf("null", "true", "false")
        val SOURCE_EXTENSIONS = setOf("kt", "kts", "java", "xml")
        const val REF_COLUMN_INDEX = 2
        const val REF_COLUMN_MIN_CELLS = 4
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
