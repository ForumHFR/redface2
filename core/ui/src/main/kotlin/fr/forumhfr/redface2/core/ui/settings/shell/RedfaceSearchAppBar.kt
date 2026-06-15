package fr.forumhfr.redface2.core.ui.settings.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.R

/**
 * #494 v2 — Search app bar (docked search bar M3) du shell de configuration : icône menu (hamburger,
 * rôle à définir) · champ de recherche cliquable · avatar de compte. Custom STABLE (Surface + Row +
 * pill cliquable), pas la `SearchBar`/`DockedSearchBar` `@ExperimentalMaterial3Api` : cette barre est
 * le chrome PERMANENT de la zone réglages (et un pilote app-wide), on veut le contrôle total des
 * insets edge-to-edge, du back et de l'IME sans opt-in expérimental.
 *
 * Edge-to-edge : la barre applique `statusBarsPadding()` pour se loger sous la status bar transparente.
 * Le pill ouvre la recherche ([onSearchClick]) ; le filtrage/résultats vivent au niveau du shell.
 *
 * [elevated] pilote l'effet « contenu sous la barre » (idiome M3) : au repos la barre est `surface`
 * (continue avec le contenu) ; quand du contenu scrolle DESSOUS, elle prend la teinte `surfaceContainer`
 * + une ombre fine. Le shell la superpose à la liste (Box + `contentPadding` haut) et passe
 * `elevated = listState.canScrollBackward`. Transition tonale animée (pas de flou : non-standard M3).
 */
@Composable
@Suppress("LongParameterList") // Top-bar API : placeholder + 2 a11y labels + 2 callbacks + elevated + avatar.
fun RedfaceSearchAppBar(
    placeholder: String,
    menuContentDescription: String,
    searchContentDescription: String,
    onMenuClick: (() -> Unit)? = null,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    avatar: @Composable () -> Unit = { DefaultAvatarPlaceholder() },
) {
    val containerColor by animateColorAsState(
        targetValue = if (elevated) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        label = "searchAppBarContainer",
    )
    val shadowElevation by animateDpAsState(
        targetValue = if (elevated) 3.dp else 0.dp,
        label = "searchAppBarShadow",
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        shadowElevation = shadowElevation,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Hamburger gardé par décision produit (#494 v2), rôle à définir : désactivé tant qu'aucune
            // action n'est câblée → présent visuellement mais pas de bouton mort (a11y : annoncé désactivé).
            IconButton(
                onClick = onMenuClick ?: {},
                enabled = onMenuClick != null,
                modifier = Modifier.semantics { contentDescription = menuContentDescription },
            ) {
                Icon(painter = painterResource(R.drawable.ic_ms_menu), contentDescription = null)
            }
            Surface(
                onClick = onSearchClick,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp) // gabarit docked search bar M3 ; porte l'app bar à ~72dp (Claude+Codex)
                    .semantics { contentDescription = searchContentDescription },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            avatar()
        }
    }
}

/** Avatar de compte par défaut (placeholder) — remplacé par l'avatar HFR réel quand connecté (#479). */
@Composable
private fun DefaultAvatarPlaceholder() {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {}
}
