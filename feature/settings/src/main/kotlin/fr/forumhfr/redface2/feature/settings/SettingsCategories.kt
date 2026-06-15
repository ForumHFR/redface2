package fr.forumhfr.redface2.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import fr.forumhfr.redface2.core.ui.settings.shell.SettingsCategoryGroup
import fr.forumhfr.redface2.core.ui.settings.shell.SettingsCategoryUi
import fr.forumhfr.redface2.core.ui.R as CoreUiR

/**
 * #494 v2 — modèle de la racine « catégories d'abord » : 12 catégories regroupées en 5 familles.
 * Les titres de catégorie réutilisent les titres de section existants ; sous-titres et familles sont
 * propres à la racine v2. Le routage (catégorie → sous-page dédiée ou détail générique) et le mapping
 * catégorie → section(s) du catalogue vivent ici pour rester testables et hors du composant racine.
 */

@Composable
internal fun rememberSettingsCategoryGroups(): List<SettingsCategoryGroup> = listOf(
    SettingsCategoryGroup(
        id = "appearance",
        title = stringResource(R.string.settings_family_appearance),
        categories = listOf(
            category(
                "display", R.string.settings_section_display,
                R.string.settings_category_subtitle_display,
                CoreUiR.drawable.ic_ms_display_settings,
            ),
            category(
                "flags", R.string.settings_section_flags,
                R.string.settings_category_subtitle_flags,
                CoreUiR.drawable.ic_ms_flag,
            ),
            category(
                "topic", R.string.settings_section_topic,
                R.string.settings_category_subtitle_topic,
                CoreUiR.drawable.ic_ms_article,
            ),
        ),
    ),
    SettingsCategoryGroup(
        id = "communication",
        title = stringResource(R.string.settings_family_communication),
        categories = listOf(
            category(
                "mp", R.string.settings_section_mp,
                R.string.settings_category_subtitle_mp,
                CoreUiR.drawable.ic_ms_mail,
            ),
            category(
                "editing", R.string.settings_section_editing,
                R.string.settings_category_subtitle_editing,
                CoreUiR.drawable.ic_ms_edit_square,
            ),
        ),
    ),
    SettingsCategoryGroup(
        id = "content",
        title = stringResource(R.string.settings_family_content),
        categories = listOf(
            category(
                "images", R.string.settings_section_images,
                R.string.settings_category_subtitle_images,
                CoreUiR.drawable.ic_ms_add_photo_alternate,
            ),
            category(
                "start", R.string.settings_section_start,
                R.string.settings_category_subtitle_start,
                CoreUiR.drawable.ic_ms_rocket_launch,
            ),
        ),
    ),
    SettingsCategoryGroup(
        id = "system",
        title = stringResource(R.string.settings_family_system),
        categories = listOf(
            category(
                "network", R.string.settings_section_network,
                R.string.settings_category_subtitle_network,
                CoreUiR.drawable.ic_ms_cached,
            ),
            category(
                "account", R.string.settings_section_hfr_account,
                R.string.settings_category_subtitle_account,
                CoreUiR.drawable.ic_ms_account_circle,
            ),
        ),
    ),
    // #494 — « À venir » éclatée en 3 vraies catégories (décision produit XaTriX 2026-06-15) : chacune
    // pointe sur sa propre section future du catalogue (cf. [sectionIdsForCategory]).
    SettingsCategoryGroup(
        id = "upcoming",
        title = stringResource(R.string.settings_family_upcoming),
        categories = listOf(
            category(
                "notifications", R.string.settings_section_notifications,
                R.string.settings_category_subtitle_notifications,
                CoreUiR.drawable.ic_ms_upcoming,
            ),
            category(
                "accessibility", R.string.settings_section_accessibility,
                R.string.settings_category_subtitle_accessibility,
                CoreUiR.drawable.ic_ms_settings,
            ),
            category(
                "extensions", R.string.settings_section_extensions,
                R.string.settings_category_subtitle_extensions,
                CoreUiR.drawable.ic_ms_article,
            ),
        ),
    ),
)

@Composable
private fun category(id: String, titleRes: Int, subtitleRes: Int, iconRes: Int): SettingsCategoryUi =
    SettingsCategoryUi(
        id = id,
        title = stringResource(titleRes),
        subtitle = stringResource(subtitleRes),
        iconRes = iconRes,
    )

/**
 * Routage d'un tap sur une catégorie de la racine : les catégories adossées à une sous-page dédiée
 * existante y mènent directement (pas de double saut) ; les autres ouvrent un détail générique.
 */
internal fun routeSettingsCategory(
    id: String,
    onOpenDisplay: () -> Unit,
    onOpenImages: () -> Unit,
    onOpenAccountAbout: () -> Unit,
    onOpenCategory: (String) -> Unit,
) {
    when (id) {
        "display" -> onOpenDisplay()
        "images" -> onOpenImages()
        "account" -> onOpenAccountAbout()
        else -> onOpenCategory(id)
    }
}

/**
 * Les sections du catalogue rendues par le détail générique d'une catégorie. Chaque catégorie (y compris
 * les 3 catégories « à venir » Notifications/Accessibilité/Extensions, désormais distinctes) pointe sur
 * la section homonyme du catalogue ; l'identité id-catégorie == id-section suffit.
 */
internal fun sectionIdsForCategory(categoryId: String): List<String> = listOf(categoryId)

/** Titre du détail générique d'une catégorie (réutilise les titres de section quand ils existent). */
internal fun categoryTitleRes(categoryId: String): Int = when (categoryId) {
    "network" -> R.string.settings_section_network
    "start" -> R.string.settings_section_start
    "flags" -> R.string.settings_section_flags
    "topic" -> R.string.settings_section_topic
    "editing" -> R.string.settings_section_editing
    "mp" -> R.string.settings_section_mp
    "notifications" -> R.string.settings_section_notifications
    "accessibility" -> R.string.settings_section_accessibility
    "extensions" -> R.string.settings_section_extensions
    else -> R.string.settings_title
}
