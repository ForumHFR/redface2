package fr.forumhfr.redface2.feature.topic

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import fr.forumhfr.redface2.core.ui.post.PostImageTarget
import fr.forumhfr.redface2.core.ui.post.PostImageThumbnail

/**
 * #831 — contextual menu of a post image, opened by a long-press on the image (inline `[img]`,
 * block image or promoted gallery image — cf. `LocalPostImageActions` in `:core:ui`). Layout
 * clones [PostMenuSheet] (#362): a hero row identifying the target, then stacked full-width
 * actions:
 *
 * - « Enregistrer l'image » (filled, primary action) — delegates to [onSave]
 *   ([PostImageActionsViewModel] → `PostImageSaver`); feedback arrives as a Toast from the
 *   ViewModel's effects, after the sheet has closed;
 * - « Copier l'URL de l'image » — clipboard write, Diagnostics feedback pattern (system overlay
 *   on Android 13+, Toast below);
 * - « Ouvrir dans le navigateur » — `ACTION_VIEW` on the image URL (the DIRECT image, not the
 *   `[url=…]` link, which the block tap already covers);
 * - « Afficher en taille réelle (à venir) » — DISABLED placeholder (#288 « menu vitrine »
 *   pattern, same as PostMenuSheet's « Alerter »): the affordance shows the roadmap, the PR2
 *   fullscreen viewer (#182) will enable it.
 *
 * The hero shows the image thumbnail (Coil caches, `:core:ui`) + the host and full URL so the
 * user can tell WHICH image the menu targets when a post carries several.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PostImageMenuSheet(
    target: PostImageTarget,
    onSave: (url: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    // Resolved at composition time — the action callbacks run outside composition.
    val copiedFeedback = stringResource(R.string.topic_image_menu_url_copied)
    val browserFailedFeedback = stringResource(R.string.topic_post_menu_no_browser)

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
            PostImageMenuHero(target)

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    onSave(target.url)
                    hideThenDismiss(coroutineScope, sheetState, onDismiss)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.topic_image_menu_save))
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    copyImageUrlToClipboard(context, target.url, copiedFeedback)
                    hideThenDismiss(coroutineScope, sheetState, onDismiss)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.topic_image_menu_copy_url))
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    openImageUrlInBrowser(context, target.url, browserFailedFeedback)
                    hideThenDismiss(coroutineScope, sheetState, onDismiss)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.topic_post_menu_open_in_browser))
            }

            Spacer(Modifier.height(8.dp))

            // PR2 (#182) — fullscreen viewer placeholder, greyed « menu vitrine » (#288 pattern):
            // the affordance is visible, the « (à venir) » suffix explains why it is disabled.
            OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.topic_image_menu_full_size_soon))
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Hero row of the sheet — thumbnail + host + full URL, mirroring [PostMenuHero]'s
 * avatar + identity layout. The host alone is the human-readable line (« rehost.diberie.com »),
 * the full URL underneath disambiguates several images from the same host.
 */
@Composable
private fun PostImageMenuHero(target: PostImageTarget) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        PostImageThumbnail(
            url = target.url,
            contentDescription = target.description,
        )
        Column {
            Text(
                text = target.url.toUri().host ?: target.url,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = target.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Clipboard write + feedback for the image URL — same Diagnostics pattern as
 * [copyPermalinkToClipboard]: Android 13+ (T) shows the system « copié » overlay on its own,
 * older API levels get a Toast.
 */
private fun copyImageUrlToClipboard(context: Context, url: String, feedback: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("redface2 image url", url))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, feedback, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Fires an `ACTION_VIEW` on the direct image URL — same survive-no-handler contract as
 * [openPermalinkInBrowser] (a Toast instead of a crash).
 */
private fun openImageUrlInBrowser(context: Context, url: String, failureFeedback: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (ignored: ActivityNotFoundException) {
        Toast.makeText(context, failureFeedback, Toast.LENGTH_SHORT).show()
    }
}
