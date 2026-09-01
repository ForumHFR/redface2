package fr.forumhfr.redface2.navigation

import java.util.Locale

/** Maps the category segment used by HFR pretty URLs to the numeric category key. */
internal object HfrCategorySlugMap {
    private val categoryIdsBySlug = mapOf(
        "hardware" to 1,
        "hardwareperipheriques" to 16,
        "ordinateursportables" to 15,
        "overclockingcoolingmodding" to 2,
        "electroniquedomotiquediy" to 30,
        "gsmgpspda" to 23,
        "apple" to 25,
        "videoson" to 3,
        "photonumerique" to 14,
        "jeuxvideo" to 5,
        "windowssoftware" to 4,
        "reseauxpersosoho" to 22,
        "systemereseauxpro" to 21,
        "osalternatifs" to 11,
        "programmation" to 10,
        "ia" to 32,
        "graphisme" to 12,
        "achatsventes" to 6,
        "emploietudes" to 8,
        "discussions" to 13,
    )

    fun categoryIdFor(slug: String): Int? = categoryIdsBySlug[slug.lowercase(Locale.ROOT)]
}
