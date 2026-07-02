package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.ui.R

/**
 * #459 — shared image-upload UI vocabulary of the BBCode editors. Born in `:feature:editor`
 * (reply editor #485, multi-image #490, topic composer PR #766) and promoted here for the MP
 * composers (`:feature:messages`) — same promotion path as [SmileyPickerSheet] (#387) and
 * `EditorOptionsSheet` (#388), so every editor surface shares one error taxonomy, one « n/N »
 * progress label and one banner wording.
 */

/** Ceiling of the photo picker's multi-select (`PickMultipleVisualMedia`), all editor surfaces. */
const val MAX_IMAGES_PER_UPLOAD = 10

/**
 * #459 PR2 — upload failure surfaced to the user, mapped from the repository's typed
 * `UploadException`. `LoginRequired` has no equivalent here : an anonymous session never
 * gets here — the ViewModels ignore a pick without a userId.
 */
sealed interface UploadError {
    /** The picked image exceeds the host's accepted size. */
    data object TooLarge : UploadError

    /** The host rejected the MIME type. */
    data object UnsupportedType : UploadError

    /** The host answered a non-2xx HTTP status. [code] is the status, [providerId] the host (#474). */
    data class Server(val code: Int, val providerId: UploadProviderId) : UploadError

    /** The host answered 2xx but the body could not be parsed into the expected shape (#474). */
    data class Malformed(val providerId: UploadProviderId) : UploadError

    /** The upload provider is not configured (e.g. a blank Imgur Client-ID): the user must set it
     * up in Settings, not retry — so the banner points at configuration, not connectivity (#474). */
    data object Configuration : UploadError

    /** No network / DNS / timeout — also covers an unreadable picked Uri (mapped to Network). */
    data object Network : UploadError
}

/**
 * Progress of a multi-image upload batch: [completed] images uploaded and inserted out of [total]
 * picked. Surfaced as an « n/N » counter while the surface's `isUploading` flag is true.
 */
data class UploadProgress(val completed: Int, val total: Int)

/**
 * Multi-image upload — « n/N » counter shown under the toolbar while a batch is in flight. Emits
 * nothing for a single image (null progress), which only flips the toolbar spinner.
 */
@Composable
fun UploadProgressLabel(progress: UploadProgress?) {
    if (progress == null) return
    Text(
        text = stringResource(R.string.editor_upload_progress, progress.completed, progress.total),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * #474 — resolves the upload-error banner text. [UploadError.Server] / [UploadError.Malformed] carry
 * the host (and the HTTP code for Server), so they need string args and a `@Composable` resolver
 * rather than a plain `@StringRes Int` — the others stay argument-free.
 */
@Composable
fun UploadError.bannerText(): String = when (this) {
    UploadError.TooLarge -> stringResource(R.string.editor_upload_error_too_large)
    UploadError.UnsupportedType -> stringResource(R.string.editor_upload_error_unsupported_type)
    is UploadError.Server ->
        stringResource(R.string.editor_upload_error_server, providerId.displayName(), code)
    is UploadError.Malformed ->
        stringResource(R.string.editor_upload_error_malformed, providerId.displayName())
    UploadError.Configuration -> stringResource(R.string.editor_upload_error_configuration)
    UploadError.Network -> stringResource(R.string.editor_upload_error_network)
}

/** French display name of an image host, for the upload-error banner (#474). */
@Composable
private fun UploadProviderId.displayName(): String = when (this) {
    UploadProviderId.DIBERIE -> stringResource(R.string.editor_upload_provider_diberie)
    UploadProviderId.IMGUR -> stringResource(R.string.editor_upload_provider_imgur)
}
