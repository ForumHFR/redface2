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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.preferences.ThemeColorPreferences
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.post.readingContentColors
import fr.forumhfr.redface2.core.ui.theme.FlagPalette

internal const val SETTINGS_COLOR_PREVIEW_TAG = "settings_color_preview"
internal const val SETTINGS_COLOR_PREVIEW_POST_TAG = "settings_color_preview_post"
internal const val SETTINGS_COLOR_PREVIEW_HEADER_TAG = "settings_color_preview_header"
internal const val SETTINGS_COLOR_PREVIEW_QUOTE_TAG = "settings_color_preview_quote"
internal const val SETTINGS_COLOR_PREVIEW_SPOILER_TAG = "settings_color_preview_spoiler"
internal val SettingsColorPreviewPostContainerColorKey = SemanticsPropertyKey<Color>(
    "SettingsColorPreviewPostContainerColor",
)
internal val SettingsColorPreviewHeaderContainerColorKey = SemanticsPropertyKey<Color>(
    "SettingsColorPreviewHeaderContainerColor",
)
internal val SettingsColorPreviewQuoteContainerColorKey = SemanticsPropertyKey<Color>(
    "SettingsColorPreviewQuoteContainerColor",
)
internal val SettingsColorPreviewQuoteAccentColorKey = SemanticsPropertyKey<Color>(
    "SettingsColorPreviewQuoteAccentColor",
)
internal val SettingsColorPreviewSpoilerContainerColorKey = SemanticsPropertyKey<Color>(
    "SettingsColorPreviewSpoilerContainerColor",
)

private val PREVIEW_QUOTE_ACCENT_WIDTH: Dp = 4.dp
private val PREVIEW_QUOTE_TEXT_GUTTER: Dp = 12.dp
private val PREVIEW_QUOTE_CONTENT_PADDING: Dp = 12.dp

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
    val headerContainerColor = MaterialTheme.colorScheme.secondaryContainer
    Surface(
        modifier = Modifier
            .testTag(SETTINGS_COLOR_PREVIEW_HEADER_TAG)
            .semantics {
                this[SettingsColorPreviewHeaderContainerColorKey] = headerContainerColor
            },
        color = headerContainerColor,
        contentColor = contentColorFor(headerContainerColor),
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
    val readingColors = readingContentColors()
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
            color = readingColors.linkColor,
        )
        PreviewQuote()
        PreviewSpoiler()
        PreviewFooter()
    }
}

@Composable
private fun PreviewQuote() {
    val readingColors = readingContentColors()
    val quoteContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val quoteAccentColor = MaterialTheme.colorScheme.primary
    Card(
        modifier = Modifier
            .testTag(SETTINGS_COLOR_PREVIEW_QUOTE_TAG)
            .semantics {
                this[SettingsColorPreviewQuoteContainerColorKey] = quoteContainerColor
                this[SettingsColorPreviewQuoteAccentColorKey] = quoteAccentColor
            },
        colors = CardDefaults.cardColors(containerColor = quoteContainerColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRect(
                        color = quoteAccentColor,
                        topLeft = Offset.Zero,
                        size = Size(
                            width = PREVIEW_QUOTE_ACCENT_WIDTH.toPx(),
                            height = this.size.height,
                        ),
                    )
                }
                .padding(
                    start = PREVIEW_QUOTE_ACCENT_WIDTH + PREVIEW_QUOTE_TEXT_GUTTER,
                    top = PREVIEW_QUOTE_CONTENT_PADDING,
                    end = PREVIEW_QUOTE_CONTENT_PADDING,
                    bottom = PREVIEW_QUOTE_CONTENT_PADDING,
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_colors_preview_quote_author),
                style = MaterialTheme.typography.labelMedium,
                color = readingColors.onBodyVariant,
            )
            Text(
                text = stringResource(R.string.settings_colors_preview_quote_body),
                style = MaterialTheme.typography.bodyMedium,
                color = readingColors.onBody,
            )
        }
    }
}

@Composable
private fun PreviewSpoiler() {
    val readingColors = readingContentColors()
    val spoilerContainerColor = previewSpoilerContainerColor()
    Card(
        modifier = Modifier
            .testTag(SETTINGS_COLOR_PREVIEW_SPOILER_TAG)
            .semantics {
                this[SettingsColorPreviewSpoilerContainerColorKey] = spoilerContainerColor
            },
        colors = CardDefaults.cardColors(containerColor = spoilerContainerColor),
    ) {
        Text(
            text = stringResource(R.string.settings_colors_preview_spoiler),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = readingColors.onBodyVariant,
            modifier = Modifier.padding(PREVIEW_QUOTE_CONTENT_PADDING),
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
                color = FlagPalette.Cyan,
                shape = CircleShape,
                content = {},
            )
            Text(
                text = stringResource(R.string.settings_colors_preview_flag),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val replyContainerColor = MaterialTheme.colorScheme.primaryContainer
        Button(
            onClick = {},
            colors = ButtonDefaults.buttonColors(
                containerColor = replyContainerColor,
                contentColor = contentColorFor(replyContainerColor),
            ),
        ) {
            Text(stringResource(R.string.settings_colors_preview_button))
        }
    }
}

// Mirrors PostRenderer.spoilerContainerColor (#978). Reads MaterialTheme directly: the Konsist rule
// keeps the material3 ColorScheme type confined to core ui.
@Composable
private fun previewSpoilerContainerColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.surface == Color.Black) scheme.surfaceBright else scheme.surfaceContainerLow
}
