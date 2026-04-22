package fr.forumhfr.redface2.core.parser.common

object HfrSelectors {
    const val CATEGORY_ID_INPUT = "input[name=cat]"
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
    const val POST_CITATION_AUTHOR = "table.citation b.s1 a.Topic"
    const val POST_EDITED = "div.edited"
    const val POST_SIGNATURE = "span.signature"
}
