package fr.forumhfr.redface2.feature.forum

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.icon.RedfaceVectorIcon
import fr.forumhfr.redface2.core.ui.R as CoreUiR

/** #1303 — one counted header before the loaded page's sticky group; informational during search. */
@Composable
internal fun ForumStickyTopicsHeader(count: Int, collapsed: Boolean, onToggle: (() -> Unit)?) {
    val label = pluralStringResource(
        if (collapsed) R.plurals.category_sticky_hidden else R.plurals.category_sticky_count,
        count,
        count,
    )
    val action = pluralStringResource(
        if (collapsed) R.plurals.category_sticky_expand else R.plurals.category_sticky_collapse,
        count,
        count,
    )
    val expandedState = stringResource(
        if (collapsed) R.string.category_layout_collapsed else R.string.category_layout_expanded,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onToggle != null) {
                    Modifier.clickable(role = Role.Button, onClickLabel = action, onClick = onToggle)
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) { stateDescription = expandedState }
            .heightIn(min = 48.dp)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (onToggle != null) {
            RedfaceVectorIcon(if (collapsed) CoreUiR.drawable.ic_expand_more else CoreUiR.drawable.ic_expand_less)
        }
    }
}
