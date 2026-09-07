package fr.forumhfr.redface2.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.domain.upload.UploadedImageRecord
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * « Mes images uploadées » screen (#459 PR3). Lists the images previously uploaded by the active
 * HFR pseudo and lets the user delete them (deferred delete: confirm dialog → repository call).
 * Loading / empty / connexion-requise states are handled. Stateful entry point ; the layout lives
 * in [MyImagesContent] so it stays previewable / testable without Hilt.
 */
@Composable
fun MyImagesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    topBarActions: @Composable (() -> Unit)? = null,
    viewModel: MyImagesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MyImagesContent(
        state = state,
        onIntent = viewModel::submit,
        onBack = onBack,
        modifier = modifier,
        topBarActions = topBarActions,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MyImagesContent(
    state: MyImagesUiState,
    onIntent: (MyImagesIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    topBarActions: @Composable (() -> Unit)? = null,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    DeletionMessageEffect(
        message = state.deletionMessage,
        snackbarHostState = snackbarHostState,
        onShown = { onIntent(MyImagesIntent.MessageShown) },
    )
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.my_images_title)) },
                actions = { topBarActions?.invoke() },
                navigationIcon = {
                    // detekt ForbiddenImport blocks `androidx.compose.material.*` (incl.
                    // material-icons), so the back glyph uses the local `ic_arrow_back` vector
                    // from :core:ui rendered with material3 `Icon` (same pattern as ProfileScreen).
                    // The a11y label is on the IconButton ; the icon itself is decorative.
                    val backLabel = stringResource(R.string.my_images_back)
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = backLabel },
                    ) {
                        Icon(
                            painter = painterResource(fr.forumhfr.redface2.core.ui.R.drawable.ic_arrow_back),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val mode = state.mode) {
                MyImagesUiState.Mode.Loading -> CenteredProgress()
                MyImagesUiState.Mode.RequiresLogin ->
                    CenteredMessage(stringResource(R.string.my_images_requires_login))
                is MyImagesUiState.Mode.Content ->
                    if (mode.images.isEmpty()) {
                        CenteredMessage(stringResource(R.string.my_images_empty))
                    } else {
                        ImagesList(
                            images = mode.images,
                            onRequestDelete = { onIntent(MyImagesIntent.RequestDelete(it)) },
                        )
                    }
            }
        }
    }

    val pending = state.pendingDeletion
    if (pending != null) {
        DeleteConfirmDialog(
            record = pending,
            onConfirm = { onIntent(MyImagesIntent.ConfirmDelete) },
            onDismiss = { onIntent(MyImagesIntent.CancelDelete) },
        )
    }
}

@Composable
private fun DeletionMessageEffect(
    message: MyImagesUiState.DeletionMessage?,
    snackbarHostState: SnackbarHostState,
    onShown: () -> Unit,
) {
    val confirmedText = stringResource(R.string.my_images_delete_confirmed)
    val bestEffortText = stringResource(R.string.my_images_delete_best_effort)
    LaunchedEffect(message) {
        if (message != null) {
            val text = when (message) {
                MyImagesUiState.DeletionMessage.Confirmed -> confirmedText
                MyImagesUiState.DeletionMessage.BestEffort -> bestEffortText
            }
            snackbarHostState.showSnackbar(text)
            onShown()
        }
    }
}

@Composable
private fun ImagesList(
    images: List<UploadedImageRecord>,
    onRequestDelete: (UploadedImageRecord) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items = images, key = { "${it.provider.name}:${it.picId}" }) { record ->
            UploadedImageCard(record = record, onRequestDelete = onRequestDelete)
        }
    }
}

@Composable
private fun UploadedImageCard(
    record: UploadedImageRecord,
    onRequestDelete: (UploadedImageRecord) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = record.thumbnailUrl ?: record.imageUrl,
                contentDescription = stringResource(R.string.my_images_thumbnail_description),
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = providerLabel(record.provider),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.my_images_uploaded_at, formatUploadedAt(record)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Always enabled : UploadRepository.delete() evicts the local trace REGARDLESS of the
            // host outcome, so even a handle-less record (canDelete == false) must be removable from
            // the list — the confirm dialog warns when the host cannot confirm (Codex review).
            val deleteLabel = stringResource(R.string.my_images_delete_action)
            TextButton(
                onClick = { onRequestDelete(record) },
                modifier = Modifier.semantics { contentDescription = deleteLabel },
            ) {
                Text(stringResource(R.string.my_images_delete_action))
            }
        }
    }
}

@Composable
private fun DeleteConfirmDialog(
    record: UploadedImageRecord,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The "removed from the host" wording is only honest for a host that CONFIRMS deletion (imgur).
    // Best-effort hosts (diberie) have a delete handle (the picID) but never confirm a server-side
    // deletion, so they fall back to the no-handle wording that only promises the local trace is
    // removed and warns the image may linger on the host (Codex beta review).
    val bodyRes = if (record.deleteHandle != null && record.provider.confirmsHostDeletion) {
        R.string.my_images_delete_confirm_body
    } else {
        R.string.my_images_delete_confirm_body_no_handle
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.my_images_delete_confirm_title)) },
        text = { Text(stringResource(bodyRes)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.my_images_delete_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.my_images_delete_confirm_cancel))
            }
        },
    )
}

@Composable
private fun CenteredProgress() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Human label for an upload host. Not localized (proper nouns), but routed for consistency. */
@Composable
private fun providerLabel(provider: UploadProviderId): String = when (provider) {
    UploadProviderId.DIBERIE -> stringResource(R.string.my_images_provider_diberie)
    UploadProviderId.IMGUR -> stringResource(R.string.my_images_provider_imgur)
}

private val uploadedAtFormatter: DateTimeFormatter = DateTimeFormatter
    .ofPattern("dd/MM/yyyy HH:mm", Locale.FRANCE)
    .withZone(ZoneId.of("Europe/Paris"))

private fun formatUploadedAt(record: UploadedImageRecord): String =
    uploadedAtFormatter.format(record.uploadedAt)
