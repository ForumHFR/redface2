package fr.forumhfr.redface2.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.ui.settings.RedfaceSettingsChoice
import fr.forumhfr.redface2.core.ui.settings.RedfaceSettingsChoiceGroup
import fr.forumhfr.redface2.core.ui.settings.RedfaceSettingsListItem

/**
 * #494 — « Images » sub-page. Extracts the upload provider selector (#459, with the imgur Client-ID
 * field surfaced only for imgur), the image-insert mode and a navigation row to the « Mes images
 * uploadées » screen. Binds its own [SettingsViewModel] instance (DataStore source of truth — same
 * trade-off as `SettingsProxyScreen`).
 */
@Composable
fun SettingsImagesScreen(
    onBack: () -> Unit,
    onOpenMyImages: () -> Unit,
    modifier: Modifier = Modifier,
    topBarActions: @Composable (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val providerOptions = listOf(
        RedfaceSettingsChoice(UploadProviderId.DIBERIE, stringResource(R.string.settings_upload_provider_diberie)),
        RedfaceSettingsChoice(UploadProviderId.IMGUR, stringResource(R.string.settings_upload_provider_imgur)),
    )
    val imageInsertOptions = listOf(
        RedfaceSettingsChoice(EditorImageInsert.FULL, stringResource(R.string.settings_image_insert_full)),
        RedfaceSettingsChoice(EditorImageInsert.LINKED, stringResource(R.string.settings_image_insert_linked)),
        RedfaceSettingsChoice(EditorImageInsert.REDUCED, stringResource(R.string.settings_image_insert_reduced)),
    )
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SettingsSubPageTopBar(
                title = stringResource(R.string.settings_section_images),
                onBack = onBack,
                topBarActions = topBarActions,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_upload_provider_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_upload_provider_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RedfaceSettingsChoiceGroup(
                options = providerOptions,
                selected = state.uploadProvider,
                onSelected = { viewModel.submit(SettingsIntent.SetUploadProvider(it)) },
                enabled = state.canChangeUploadProvider,
            )
            if (state.uploadProviderError) {
                PreferencePersistError(R.string.settings_upload_provider_persist_failed)
            }
            // The Client-ID field is only meaningful for imgur (diberie needs no credentials).
            if (state.uploadProvider == UploadProviderId.IMGUR) {
                OutlinedTextField(
                    value = state.imgurClientId,
                    onValueChange = { viewModel.submit(SettingsIntent.SetImgurClientId(it)) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_upload_imgur_client_id_label)) },
                    supportingText = {
                        Text(stringResource(R.string.settings_upload_imgur_client_id_helper))
                    },
                    isError = state.imgurClientIdError,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.imgurClientIdError) {
                    PreferencePersistError(R.string.settings_upload_imgur_client_id_persist_failed)
                }
            }
            // #459 PR-images follow-up — how the editor wraps an inserted image.
            Text(
                text = stringResource(R.string.settings_image_insert_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_image_insert_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RedfaceSettingsChoiceGroup(
                options = imageInsertOptions,
                selected = state.editorImageInsert,
                onSelected = { viewModel.submit(SettingsIntent.SetEditorImageInsert(it)) },
                enabled = !state.isUpdatingEditorImageInsert,
            )
            if (state.editorImageInsertError) {
                PreferencePersistError(R.string.settings_image_insert_persist_failed)
            }

            HorizontalDivider()
            RedfaceSettingsListItem(
                title = stringResource(R.string.settings_my_images_title),
                description = stringResource(R.string.settings_my_images_description),
                onClick = onOpenMyImages,
                trailingContent = { ChevronTrailing() },
            )
        }
    }
}
