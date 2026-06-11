package fr.forumhfr.redface2.core.model

/**
 * Phase 2F-B (#11 partial), completed by #415 — canonical HFR built-in smiley set.
 *
 * Canonical source : HFR's own help page `smilies.php` (58 typed codes, live fixture
 * `core/parser/src/test/resources/fixtures/smilies_help_page.html`, captured 2026-06-11).
 * The first 25 entries keep the composer-toolbar order (fixture
 * `write_reply_form_open_topic.html`, historical popularity ranking — the picker renders in
 * order) ; the remaining 33 follow in `smilies.php` order. The toolbar only SHOWS a popular
 * subset of what the server interprets, which is how `:sweat:` went missing (#415, beta
 * report by DjullClint).
 *
 * Kept as a Kotlin constant rather than parsed at runtime because :
 *  - the built-in set rarely changes (one drift in the last 10 years observed) ;
 *  - the picker can render the Standard tab without waiting for any GET ;
 *  - every code is verified against a real fixture, so nothing here is an « invented code »
 *    in the sense `AGENTS.md` forbids (cf. CLAUDE.md § « Smileys HFR »).
 *
 * `BuiltinSmileysSymmetryTest` (`:core:parser`) re-parses the `smilies.php` fixture and fails
 * if this constant ever drifts from it (missing code, wrong URL).
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
    // Below : the 33 built-ins the server interprets but the composer toolbar does not show
    // (#415) — smilies.php order.
    EditorSmiley(":'(", "https://forum-images.hardware.fr/icones/ohill.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":eek:", "https://forum-images.hardware.fr/icones/smilies/eek.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":hap:", "https://forum-images.hardware.fr/icones/smilies/hap.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":dtc:", "https://forum-images.hardware.fr/icones/smilies/dtc.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":gun:", "https://forum-images.hardware.fr/icones/smilies/gun.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":miam:", "https://forum-images.hardware.fr/icones/smilies/miam.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":bic:", "https://forum-images.hardware.fr/icones/smilies/bic.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":hebe:", "https://forum-images.hardware.fr/icones/smilies/hebe.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":gratgrat:", "https://forum-images.hardware.fr/icones/smilies/gratgrat.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":hello:", "https://forum-images.hardware.fr/icones/smilies/hello.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":mmmfff:", "https://forum-images.hardware.fr/icones/smilies/mmmfff.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":mouais:", "https://forum-images.hardware.fr/icones/smilies/mouais.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":benetton:", "https://forum-images.hardware.fr/icones/smilies/benetton.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":eek2:", "https://forum-images.hardware.fr/icones/smilies/eek2.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":int:", "https://forum-images.hardware.fr/icones/smilies/int.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":evil:", "https://forum-images.hardware.fr/icones/smilies/evil.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":spamafote:", "https://forum-images.hardware.fr/icones/smilies/spamafote.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":calimero:", "https://forum-images.hardware.fr/icones/smilies/calimero.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":fuck:", "https://forum-images.hardware.fr/icones/smilies/fuck.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":sum:", "https://forum-images.hardware.fr/icones/smilies/sum.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":ouimaitre:", "https://forum-images.hardware.fr/icones/smilies/ouimaitre.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":foudtag:", "https://forum-images.hardware.fr/icones/smilies/foudtag.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":sweat:", "https://forum-images.hardware.fr/icones/smilies/sweat.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":mad:", "https://forum-images.hardware.fr/icones/smilies/mad.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":hot:", "https://forum-images.hardware.fr/icones/smilies/hot.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":bug:", "https://forum-images.hardware.fr/icones/smilies/bug.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":spookie:", "https://forum-images.hardware.fr/icones/smilies/spookie.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":fouyaya:", "https://forum-images.hardware.fr/icones/smilies/fouyaya.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":sleep:", "https://forum-images.hardware.fr/icones/smilies/sleep.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":vomi:", "https://forum-images.hardware.fr/icones/smilies/vomi.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":pouah:", "https://forum-images.hardware.fr/icones/smilies/pouah.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":24:", "https://forum-images.hardware.fr/icones/smilies/24.gif", EditorSmileySource.BUILTIN),
    EditorSmiley(":crazy:", "https://forum-images.hardware.fr/icones/smilies/crazy.gif", EditorSmileySource.BUILTIN),
)
