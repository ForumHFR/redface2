package fr.forumhfr.redface2.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.preferences.ThemeColorPreferences
import fr.forumhfr.redface2.core.ui.RedfaceTheme

internal const val SETTINGS_COLOR_PREVIEW_TAG = "settings_color_preview"
internal const val SETTINGS_COLOR_PREVIEW_POST_TAG = "settings_color_preview_post"
internal val SettingsColorPreviewPostContainerColorKey = SemanticsPropertyKey<Color>(
    "SettingsColorPreviewPostContainerColor",
)

/** Live colour preview for Settings > Display > Colours. */
@Composable
internal fun SettingsColorPreview(
    preferences: ThemeColorPreferences,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    RedfaceTheme(darkTheme = darkTheme, themeColorPreferences = preferences) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = SETTINGS_COLOR_PREVIEW_TAG
                },
            color = MaterialTheme.colorScheme.background,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_colors_preview_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                PreviewPost()
            }
        }
    }
}

@Composable
private fun PreviewPost() {
    val postContainerColor = MaterialTheme.colorScheme.surfaceContainer
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SETTINGS_COLOR_PREVIEW_POST_TAG)
            .semantics {
                this[SettingsColorPreviewPostContainerColorKey] = postContainerColor
            },
        color = postContainerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PreviewHeader()
            PreviewBody()
        }
    }
}

@Composable
private fun PreviewHeader() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_colors_preview_pseudo),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.settings_colors_preview_date),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun PreviewBody() {
    Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_colors_preview_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.settings_colors_preview_link),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        PreviewQuote()
        PreviewSpoiler()
        PreviewFooter()
    }
}

@Composable
private fun PreviewQuote() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_colors_preview_quote_author),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = stringResource(R.string.settings_colors_preview_quote_body),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PreviewSpoiler() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = stringResource(R.string.settings_colors_preview_spoiler),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun PreviewFooter() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(14.dp),
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
                content = {},
            )
            Text(
                text = stringResource(R.string.settings_colors_preview_flag),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = {}) {
            Text(stringResource(R.string.settings_colors_preview_button))
        }
    }
}
