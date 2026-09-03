package fr.forumhfr.redface2.core.domain.preferences

/**
 * User-selected accent source.
 *
 * Custom colours are strict RGB values (`0x000000..0xFFFFFF`), never ARGB. The alpha channel is
 * added only when the UI theme feeds the value into Material colour utilities.
 */
sealed interface ThemeAccent {
    data class Preset(val preset: AccentPreset) : ThemeAccent

    data class Custom(val rgb: Int) : ThemeAccent {
        init {
            require(rgb in MIN_RGB..MAX_RGB) { "Custom theme accent must be a 24-bit RGB value" }
        }
    }
}

/** Returns the opaque ARGB seed corresponding to this accent. */
fun ThemeAccent.seedArgb(): Int = OPAQUE_ALPHA_MASK or when (this) {
    is ThemeAccent.Preset -> preset.seedRgb
    is ThemeAccent.Custom -> rgb
}

private const val MIN_RGB = 0x000000
private const val MAX_RGB = 0xFFFFFF
private const val OPAQUE_ALPHA_MASK = -0x1000000
