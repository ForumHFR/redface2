package fr.forumhfr.redface2.navigation

import android.content.Intent

/**
 * One delivery of an [Intent], identified independently from its action and URI for #1203.
 *
 * IDs follow a monotonicity invariant within one saved-state lineage: every newly minted ID
 * (`restoredId + 1`, then `nextId++`) is strictly greater than every ID already consumed.
 */
internal data class IntentDelivery(
    val intent: Intent,
    val id: Long,
)

/**
 * Returns whether the delivery has not already been consumed, as fixed by #1203.
 *
 * The `!=` contract depends on [IntentDelivery]'s monotonicity invariant: any newly minted ID is
 * strictly greater than every ID already consumed in the same saved-state lineage.
 */
internal fun shouldApplyDeepLinkDelivery(
    deliveryId: Long,
    lastResolvedDeliveryId: Long?,
): Boolean = deliveryId != lastResolvedDeliveryId
