package fr.forumhfr.redface2.feature.settings

import java.util.Locale

private const val RGB_HEX_DIGITS = 6
private const val RGB_RADIX = 16
private const val MIN_RGB = 0x000000
private const val MAX_RGB = 0xFFFFFF

private val AccentHexRegex = Regex("^#?[0-9A-Fa-f]{$RGB_HEX_DIGITS}$")

/** Parses a strict `#RRGGBB` / `RRGGBB` accent input, without alpha. */
internal fun parseThemeAccentHexOrNull(text: String): Int? {
    if (!AccentHexRegex.matches(text)) return null
    return text.removePrefix("#").toInt(RGB_RADIX)
}

/** Formats a 24-bit RGB value as the normalized custom-accent field value. */
internal fun Int.toThemeAccentHex(): String {
    require(this in MIN_RGB..MAX_RGB) { "Theme accent RGB must fit in 24 bits" }
    return String.format(Locale.US, "#%06X", this)
}
