package fr.forumhfr.redface2.core.parser.common

object HfrSelectors {
    const val CATEGORY_ID_INPUT = "input[name=cat]"
    const val SUBCATEGORY_ID_INPUT = "input[name=subcat]"
    const val TOPIC_ID_INPUT = "input[name=post]"
    const val TOPIC_TITLE = "tr.fondForum2Title h3"
    const val TOP_PAGER = "tr.fondForum2PagesHaut"
    const val TOP_PAGER_LEFT = ".left"
    const val TOP_PAGER_CURRENT = "b"
    const val TOP_PAGER_LINK = "a.cHeader"

    const val POST_TABLE = "table.messagetable"
    const val POST_ANCHOR = "td.messCase1 a[name^=t]"
    const val POST_AUTHOR = "td.messCase1 b.s2"
    const val POST_AVATAR = ".avatar_center img[src]"
    const val POST_TOOLBAR_LEFT = ".toolbar .left"
    const val POST_CONTENT = "div[id^=para]"
    // HFR sert deux variantes de table de citation selon le contexte : `table.citation` en
    // anonyme, `table.oldcitation` pour un utilisateur connecté dont le profil utilise le style
    // de citation classique (cas réel observé sur le compte XaTriX, topic RF2 en mode loggé).
    // Les deux doivent être reconnues, sinon la citation d'un post est avalée et rendue en
    // texte brut côté connecté (bug confirmé sur S25, v0.3.21).
    const val POST_CITATION_AUTHOR =
        "table.citation b.s1 a.Topic, table.oldcitation b.s1 a.Topic"
    const val POST_EDITED = "div.edited"
    const val POST_SIGNATURE = "span.signature"

    const val POLL = "div.sondage"
    const val POLL_QUESTION = "b.s2"
    const val POLL_OPTION_BAR = ".sondageLeft"
    const val POLL_OPTION_LABEL = ".sondageRight"
    const val POLL_OPTION_PERCENT = ".sondageTop"

    // Private-message inbox listing (forum1.php?cat=prive). Each conversation is a
    // `tr.sujet` row whose cells carry the read/unread icon, the subject link (which embeds
    // the thread `post` id), the correspondent and the last-activity date. The list pager has
    // the same `.left > b (current) + a.cHeader (links)` shape as the topic pager, only the
    // wrapping row class differs (Forum1 vs Forum2), so it reuses TOP_PAGER_LEFT/CURRENT/LINK.
    const val MP_LIST_ROW = "tr.sujet"
    const val MP_LIST_ICON = "td.sujetCase1 img[src]"
    const val MP_LIST_SUBJECT_LINK = "td.sujetCase3 a.cCatTopic"
    // "Pages" cell: HFR renders a link to the conversation's LAST page there, but only when the
    // conversation spans several pages — a single-page row holds a bare `&nbsp;` (#430).
    const val MP_LIST_LAST_PAGE_LINK = "td.sujetCase4 a.cCatTopic"
    const val MP_LIST_CORRESPONDENT = "td.sujetCase6 a"
    // Multi-recipient (MultiMP / "DT") rows have no profile link: the Interlocuteur cell is a
    // `<span title="…truncated participant list…">Interlocuteurs multiples</span>` instead.
    const val MP_LIST_CORRESPONDENT_GROUP = "td.sujetCase6 span"
    const val MP_LIST_DATE = "td.sujetCase9 a"
    const val MP_LIST_TOP_PAGER = "tr.fondForum1PagesHaut"
    // On an AUTHENTICATED inbox, the pager's forward page-number links are `a.cHeader` on page 1
    // only; on page 2+ HFR obfuscates them as `<span class="md_cryptlink…">N</span>`. The page
    // count must read these too, otherwise `totalPages` collapses to the current page from page 2
    // on, and a paged scan (MPStorage discovery, #6) terminates after page 2.
    const val MP_LIST_PAGER_CRYPTLINK = "span[class^=md_cryptlink]"
}
