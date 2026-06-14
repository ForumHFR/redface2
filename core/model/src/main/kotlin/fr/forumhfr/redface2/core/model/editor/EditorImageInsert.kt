package fr.forumhfr.redface2.core.model.editor

/**
 * How the editor wraps an inserted image (#459 PR-images follow-up). Chosen in Settings, applied to
 * both uploaded images (which expose a reduced URL) and pasted image URLs (which do not — they
 * degrade [REDUCED] to [LINKED]).
 *
 * The enum [name] is serialised verbatim into DataStore — renaming an entry needs a defensive read.
 */
enum class EditorImageInsert {
    /** `[img]full[/img]` — the raw full-size image, not a link. */
    FULL,

    /** `[url=full][img]full[/img][/url]` — full-size image, click opens the original. */
    LINKED,

    /**
     * `[url=full][img]reduced[/img][/url]` — a reduced thumbnail, click opens the original. The
     * classic HFR "vignette cliquable"; falls back to the full URL when the host exposes no reduced
     * variant (e.g. imgur), which makes it identical to [LINKED].
     */
    REDUCED,
}
