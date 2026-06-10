package fr.forumhfr.redface2.core.ui.error

import androidx.annotation.StringRes
import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
import fr.forumhfr.redface2.core.ui.R

/**
 * #324 — single mapping between the domain failure classification ([HfrErrorKind]) and the
 * user-facing labels shared by every reading screen (topic, forum/catégories, drapeaux,
 * recherche, MP, profil).
 *
 * Contract:
 * - [HfrErrorKind.ServerDown] → [R.string.error_hfr_server_down] (« HFR est en panne ») ;
 * - [HfrErrorKind.Network] → [R.string.error_no_connection] (« Pas de connexion ») ;
 * - [HfrErrorKind.Other] → `null` — there is intentionally NO shared fallback: each screen
 *   keeps its own generic message (or raw diagnostic detail), e.g.
 *   `kind.sharedLabelResOrNull() ?: R.string.flags_error`.
 *
 * Pure function (no Compose dependency) so screens can resolve the id in any context and
 * tests can assert the mapping on the JVM without resources.
 */
@StringRes
fun HfrErrorKind.sharedLabelResOrNull(): Int? = when (this) {
    HfrErrorKind.ServerDown -> R.string.error_hfr_server_down
    HfrErrorKind.Network -> R.string.error_no_connection
    HfrErrorKind.Other -> null
}
