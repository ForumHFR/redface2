package fr.forumhfr.redface2.feature.settings

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.forumhfr.redface2.core.domain.preferences.AccentPreset
import fr.forumhfr.redface2.core.domain.preferences.DarkSurfaceTone
import fr.forumhfr.redface2.core.domain.preferences.LightSurfaceTone
import fr.forumhfr.redface2.core.domain.preferences.ThemeAccent
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import fr.forumhfr.redface2.core.ui.settings.RedfaceSettingsChoice
import fr.forumhfr.redface2.core.ui.settings.RedfaceSettingsChoiceGroup

internal const val SETTINGS_COLORS_CUSTOM_HEX_FIELD_TAG = "settings_colors_custom_hex_field"

private const val OPAQUE_ARGB_MASK = -0x1000000
private const val ARGB_LONG_MASK = 0xFFFF_FFFFL
private const val SWATCH_ENABLED_ALPHA = 1f
private const val SWATCH_DISABLED_ALPHA = 0.38f

@Composable
fun SettingsColorsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    topBarActions: @Composable (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsColorsContent(
        state = state,
        callbacks = SettingsColorsCallbacks(
            onBack = onBack,
            onIntent = viewModel::submit,
        ),
        environment = rememberSettingsColorsEnvironment(state.themeMode),
        modifier = modifier,
        topBarActions = topBarActions,
    )
}

@Composable
private fun rememberSettingsColorsEnvironment(themeMode: ThemeMode): SettingsColorsEnvironment {
    val systemDark = isSystemInDarkTheme()
    val effectiveDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemDark
    }
    return SettingsColorsEnvironment(
        effectiveDark = effectiveDark,
        systemColorsAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    )
}

internal data class SettingsColorsCallbacks(
    val onBack: () -> Unit,
    val onIntent: (SettingsIntent) -> Unit,
)

internal data class SettingsColorsEnvironment(
    val effectiveDark: Boolean,
    val systemColorsAvailable: Boolean,
)

@Composable
internal fun SettingsColorsContent(
    state: SettingsState,
    callbacks: SettingsColorsCallbacks,
    environment: SettingsColorsEnvironment,
    modifier: Modifier = Modifier,
    topBarActions: @Composable (() -> Unit)? = null,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SettingsSubPageTopBar(
                title = stringResource(R.string.settings_nav_colors),
                onBack = callbacks.onBack,
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsColorPreview(
                preferences = state.themeColorPreferences,
                darkTheme = environment.effectiveDark,
            )
            SystemColorsSetting(
                state = state,
                environment = environment,
                onIntent = callbacks.onIntent,
            )
            AccentSettings(
                state = state,
                environment = environment,
                onIntent = callbacks.onIntent,
            )
            SurfaceToneSettings(
                state = state,
                environment = environment,
                onIntent = callbacks.onIntent,
            )
            if (state.themeColorsError) {
                PreferencePersistError(R.string.settings_colors_persist_failed)
            }
        }
    }
}

@Composable
private fun SystemColorsSetting(
    state: SettingsState,
    environment: SettingsColorsEnvironment,
    onIntent: (SettingsIntent) -> Unit,
) {
    if (!environment.systemColorsAvailable) return
    ColorsToggleRow(
        title = stringResource(R.string.settings_colors_system_title),
        description = stringResource(R.string.settings_colors_system_description),
        checked = state.themeColorPreferences.dynamicColorEnabled,
        enabled = state.canChangeThemeColors,
        onCheckedChange = { onIntent(SettingsIntent.DynamicColorEnabledChanged(it)) },
    )
}

@Composable
private fun AccentSettings(
    state: SettingsState,
    environment: SettingsColorsEnvironment,
    onIntent: (SettingsIntent) -> Unit,
) {
    val dynamicColorsActive = environment.systemColorsAvailable &&
        state.themeColorPreferences.dynamicColorEnabled
    val enabled = state.canChangeThemeColors && !dynamicColorsActive
    SettingsSectionTitle(
        title = stringResource(R.string.settings_colors_accent_title),
        description = if (dynamicColorsActive) {
            stringResource(R.string.settings_colors_accent_disabled_by_system)
        } else {
            stringResource(R.string.settings_colors_accent_description)
        },
    )
    AccentPresetFlow(
        selected = state.themeColorPreferences.accent,
        enabled = enabled,
        onSelected = { onIntent(SettingsIntent.ThemeAccentPresetChanged(it)) },
    )
    CustomAccentField(
        state = CustomAccentFieldState(
            text = state.customAccentHexInput,
            error = state.customAccentHexError,
            previewRgb = state.customAccentPreviewRgb,
        ),
        enabled = enabled,
        callbacks = CustomAccentFieldCallbacks(
            onChange = { onIntent(SettingsIntent.CustomAccentHexChanged(it)) },
            onCommit = { onIntent(SettingsIntent.CustomAccentHexCommitted) },
        ),
    )
}

@Composable
private fun SurfaceToneSettings(
    state: SettingsState,
    environment: SettingsColorsEnvironment,
    onIntent: (SettingsIntent) -> Unit,
) {
    SettingsSectionTitle(
        title = stringResource(R.string.settings_colors_surface_title),
        description = stringResource(R.string.settings_colors_surface_description),
    )
    LightSurfaceToneSetting(
        selected = state.themeColorPreferences.lightSurfaceTone,
        active = !environment.effectiveDark,
        enabled = state.canChangeThemeColors,
        onSelected = { onIntent(SettingsIntent.LightSurfaceToneChanged(it)) },
    )
    DarkSurfaceToneSetting(
        selected = state.themeColorPreferences.darkSurfaceTone,
        active = environment.effectiveDark,
        enabled = state.canChangeThemeColors,
        onSelected = { onIntent(SettingsIntent.DarkSurfaceToneChanged(it)) },
    )
}

@Composable
private fun SettingsSectionTitle(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ColorsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AccentPresetFlow(
    selected: ThemeAccent,
    enabled: Boolean,
    onSelected: (AccentPreset) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AccentPreset.entries.forEach { preset ->
            AccentPresetChip(
                preset = preset,
                selected = selected == ThemeAccent.Preset(preset),
                enabled = enabled,
                onSelected = onSelected,
            )
        }
    }
}

@Composable
private fun AccentPresetChip(
    preset: AccentPreset,
    selected: Boolean,
    enabled: Boolean,
    onSelected: (AccentPreset) -> Unit,
) {
    FilterChip(
        selected = selected,
        enabled = enabled,
        onClick = { onSelected(preset) },
        leadingIcon = {
            ColorSwatch(rgb = preset.seedRgb, enabled = enabled, modifier = Modifier.size(16.dp))
        },
        label = { Text(accentPresetLabel(preset)) },
    )
}

@Composable
private fun accentPresetLabel(preset: AccentPreset): String = when (preset) {
    AccentPreset.ROSE -> stringResource(R.string.settings_colors_accent_rose)
    AccentPreset.ROUGE_REDFACE1 -> stringResource(R.string.settings_colors_accent_rouge_redface1)
    AccentPreset.BLUE -> stringResource(R.string.settings_colors_accent_blue)
    AccentPreset.GREEN -> stringResource(R.string.settings_colors_accent_green)
    AccentPreset.VIOLET -> stringResource(R.string.settings_colors_accent_violet)
    AccentPreset.ORANGE -> stringResource(R.string.settings_colors_accent_orange)
    AccentPreset.TEAL -> stringResource(R.string.settings_colors_accent_teal)
    AccentPreset.NEUTRAL -> stringResource(R.string.settings_colors_accent_neutral)
}

private data class CustomAccentFieldState(
    val text: String,
    val error: Boolean,
    val previewRgb: Int?,
)

private data class CustomAccentFieldCallbacks(
    val onChange: (String) -> Unit,
    val onCommit: () -> Unit,
)

@Composable
private fun CustomAccentField(
    state: CustomAccentFieldState,
    enabled: Boolean,
    callbacks: CustomAccentFieldCallbacks,
) {
    val focusManager = LocalFocusManager.current
    var wasFocused by remember { mutableStateOf(false) }
    var skipNextBlurCommit by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = state.text,
        onValueChange = callbacks.onChange,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = SETTINGS_COLORS_CUSTOM_HEX_FIELD_TAG }
            .onFocusChanged { focusState ->
                if (wasFocused && !focusState.isFocused) {
                    if (skipNextBlurCommit) {
                        skipNextBlurCommit = false
                    } else {
                        callbacks.onCommit()
                    }
                }
                wasFocused = focusState.isFocused
            },
        enabled = enabled,
        isError = state.error,
        singleLine = true,
        label = { Text(stringResource(R.string.settings_colors_custom_label)) },
        supportingText = {
            val text = if (state.error) {
                stringResource(R.string.settings_colors_custom_error)
            } else {
                stringResource(R.string.settings_colors_custom_helper)
            }
            Text(text)
        },
        trailingIcon = {
            ColorSwatch(rgb = state.previewRgb, enabled = enabled)
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                callbacks.onCommit()
                skipNextBlurCommit = true
                focusManager.clearFocus()
            },
        ),
    )
}

@Composable
private fun LightSurfaceToneSetting(
    selected: LightSurfaceTone,
    active: Boolean,
    enabled: Boolean,
    onSelected: (LightSurfaceTone) -> Unit,
) {
    Text(
        text = surfaceGroupTitle(stringResource(R.string.settings_colors_light_surface_title), active),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    RedfaceSettingsChoiceGroup(
        options = listOf(
            RedfaceSettingsChoice(
                LightSurfaceTone.MATERIAL_TINTED,
                stringResource(R.string.settings_colors_surface_material_tinted),
            ),
            RedfaceSettingsChoice(LightSurfaceTone.WHITE, stringResource(R.string.settings_colors_surface_white)),
            RedfaceSettingsChoice(
                LightSurfaceTone.REDFACE1_GRAY,
                stringResource(R.string.settings_colors_surface_redface1_gray),
            ),
        ),
        selected = selected,
        onSelected = onSelected,
        enabled = enabled,
    )
}

@Composable
private fun DarkSurfaceToneSetting(
    selected: DarkSurfaceTone,
    active: Boolean,
    enabled: Boolean,
    onSelected: (DarkSurfaceTone) -> Unit,
) {
    Text(
        text = surfaceGroupTitle(stringResource(R.string.settings_colors_dark_surface_title), active),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    RedfaceSettingsChoiceGroup(
        options = listOf(
            RedfaceSettingsChoice(
                DarkSurfaceTone.MATERIAL_TINTED,
                stringResource(R.string.settings_colors_surface_material_tinted),
            ),
            RedfaceSettingsChoice(DarkSurfaceTone.AMOLED, stringResource(R.string.settings_colors_surface_amoled)),
        ),
        selected = selected,
        onSelected = onSelected,
        enabled = enabled,
    )
}

@Composable
private fun surfaceGroupTitle(title: String, active: Boolean): String =
    if (active) stringResource(R.string.settings_colors_surface_active, title) else title

@Composable
private fun ColorSwatch(
    rgb: Int?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = rgb?.toOpaqueComposeColor() ?: MaterialTheme.colorScheme.surfaceVariant
    val alpha = if (enabled) SWATCH_ENABLED_ALPHA else SWATCH_DISABLED_ALPHA
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(18.dp)
            .alpha(alpha)
            .background(color = color, shape = CircleShape)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), CircleShape),
    )
}

private fun Int.toOpaqueComposeColor(): Color =
    Color((OPAQUE_ARGB_MASK or this).toLong() and ARGB_LONG_MASK)
