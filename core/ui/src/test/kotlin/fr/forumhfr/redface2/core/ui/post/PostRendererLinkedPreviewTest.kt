package fr.forumhfr.redface2.core.ui.post

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.size.Size
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.domain.preferences.MediaDisplayProfile
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.theme.LocalMediaDisplayProfile
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
 * #876 ([AMENDEMENT-v1.5-4]) — the linked-preview upscale WIRED into the renderer, the counterpart
 * of the pure [isEligibleLinkedPreview] contract ([LinkedPreviewEligibilityTest]):
 *
 *  - an eligible linked thumbnail renders in a box of `mApercu = min(densité, 3)` × its native
 *    physical size, i.e. its source pixels read as dp — the sub-size the amendment fixes;
 *  - the plafond effectif is `mEffectif = max(mApercu, mGif)`: the two multipliers relax the SAME
 *    no-upscale ceiling and the largest wins — they NEVER multiply (a linked GIF is ×3 under M,
 *    never ×4,5) — and that `max` IS the `1,0` floor, so a density below 1 never SHRINKS an image;
 *  - the guards close the false positives at the render site: auto-link, foreign host, native axis
 *    past 400 px;
 *  - §7 stays unchanged ON THIS PATH: the decode request of an enlarged preview is clamped at the
 *    NATIVE dimensions (the enlargement happens at draw), and the hard caps (fImage × container,
 *    capBloc) re-clamp an ELIGIBLE push past them;
 *  - the §6 anti-CLS shape is untouched: the cold slot takes no factor and the arrival of the
 *    dimensions produces THE single correction, computed directly with `mEffectif` (covered on the
 *    painter-only path, probe dead — `mApercu` does not depend on the MIME);
 *  - INLINE stays out of scope, `linkUrl` or not.
 *
 * Screen 360×780 dp @xxhdpi (density 3) unless a method overrides the qualifiers: fImage cap =
 * 0.95×1080 = 1026 px (342 dp), capBloc = min(780, max(400, 390)) = 400 dp = 1200 px.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostRendererLinkedPreviewTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** The real diberie shape: a `/t/` thumbnail wrapped in a link to its `/f/` full-size page. */
    private val thumbUrl = "https://reho.st/t/abcdef0123456789.jpg"
    private val fullUrl = "https://reho.st/f/abcdef0123456789.jpg"
    private val foreignUrl = "https://example.org/f/abcdef0123456789.jpg"

    private val appContext: Context = ApplicationProvider.getApplicationContext()

    /**
     * (url, requested decode size) of every NON-probe (painter) request — the §7 seam, same
     * recording harness as [PostRendererGifProfileTest].
     */
    private val recordedDecodeSizes = CopyOnWriteArrayList<Pair<String, Size>>()

    @OptIn(DelicateCoilApi::class)
    @Before
    fun installFakeImageLoader() {
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(
                { it is String && it.startsWith("https://reho.st/") },
                ColorImage(0xFF2E7D32.toInt(), width = 150, height = 112),
            )
            .build()
        val recorder = object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                val data = chain.request.data
                if (data is String && chain.request.decoderFactory !is ProbeMetadataDecoder.Factory) {
                    recordedDecodeSizes.add(data to chain.size)
                }
                return chain.proceed()
            }
        }
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(appContext).components {
                add(recorder)
                add(engine)
            }.build(),
        )
    }

    // ---------- harnais ----------

    private fun img(url: String, desc: String) = PostInline.InlineImage(url = url, description = desc)

    /** `[url=link][img]url[/img][/url]` isolated on its line → the BLOCK path with a linkUrl. */
    private fun linkedBlock(url: String, link: String, desc: String) = PostContent(
        blocks = listOf(
            PostBlock.Paragraph(inlines = listOf(PostInline.Link(url = link, children = listOf(img(url, desc))))),
        ),
    )

    private fun cacheOf(
        url: String = thumbUrl,
        native: IntSize = IntSize(150, 112),
        mime: String? = "image/jpeg",
    ) = DefaultIntrinsicMediaSizeCache().apply {
        putSuccess(url, IntrinsicMediaMetadata(native, mimeType = mime))
    }

    private fun setPost(
        cache: IntrinsicMediaSizeCache,
        content: PostContent,
        profile: MediaDisplayProfile? = null,
        ledger: MediaAttemptLedger? = null,
    ) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalIntrinsicMediaSizeCache provides cache) {
                    val withProfile = profile ?: LocalMediaDisplayProfile.current
                    val withLedger = ledger ?: LocalMediaAttemptLedger.current
                    CompositionLocalProvider(
                        LocalMediaDisplayProfile provides withProfile,
                        LocalMediaAttemptLedger provides withLedger,
                    ) {
                        PostRenderer(content = content)
                    }
                }
            }
        }
    }

    private val DpRect.w get() = (right - left).value
    private val DpRect.h get() = (bottom - top).value

    private fun boundsOf(desc: String) = composeTestRule.onNodeWithContentDescription(desc).getBoundsInRoot()

    private val configuredDensity: Float get() = appContext.resources.displayMetrics.density

    // ---------- l'agrandissement des aperçus liés ----------

    @Test
    fun `une miniature liée de 150x112 est agrandie par mApercu`() {
        // mApercu = min(3, 3) = 3 → 450×336 px, sous fImage (1026) et capBloc (1200) → la
        // miniature occupe enfin ses pixels sources en dp (v1.5 rendait 50×37,3 dp).
        setPost(cache = cacheOf(), content = linkedBlock(thumbUrl, fullUrl, "miniature"))
        val bounds = boundsOf("miniature")
        assertEquals(150f, bounds.w, 2f)
        assertEquals(112f, bounds.h, 2f)
    }

    @Test
    fun `un auto-lien ne gagne aucun agrandissement`() {
        // G1 — `linkUrl = url` : la cible EST l'image affichée, l'agrandir n'ajouterait que du flou.
        // Faux positif reproduit sur device avant la garde ; ici la boîte reste au natif physique.
        setPost(cache = cacheOf(), content = linkedBlock(thumbUrl, thumbUrl, "autolien"))
        val bounds = boundsOf("autolien")
        assertEquals(50f, bounds.w, 2f)
        assertEquals(37.3f, bounds.h, 2f)
    }

    @Test
    fun `une miniature liée vers un autre hôte reste au natif`() {
        setPost(cache = cacheOf(), content = linkedBlock(thumbUrl, foreignUrl, "horshote"))
        val bounds = boundsOf("horshote")
        assertEquals(50f, bounds.w, 2f)
        assertEquals(37.3f, bounds.h, 2f)
    }

    @Test
    fun `une image liée de 800x600 échoue à la garde de taille`() {
        // G2 — grand axe natif > 400 px : ce n'est pas une miniature, no-upscale strict v1.5
        // (800×600 px = 266,7×200 dp, sous les deux caps).
        setPost(
            cache = cacheOf(native = IntSize(800, 600)),
            content = linkedBlock(thumbUrl, fullUrl, "grande"),
        )
        val bounds = boundsOf("grande")
        assertEquals(266.7f, bounds.w, 2f)
        assertEquals(200f, bounds.h, 2f)
    }

    // ---------- mEffectif = max(mApercu, mGif) : jamais un produit ----------

    @Test
    fun `un GIF lié éligible prend le max des deux plafonds - profil S`() {
        // max(3 ; 1,0) = 3 → 450×336 px. Le profil S ne rabote pas l'aperçu.
        setPost(
            cache = cacheOf(mime = "image/gif"),
            content = linkedBlock(thumbUrl, fullUrl, "gifs"),
            profile = MediaDisplayProfile.S,
        )
        val bounds = boundsOf("gifs")
        assertEquals(150f, bounds.w, 2f)
        assertEquals(112f, bounds.h, 2f)
    }

    @Test
    fun `un GIF lié éligible prend le max des deux plafonds - profil M jamais le produit`() {
        // max(3 ; 1,5) = 3 → 450 px = 150 dp. Un PRODUIT donnerait ×4,5 = 675 px = 225 dp, sous
        // les deux caps donc parfaitement observable : c'est l'interdit du contrat.
        setPost(
            cache = cacheOf(mime = "image/gif"),
            content = linkedBlock(thumbUrl, fullUrl, "gifm"),
            profile = MediaDisplayProfile.M,
        )
        val bounds = boundsOf("gifm")
        assertEquals(150f, bounds.w, 2f)
        assertEquals(112f, bounds.h, 2f)
    }

    @Test
    fun `un GIF lié éligible prend le max des deux plafonds - profil L jamais le produit`() {
        // max(3 ; 2,5) = 3 → 150 dp ; le produit ×7,5 (1125 px) serait re-clampé par fImage à
        // 1026 px = 342 dp — tout aussi visible.
        setPost(
            cache = cacheOf(mime = "image/gif"),
            content = linkedBlock(thumbUrl, fullUrl, "gifl"),
            profile = MediaDisplayProfile.L,
        )
        val bounds = boundsOf("gifl")
        assertEquals(150f, bounds.w, 2f)
        assertEquals(112f, bounds.h, 2f)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h780dp-hdpi")
    fun `quand mGif dépasse mApercu le GIF lié gagne par le max - profil L à densité basse`() {
        // À densité 1,5 les trois cas ci-dessus s'inversent : mApercu = min(1,5 ; 3) = 1,5 et
        // mGif (L) = 2,5 → mEffectif = max(1,5 ; 2,5) = 2,5 : 150×112 ×2,5 = 375×280 px =
        // 250×186,7 dp @d1,5, sous fImage (0,95×540 = 513 px) et capBloc (400 dp = 600 px).
        // SANS le max (mApercu seul) la boîte retomberait à 150×112 dp ; un PRODUIT (×3,75 =
        // 562,5 px → re-clampé fImage à 513 px = 342 dp) dériverait dans l'autre sens — les deux
        // mutations sont observables ici, contrairement à la densité 3 où mApercu domine toujours.
        assertEquals("le qualifier hdpi doit s'appliquer", 1.5f, configuredDensity, 0.001f)
        setPost(
            cache = cacheOf(mime = "image/gif"),
            content = linkedBlock(thumbUrl, fullUrl, "gifgagne"),
            profile = MediaDisplayProfile.L,
        )
        val bounds = boundsOf("gifgagne")
        assertEquals(250f, bounds.w, 2f)
        assertEquals(186.7f, bounds.h, 2f)
    }

    // ---------- §7 : décodage au natif, hard caps sur un aperçu ÉLIGIBLE ----------

    @Test
    fun `le décodage d'un aperçu lié agrandi reste borné au natif`() {
        // §7 inchangé sur CE chemin : la boîte ×3 (450×336 px) alimente decodeSizePx mais le
        // clamp au natif est terminal — la requête painter demande 150×112, jamais 450×336 :
        // décodage natif, agrandissement au draw (FilterQuality.Low).
        setPost(cache = cacheOf(), content = linkedBlock(thumbUrl, fullUrl, "decodage"))
        composeTestRule.waitForIdle()
        val bounds = boundsOf("decodage")
        assertEquals(150f, bounds.w, 2f)
        val sizes = recordedDecodeSizes.filter { it.first == thumbUrl }.map { it.second }
        assertTrue("at least one painter decode must have run", sizes.isNotEmpty())
        sizes.forEach { size ->
            assertEquals("decode must stay at native (was $size)", Size(150, 112), size)
        }
    }

    @Test
    fun `le hard cap fImage re-clampe un aperçu lié éligible`() {
        // Miniature ÉLIGIBLE (grand axe = 400 px, inclus dans G2) dont l'agrandissement rencontre
        // le hard cap : ×3 demanderait 1200 px, fImage n'en autorise que 0,95×1080 = 1026 →
        // scale = 1026/400 = 2,565 → 1026×770 px = 342×256,7 dp. Le décodage reste au natif
        // (400×300) : le re-clamp de la boîte ne change rien au clamp terminal du §7.
        setPost(
            cache = cacheOf(native = IntSize(400, 300)),
            content = linkedBlock(thumbUrl, fullUrl, "capee"),
        )
        composeTestRule.waitForIdle()
        val bounds = boundsOf("capee")
        assertEquals(342f, bounds.w, 2f)
        assertEquals(256.7f, bounds.h, 2f)
        val sizes = recordedDecodeSizes.filter { it.first == thumbUrl }.map { it.second }
        assertTrue("at least one painter decode must have run", sizes.isNotEmpty())
        sizes.forEach { size ->
            assertEquals("decode must stay at native (was $size)", Size(400, 300), size)
        }
    }

    // ---------- densités ≠ 3 ----------

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h780dp-ldpi")
    fun `à densité inférieure à 1 le max avec mGif garantit le plancher`() {
        // mApercu = min(0,75 ; 3) = 0,75 : SANS le max avec mGif (1,0) la miniature RÉTRÉCIRAIT
        // (113 px ≈ 150,7 dp). mEffectif = max(0,75 ; 1,0) = 1 → 150 px natifs = 200 dp @d0,75.
        assertEquals("le qualifier ldpi doit s'appliquer", 0.75f, configuredDensity, 0.001f)
        setPost(cache = cacheOf(), content = linkedBlock(thumbUrl, fullUrl, "basse"))
        val bounds = boundsOf("basse")
        assertEquals(200f, bounds.w, 2f)
        assertEquals(149.3f, bounds.h, 2f)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h780dp-hdpi")
    fun `à densité 1 virgule 5 mApercu suit la densité`() {
        // mApercu = min(1,5 ; 3) = 1,5 → 225×168 px = 150×112 dp : la miniature occupe ses pixels
        // sources en dp à TOUTE densité ≤ 3. Un plafond figé à 3 rendrait 300 dp de large.
        assertEquals("le qualifier hdpi doit s'appliquer", 1.5f, configuredDensity, 0.001f)
        setPost(cache = cacheOf(), content = linkedBlock(thumbUrl, fullUrl, "moyenne"))
        val bounds = boundsOf("moyenne")
        assertEquals(150f, bounds.w, 2f)
        assertEquals(112f, bounds.h, 2f)
    }

    // ---------- §6 : chemin painter-only (probe morte), UNE seule correction ----------

    @OptIn(DelicateCoilApi::class)
    @Test
    fun `le chemin painter-only agrandit dès la première mesure`() {
        // Probe tuée sélectivement (le seul requête portant ProbeMetadataDecoder.Factory) : les
        // dimensions arrivent par le painter, SANS MIME — sans effet, mApercu n'en dépend pas.
        // Tant qu'aucune dimension n'est connue la garde ne passe pas et le slot cold (sans
        // facteur) est conservé ; l'arrivée du couple produit l'UNIQUE correction du §6, calculée
        // directement avec mEffectif = 3.
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(thumbUrl, ColorImage(0xFF6A1B9A.toInt(), width = 150, height = 112))
            .build()
        val probeKiller = object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                if (chain.request.decoderFactory is ProbeMetadataDecoder.Factory) {
                    return ErrorResult(
                        image = null,
                        request = chain.request,
                        throwable = IllegalStateException("probe refused by host"),
                    )
                }
                return chain.proceed()
            }
        }
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(appContext).components {
                add(probeKiller)
                add(engine)
            }.build(),
        )
        val cache = DefaultIntrinsicMediaSizeCache()
        setPost(
            cache = cache,
            content = linkedBlock(thumbUrl, fullUrl, "painter"),
            ledger = MediaAttemptLedger(),
        )

        composeTestRule.waitUntil(timeoutMillis = 5_000) { cache.get(thumbUrl) != null }
        assertEquals(IntSize(150, 112), cache.get(thumbUrl)?.size)
        assertEquals("le dépôt painter ne porte AUCUN MIME", null, cache.get(thumbUrl)?.mimeType)
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {}

        val bounds = composeTestRule.onNodeWithTag(BLOCK_IMAGE_TEST_TAG).getBoundsInRoot()
        assertEquals(150f, bounds.w, 2f)
        assertEquals(112f, bounds.h, 2f)
    }

    @Test
    fun `le slot cold d'un aperçu lié ne prend aucun facteur`() {
        // Aucune dimension connue → la garde G2 est fail-closed et le slot §6 reste sans facteur
        // (largeur ≈ fImage × conteneur, hauteur = 0,75 × largeur), lien ou pas.
        // URL jamais servie par le loader (ni probe ni painter) : aucune dimension n'arrive.
        val deadUrl = "https://images.example.org/never-served/cold-thumb.jpg"
        val deadLink = "https://images.example.org/never-served/cold-page.jpg"
        setPost(
            cache = DefaultIntrinsicMediaSizeCache(),
            content = linkedBlock(deadUrl, deadLink, "froide"),
            ledger = MediaAttemptLedger(),
        )
        val bounds = composeTestRule.onNodeWithTag(BLOCK_IMAGE_TEST_TAG).getBoundsInRoot()
        val width = bounds.w
        assertTrue("cold width ~0.95×container, was $width", width in 320f..355f)
        assertEquals(width * 0.75f, bounds.h, 1f)
    }

    // ---------- INLINE hors périmètre ----------

    @Test
    fun `une image inline liée ne prend aucun agrandissement`() {
        // Même miniature, même lien distinct du même hôte, mais NON isolée : elle reste INLINE →
        // plafond 1,0 (150×112 px = 50×37,3 dp), jamais mApercu.
        setPost(
            cache = cacheOf(),
            content = PostContent(
                blocks = listOf(
                    PostBlock.Paragraph(
                        inlines = listOf(
                            PostInline.Text("avant "),
                            PostInline.Link(url = fullUrl, children = listOf(img(thumbUrl, "danstexte"))),
                            PostInline.Text(" après"),
                        ),
                    ),
                ),
            ),
        )
        val bounds = boundsOf("danstexte")
        assertEquals(50f, bounds.w, 1.1f)
        assertEquals(112f / 3f, bounds.h, 1.1f)
    }
}
