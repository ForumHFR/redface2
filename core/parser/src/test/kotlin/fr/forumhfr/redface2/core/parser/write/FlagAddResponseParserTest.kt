package fr.forumhfr.redface2.core.parser.write

import fr.forumhfr.redface2.core.model.write.FlagAddResult
import org.junit.Assert.assertEquals
import org.junit.Test

class FlagAddResponseParserTest {

    private val parser = FlagAddResponseParser()

    @Test
    fun `success response carries the favori positionné sentence`() {
        // Pas de fixture `addflag.php` capturée : ces corps sont MINIMAUX et écrits à la main, ce
        // que la charte fixtures n'autorise que parce qu'ils ne servent PAS de contrat — seul le
        // marqueur de succès « Favori positionné » est attesté (vérifié en live, #986, et consigné
        // dans `docs/guides/protocol-hfr.md`). Le reste du corps est une enveloppe `.hop` calquée
        // sur `flag_delete_success.html`. À remplacer par une capture réelle dès qu'un ajout de
        // favori pourra être fait sur un compte de test sans polluer les drapeaux d'un vrai compte.
        val html = """<html><body><div class="hop">Favori positionné</div></body></html>"""
        assertEquals(FlagAddResult.Success, parser.parse(html))
    }

    @Test
    fun `success response also accepts the longer HFR wording`() {
        // Le marqueur court suffit : s'il existe une variante plus longue côté HFR, elle contient
        // la phrase courte, donc la branche succès la couvre sans qu'on ait à postuler un second
        // libellé. Ce test verrouille cette propriété, il n'atteste pas d'un libellé HFR.
        val html = """<html><body><div class="hop">Favori positionné avec succès</div></body></html>"""
        assertEquals(FlagAddResult.Success, parser.parse(html))
    }

    @Test
    fun `unrecognised page falls back to Failure`() {
        // Defensive : any page that does not carry the success sentence is a non-add,
        // so the caller leaves every cache row it cannot prove untouched.
        val html = "<html><body>Page non documentée</body></html>"
        assertEquals(FlagAddResult.Failure, parser.parse(html))
    }
}
