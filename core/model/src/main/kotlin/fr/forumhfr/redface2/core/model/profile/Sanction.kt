package fr.forumhfr.redface2.core.model.profile

/** HFR sanction; dates retain the server's Paris local time, with normalized spaces. */
data class Sanction(
    val pseudo: String,
    val kind: String,
    val moderator: String,
    val category: String,
    val issuedAt: String,
    val liftedAt: String?,
    val reason: String,
)
