package fr.forumhfr.redface2.feature.topic

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.ui.avatar.RedfaceUserAvatar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * #362 — per-post contextual menu, opened from the `⋯` trigger in the post header
 * ([TopicPostCard]). Layout mirrors `ProfilePreviewSheet` (dogfooding feedback on v102:
 * the original plain-text sheet read as unfinished next to the profile one):
 *
 * - a hero row: avatar + author, with « Post n°{numreponse} » (the post number moved
 *   here from the header bar) and the post date underneath;
 * - optional info lines: « Édité le … » when [Post.editedAt] is non-null, and
 *   « Cité N fois dans le sujet » when [citedCount] > 0 (hidden at 0) — #863 : the SERVER
 *   counter, cross-page, same value as the card's badge;
 * - stacked full-width actions, profile-sheet style: a filled « Copier le lien de ce
 *   post » (primary), an outlined « Ouvrir dans le navigateur » (debug-friendly: the
 *   canonical permalink opens in the default browser), and a DISABLED « Alerter »
 *   placeholder — the report flow is not implemented yet, the greyed button shows the
 *   roadmap like the Settings « menu vitrine » (#288).
 *
 * Clipboard feedback follows the Diagnostics pattern: Android 13+ shows the system
 * overlay natively, older devices get a Toast. Both real actions play the sheet's hide
 * animation before releasing the state (cf. `hideThenDismiss`).
 *
 * Lives in `:feature:topic` (local UI state in `TopicScreen`, no ViewModel): unlike
 * `ProfilePreviewSheet`, hoisted in `:app` only because it needs a Hilt ViewModel,
 * this menu has no async data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList") // contextual-menu surface: each optional action (delete #418,
// profile #395) is a defaulted nullable param — the idiomatic Compose API, same as TopicBottomActions.
internal fun PostMenuSheet(
    post: Post,
    permalink: String,
    citedCount: Int,
    onDismiss: () -> Unit,
    /**
     * #418 — « Supprimer ce message », moved here from the post card's action row (beta
     * feedback by nicko : a destructive one-tap button under every own post invites
     * accidental taps). Null hides the entry — same #292 gates as before (own editable
     * post, postable topic, never the first post, no deletion in flight). The existing
     * #292 confirmation dialog still guards the actual POST.
     */
    onDelete: (() -> Unit)? = null,
    /**
     * Vague 3 (#604) — « Modifier le premier message », migrated here from the dissolved header
     * card (Phase 2D #148). Non-null ONLY on the topic's first post when the FP edit gates hold
     * (`shouldShowEditFirstPost`: page 1, owner toolbar link, postable topic with a real
     * sub-category — #213). Null hides the entry.
     */
    onEditFirstPost: (() -> Unit)? = null,
    /**
     * #395 — opens the author's profile from the hero row (avatar + pseudo), parity with
     * the #208 tap on the post card. Null keeps the hero inert — same gate as the card
     * (`Post.profileId == null` : Publicité rows, anonymous reads). The sheet plays its
     * hide animation first so the profile sheet never stacks over this one.
     */
    onOpenProfile: (() -> Unit)? = null,
    /**
     * #792 — « Envoyer un MP » : opens the NEW-conversation MP composer with this post's
     * author prefilled as recipient. Null hides the entry (anonymous session, own post, or
     * no real profile — cf. `shouldShowSendPrivateMessage`). The sheet hides first, then
     * dismisses AND navigates (same order as « Modifier le premier message ») so the
     * composer never opens under a still-visible menu sheet.
     */
    onSendPrivateMessage: (() -> Unit)? = null,
    /** #986 — visible state of the server-side HFR favourite action. */
    favoriteAction: PostFavoriteAction = PostFavoriteAction.HIDDEN,
    /** Invoked only for [PostFavoriteAction.ADD] / [PostFavoriteAction.MOVE]. */
    onFavoriteClick: () -> Unit = {},
    /**
     * #291 — whether this post already sits in the multi-quote basket; flips the entry's
     * label between « Ajouter à » and « Retirer de » la citation multiple.
     */
    multiQuoteSelected: Boolean = false,
    /**
     * #291 — toggles this post in the multi-quote basket. Null hides the entry — same gate
     * as « Citer » (`shouldShowQuoteAction`): locked topic or anonymous session.
     */
    onToggleMultiQuote: (() -> Unit)? = null,
    /**
     * #509 — whether this post's author is currently blacklisted; flips the entry's label between
     * « Masquer cet utilisateur » and « Ne plus masquer cet utilisateur ».
     */
    authorBlocked: Boolean = false,
    /**
     * #509 — blocks / unblocks this post's author. Null hides the entry (e.g. the user's own posts —
     * blacklisting oneself is pointless).
     */
    onToggleBlockAuthor: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    // Resolved at composition time — the action callbacks run outside composition.
    val copiedFeedback = stringResource(R.string.topic_post_menu_link_copied)
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
            PostMenuHero(
                post = post,
                onClick = onOpenProfile?.let { openProfile ->
                    {
                        // Hide first, then dismiss AND navigate — the profile bottom sheet
                        // must not stack over a still-visible menu sheet.
                        hideThenDismiss(coroutineScope, sheetState) {
                            onDismiss()
                            openProfile()
                        }
                    }
                },
            )

            if (post.editedAt != null || citedCount > 0) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))
                post.editedAt?.let { editedAt ->
                    Text(
                        text = stringResource(
                            R.string.topic_post_menu_edited,
                            editedAt.asTopicDate(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                if (citedCount > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.topic_post_menu_cited_in_topic,
                            citedCount,
                            citedCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    copyPermalinkToClipboard(context, permalink, copiedFeedback)
                    hideThenDismiss(coroutineScope, sheetState, onDismiss)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.topic_post_menu_copy_link))
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    openPermalinkInBrowser(context, permalink, browserFailedFeedback)
                    hideThenDismiss(coroutineScope, sheetState, onDismiss)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.topic_post_menu_open_in_browser))
            }

            PostFavoriteButton(
                favoriteAction = favoriteAction,
                onClick = {
                    onFavoriteClick()
                    hideThenDismiss(coroutineScope, sheetState, onDismiss)
                },
            )

            if (onEditFirstPost != null) {
                Spacer(Modifier.height(8.dp))
                // Vague 3 (#604) — topic-level edit, first post only (Phase 2D #148). Hide first,
                // then dismiss AND navigate (same order as the profile hero above) — the editor
                // must not open under a still-visible menu sheet.
                OutlinedButton(
                    onClick = {
                        hideThenDismiss(coroutineScope, sheetState) {
                            onDismiss()
                            onEditFirstPost()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.topic_edit_first_post))
                }
            }

            if (onToggleMultiQuote != null) {
                Spacer(Modifier.height(8.dp))
                // #291 — adds/removes this post in the multi-quote basket. The sheet closes on
                // tap so the « Citer N » FAB count is immediately visible as feedback.
                OutlinedButton(
                    onClick = {
                        onToggleMultiQuote()
                        hideThenDismiss(coroutineScope, sheetState, onDismiss)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (multiQuoteSelected) {
                                R.string.topic_post_menu_multi_quote_remove
                            } else {
                                R.string.topic_post_menu_multi_quote_add
                            },
                        ),
                    )
                }
            }

            if (onSendPrivateMessage != null) {
                Spacer(Modifier.height(8.dp))
                // #792 — person-directed action, grouped with the author-scoped entry below. Hide
                // first, then dismiss AND navigate (same order as « Modifier le premier message »).
                OutlinedButton(
                    onClick = {
                        hideThenDismiss(coroutineScope, sheetState) {
                            onDismiss()
                            onSendPrivateMessage()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.topic_post_menu_send_private_message))
                }
            }

            if (onToggleBlockAuthor != null) {
                Spacer(Modifier.height(8.dp))
                // #509 — blacklist the post's author (or lift it). Closing on tap lets the reader see
                // the post collapse to the « masqué » placeholder immediately as feedback.
                OutlinedButton(
                    onClick = {
                        onToggleBlockAuthor()
                        hideThenDismiss(coroutineScope, sheetState, onDismiss)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (authorBlocked) {
                                R.string.topic_post_menu_unblock_author
                            } else {
                                R.string.topic_post_menu_block_author
                            },
                        ),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Report flow not implemented yet — greyed « menu vitrine » placeholder (#288
            // pattern): the affordance is visible, the « (à venir) » suffix explains why it
            // is disabled.
            OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.topic_post_menu_report_soon))
            }

            if (onDelete != null) {
                Spacer(Modifier.height(8.dp))
                // #418 — destructive action LAST (M3 idiom), error-tinted. The tap closes the
                // sheet and hands over to the #292 confirmation dialog owned by TopicScreen.
                OutlinedButton(
                    onClick = {
                        onDelete()
                        hideThenDismiss(coroutineScope, sheetState, onDismiss)
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.topic_post_menu_delete))
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Render state for the post-menu favourite row (#986); pure mapping is in TopicScreen. */
internal enum class PostFavoriteAction { HIDDEN, CHECKING, ADD, MOVE, ADDING, UNAVAILABLE }

@Composable
private fun PostFavoriteButton(favoriteAction: PostFavoriteAction, onClick: () -> Unit) {
    if (favoriteAction == PostFavoriteAction.HIDDEN) return

    Spacer(Modifier.height(8.dp))
    val label = when (favoriteAction) {
        PostFavoriteAction.HIDDEN -> error("hidden action is not rendered")
        PostFavoriteAction.CHECKING -> R.string.topic_post_menu_favorite_checking
        PostFavoriteAction.ADD -> R.string.topic_post_menu_add_favorite
        PostFavoriteAction.MOVE -> R.string.topic_post_menu_move_favorite
        PostFavoriteAction.ADDING -> R.string.topic_post_menu_favorite_adding
        PostFavoriteAction.UNAVAILABLE -> R.string.topic_post_menu_favorite_unavailable
    }
    OutlinedButton(
        onClick = onClick,
        enabled = favoriteAction == PostFavoriteAction.ADD || favoriteAction == PostFavoriteAction.MOVE,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(label))
    }
}

/**
 * Hero row of the sheet — avatar + author with the post identity (number, date)
 * underneath, mirroring `ProfilePreviewHero`. [RedfaceUserAvatar] handles the
 * `avatarUrl == null` / load-error placeholder on its own.
 *
 * #395 — when [onClick] is non-null the WHOLE row is one « Voir le profil » tap target:
 * in a menu sheet a row reads as one action (M3 list-item idiom), unlike the post card
 * where the date had to stay inert (#208 review I6) because it sits in flowing content.
 */
@Composable
private fun PostMenuHero(post: Post, onClick: (() -> Unit)?) {
    val openProfileLabel = stringResource(R.string.topic_open_profile_action)
    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            onClick = onClick,
            role = Role.Button,
            onClickLabel = openProfileLabel,
        )
    } else {
        Modifier
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(clickModifier),
    ) {
        RedfaceUserAvatar(
            avatarUrl = post.avatarUrl,
            author = post.author,
            size = 56.dp,
        )
        Column {
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
 * Fires an `ACTION_VIEW` on the canonical permalink — lands in the default browser (or
 * the user's link-handling app). A device without any handler is vanishingly rare but
 * cheap to survive: the failure surfaces as a Toast instead of a crash.
 */
private fun openPermalinkInBrowser(context: Context, permalink: String, failureFeedback: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, permalink.toUri()))
    } catch (ignored: ActivityNotFoundException) {
        Toast.makeText(context, failureFeedback, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Plays the sheet's hide animation, then invokes [onDismiss] once the sheet is actually
 * off-screen — same Material 3 « animated dismiss » idiom as ProfilePreviewSheet's
 * `hideThenNavigate`. `internal` (#831): shared with [PostImageMenuSheet].
 */
@OptIn(ExperimentalMaterial3Api::class)
internal fun hideThenDismiss(
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
