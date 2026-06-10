package fr.forumhfr.redface2.feature.topic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.Post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * #362 — per-post contextual menu, opened from the `⋯` trigger in the post header
 * ([TopicPostCard]). Carries:
 *
 * - an identity header: author + « Post n°{numreponse} » (the post number moved here
 *   from the header bar) + post date;
 * - the « Copier le lien de ce post » action — copies the canonical permalink
 *   ([buildPostPermalink]) and closes the sheet. Feedback follows the Diagnostics
 *   clipboard pattern: Android 13+ shows the system clipboard overlay natively, older
 *   devices get a Toast;
 * - an « Édité le … » info line when [Post.editedAt] is non-null;
 * - a « Cité N fois sur cette page » info line when [citedCount] > 0 (hidden at 0 —
 *   same page-scoped #239 count as the badge, which stays on the card).
 *
 * Lives in `:feature:topic` (local UI state in `TopicScreen`, no ViewModel): unlike
 * `ProfilePreviewSheet`, hoisted in `:app` only because it needs a Hilt ViewModel,
 * this menu has no async data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PostMenuSheet(
    post: Post,
    permalink: String,
    citedCount: Int,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    // Resolved at composition time — the copy callback runs outside composition.
    val copiedFeedback = stringResource(R.string.topic_post_menu_link_copied)
    val copyLabel = stringResource(R.string.topic_post_menu_copy_link)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = post.author,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.topic_post_menu_number, post.numreponse),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = post.date.asTopicDate(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                text = copyLabel,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        // Copy immediately, then play the hide animation before releasing the
                        // state (cf. ProfilePreviewSheet's hideThenNavigate) — « au plus
                        // simple »: the clipboard write has no reason to wait for the sheet.
                        onClick = {
                            copyPermalinkToClipboard(context, permalink, copiedFeedback)
                            hideThenDismiss(coroutineScope, sheetState, onDismiss)
                        },
                        role = Role.Button,
                        onClickLabel = copyLabel,
                    )
                    .padding(vertical = 14.dp),
            )

            post.editedAt?.let { editedAt ->
                Text(
                    text = stringResource(R.string.topic_post_menu_edited, editedAt.asTopicDate()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            if (citedCount > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.topic_post_menu_cited_on_page,
                        citedCount,
                        citedCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Clipboard write + feedback, mirroring the Diagnostics export pattern
 * (`DiagnosticsViewModel`): Android 13+ (T) shows the system « copié » overlay on its
 * own, so the Toast is only raised on older API levels to avoid double feedback.
 */
private fun copyPermalinkToClipboard(context: Context, permalink: String, feedback: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("redface2 post link", permalink))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, feedback, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Plays the sheet's hide animation, then invokes [onDismiss] once the sheet is actually
 * off-screen — same Material 3 « animated dismiss » idiom as ProfilePreviewSheet's
 * `hideThenNavigate`.
 */
@OptIn(ExperimentalMaterial3Api::class)
private fun hideThenDismiss(
    coroutineScope: CoroutineScope,
    sheetState: SheetState,
    onDismiss: () -> Unit,
) {
    coroutineScope.launch { sheetState.hide() }
        .invokeOnCompletion {
            if (!sheetState.isVisible) {
                onDismiss()
            }
        }
}
