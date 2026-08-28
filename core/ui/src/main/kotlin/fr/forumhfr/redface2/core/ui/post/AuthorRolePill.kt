package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.AuthorRole
import fr.forumhfr.redface2.core.ui.R

/** Compact, non-interactive staff-role marker shared by Topic and private-message post headers. */
@Composable
fun AuthorRolePill(role: AuthorRole, modifier: Modifier = Modifier) {
    val (visibleLabelRes, longLabelRes) = when (role) {
        AuthorRole.MEMBER -> return
        AuthorRole.MODERATOR -> R.string.author_role_moderator to R.string.author_role_moderator
        AuthorRole.ADMIN -> R.string.author_role_admin to R.string.author_role_admin_long
        AuthorRole.SUPER_ADMIN ->
            R.string.author_role_super_admin to R.string.author_role_super_admin_long
        AuthorRole.DEVELOPER -> R.string.author_role_developer to R.string.author_role_developer_long
        AuthorRole.ARCHITECT -> R.string.author_role_architect to R.string.author_role_architect_long
    }
    val visibleLabel = stringResource(visibleLabelRes)
    val longLabel = stringResource(longLabelRes)
    val roleDescription = stringResource(R.string.author_role_content_description, longLabel)

    Surface(
        modifier = modifier.clearAndSetSemantics { contentDescription = roleDescription },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = visibleLabel,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
