package fr.forumhfr.redface2.core.parser.smiley

import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import org.jsoup.Jsoup

/**
 * Phase 2F-B (#11 partial) — parses the wiki-search HTML fragment HFR returns at
 * `GET /message-smi-mp-aj.php?config=hfr.inc&user_id=<id>&findsmilies=<query>`.
 *
 * The response is **not** a full HTML page : it's a plain fragment of one
 * `<img src="…" alt="[:token]" title="[:token]" onclick="putSmiley(this.alt,this.src)" />` per
 * matching smiley, concatenated without separators. `Jsoup.parse` accepts the fragment because
 * its lenient parser wraps it in `<html><body>` automatically.
 *
 * Behaviour pinned by [SmileySearchParserTest] against the real fixture
 * `smiley_search_jap.html` captured 2026-05-22 :
 *  - the token comes from `alt` (falls back to `title`), kept verbatim ;
 *  - the image URL comes from `src` ;
 *  - entries missing either field are dropped ;
 *  - duplicates by `(token, url)` are deduplicated in declaration order, the first occurrence
 *    wins ; HFR sometimes emits the same smiley twice in a row on perso variants
 *    (`[:grozibouille:1]`, `[:pradar:3]`, `[:redneck wannabe:1]` in the live fixture) ;
 *  - perso variants `[:name:N]`, names with spaces / accents / underscores / dashes /
 *    apostrophes / internal colons all survive untouched (no normalisation).
 */
class SmileySearchParser {

    fun parse(fragment: String): List<EditorSmiley> {
        val document = Jsoup.parse(fragment)
        val seen = HashSet<Pair<String, String>>()
        return document.select("img")
            .mapNotNull { img ->
                // Prefer `alt` ; some legacy variants only carry `title`. We never invent a
                // token from the URL : if neither attribute carries a token we drop the row.
                val token = img.attr("alt").takeIf { it.isNotBlank() }
                    ?: img.attr("title").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val src = img.attr("src").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                EditorSmiley(token = token, imageUrl = src, source = EditorSmileySource.WIKI)
            }
            .filter { entry -> seen.add(entry.token to entry.imageUrl) }
    }
}
