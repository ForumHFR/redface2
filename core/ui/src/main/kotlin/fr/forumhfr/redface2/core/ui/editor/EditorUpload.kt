package fr.forumhfr.redface2.core.ui.editor

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import fr.forumhfr.redface2.core.ui.R

/** Native photo-picker limit and app-side ceiling for DocumentsUI. */
const val MAX_IMAGES_PER_UPLOAD = 10

/**
 * Progress of a multi-image upload batch: [completed] images uploaded and inserted out of [total]
 * picked. Surfaced as an « n/N » counter while the surface's `isUploading` flag is true.
 */
data class UploadProgress(val completed: Int, val total: Int)

/**
 * Multi-image upload — « n/N » counter shown under the toolbar while a batch is in flight.
 * Callers omit this composable for a single image (`uploadProgress == null`), which only flips the
 * toolbar spinner; this keeps empty children out of spaced editor columns.
 */
@Composable
fun UploadProgressLabel(progress: UploadProgress) {
    Text(
        text = stringResource(R.string.editor_upload_progress, progress.completed, progress.total),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
