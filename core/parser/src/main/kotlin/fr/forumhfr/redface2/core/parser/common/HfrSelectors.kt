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
    const val POST_MODERATION_CELL = "td.messCase1.messageModo"
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

    // Chantier C (#546) — the intra-topic search form HFR renders in the topic-page header
    // (`<form action="/transsearch.php" method="post">`). Its hidden inputs carry the anti-CSRF
    // `hash_check`, the `(post, cat)` ids and the `firstnum` page anchor. We scope every field
    // lookup to THIS form (not `input[name=cat]` document-wide) because the topic page also ships
    // a `cat`/`post` input on the reply + fast-search forms.
    const val TOPIC_SEARCH_FORM = "form[action*=transsearch.php]"
    const val TOPIC_SEARCH_HASH_CHECK = "input[name=hash_check]"
    const val TOPIC_SEARCH_POST = "input[name=post]"
    const val TOPIC_SEARCH_CAT = "input[name=cat]"
    const val TOPIC_SEARCH_FIRSTNUM = "input[name=firstnum]"
    const val TOPIC_SEARCH_OWNTOPIC = "input[name=owntopic]"
    // Chantier B (#546) — the navigation cursor. A NORMAL topic page ships no `currentnum` input
    // (HFR's own JS injects one client-side), so this resolves to null there ; a `transsearch`
    // RESPONSE page carries it with the `numreponse` of the anchored match, which we read back to
    // drive next/previous result navigation and detect the end of results.
    const val TOPIC_SEARCH_CURRENTNUM = "input[name=currentnum]"

    const val POLL = "div.sondage"
    const val POLL_QUESTION = "b.s2"
    const val POLL_OPTION_BAR = ".sondageLeft"
    const val POLL_OPTION_LABEL = ".sondageRight"
    const val POLL_OPTION_PERCENT = ".sondageTop"
    const val POLL_CLOSED_MARKER = "div.sondage + b.s1Ext"

    // #697 — the poll's FORM shape (not-yet-voted / anonymous): options are <ol><li> rows with a
    // vote input and a <label> text. Naming contract (both proven on live fixtures): single-choice
    // = radios ALL named `reponse` (value = option index) ; multi-choice = one checkbox PER option
    // named `reponse1`..`reponseN` (value = 1) — hence the PREFIX match. The container POLL and the
    // question POLL_QUESTION are common to both shapes. The `:has(...)` clause pins each row to its
    // vote input (gate Codex on PR #780): a stray ol/li inside the sondage block can never
    // masquerade as an option.
    const val POLL_FORM_OPTION = "ol > li:has(input[name^=reponse])"
    const val POLL_FORM_OPTION_LABEL = "label"
    const val POLL_FORM_OPTION_INPUT = "input[name^=reponse]"
    const val POLL_FORM_MULTI_INPUT = "input[type=checkbox][name^=reponse]"

    // #779 (PR 1) — HFR's poll VOTE form, served inside a topic page as `<form method="post"
    // action="/user/vote.php?config=hfr.inc">`. It wraps the same `div.sondage` FORM shape parsed
    // for read (POLL_FORM_* above) plus the hidden ids HFR needs to record a vote (`cat`, `p`,
    // `page`, `sondage`, `owntopic`, `subcat`, `numeropost`) and an anti-CSRF `hash_check` that is
    // EMPTY on an anonymous capture (the only shape we have). No vote is submitted in PR 1 : this
    // is parsed into an internal wire model so PR 2/PR 3 can build the POST once an authenticated
    // capture exists. `hash_check` is read separately (see PollVoteFormParser) ; the hidden-field
    // sweep below deliberately grabs `input[type=hidden]` only, so the `reponse*` radios/checkboxes
    // (the choices) and the `sondage_submit` buttons never leak into the hidden map.
    const val POLL_VOTE_FORM = "form[method=post][action*=vote.php]"
    const val POLL_VOTE_HIDDEN_INPUT = "input[type=hidden]"
    const val POLL_VOTE_HASH_CHECK = "input[name=hash_check]"

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
