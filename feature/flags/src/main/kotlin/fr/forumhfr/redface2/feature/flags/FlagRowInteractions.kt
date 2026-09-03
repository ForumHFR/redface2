package fr.forumhfr.redface2.feature.flags

/** A fallback Super row without a real HFR category cannot be opened as a topic. */
internal fun flagRowClickEnabled(row: FlagRowUiModel): Boolean = row.cat > 0

/** Local Super fallbacks still need the long-press sheet so their pin can be removed. */
internal fun flagRowActionsEnabled(row: FlagRowUiModel): Boolean = row.topicId > 0
