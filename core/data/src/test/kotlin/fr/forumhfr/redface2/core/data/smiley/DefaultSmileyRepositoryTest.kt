package fr.forumhfr.redface2.core.data.smiley

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.smiley.SmileySearchParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #794 — the wiki search applies an implicit AND between terms. The engine's operators were
 * live-verified (2026-07-05) : bare space = OR, `+term` = AND, `-term` = NOT — so the repository
 * prefixes every unoperated term with `+` before the network call, and never rewrites an
 * operator the user typed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSmileyRepositoryTest {

    private val hfrClient = mockk<HfrClient> {
        coEvery { getSmileySearch(userId = any(), query = any()) } returns ""
    }
    private val parser = mockk<SmileySearchParser> {
        every { parse(any()) } returns emptyList()
    }
    private val diagnostics = mockk<DiagnosticsLog>(relaxed = true)

    private fun repository() = DefaultSmileyRepository(
        hfrClient = hfrClient,
        parser = parser,
        diagnostics = diagnostics,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    @Test
    fun `two bare terms reach the network with a plus prefix each`() = runTest {
        repository().searchWiki(userId = 54596, query = "chat noir")

        coVerify(exactly = 1) { hfrClient.getSmileySearch(54596, "+chat +noir") }
    }

    @Test
    fun `user-typed operators are preserved, bare terms still get the AND prefix`() = runTest {
        repository().searchWiki(userId = 54596, query = "chat -noir +blanc")

        coVerify(exactly = 1) { hfrClient.getSmileySearch(54596, "+chat -noir +blanc") }
    }

    @Test
    fun `a single term is prefixed too and extra whitespace collapses`() = runTest {
        repository().searchWiki(userId = 54596, query = "  jap\t roi ")

        coVerify(exactly = 1) { hfrClient.getSmileySearch(54596, "+jap +roi") }
    }

    // Pure-helper edge cases (no network shape to pin) :

    @Test
    fun `blank input is returned untouched`() {
        assertEquals("   ", toImplicitAndQuery("   "))
        assertEquals("", toImplicitAndQuery(""))
    }

    @Test
    fun `already fully operated input is idempotent`() {
        assertEquals("+chat -noir", toImplicitAndQuery("+chat -noir"))
        assertEquals("+chat -noir", toImplicitAndQuery(toImplicitAndQuery("chat -noir")))
    }
}
