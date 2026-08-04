package fr.forumhfr.redface2.spike

import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource

/**
 * #989 spike corpus — the perso smileys of HFR's community top-100 (fixture
 * `smileys_top100_roger21.json`), with their NATIVE dimensions read from the served bytes.
 *
 * Provenance: the 74 perso entries of that fixture, each URL fetched and its header parsed. 22 of
 * them turned out to be **JPEG served under a `.gif` extension with `Content-Type: image/gif`** —
 * Coil sniffs the content so the app is unaffected, but it is worth knowing that on HFR the
 * extension does not tell the format. Distribution: 70×50 dominates at 23/74, then 50×50 (7),
 * 67×50 (3), the flat 39×15 / 30×15 band (6), and a tail of 15×15..20×30 mini-sprites.
 *
 * The order is a deterministic big / small / medium interleave — the WORST case for heterogeneity,
 * which is exactly what a cell-outline decision has to be judged against. A size-sorted list would
 * flatter every preset.
 */
private const val PERSO_BASE = "https://forum-images.hardware.fr/images/perso/"

private fun perso(token: String, path: String) =
    EditorSmiley(token, PERSO_BASE + path, EditorSmileySource.WIKI)

internal val SPIKE_PERSO_CORPUS: List<EditorSmiley> = listOf(
    perso("[:moundir]", "moundir.gif"),  // 70×50
    perso("[:tinostar]", "tinostar.gif"),  // 15×15
    perso("[:mc2messiah]", "mc2messiah.gif"),  // 33×35
    perso("[:zedlefou:1]", "1/zedlefou.gif"),  // 70×50
    perso("[:ddr555]", "ddr555.gif"),  // 15×15
    perso("[:ti_thom]", "ti_thom.gif"),  // 59×32
    perso("[:aloy]", "aloy.gif"),  // 70×50
    perso("[:inick:2]", "2/inick.gif"),  // 16×16
    perso("[:silvershaded]", "silvershaded.gif"),  // 61×31
    perso("[:stephan_lapaix]", "stephan_lapaix.gif"),  // 70×50
    perso("[:canaille]", "canaille.gif"),  // 15×20
    perso("[:frag_facile]", "frag_facile.gif"),  // 45×50
    perso("[:moonblood8:9]", "9/moonblood8.gif"),  // 70×50
    perso("[:gratgrat]", "gratgrat.gif"),  // 15×21
    perso("[:tomatookc]", "tomatookc.gif"),  // 70×50
    perso("[:transparency]", "transparency.gif"),  // 20×19
    perso("[:meteorik:1]", "1/meteorik.gif"),  // 70×50
    perso("[:klemton]", "klemton.gif"),  // 20×19
    perso("[:moonbloood:2]", "2/moonbloood.gif"),  // 70×50
    perso("[:ojap]", "ojap.gif"),  // 20×20
    perso("[:discord-mlp]", "discord-mlp.gif"),  // 70×50
    perso("[:cerveau delight]", "cerveau%20delight.gif"),  // 22×19
    perso("[:michel_cymerde:7]", "7/michel_cymerde.gif"),  // 70×50
    perso("[:spamafote]", "spamafote.gif"),  // 30×15
    perso("[:timoonn:6]", "6/timoonn.gif"),  // 70×50
    perso("[:spamafoote]", "spamafoote.gif"),  // 30×15
    perso("[:fegafobobos:2]", "2/fegafobobos.gif"),  // 70×50
    perso("[:airforceone]", "airforceone.gif"),  // 30×15
    perso("[:johnclaude:8]", "8/johnclaude.gif"),  // 70×50
    perso("[:cerveau lent]", "cerveau%20lent.gif"),  // 24×21
    perso("[:so-saugrenu13:10]", "10/so-saugrenu13.gif"),  // 70×50
    perso("[:rofl]", "rofl.gif"),  // 39×15
    perso("[:spawn_cqn:2]", "2/spawn_cqn.gif"),  // 70×50
    perso("[:roxelay]", "roxelay.gif"),  // 39×15
    perso("[:junk1e:3]", "3/junk1e.gif"),  // 70×50
    perso("[:rotflmao]", "rotflmao.gif"),  // 39×15
    perso("[:logicsystem360:5]", "5/logicsystem360.gif"),  // 70×50
    perso("[:o_doc]", "o_doc.gif"),  // 20×30
    perso("[:mme michu:4]", "4/mme%20michu.gif"),  // 70×50
    perso("[:adodonicoco]", "adodonicoco.gif"),  // 32×28
    perso("[:johnclaude:7]", "7/johnclaude.gif"),  // 70×50
    perso("[:otobox:2]", "2/otobox.gif"),  // 70×50
    perso("[:amonchakai:1]", "1/amonchakai.gif"),  // 70×50
    perso("[:apges:5]", "5/apges.gif"),  // 70×50
    perso("[:clooney2]", "clooney2.gif"),  // 70×50
    perso("[:shlavos]", "shlavos.gif"),  // 68×50
    perso("[:nelsonmontel:5]", "5/nelsonmontel.gif"),  // 67×50
    perso("[:adrien monk:2]", "2/adrien%20monk.gif"),  // 67×50
    perso("[:massys]", "massys.gif"),  // 67×50
    perso("[:cosmoschtroumpf]", "cosmoschtroumpf.gif"),  // 70×47
    perso("[:chacal31]", "chacal31.gif"),  // 70×47
    perso("[:vave:3]", "3/vave.gif"),  // 64×50
    perso("[:911gt3]", "911gt3.gif"),  // 65×49
    perso("[:depardieu:3]", "3/depardieu.gif"),  // 70×45
    perso("[:moonblood12:1]", "1/moonblood12.gif"),  // 60×50
    perso("[:lol_yvele]", "lol_yvele.gif"),  // 60×50
    perso("[:caravaj:2]", "2/caravaj.gif"),  // 61×49
    perso("[:siluro]", "siluro.gif"),  // 59×50
    perso("[:vizera]", "vizera.gif"),  // 59×50
    perso("[:rsh:8]", "8/rsh.gif"),  // 58×50
    perso("[:ixemul:3]", "3/ixemul.gif"),  // 55×50
    perso("[:implosion du tibia]", "implosion%20du%20tibia.gif"),  // 60×45
    perso("[:max evans]", "max%20evans.gif"),  // 60×45
    perso("[:la muletta]", "la%20muletta.gif"),  // 54×50
    perso("[:iryngael]", "iryngael.gif"),  // 53×49
    perso("[:clooney24]", "clooney24.gif"),  // 50×50
    perso("[:fredmoul:1]", "1/fredmoul.gif"),  // 50×50
    perso("[:julian33:4]", "4/julian33.gif"),  // 50×50
    perso("[:vince_astuce]", "vince_astuce.gif"),  // 50×50
    perso("[:4lkaline3:2]", "2/4lkaline3.gif"),  // 50×50
    perso("[:moustik42]", "moustik42.gif"),  // 50×50
    perso("[:micheline_tchoutchou1:7]", "7/micheline_tchoutchou1.gif"),  // 50×50
    perso("[:sombrero67]", "sombrero67.gif"),  // 51×49
    perso("[:50dlanj:4]", "4/50dlanj.gif"),  // 49×49
)
