package fr.forumhfr.redface2.core.model.write

/** ED-100: the strict first-post parser rejected a form without a subcategory selector. */
class MissingFirstPostSubcategoryException : IllegalStateException(
    "topic form has no <select name=subcat> — refusing to edit a first post " +
        "without an explicit sub-category to avoid silent re-categorisation on submit",
)
