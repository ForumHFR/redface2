package fr.forumhfr.redface2.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression matrix for the pure [shouldApplyDeepLinkDelivery] truth table fixed by #1203.
 *
 * Only the predicate is tested here. The lifecycle wiring that mints and restores IDs in
 * `MainActivity` is verified manually: a Robolectric `ActivityScenario` test would require the
 * costly Hilt and `setContent` setup, which is not justified by the project's risk-guided coverage.
 */
class IntentDeliveryTest {

    @Test
    fun `first cold-start delivery is applied`() {
        assertTrue(shouldApplyDeepLinkDelivery(deliveryId = 0, lastResolvedDeliveryId = null))
    }

    @Test
    fun `rotation does not replay the current delivery`() {
        assertFalse(shouldApplyDeepLinkDelivery(deliveryId = 4, lastResolvedDeliveryId = 4))
    }

    @Test
    fun `re-tap of the same link is a new delivery and is applied`() {
        assertTrue(shouldApplyDeepLinkDelivery(deliveryId = 5, lastResolvedDeliveryId = 4))
    }

    @Test
    fun `a distinct link delivery is applied`() {
        assertTrue(shouldApplyDeepLinkDelivery(deliveryId = 6, lastResolvedDeliveryId = 5))
    }

    @Test
    fun `process restoration without a new intent does not replay the delivery`() {
        assertFalse(shouldApplyDeepLinkDelivery(deliveryId = 6, lastResolvedDeliveryId = 6))
    }

    @Test
    fun `fresh cold delivery after process restoration is applied`() {
        assertTrue(shouldApplyDeepLinkDelivery(deliveryId = 7, lastResolvedDeliveryId = 6))
    }

    @Test
    fun `older id is unreachable by monotonicity but any distinct id is applied`() {
        assertTrue(shouldApplyDeepLinkDelivery(deliveryId = 5, lastResolvedDeliveryId = 6))
    }
}
