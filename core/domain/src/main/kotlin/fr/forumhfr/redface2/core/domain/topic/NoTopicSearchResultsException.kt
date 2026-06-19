package fr.forumhfr.redface2.core.domain.topic

/**
 * Chantier B (#546) — the intra-topic search (`transsearch.php`) completed normally but HFR found
 * NO message matching the term / author. HFR answers this with a short « aucune réponse n'a été
 * trouvée » page (no posts), not an HTTP error, so the data layer detects the marker and raises this
 * instead of letting the empty page surface as a misleading « recherche échouée ».
 *
 * Distinct from a transport / parse failure : the caller maps it to a sober « Aucun résultat » state
 * (not the retry-inviting error Toast). A frequent term hitting HFR's MyISAM fulltext 50%-rule is the
 * common cause — it is HFR behaviour, not an app bug.
 *
 * Not an [java.io.IOException] : this is a successful HTTP round-trip with an empty result set, not a
 * network condition.
 */
class NoTopicSearchResultsException : Exception("Intra-topic search returned no result")
