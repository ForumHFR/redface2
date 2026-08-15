package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R

/**
 * #509/#1050 — collapsed placeholder shown in place of a blacklisted author's post or private
 * message. The caller keeps the post in its list (index/anchor/`numreponse` invariants stay intact)
 * and replaces only its card with this one-line surface. « Afficher » reveals the real card for the
 * current page only; page-scoped reveal state belongs to the host.
 *
 * By design this placeholder exposes no quote/edit/menu action: the reader reveals first, then acts
 * on the full card. The author label remains the item's exactly-one accessibility heading (#884),
 * mirroring the identity heading of a visible reading card.
 */
@Composable
fun HiddenPostCard(
    author: String,
    onReveal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.post_hidden_by_author, author),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .semantics { heading() },
            )
            TextButton(onClick = onReveal) {
                Text(text = stringResource(R.string.post_hidden_reveal))
            }
        }
    }
}
