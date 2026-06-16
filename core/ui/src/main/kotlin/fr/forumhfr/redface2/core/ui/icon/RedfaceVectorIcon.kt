package fr.forumhfr.redface2.core.ui.icon

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Icône-bouton vectorielle unifiée (#360, ADR-015).
 *
 * Le projet **interdit `androidx.compose.material.*`** (détekt `ForbiddenImport`) : pas d'`Icons.*`
 * Material. Les icônes sont donc des **vector drawables locaux** (`:core:ui/res/drawable`) tracés en
 * *stroke* (`strokeWidth` 2.5, caps/joins ronds) pour un poids optique homogène — c'est ce composable
 * qui matérialise la convention décidée pour la flèche retour et étendue aux chevrons de page et au
 * crayon (cf. ADR-015).
 *
 * Un glyphe texte (`Text("←")`, `Text("✎")`…) dépendait de la police système, de la baseline et du
 * font-scale : rendu incohérent selon l'appareil. Ce vector dimensionné en dp est indépendant de la
 * police et optiquement centré par le conteneur (`IconButton` / `*FloatingActionButton`).
 *
 * L'icône est **décorative** : l'étiquette d'accessibilité reste portée par le conteneur cliquable
 * (`contentDescription` sur l'`IconButton`/le FAB), donc [contentDescription] vaut `null` par défaut.
 */
@Composable
fun RedfaceVectorIcon(
    @DrawableRes resId: Int,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = LocalContentColor.current,
    size: Dp = RedfaceIconDefaults.Size,
) {
    Icon(
        painter = painterResource(resId),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint,
    )
}

/** Valeurs de référence de l'iconographie (#360, ADR-015). */
object RedfaceIconDefaults {
    /** Taille canonique des icônes-boutons, alignée sur la flèche retour des top bars. */
    val Size: Dp = 24.dp
}
