package fr.forumhfr.redface2.core.ui.settings.shell

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R

/**
 * #494 v2 — une catégorie de la racine « catégories d'abord ». Identité visuelle : icône dans un
 * conteneur tonal rond + titre + sous-titre optionnel + chevron. La racine n'expose QUE des catégories
 * (aucun toggle/bouton inline) : chaque ligne ouvre une sous-vue de réglages.
 */
data class SettingsCategoryUi(
    val id: String,
    val title: String,
    val subtitle: String?,
    @param:DrawableRes val iconRes: Int,
)

/**
 * #494 v2 — un groupe de catégories (famille) de la racine, avec un en-tête de section. Le regroupement
 * (D4 léger) donne une carte mentale à la liste sans exposer de réglage inline. Le groupage est
 * éditorial et fourni par la couche feature ; `:core:ui` ne fait que le rendre.
 */
data class SettingsCategoryGroup(
    val id: String,
    val title: String,
    val categories: List<SettingsCategoryUi>,
)

/**
 * #494 v2 — racine du menu de configuration (direction verrouillée 2026-06-15, Claude + Codex) :
 * [RedfaceSearchAppBar] + liste de catégories REGROUPÉES par familles (D4 léger), chaque ligne en
 * cercle tonal + sous-titre + chevron (D1). En-têtes de section sobres (espace blanc plutôt que
 * separator). Stateless et Roborazzi-able sans nav/Hilt (groupes + callbacks hoistés).
 */
@Composable
@Suppress("LongParameterList") // shell racine : data + placeholder + 2 a11y + 3 callbacks, tous distincts.
fun SettingsHomeScreen(
    groups: List<SettingsCategoryGroup>,
    searchPlaceholder: String,
    menuContentDescription: String,
    searchContentDescription: String,
    onMenuClick: (() -> Unit)? = null,
    onSearchClick: () -> Unit,
    onCategoryClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    accountSlot: (@Composable () -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    // « Contenu sous la barre » (edge-to-edge) : la barre est SUPERPOSÉE à la liste (Box, pas Column),
    // donc le contenu glisse derrière elle. Elle s'élève (teinte surfaceContainer) dès que la liste a
    // défilé — canScrollBackward passe true au 1er pixel sous la barre.
    val barElevated by remember { derivedStateOf { listState.canScrollBackward } }
    // Hauteur réelle de la barre (status bar + pill + paddings), mesurée au runtime et reportée en
    // contentPadding haut : le 1er item démarre VISIBLE juste sous la barre, puis passe DERRIÈRE elle.
    var barHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = with(density) { barHeightPx.toDp() }),
        ) {
            groups.forEachIndexed { index, group ->
                item(key = "header_${group.id}") {
                    SettingsSectionHeader(title = group.title, first = index == 0)
                }
                items(group.categories, key = { it.id }) { category ->
                    SettingsCategoryRow(
                        category = category,
                        onClick = { onCategoryClick(category.id) },
                    )
                }
            }
        }
        // Barre superposée en haut. Le slot compte (menu app-wide) remplace l'avatar placeholder quand
        // le host le fournit. onSizeChanged alimente le contentPadding de la liste ci-dessus.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { barHeightPx = it.height },
        ) {
            if (accountSlot != null) {
                RedfaceSearchAppBar(
                    placeholder = searchPlaceholder,
                    menuContentDescription = menuContentDescription,
                    searchContentDescription = searchContentDescription,
                    onMenuClick = onMenuClick,
                    onSearchClick = onSearchClick,
                    elevated = barElevated,
                    avatar = accountSlot,
                )
            } else {
                RedfaceSearchAppBar(
                    placeholder = searchPlaceholder,
                    menuContentDescription = menuContentDescription,
                    searchContentDescription = searchContentDescription,
                    onMenuClick = onMenuClick,
                    onSearchClick = onSearchClick,
                    elevated = barElevated,
                )
            }
        }
        // Scrim : juste sous la barre, un court dégradé surfaceContainer → transparent qui fond le
        // contenu émergeant de dessous la barre (pas de coupure nette). N'apparaît qu'à l'élévation.
        val scrimAlpha by animateFloatAsState(
            targetValue = if (barElevated) 1f else 0f,
            label = "searchBarScrim",
        )
        val scrimTop = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = scrimAlpha)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .offset { IntOffset(0, barHeightPx) }
                .height(24.dp)
                .background(Brush.verticalGradient(listOf(scrimTop, Color.Transparent))),
        )
    }
}

/**
 * En-tête de section sobre : `titleSmall`, teinte primaire, padding start 16dp. Le premier en-tête
 * (juste sous l'app bar) a un top réduit (16dp) ; les suivants respirent davantage (24dp) — gabarit
 * M3 « espace plutôt que divider ».
 */
@Composable
private fun SettingsSectionHeader(title: String, first: Boolean) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = 16.dp,
            end = 16.dp,
            top = if (first) 16.dp else 24.dp,
            bottom = 8.dp,
        ),
    )
}

@Composable
private fun SettingsCategoryRow(
    category: SettingsCategoryUi,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(category.title) },
        supportingContent = category.subtitle?.let { sub -> { Text(sub) } },
        leadingContent = {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(category.iconRes),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        },
        trailingContent = {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}
