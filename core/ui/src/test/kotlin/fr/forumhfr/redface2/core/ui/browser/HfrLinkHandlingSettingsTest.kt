package fr.forumhfr.redface2.core.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1032 R3 — truth table of the pure default-handler decision, isolated from Android. A third-party
 * host without `autoVerify` moves NONE -> SELECTED on manual activation, so SELECTED means "default";
 * VERIFIED never happens for such a host but is accepted for robustness.
 */
class HfrLinkHandlingSettingsTest {

    @Test
    fun `selected host with link handling allowed is the default handler`() {
        assertEquals(
            HfrLinkHandlingStatus.DEFAULT_HANDLER,
            hfrLinkHandlingStatusOf(isLinkHandlingAllowed = true, hostState = DOMAIN_STATE_SELECTED),
        )
    }

    @Test
    fun `verified host with link handling allowed is the default handler`() {
        assertEquals(
            HfrLinkHandlingStatus.DEFAULT_HANDLER,
            hfrLinkHandlingStatusOf(isLinkHandlingAllowed = true, hostState = DOMAIN_STATE_VERIFIED),
        )
    }

    @Test
    fun `selected host is not default when master link handling is disabled`() {
        assertEquals(
            HfrLinkHandlingStatus.NOT_DEFAULT,
            hfrLinkHandlingStatusOf(isLinkHandlingAllowed = false, hostState = DOMAIN_STATE_SELECTED),
        )
    }

    @Test
    fun `none state is not the default handler even when link handling is allowed`() {
        assertEquals(
            HfrLinkHandlingStatus.NOT_DEFAULT,
            hfrLinkHandlingStatusOf(isLinkHandlingAllowed = true, hostState = DOMAIN_STATE_NONE),
        )
    }

    @Test
    fun `an absent host mapping is not the default handler`() {
        assertEquals(
            HfrLinkHandlingStatus.NOT_DEFAULT,
            hfrLinkHandlingStatusOf(isLinkHandlingAllowed = true, hostState = null),
        )
    }

    private companion object {
        const val DOMAIN_STATE_NONE = 0
    }
}
