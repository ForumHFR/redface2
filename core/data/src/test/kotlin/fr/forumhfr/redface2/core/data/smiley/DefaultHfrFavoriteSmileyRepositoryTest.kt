package fr.forumhfr.redface2.core.data.smiley

import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.model.HfrFavoriteSmileyMutationResult
import fr.forumhfr.redface2.core.network.HfrFavoriteSmileyClient
import fr.forumhfr.redface2.core.parser.smiley.HfrFavoriteSmileyFormParser
import fr.forumhfr.redface2.core.parser.smiley.HfrFavoriteSmileyResponseParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultHfrFavoriteSmileyRepositoryTest {
    private val client = mockk<HfrFavoriteSmileyClient>()
    private val auth = FakeAuthRepository()
    private val diagnostics = DiagnosticsLog()
    private val repository = DefaultHfrFavoriteSmileyRepository(
        client,
        HfrFavoriteSmileyFormParser(),
        HfrFavoriteSmileyResponseParser(),
        auth,
        diagnostics,
        Dispatchers.Unconfined,
    )

    @Test
    fun `add returns the confirmed server order and images rather than a local append`() = runTest {
        coEvery { client.getFavoriteSmileyForm() } returnsMany listOf(
            form("[:un]"), form("[:tiers]", "[:un]", "[:deux:2]"),
        )
        coEvery { client.addFavoriteSmiley(HASH_CHECK, "[:deux:2]") } returns hop("Ajout effectué avec succès !")

        assertEquals(
            HfrFavoriteSmileyMutationResult.Success(favorites("[:tiers]", "[:un]", "[:deux:2]")),
            repository.add("tester", "[:deux:2]"),
        )
        coVerify(exactly = 2) { client.getFavoriteSmileyForm() }
        coVerify(exactly = 1) { client.addFavoriteSmiley(HASH_CHECK, "[:deux:2]") }
    }

    @Test
    fun `concurrent add waits for removal to finish its initial read POST and confirmation`() = runTest {
        assertMutationsSerialized(removalFirst = true)
    }

    @Test
    fun `concurrent removal waits for add to finish its initial read POST and confirmation`() = runTest {
        assertMutationsSerialized(removalFirst = false)
    }

    @Test
    fun `queued add and removal keep the gesture owner after waiting for the mutex`() = runTest {
        listOf(false, true).forEach { removal ->
            auth.state.value = AuthState.Authenticated("Tester", 1)
            val releaseRead = CompletableDeferred<Unit>()
            val requestOwners = mutableListOf<AuthState>()
            coEvery { client.getFavoriteSmileyForm() } coAnswers {
                requestOwners += auth.state.value
                releaseRead.await()
                form("[:un]")
            }
            val first = async(start = CoroutineStart.UNDISPATCHED) { repository.add("tester", "[:deux]") }
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                if (removal) repository.remove("tester", "[:un]") else repository.add("tester", "[:trois]")
            }
            assertEquals(listOf(AuthState.Authenticated("Tester", 1)), requestOwners)
            assertFalse(first.isCompleted)
            assertFalse(queued.isCompleted)
            auth.state.value = AuthState.Authenticated("Other", 2)
            releaseRead.complete(Unit)

            assertEquals(HfrFavoriteSmileyMutationResult.AccountChanged, first.await())
            assertEquals(HfrFavoriteSmileyMutationResult.AccountChanged, queued.await())
            assertEquals(listOf(AuthState.Authenticated("Tester", 1)), requestOwners)
        }
        coVerify(exactly = 2) { client.getFavoriteSmileyForm() }
        coVerify(exactly = 0) { client.addFavoriteSmiley(any(), any()) }
        coVerify(exactly = 0) { client.removeFavoriteSmileys(any(), any(), any(), any()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `dispatcher wait cannot rebind either gesture to the next account`() = runTest {
        val delayedRepository = DefaultHfrFavoriteSmileyRepository(
            client, HfrFavoriteSmileyFormParser(), HfrFavoriteSmileyResponseParser(),
            auth, diagnostics, StandardTestDispatcher(testScheduler),
        )
        listOf(false, true).forEach { removal ->
            auth.state.value = AuthState.Authenticated("Tester", 1)
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                if (removal) delayedRepository.remove("tester", "[:un]")
                else delayedRepository.add("tester", "[:deux]")
            }
            assertFalse(queued.isCompleted)
            auth.state.value = AuthState.Authenticated("Other", 2)
            runCurrent()
            assertEquals(HfrFavoriteSmileyMutationResult.AccountChanged, queued.await())
        }
        coVerify(exactly = 0) { client.getFavoriteSmileyForm() }
        coVerify(exactly = 0) { client.addFavoriteSmiley(any(), any()) }
        coVerify(exactly = 0) { client.removeFavoriteSmileys(any(), any(), any(), any()) }
    }

    @Test
    fun `duplicate and full are short circuited and duplicate uses exact token even when full`() = runTest {
        val tokens = (0 until 10).map { "[:nom:$it]" }.toTypedArray()
        coEvery { client.getFavoriteSmileyForm() } returns form(*tokens)

        assertEquals(
            HfrFavoriteSmileyMutationResult.AlreadyPresent(favorites(*tokens)),
            repository.add("tester", tokens[0]),
        )
        assertEquals(
            HfrFavoriteSmileyMutationResult.Full(10, favorites(*tokens)), repository.add("tester", "[:nouveau]"),
        )
        coVerify(exactly = 0) { client.addFavoriteSmiley(any(), any()) }
    }

    @Test
    fun `invalid add tokens and empty removal never access auth or network`() = runTest {
        listOf("", "aaa", ":o", ":pt1cable:", "[:]", "[: ]", "[:un]\n", "[:un\ndeux]", "[:[un]]").forEach { token ->
            assertEquals(HfrFavoriteSmileyMutationResult.InvalidToken, repository.add("tester", token))
        }
        assertEquals(HfrFavoriteSmileyMutationResult.InvalidToken, repository.remove("tester", ""))
        assertEquals(0, auth.reads)
        coVerify(exactly = 0) { client.getFavoriteSmileyForm() }
    }

    @Test
    fun `wiki names keep spaces accents apostrophes punctuation and internal colons`() = runTest {
        val token = "[:l'été ami_a-b:interne:2]"
        coEvery { client.getFavoriteSmileyForm() } returns form()
        coEvery { client.addFavoriteSmiley(HASH_CHECK, token) } returns hop(
            "Le smiley correspondant à ce code n'existe pas",
        )
        assertEquals(HfrFavoriteSmileyMutationResult.NotFound, repository.add("tester", token))
        coVerify(exactly = 1) { client.addFavoriteSmiley(HASH_CHECK, token) }
    }

    @Test
    fun `server duplicate and full remain authoritative after the precheck`() = runTest {
        coEvery { client.getFavoriteSmileyForm() } returns form("[:un]")
        coEvery { client.addFavoriteSmiley(any(), any()) } returns hop("Ce smilie est déjà dans votre liste !")
        assertEquals(
            HfrFavoriteSmileyMutationResult.AlreadyPresent(favorites("[:un]")),
            repository.add("tester", "[:deux]"),
        )
        coEvery { client.addFavoriteSmiley(any(), any()) } returns hop(
            "Désolé, vous ne pouvez rajouter plus de 25 smilies",
        )
        assertEquals(HfrFavoriteSmileyMutationResult.Full(25, favorites("[:un]")), repository.add("tester", "[:deux]"))
    }

    @Test
    fun `remove targets the current value after a reorder and posts every row including a broken token`() = runTest {
        // Synthetic server list differs from any earlier editor snapshot: target is now in the middle.
        coEvery { client.getFavoriteSmileyForm() } returnsMany listOf(
            form("[:trois]", "[:cible:2]", "aaa", "[:un]"),
            form("[:trois]", "aaa", "[:un]"),
        )
        coEvery { client.removeFavoriteSmileys(any(), any(), any(), any()) } returns hop(
            "Suppression effectuée avec succès !",
        )

        assertEquals(
            HfrFavoriteSmileyMutationResult.Success(favorites("[:trois]", "aaa", "[:un]")),
            repository.remove("tester", "[:cible:2]"),
        )
        coVerify(exactly = 1) {
            client.removeFavoriteSmileys(HASH_CHECK, CODEHEX, listOf("[:trois]", "[:cible:2]", "aaa", "[:un]"), 1)
        }
        coVerify(exactly = 2) { client.getFavoriteSmileyForm() }
    }

    @Test
    fun `absent remove returns fresh favorites without POST even if another variant exists`() = runTest {
        coEvery { client.getFavoriteSmileyForm() } returns form("[:nom:2]")
        assertEquals(
            HfrFavoriteSmileyMutationResult.NotInFavorites(favorites("[:nom:2]")),
            repository.remove("tester", "[:nom]"),
        )
        coVerify(exactly = 1) { client.getFavoriteSmileyForm() }
        coVerify(exactly = 0) { client.removeFavoriteSmileys(any(), any(), any(), any()) }
        coVerify(exactly = 0) { client.addFavoriteSmiley(any(), any()) }
    }

    @Test
    fun `broken token can be removed by exact value while canonical and case variants remain`() = runTest {
        coEvery { client.getFavoriteSmileyForm() } returnsMany listOf(
            form("[:aaa]", "aaa", "AAA"), form("[:aaa]", "AAA"),
        )
        coEvery { client.removeFavoriteSmileys(any(), any(), any(), any()) } returns hop(
            "Suppression effectuée avec succès !",
        )

        assertEquals(
            HfrFavoriteSmileyMutationResult.Success(favorites("[:aaa]", "AAA")), repository.remove("tester", "aaa"),
        )
        coVerify(exactly = 1) {
            client.removeFavoriteSmileys(HASH_CHECK, CODEHEX, listOf("[:aaa]", "aaa", "AAA"), 1)
        }
        coVerify(exactly = 2) { client.getFavoriteSmileyForm() }
    }

    @Test
    fun `nonempty noncanonical removal tokens are not normalized and absent ones need no POST`() = runTest {
        coEvery { client.getFavoriteSmileyForm() } returns form("[:aaa]", "aaa")
        listOf("AAA", " aaa", "aaa ", ":o", " ").forEach { token ->
            assertEquals(
                HfrFavoriteSmileyMutationResult.NotInFavorites(favorites("[:aaa]", "aaa")),
                repository.remove("tester", token),
            )
        }
        coVerify(exactly = 5) { client.getFavoriteSmileyForm() }
        coVerify(exactly = 0) { client.removeFavoriteSmileys(any(), any(), any(), any()) }
    }

    @Test
    fun `successful removal text cannot conceal lost retained or reordered rows`() = runTest {
        listOf(emptyArray(), arrayOf("[:un]", "[:cible]", "[:trois]"), arrayOf("[:trois]", "[:un]")).forEach { real ->
            coEvery { client.getFavoriteSmileyForm() } returnsMany listOf(
                form("[:un]", "[:cible]", "[:trois]"), form(*real),
            )
            coEvery { client.removeFavoriteSmileys(any(), any(), any(), any()) } returns hop(
                "Suppression effectuée avec succès !",
            )
            assertEquals(
                HfrFavoriteSmileyMutationResult.VerificationFailed(favorites(*real)),
                repository.remove("tester", "[:cible]"),
            )
        }
    }

    @Test
    fun `removal is confirmed by the actual list even when the acknowledgement text is unknown`() = runTest {
        coEvery { client.getFavoriteSmileyForm() } returnsMany listOf(form("[:un]", "[:cible]"), form("[:un]"))
        coEvery { client.removeFavoriteSmileys(any(), any(), any(), any()) } returns hop("Texte inconnu $CODEHEX")
        assertEquals(
            HfrFavoriteSmileyMutationResult.Success(favorites("[:un]")), repository.remove("tester", "[:cible]"),
        )
        assertSafeDiagnostics()
    }

    @Test
    fun `add returns the actual server list when the acknowledged token is absent`() = runTest {
        coEvery { client.getFavoriteSmileyForm() } returnsMany listOf(form("[:un]"), form("[:tiers]", "[:un]"))
        coEvery { client.addFavoriteSmiley(any(), any()) } returns hop("Ajout effectué avec succès !")
        assertEquals(
            HfrFavoriteSmileyMutationResult.VerificationFailed(favorites("[:tiers]", "[:un]")),
            repository.add("tester", "[:deux]"),
        )
        coVerify(exactly = 2) { client.getFavoriteSmileyForm() }
    }

    @Test
    fun `malformed form prevents both mutations instead of sending a partial list`() = runTest {
        coEvery { client.getFavoriteSmileyForm() } returns form("[:un]", "[:deux]")
            .replace("name=\"smiley1\"", "name=\"smiley2\"")
        assertEquals(HfrFavoriteSmileyMutationResult.UnknownResponse, repository.remove("tester", "[:un]"))
        assertEquals(HfrFavoriteSmileyMutationResult.UnknownResponse, repository.add("tester", "[:trois]"))
        coVerify(exactly = 0) { client.addFavoriteSmiley(any(), any()) }
        coVerify(exactly = 0) { client.removeFavoriteSmileys(any(), any(), any(), any()) }
    }

    @Test
    fun `rows moved outside the working form cannot authorize a partial removal POST`() = runTest {
        val html = form("[:un]", "[:retenu]")
        val rows = Regex("""(?s)<tr class=s2Topic>.*?</tr>""").findAll(html).map { it.value }.toList()
        coEvery { client.removeFavoriteSmileys(any(), any(), any(), any()) } returns hop(
            "Suppression effectuée avec succès !",
        )
        listOf(1, rows.size).forEach { count ->
            val moved = rows.takeLast(count)
            val partial = moved.fold(html) { document, row -> document.replace(row, "") }
            outsideFormVariants("<table>${moved.joinToString("\n")}</table>").forEach { outside ->
                // A POST would erase the outside rows, and an empty confirmation would wrongly match.
                coEvery { client.getFavoriteSmileyForm() } returnsMany listOf(partial + outside, form())
                assertEquals(HfrFavoriteSmileyMutationResult.UnknownResponse, repository.remove("tester", "[:un]"))
            }
        }
        coVerify(exactly = 6) { client.getFavoriteSmileyForm() }
        coVerify(exactly = 0) { client.removeFavoriteSmileys(any(), any(), any(), any()) }
    }

    @Test
    fun `stray smiley fields delete fields or rows prevent removal without any POST`() = runTest {
        listOf(
            "<input name=smiley99 value=aaa>",
            "<input type=hidden name=smiley99 value=aaa>",
            "<input type=checkbox name=delete99>",
            "<input name=delete99>",
            "<table><tr class=s2Topic><td>orphan</td></tr></table>",
        ).forEach { extra ->
            outsideFormVariants(extra).forEach { outside ->
                coEvery { client.getFavoriteSmileyForm() } returns form("[:un]") + outside
                assertEquals(HfrFavoriteSmileyMutationResult.UnknownResponse, repository.remove("tester", "[:un]"))
            }
        }
        coVerify(exactly = 15) { client.getFavoriteSmileyForm() }
        coVerify(exactly = 0) { client.removeFavoriteSmileys(any(), any(), any(), any()) }
    }

    @Test
    fun `raw orphan rows nested forms bad closures and mistyped fields prevent any removal POST`() = runTest {
        val html = form("[:un]")
        val malformedForms = listOf(
            html + "<tr class=s2Topic><td>forgotten favorite</td></tr>",
            html.replace("<table>", "<form name=test><table>").replace("</table>", "</table></form>"),
            html.replace("<table>", "<form name=other><table>").replace("</table>", "</table></form>"),
            html.replace("</form>", ""),
            html.replace("</form>", "</form"),
            html.replace("</form>", "</form></form>"),
            html.replace("</form>", "<input type=text name=smiley1 value=aaa></form>"),
            html.replace("</form>", "<input name=smiley1 value=aaa></form>"),
            html.replace("</form>", "<input type=hidden name=delete1></form>"),
            html.replace("</form>", "<input name=delete1></form>"),
            html.replace("</form>", "<textarea name=smiley1>aaa</textarea></form>"),
            html.replace("</form>", "<select name=delete1></select></form>"),
        )
        coEvery { client.removeFavoriteSmileys(any(), any(), any(), any()) } returns hop(
            "Suppression effectuée avec succès !",
        )
        malformedForms.forEach { malformed ->
            // An unsafe POST followed by an empty form could otherwise look like a confirmed removal.
            coEvery { client.getFavoriteSmileyForm() } returnsMany listOf(malformed, form())
            assertEquals(HfrFavoriteSmileyMutationResult.UnknownResponse, repository.remove("tester", "[:un]"))
        }
        coVerify(exactly = malformedForms.size) { client.getFavoriteSmileyForm() }
        coVerify(exactly = 0) { client.removeFavoriteSmileys(any(), any(), any(), any()) }
    }

    @Test
    fun `commented fields and attribute text still allow removal of a complete list`() = runTest {
        val noise = """
            <!-- <input name=smiley1><input name=delete1><tr class=s2Topic><form name=test> -->
            <img alt="name='smiley1' name='delete1' class='s2Topic' <form name='test'>">
            <span title='name="smiley1" name="delete1" class="s2Topic" <form name="test">'>
                name=smiley1 name=delete1 class=s2Topic &lt;form name=test&gt;
            </span>
        """.trimIndent()
        coEvery { client.getFavoriteSmileyForm() } returnsMany listOf(
            form("[:un]").replace("</form>", "$noise</form>"), form(),
        )
        coEvery { client.removeFavoriteSmileys(any(), any(), any(), any()) } returns hop(
            "Suppression effectuée avec succès !",
        )

        assertEquals(HfrFavoriteSmileyMutationResult.Success(emptyList()), repository.remove("tester", "[:un]"))
        coVerify(exactly = 2) { client.getFavoriteSmileyForm() }
        coVerify(exactly = 1) { client.removeFavoriteSmileys(HASH_CHECK, CODEHEX, listOf("[:un]"), 0) }
    }

    @Test
    fun `resolving userId and changing pseudo case during either mutation keeps the same account`() = runTest {
        listOf(false, true).forEach { removal ->
            auth.state.value = AuthState.Authenticated("Tester")
            val confirmed = if (removal) emptyArray() else arrayOf("[:un]", "[:deux]")
            var reads = 0
            coEvery { client.getFavoriteSmileyForm() } answers {
                auth.state.value = AuthState.Authenticated(if (reads == 0) "tEsTeR" else "TESTER", 1)
                if (reads++ == 0) form("[:un]") else form(*confirmed)
            }
            coEvery { client.addFavoriteSmiley(HASH_CHECK, "[:deux]") } returns hop("Ajout effectué avec succès !")
            coEvery { client.removeFavoriteSmileys(any(), any(), any(), any()) } returns hop(
                "Suppression effectuée avec succès !",
            )

            val result = if (removal) repository.remove("tEsTeR", "[:un]") else repository.add("TESTER", "[:deux]")
            assertEquals(HfrFavoriteSmileyMutationResult.Success(favorites(*confirmed)), result)
        }
        coVerify(exactly = 4) { client.getFavoriteSmileyForm() }
        coVerify(exactly = 1) { client.addFavoriteSmiley(HASH_CHECK, "[:deux]") }
        coVerify(exactly = 1) { client.removeFavoriteSmileys(HASH_CHECK, CODEHEX, listOf("[:un]"), 0) }
    }

    @Test
    fun `changing account or becoming anonymous before either POST aborts the operation`() = runTest {
        listOf(AuthState.Authenticated("Other", 2), AuthState.Anonymous).forEach { next ->
            listOf(false, true).forEach { removal ->
                auth.state.value = AuthState.Authenticated("Tester", 1)
                coEvery { client.getFavoriteSmileyForm() } answers {
                    auth.state.value = next
                    form("[:un]")
                }
                val result = if (removal) repository.remove("tester", "[:un]") else repository.add("tester", "[:deux]")
                assertEquals(HfrFavoriteSmileyMutationResult.AccountChanged, result)
            }
        }
        coVerify(exactly = 0) { client.addFavoriteSmiley(any(), any()) }
        coVerify(exactly = 0) { client.removeFavoriteSmileys(any(), any(), any(), any()) }
    }

    @Test
    fun `owner comparison does not trim distinct pseudos or accept blank owners`() = runTest {
        listOf(" tester", "tester ", "", " ").forEach { owner ->
            assertEquals(HfrFavoriteSmileyMutationResult.AccountChanged, repository.add(owner, "[:un]"))
            assertEquals(HfrFavoriteSmileyMutationResult.AccountChanged, repository.remove(owner, "[:un]"))
        }
        coVerify(exactly = 0) { client.getFavoriteSmileyForm() }
        coVerify(exactly = 0) { client.addFavoriteSmiley(any(), any()) }
        coVerify(exactly = 0) { client.removeFavoriteSmileys(any(), any(), any(), any()) }
    }

    @Test
    fun `gesture owner is rejected silently before any GET when the session is anonymous`() = runTest {
        auth.state.value = AuthState.Anonymous
        assertEquals(HfrFavoriteSmileyMutationResult.AccountChanged, repository.add("tester", "[:un]"))
        assertEquals(HfrFavoriteSmileyMutationResult.AccountChanged, repository.remove("tester", "[:un]"))
        coVerify(exactly = 0) { client.getFavoriteSmileyForm() }
    }

    @Test
    fun `expired sessions in GET POST or confirmation are reported without secrets`() = runTest {
        coEvery { client.getFavoriteSmileyForm() } throws SessionExpiredException("secret-$CODEHEX")
        assertEquals(HfrFavoriteSmileyMutationResult.NotAuthenticated, repository.add("tester", "[:un]"))
        coEvery { client.getFavoriteSmileyForm() } returns form("[:un]")
        coEvery { client.removeFavoriteSmileys(any(), any(), any(), any()) } throws SessionExpiredException(CODEHEX)
        assertEquals(HfrFavoriteSmileyMutationResult.NotAuthenticated, repository.remove("tester", "[:un]"))
        coEvery { client.addFavoriteSmiley(any(), any()) } returns hop("Ajout effectué avec succès !")
        coEvery { client.getFavoriteSmileyForm() } returns form() andThenThrows SessionExpiredException(CODEHEX)
        assertEquals(HfrFavoriteSmileyMutationResult.NotAuthenticated, repository.add("tester", "[:un]"))
        assertSafeDiagnostics()
    }

    @Test
    fun `transport and unknown responses keep raw payloads out of results and diagnostics`() = runTest {
        coEvery { client.getFavoriteSmileyForm() } returns form("[:un]")
        coEvery { client.addFavoriteSmiley(any(), any()) } throws IOException("codehex=$CODEHEX hash=$HASH_CHECK")
        val transport = repository.add("tester", "[:deux]")
        assertEquals(HfrFavoriteSmileyMutationResult.TransportFailure, transport)
        assertFalse(transport.toString().contains(CODEHEX))
        coEvery { client.addFavoriteSmiley(any(), any()) } returns hop("unknown $CODEHEX")
        val unknown = repository.add("tester", "[:deux]")
        assertEquals(HfrFavoriteSmileyMutationResult.UnknownResponse, unknown)
        assertFalse(unknown.toString().contains(CODEHEX))
        coEvery { client.getFavoriteSmileyForm() } returns """
            <form action='login_validation.php'><input name=pseudo><input name=password></form>
        """.trimIndent()
        assertEquals(HfrFavoriteSmileyMutationResult.NotAuthenticated, repository.remove("tester", "[:un]"))
        assertSafeDiagnostics()
    }

    @Test
    fun `client argument failures return unknown response without logging their message or cause`() = runTest {
        val failure = IllegalArgumentException("codehex=$CODEHEX", IOException("hash_check=$HASH_CHECK"))
        coEvery { client.getFavoriteSmileyForm() } returns form("[:un]")
        coEvery { client.addFavoriteSmiley(any(), any()) } throws failure
        coEvery { client.removeFavoriteSmileys(any(), any(), any(), any()) } throws failure

        assertEquals(HfrFavoriteSmileyMutationResult.UnknownResponse, repository.add("tester", "[:deux]"))
        assertEquals(HfrFavoriteSmileyMutationResult.UnknownResponse, repository.remove("tester", "[:un]"))
        assertEquals(
            listOf("add UnknownResponse", "remove UnknownResponse"),
            diagnostics.entries.value.map { it.message },
        )
        assertSafeDiagnostics()
    }

    @Test
    fun `cancellation propagates without logging the exception message`() = runTest {
        coEvery { client.getFavoriteSmileyForm() } throws CancellationException(CODEHEX)
        assertTrue(runCatching { repository.add("tester", "[:un]") }.exceptionOrNull() is CancellationException)
        assertSafeDiagnostics()
    }

    private suspend fun assertMutationsSerialized(removalFirst: Boolean) = coroutineScope {
        val releaseInitialRead = CompletableDeferred<Unit>()
        val confirmationStarted = CompletableDeferred<Unit>()
        val releaseConfirmation = CompletableDeferred<Unit>()
        val initial = arrayOf("[:un]", "[:cible]", "[:trois]")
        val afterRemoval = arrayOf("[:un]", "[:trois]")
        val afterAddition = initial + "[:nouveau]"
        val afterFirst = if (removalFirst) afterRemoval else afterAddition
        val afterBoth = afterRemoval + "[:nouveau]"
        val snapshots = listOf(initial, afterFirst, afterFirst, afterBoth)
        val events = mutableListOf<String>()
        var reads = 0
        coEvery { client.getFavoriteSmileyForm() } coAnswers {
            val read = reads++
            events += "GET"
            if (read == 0) releaseInitialRead.await()
            if (read == 1) {
                confirmationStarted.complete(Unit)
                releaseConfirmation.await()
            }
            form(*snapshots[read])
        }
        coEvery { client.addFavoriteSmiley(HASH_CHECK, "[:nouveau]") } answers {
            events += "add"
            hop("Ajout effectué avec succès !")
        }
        val removalTokens = if (removalFirst) initial else afterAddition
        coEvery { client.removeFavoriteSmileys(HASH_CHECK, CODEHEX, removalTokens.toList(), 1) } answers {
            events += "remove"
            hop("Suppression effectuée avec succès !")
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            if (removalFirst) repository.remove("tester", "[:cible]") else repository.add("tester", "[:nouveau]")
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            if (removalFirst) repository.add("tester", "[:nouveau]") else repository.remove("tester", "[:cible]")
        }
        assertEquals(listOf("GET"), events)
        assertFalse(second.isCompleted)
        releaseInitialRead.complete(Unit)
        confirmationStarted.await()
        val firstOperation = if (removalFirst) "remove" else "add"
        val secondOperation = if (removalFirst) "add" else "remove"
        assertEquals(listOf("GET", firstOperation, "GET"), events)
        assertFalse(second.isCompleted)
        releaseConfirmation.complete(Unit)
        assertEquals(HfrFavoriteSmileyMutationResult.Success(favorites(*afterFirst)), first.await())
        assertEquals(HfrFavoriteSmileyMutationResult.Success(favorites(*afterBoth)), second.await())
        assertEquals(listOf("GET", firstOperation, "GET", "GET", secondOperation, "GET"), events)
    }

    private fun assertSafeDiagnostics() {
        val entries = diagnostics.entries.value
        assertFalse(entries.toString().contains(CODEHEX))
        assertFalse(entries.toString().contains(HASH_CHECK))
        assertTrue(entries.all { it.message.matches(Regex("(add|remove) [A-Za-z]+")) })
    }

    private fun favorites(vararg tokens: String): List<EditorSmiley> = tokens.mapIndexed { index, token ->
        EditorSmiley(token, "https://forum-images.hardware.fr/images/perso/$index.gif", EditorSmileySource.HFR_FAVORITE)
    }

    // Synthetic HTML for orchestration tests only; captured parser fixtures remain untouched.
    private fun form(vararg tokens: String): String = """
        <form name=test method=post action="supprperso_validation.php?config=hfr.inc&amp;codehex=$CODEHEX">
        <input type=hidden name=hash_check value="$HASH_CHECK"><table>
        ${favorites(*tokens).mapIndexed { index, smiley ->
            """<tr class=s2Topic><td><img src="${smiley.imageUrl}"></td>
                <td><input type=hidden name="smiley$index" value="${smiley.token}">
                <input type=checkbox name="delete$index"></td></tr>"""
        }.joinToString("\n")}
        </table></form>
    """.trimIndent()

    private fun outsideFormVariants(html: String): List<String> = listOf(
        "<form name=other>$html</form>",
        "<form>$html</form>",
        html,
    )

    private fun hop(text: String): String = "<div class=hop>$text</div>"

    private class FakeAuthRepository : AuthRepository {
        val state = MutableStateFlow<AuthState>(AuthState.Authenticated("Tester", 1))
        var reads = 0
        override fun observeAuthState(): Flow<AuthState> {
            reads++
            return state
        }
        override suspend fun login(pseudo: String, password: String): Result<AuthState.Authenticated> =
            error("Not used")
        override suspend fun logout() { state.value = AuthState.Anonymous }
    }

    private companion object {
        const val HASH_CHECK = "0123456789abcdef0123456789abcdef"
        const val CODEHEX = "fedcba9876543210fedcba9876543210"
    }
}
