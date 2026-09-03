package fr.forumhfr.redface2.feature.flags

import fr.forumhfr.redface2.core.model.AuthState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gating of the « rechercher dans les drapeaux » loupe (#603 harmonisation). The loupe is offered on
 * every tab that holds a searchable list — the three flag tabs, DT and Super — but never when
 * anonymous. Pure, so a future gating change is caught here rather than only on-device. Guards the
 * Codex review reserve (test the gating directly).
 */
class FlagsSearchEnabledTest {

    private val authed = AuthState.Authenticated("xaat")

    @Test
    fun `the loupe is enabled on the three flag tabs when authenticated`() {
        assertTrue(flagsSearchEnabled(authed, FlagTab.Cyan))
        assertTrue(flagsSearchEnabled(authed, FlagTab.Red))
        assertTrue(flagsSearchEnabled(authed, FlagTab.Favorite))
    }

    @Test
    fun `the loupe is enabled on the DT tab when authenticated`() {
        assertTrue(flagsSearchEnabled(authed, FlagTab.Dt))
    }

    @Test
    fun `the loupe is enabled on the Super tab when authenticated`() {
        assertTrue(flagsSearchEnabled(authed, FlagTab.Super))
    }

    @Test
    fun `the loupe is disabled when anonymous, even on a flag tab or DT`() {
        assertFalse(flagsSearchEnabled(AuthState.Anonymous, FlagTab.Cyan))
        assertFalse(flagsSearchEnabled(AuthState.Anonymous, FlagTab.Dt))
    }

    @Test
    fun `the loupe is disabled while auth state is still unknown (null)`() {
        assertFalse(flagsSearchEnabled(null, FlagTab.Cyan))
        assertFalse(flagsSearchEnabled(null, FlagTab.Dt))
    }
}
