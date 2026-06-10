package fr.forumhfr.redface2.core.parser.mpstorage

import org.jsoup.Jsoup

/**
 * MPStorage discovery (#6, ADR-014) — extracts the storage conversation's thread id from
 * the authenticated `cat=prive` subject-search listing (fixtures `mp_storage_search_hit.html`
 * / `mp_storage_search_no_results.html`, captured live 2026-06-11).
 *
 * The subject being a fixed 32-hex hash, a hit listing carries (at most) one relevant row ;
 * we take the FIRST conversation link. The no-results page (« Désolé, aucune réponse n'a été
 * trouvée ! », same shape as the public search) simply has no such link → `null`, which the
 * repository maps to the first-class « no storage on this account » outcome.
 */
class MpStorageDiscoveryParser {

    fun parseFirstThreadId(html: String): Int? {
        val document = Jsoup.parse(html)
        val link = document.selectFirst("a.cCatTopic[href*=cat=prive]") ?: return null
        return POST_ID.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
    }

    private companion object {
        private val POST_ID = Regex("""post=(\d+)""")
    }
}
