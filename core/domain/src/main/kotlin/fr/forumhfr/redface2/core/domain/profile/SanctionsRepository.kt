package fr.forumhfr.redface2.core.domain.profile

import fr.forumhfr.redface2.core.model.profile.SanctionsHistory

interface SanctionsRepository {
    /** On-demand authenticated history of the active account; no persistent cache. */
    suspend fun loadSanctions(): Result<SanctionsHistory>
}
