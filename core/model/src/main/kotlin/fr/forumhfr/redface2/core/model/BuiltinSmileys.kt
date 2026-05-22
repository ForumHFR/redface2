package fr.forumhfr.redface2.core.model

/**
 * Phase 2F-B (#11 partial) — canonical HFR built-in smiley set.
 *
 * Codes and image URLs come from the live HFR form fixture
 * `core/parser/src/test/resources/fixtures/write_reply_form_open_topic.html` (Phase 2A capture,
 * cf. `<img … onclick="putSmiley(...)">` block). The list is kept as a Kotlin constant rather
 * than parsed from the form at runtime because :
 *  - the built-in set rarely changes (one drift in the last 10 years observed) ;
 *  - exposing it as a constant means the picker can render the Standard tab without waiting
 *    for the editor's form GET to land ;
 *  - the list is verified against a real fixture, so it is not « invented codes » in the sense
 *    `AGENTS.md` forbids (cf. CLAUDE.md § « Smileys HFR »).
 *
 * If HFR ever adds or renames a built-in, the regression surfaces at the next dogfood pass on
 * the picker — at which point we can either bump this constant or move to a runtime extraction
 * from the form HTML (`<img class="smileys">` selector). Phase 2F-B does not need either path.
 *
 * Order matches the order HFR serves the smileys in its toolbar, which itself follows the
 * historical popularity ranking. The picker renders the list in order — no resorting needed.
 */
@Suppress("MaxLineLength") // URL + token + source on one line per smiley keeps the table readable.
val BUILTIN_HFR_SMILEYS: List<EditorSmiley> = listOf(
    EditorSmiley(":pfff:", "https://forum-images.hardware.fr/icones/smilies/pfff.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":ange:", "https://forum-images.hardware.fr/icones/smilies/ange.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":non:", "https://forum-images.hardware.fr/icones/smilies/non.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":bounce:", "https://forum-images.hardware.fr/icones/smilies/bounce.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":)", "https://forum-images.hardware.fr/icones/smile.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":(", "https://forum-images.hardware.fr/icones/frown.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":D", "https://forum-images.hardware.fr/icones/biggrin.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(";)", "https://forum-images.hardware.fr/icones/wink.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":ouch:", "https://forum-images.hardware.fr/icones/smilies/ouch.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":o", "https://forum-images.hardware.fr/icones/redface.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":??:", "https://forum-images.hardware.fr/icones/confused.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":p", "https://forum-images.hardware.fr/icones/tongue.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":na:", "https://forum-images.hardware.fr/icones/smilies/na.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":sarcastic:", "https://forum-images.hardware.fr/icones/smilies/sarcastic.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":fou:", "https://forum-images.hardware.fr/icones/smilies/fou.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":jap:", "https://forum-images.hardware.fr/icones/smilies/jap.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":lol:", "https://forum-images.hardware.fr/icones/smilies/lol.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":wahoo:", "https://forum-images.hardware.fr/icones/smilies/wahoo.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":kaola:", "https://forum-images.hardware.fr/icones/smilies/kaola.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":love:", "https://forum-images.hardware.fr/icones/smilies/love.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":heink:", "https://forum-images.hardware.fr/icones/smilies/heink.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":cry:", "https://forum-images.hardware.fr/icones/smilies/cry.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":whistle:", "https://forum-images.hardware.fr/icones/smilies/whistle.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":sol:", "https://forum-images.hardware.fr/icones/smilies/sol.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":pt1cable:", "https://forum-images.hardware.fr/icones/smilies/pt1cable.gif", EditorSmileySource.BUILTIN),
)
