package fr.forumhfr.redface2.feature.flags

import fr.forumhfr.redface2.core.model.Flag

/**
 * Canonical HFR web URL of a flagged topic (#603 PR5) — for « copier le lien » / « ouvrir dans le
 * navigateur » from the long-press sheet. Mirrors the `forum2.php` permalink shape (cf. the topic
 * permalink builder) and resumes at the user's last-read page. Pure, testable.
 */
fun flagTopicUrl(flag: Flag): String =
    "https://forum.hardware.fr/forum2.php?config=hfr.inc" +
        "&cat=${flag.cat}&post=${flag.topicId}&page=${flag.lastReadPage.coerceAtLeast(1)}"
