package fr.forumhfr.redface2.core.ui.post

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.parser.TopicPageParser
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #876/#957 (Lot 1B) — the SEGMENTED renderer : structural topology (contract v1.4 §2) wired
 * into [ParagraphBlock], §4 spacing, §6 cold box, #813 parity on the block path, and the
 * four-host posture. Since #958 (Lot 2, §5) the null hosts are TOTALLY inert (see the inert-block
 * test below, Role.Image the a11y target). Screen : 360×780 dp (w360dp qualifier), insets 0 in
 * Robolectric → cold cap = min(780, max(400, 390)) = 400 dp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PostRendererSegmentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val imgA = "https://rehost.diberie.com/Picture/Get/f/segA.png"
    private val imgB = "https://rehost.diberie.com/Picture/Get/f/segB.png"
    private val imgC = "https://rehost.diberie.com/Picture/Get/f/segC.png"

    @OptIn(DelicateCoilApi::class)
    @Before
    fun installFakeImageLoader() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(
                { it is String && it.startsWith("https://rehost.diberie.com/") },
                ColorImage(0xFF2E7D32.toInt(), width = 400, height = 300),
            )
            .intercept(
                { it is String && it.contains("forumhfr.github.io") },
                ColorImage(0xFF6A1B9A.toInt(), width = 300, height = 400),
            )
            .build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
    }

    private fun paragraph(vararg inlines: PostInline) = PostContent(
        blocks = listOf(PostBlock.Paragraph(inlines = toList(inlines))),
    )

    private fun toList(inlines: Array<out PostInline>) = inlines.toList()

    private fun setPost(content: PostContent, selectable: Boolean = false) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PostRenderer(content = content, selectable = selectable)
            }
        }
    }


    private val DpRect.w get() = (right - left).value
    private val DpRect.h get() = (bottom - top).value
    private val br = PostInline.LineBreak
    private fun text(v: String) = PostInline.Text(v)
    private fun img(url: String, desc: String) = PostInline.InlineImage(url = url, description = desc)

    // ---------- §2 branchement : ordre + topologie ----------

    @Test
    fun `gallery flanked by text renders text run text in order with 8dp gaps`() {
        setPost(
            paragraph(
                text("avant"), br,
                img(imgA, "una"), br, img(imgB, "unb"),
                br, text("après"),
            ),
        )
        val before = composeTestRule.onNodeWithText("avant").getBoundsInRoot()
        val a = composeTestRule.onNodeWithContentDescription("una").getBoundsInRoot()
        val b = composeTestRule.onNodeWithContentDescription("unb").getBoundsInRoot()
        val after = composeTestRule.onNodeWithText("après").getBoundsInRoot()
        // Ordre vertical strict.
        assertTrue(before.bottom <= a.top)
        assertTrue(a.bottom <= b.top)
        assertTrue(b.bottom <= after.top)
        // §4 : 8 dp entre les images d'un run, 8 dp entre run et segments voisins.
        assertEquals(8f, (b.top - a.bottom).value, 0.51f)
        assertEquals(8f, (a.top - before.bottom).value, 0.51f)
        assertEquals(8f, (after.top - b.bottom).value, 0.51f)
    }

    @Test
    fun `isolated singleton renders as a block without any measurement`() {
        // Structural : URL MORTE (non interceptée → la mesure échoue) pour que la boîte RESTE le
        // slot cold §6 déterministe — avec une URL servie, la mesure aboutit en cours de test et
        // la boîte devient la taille physique mesurée (le pré-#959 ne le voyait pas : ses valeurs
        // mesurées coïncidaient avec le cold).
        val deadCold = "https://images.example.org/never-served/cold.png"
        setPost(paragraph(text("titre"), br, img(deadCold, "seule"), br, text("fin")))
        // Le painter en échec bascule le slot en état erreur (la description devient le wording
        // d'erreur) : viser le seam structurel du bloc, stable dans tous les états.
        val bounds = composeTestRule.onNodeWithTag(BLOCK_IMAGE_TEST_TAG).getBoundsInRoot()
        // Cold : width = fImage(0,95) × available ; height = max(160, 0,75 × width).
        val width = bounds.w
        assertTrue("cold width ~0.95×container, was $width", width in 320f..355f)
        assertEquals(width * 0.75f, bounds.h, 1f)
    }

    @Test
    fun `inline image in prose stays in the text flow - no block`() {
        setPost(paragraph(text("avant "), img(imgA, "dansletexte"), text(" après")))
        // Un seul nœud texte (la prose) et l'image dedans : pas de séparation verticale majeure.
        // Le texte fusionné contient l'alternateText du placeholder (la description de l'image).
        val textBounds = composeTestRule.onNodeWithText("avant dansletexte après", substring = false).getBoundsInRoot()
        val image = composeTestRule.onNodeWithContentDescription("dansletexte").getBoundsInRoot()
        assertTrue(image.top >= textBounds.top - 1.dp && image.bottom <= textBounds.bottom + 1.dp)
    }

    @Test
    fun `separators around a block are consumed - no blank line`() {
        setPost(paragraph(text("t"), br, br, img(imgA, "im"), br, br, text("s")))
        val t = composeTestRule.onNodeWithText("t").getBoundsInRoot()
        val im = composeTestRule.onNodeWithContentDescription("im").getBoundsInRoot()
        // multi-br consommés : l'écart reste l'unique 8 dp du §4.
        assertEquals(8f, (im.top - t.bottom).value, 0.51f)
    }

    // ---------- §4 : padding inline 4 dp/côté (contexte InlineSegment via lien mixte) ----------

    @Test
    fun `two adjacent inline images in a mixed link are 8dp apart`() {
        val cache = DefaultIntrinsicMediaSizeCache()
        cache.putSuccess(imgA, IntSize(80, 60))
        cache.putSuccess(imgB, IntSize(80, 60))
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalIntrinsicMediaSizeCache provides cache) {
                    PostRenderer(
                        content = paragraph(
                            PostInline.Link(
                                url = "https://forum.hardware.fr",
                                children = listOf(text("lien "), img(imgA, "p1"), img(imgB, "p2"), text(" fin")),
                            ),
                        ),
                    )
                }
            }
        }
        val p1 = composeTestRule.onNodeWithContentDescription("p1").getBoundsInRoot()
        val p2 = composeTestRule.onNodeWithContentDescription("p2").getBoundsInRoot()
        // #959 (§3) — le nœud décrit est le BITMAP à sa taille PHYSIQUE native : 80 px à
        // densité 3 = 26,7 dp (fini le « px natif = sp » qui rendait 80 dp) ; les placeholders
        // adjacents laissent toujours 4 dp + 4 dp = 8 dp entre les deux bitmaps (§4 intact).
        assertEquals(80f / 3f, p1.w, 1.1f)
        assertEquals(8f, (p2.left - p1.right).value, 1.6f)
    }

    @Test
    fun `a width-capped inline bitmap fills the fImage cap - padding widens the placeholder only`() {
        // #959 (§3 px physiques + fImage 0,95) : un 4000×2000 mesuré est cappé par la largeur
        // fImage — 0,95×360 dp×3 = 1026 px → 342 dp, h dérivée = round(1026×2000/4000) = 513 px
        // = 171 dp. Le padding §4 ne rétrécit PAS le bitmap (il élargit le placeholder seul).
        // Le nœud décrit est le bitmap.
        val cache = DefaultIntrinsicMediaSizeCache()
        cache.putSuccess(imgA, IntSize(4000, 2000))
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalIntrinsicMediaSizeCache provides cache) {
                    PostRenderer(
                        content = paragraph(text("avant "), img(imgA, "plafonnee"), text(" après")),
                    )
                }
            }
        }
        val bounds = composeTestRule.onNodeWithContentDescription("plafonnee").getBoundsInRoot()
        assertEquals(342f, bounds.w, 1.1f)
        assertEquals(171f, bounds.h, 1.1f)
    }

    // ---------- §6 cold + tailles mesurées inchangées ----------

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h916dp-xxhdpi")
    fun `measured PORTRAIT block is capped by the clamped useful-height cap - not the cold slot`() {
        // #959 (§3) — 1600×2400 px sur h916dp utile (@Config méthode, insets Robolectric = 0) :
        // capBloc clampé = min(916, max(400, 458)) = 458 dp = 1374 px @d3 → scale = 0,5725 →
        // 916×1374 px = 305,3×458 dp (height-bound). Le cold §6 donnerait 342×256,5 : séparés.
        val cache = DefaultIntrinsicMediaSizeCache()
        cache.putSuccess(imgA, IntSize(1600, 2400))
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalIntrinsicMediaSizeCache provides cache) {
                    PostRenderer(content = paragraph(img(imgA, "portrait")))
                }
            }
        }
        val bounds = composeTestRule.onNodeWithContentDescription("portrait").getBoundsInRoot()
        assertEquals(305.3f, bounds.w, 2f)
        assertEquals(458f, bounds.h, 2f)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h350dp-xxhdpi")
    fun `measured block follows the WINDOW in a short window - the legacy screen cap is gone`() {
        // #959 (§3, [Lot0-3]) — LE témoin discriminant clampé vs legacy : fenêtre utile 350 dp →
        // capBloc = min(350, max(400, 175)) = 350 dp = 1050 px ; le cap legacy
        // max(400, 0,5×350) = 400 dp aurait DÉPASSÉ la fenêtre. 1600×2400 px → scale =
        // 1050/2400 = 0,4375 → 700×1050 px = 233,3×350 dp.
        val cache = DefaultIntrinsicMediaSizeCache()
        cache.putSuccess(imgA, IntSize(1600, 2400))
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalIntrinsicMediaSizeCache provides cache) {
                    PostRenderer(content = paragraph(img(imgA, "courte")))
                }
            }
        }
        val bounds = composeTestRule.onNodeWithContentDescription("courte").getBoundsInRoot()
        assertEquals(233.3f, bounds.w, 2f)
        assertEquals(350f, bounds.h, 2f)
    }

    @Test
    fun `measured block is capped by the fImage width fraction`() {
        // #959 — 4000×3000 px : maxW = 0,95×360dp×3 = 1026 px → 342 dp ; h dérivée =
        // round(1026×3000/4000) = 770 px ≈ 256,7 dp (le cap hauteur 1200 px ne borde pas).
        val cache = DefaultIntrinsicMediaSizeCache()
        cache.putSuccess(imgA, IntSize(4000, 3000))
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalIntrinsicMediaSizeCache provides cache) {
                    PostRenderer(content = paragraph(img(imgA, "large")))
                }
            }
        }
        val bounds = composeTestRule.onNodeWithContentDescription("large").getBoundsInRoot()
        assertEquals(342f, bounds.w, 2f)
        assertEquals(256.7f, bounds.h, 2f)
    }

    @Test
    fun `measured block renders at its native physical size`() {
        val cache = DefaultIntrinsicMediaSizeCache()
        cache.putSuccess(imgA, IntSize(800, 600))
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalIntrinsicMediaSizeCache provides cache) {
                    PostRenderer(content = paragraph(img(imgA, "mesuree")))
                }
            }
        }
        val bounds = composeTestRule.onNodeWithContentDescription("mesuree").getBoundsInRoot()
        // #959 (§3) — natif physique : 800×600 px sous les deux caps (1026 px / 1200 px) →
        // aucun scaling, 800×600 px = 266,7×200 dp @d3 (fini le « px natif = dp » 324×243).
        assertEquals(266.7f, bounds.w, 2f)
        assertEquals(200f, bounds.h, 2f)
    }

    // ---------- fixture réelle : la torture tinc (13.1) ----------

    @Test
    // Viewport haut : les 5 blocs cold (243 dp chacun) débordent un écran de 780 dp et les
    // nœuds hors viewport rapportent des bounds nulles — le banc réel se scrolle, pas ce test.
    @Config(sdk = [34], qualifiers = "w360dp-h2400dp-xxhdpi")
    fun `tinc post renders two spaced galleries with fragments in place`() {
        val html = requireNotNull(
            javaClass.getResource("/fixtures/topic_page_banc_images_876.html"),
        ).readText()
        val topic = TopicPageParser().parse(html)
        val post13 = topic.posts.map { it.content }.first { content ->
            content.blocks.filterIsInstance<PostBlock.Paragraph>().firstOrNull()?.inlines
                ?.filterIsInstance<PostInline.Strong>()?.firstOrNull()?.children
                ?.filterIsInstance<PostInline.Text>()?.any { it.value.startsWith("POST 13") } == true
        }
        setPost(post13)
        // EXACTEMENT 5 images bloc top-level (galerie 3 + galerie 2 — le spoiler fermé ne
        // compose pas sa galerie de 2), comptées par le seam structurel BLOCK_IMAGE_TEST_TAG.
        val blocks = composeTestRule.onAllNodesWithTag(BLOCK_IMAGE_TEST_TAG)
        blocks.assertCountEquals(5)
        val boxes = (0 until 5).map { blocks[it].getBoundsInRoot() }
        // Ordre vertical strict du run order (a11y) + §4 : 8 dp DANS chaque galerie.
        boxes.zipWithNext().forEach { (upper, lower) -> assertTrue(upper.bottom <= lower.top) }
        assertEquals(8f, (boxes[1].top - boxes[0].bottom).value, 0.51f)
        assertEquals(8f, (boxes[2].top - boxes[1].bottom).value, 0.51f)
        assertEquals(8f, (boxes[4].top - boxes[3].bottom).value, 0.51f)
        // Fragments préservés À LEUR PLACE : « 6 » entre les deux galeries, « filles » après.
        val six = composeTestRule.onNodeWithText("6", substring = false).getBoundsInRoot()
        val filles = composeTestRule.onNodeWithText("filles", substring = false).getBoundsInRoot()
        assertTrue(six.top >= boxes[2].bottom && six.bottom <= boxes[3].top)
        assertTrue(filles.top >= boxes[4].bottom)
    }

    // ---------- conteneurs : quote réduite + spoiler révélé ----------

    @Test
    fun `gallery inside a quote renders blocks within the reduced width`() {
        val quote = PostContent(
            blocks = listOf(
                PostBlock.Quote(
                    author = "toto", numreponse = null, page = null,
                    content = paragraph(img(imgA, "qa"), br, img(imgB, "qb")),
                ),
            ),
        )
        setPost(quote)
        val qa = composeTestRule.onNodeWithContentDescription("qa").getBoundsInRoot()
        val qb = composeTestRule.onNodeWithContentDescription("qb").getBoundsInRoot()
        assertEquals(8f, (qb.top - qa.bottom).value, 0.51f)
        // Largeur réduite : le bloc reste dans la quote (< 0,9 × écran).
        assertTrue(qa.w < 324f)
    }

    @Test
    fun `spoiler gallery renders after reveal`() {
        val spoiler = PostContent(
            blocks = listOf(
                PostBlock.Spoiler(
                    label = null,
                    content = paragraph(img(imgA, "sa"), br, img(imgB, "sb")),
                ),
            ),
        )
        setPost(spoiler)
        composeTestRule.onNodeWithText("(afficher)", substring = true).performClick()
        // Arbre NON fusionné : le clickable de la Card spoiler fusionne la sémantique des
        // descendants (les deux descriptions matcheraient le même nœud card-entière).
        val sa = composeTestRule
            .onNodeWithContentDescription("sa", useUnmergedTree = true).getBoundsInRoot()
        val sb = composeTestRule
            .onNodeWithContentDescription("sb", useUnmergedTree = true).getBoundsInRoot()
        assertEquals(8f, (sb.top - sa.bottom).value, 0.51f)
    }

    // ---------- hôtes : non-régression de l'EXISTANT (cible Lot 2 hors périmètre) ----------

    @Test
    fun `linked block without provider is totally inert - Lot 2 target`() {
        // #958 Lot 2 (§5 matrice) : sur un hôte null (MP/aperçu/signature) une image BLOC liée est
        // TOTALEMENT inerte — plus de tap (invère la caractérisation Lot 1B), pas de long-press,
        // pas de Role.Link. Les liens TEXTE restent vivants (couverts par leur propre LinkAnnotation).
        setPost(
            paragraph(
                PostInline.Link(url = "https://example.org/full", children = listOf(img(imgA, "liee"))),
            ),
        )
        composeTestRule.onNodeWithContentDescription("liee")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
    }

    @Test
    fun `unlinked block without provider stays inert`() {
        setPost(paragraph(img(imgA, "inerte")))
        composeTestRule.onNodeWithContentDescription("inerte")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
    }

    @Test
    fun `a11y order follows the run order`() {
        setPost(paragraph(img(imgA, "premiere"), br, img(imgB, "deuxieme"), br, img(imgC, "troisieme")))
        val first = composeTestRule.onNodeWithContentDescription("premiere").getBoundsInRoot()
        val second = composeTestRule.onNodeWithContentDescription("deuxieme").getBoundsInRoot()
        val third = composeTestRule.onNodeWithContentDescription("troisieme").getBoundsInRoot()
        assertTrue(first.top < second.top && second.top < third.top)
    }

    // ---------- #813 : retry d'un MediaRun via la génération ----------

    /** Toutes les URLs demandées au loader, cumulées à travers les swaps d'engine. */
    private val requestedUrls = CopyOnWriteArrayList<String>()

    @OptIn(DelicateCoilApi::class)
    private fun installBlockLoader(serve: Boolean, url: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val builder = FakeImageLoaderEngine.Builder()
        if (serve) builder.intercept(url, ColorImage(0xFFFF5722.toInt(), width = 200, height = 100))
        // sinon : URL non interceptée → ErrorResult Coil = le mode d'échec de production.
        val engine = builder.build()
        val counter = object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                (chain.request.data as? String)?.let(requestedUrls::add)
                return chain.proceed()
            }
        }
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(context).components {
                add(counter)
                add(engine)
            }.build(),
        )
    }

    @Test
    fun `explicit refresh recovers a dead BLOCK image - probe, painter and exact box`() {
        // Parité #813 côté bloc (gate r1) : le même chemin clear + bump que l'inline
        // (PostRendererGhostImageRecoveryTest) doit relancer la probe ET recréer le painter.
        val deadUrl = "https://exemple.invalid/morte.png"
        installBlockLoader(serve = false, url = deadUrl)
        val cache = DefaultIntrinsicMediaSizeCache()
        var generation by mutableIntStateOf(0)
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalIntrinsicMediaSizeCache provides cache) {
                    PostRenderer(
                        content = paragraph(img(deadUrl, "retry")),
                        mediaRefreshGeneration = generation,
                    )
                }
            }
        }
        // Round 1 — échec RÉEL : probe memoïsée en échec, painter en erreur → slot d'erreur
        // visible dans la boîte cold §6.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            cache.isFailureFresh(deadUrl, System.currentTimeMillis())
        }
        composeTestRule.onNodeWithText("Image indisponible", substring = true).assertExists()
        // Bounds via le seam : en état d'erreur le slot remplace le contenu décrit.
        // #959/[AMENDEMENT-v1.5-1] — fImage = 0,95 : 360×0,95 = 342, h = 0,75×342 = 256,5.
        val cold = composeTestRule.onNodeWithTag(BLOCK_IMAGE_TEST_TAG).getBoundsInRoot()
        assertEquals(342f, cold.w, 2f)
        assertEquals(256.5f, cold.h, 2f)

        // Round 2 — l'hébergeur revient, refresh explicite : clear PUIS bump (ordre production).
        installBlockLoader(serve = true, url = deadUrl)
        composeTestRule.waitForIdle()
        val requestsBefore = requestedUrls.count { it == deadUrl }
        composeTestRule.runOnIdle {
            cache.clearFailures()
            generation++
        }
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { cache.get(deadUrl) != null }
        composeTestRule.runOnIdle {}
        // Boîte mesurée EXACTE (200×100 sous les caps → natif), plus d'erreur affichée,
        // et le contenu décrit est revenu (painter en succès).
        composeTestRule.onNodeWithContentDescription("retry").assertExists()
        val healed = composeTestRule.onNodeWithTag(BLOCK_IMAGE_TEST_TAG).getBoundsInRoot()
        // #959 (§3) — 200×100 px servis = 66,7×33,3 dp @d3 (taille physique native).
        assertEquals(66.7f, healed.w, 2f)
        assertEquals(33.3f, healed.h, 2f)
        composeTestRule.onNodeWithText("Image indisponible", substring = true).assertDoesNotExist()
        // ≥ 2 nouvelles requêtes : la re-probe ET le painter recréé par key(generation).
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            requestedUrls.count { it == deadUrl } >= requestsBefore + 2
        }
    }

    // ---------- fast-path : paragraphe sans run ----------

    @Test
    fun `paragraph without media run renders through the historical prose path`() {
        setPost(paragraph(text("juste du texte avec "), img(imgA, "inlineimg"), text(" au milieu")))
        // Aucun nœud bloc : l'image vit dans le flux du texte (même parent sémantique — le texte
        // fusionné porte l'alternateText du placeholder).
        composeTestRule.onNodeWithContentDescription("inlineimg").assertExists()
        composeTestRule.onNodeWithText("juste du texte avec inlineimg au milieu").assertExists()
    }
}
