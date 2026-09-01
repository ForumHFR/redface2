package fr.forumhfr.redface2.feature.forum

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * #1131 — geometry of the category topic list. The « + » (create-topic) FAB lives in the
 * screen's [androidx.compose.material3.Scaffold] `floatingActionButton` slot and floats over
 * the bottom-end of the listing, so the pager (the last list item) was overlapped by it — the
 * « Suivant » button ended up under the FAB. The [androidx.compose.foundation.lazy.LazyColumn]
 * carries no content padding of its own, so we reserve a bottom inset equal to the FAB
 * clearance, but ONLY when the FAB is actually rendered (`canCreateTopic`): an anonymous
 * session has no FAB, and reserving the inset there would leave an 88.dp void with nothing
 * floating over it.
 *
 * Extracted as a pure function (nothing mounted the screen in JVM unit tests before #1149's
 * `ForumCategoryContent` harness) so the value is unit-testable. The
 * [fr.forumhfr.redface2.feature.topic] list uses the same 88.dp clearance but keeps its own
 * private constant (it also folds in top/side insets we don't need here), so the two are
 * deliberately not shared.
 */
internal fun forumListContentPadding(reserveFabSpace: Boolean): PaddingValues =
    if (reserveFabSpace) {
        PaddingValues(bottom = FORUM_LIST_BOTTOM_INSET)
    } else {
        PaddingValues(0.dp)
    }

/** #1131 — clearance under the last list item for the floating create-topic FAB. */
private val FORUM_LIST_BOTTOM_INSET: Dp = 88.dp
