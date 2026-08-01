package fr.forumhfr.redface2.core.model.editor

/**
 * Which composition surface opens when the user starts writing from a topic (#806): the reply FAB,
 * « Citer » on a single post, or « Citer N » on the multi-quote basket. The preset decides the
 * SURFACE only — how citations render inside it (compact cards vs inline `[quotemsg]` BBCode) stays
 * the separate quote-cards preference (#805). The decision is taken AT TAP TIME: a preset change
 * never migrates a sheet (or editor) already open.
 *
 * The enum [name] is serialised verbatim into DataStore — renaming an entry needs a defensive read.
 */
enum class WritingSurfacePreset {
    /**
     * The 0.25.1 behaviour, exactly: every write action opens the quick-reply sheet, except a
     * multi-quote basket of 3+ cards which goes straight to the full-screen editor (the #604
     * lot 3 threshold, which guards THIS preset only). Experimental opt-in while the sheet is
     * not feature-complete (#951).
     */
    SHEET,

    /** The sheet for plain replies; any citation (single « Citer » or « Citer N ») opens the full-screen editor. */
    SHEET_EXCEPT_QUOTES,

    /**
     * Default — every write action opens the full-screen editor; the quick-reply sheet never
     * shows. Became the default when the sheet moved to experimental status (#951).
     */
    FULL_EDITOR,
}
