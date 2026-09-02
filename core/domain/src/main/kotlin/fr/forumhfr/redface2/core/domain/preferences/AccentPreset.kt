package fr.forumhfr.redface2.core.domain.preferences

/**
 * Built-in accent seeds exposed by the colour settings.
 *
 * [seedRgb] is stored as `0xRRGGBB` without alpha so it can be persisted and reused by both the
 * Android theme and the synchronous bootstrap mirror without depending on Compose types.
 */
enum class AccentPreset(val seedRgb: Int) {
    ROSE(seedRgb = 0xA62C2C),
    ROUGE_REDFACE1(seedRgb = 0xF44336),
    BLUE(seedRgb = 0x1976D2),
    GREEN(seedRgb = 0x388E3C),
    VIOLET(seedRgb = 0x8E24AA),
    ORANGE(seedRgb = 0xF57C00),
    TEAL(seedRgb = 0x00897B),
    NEUTRAL(seedRgb = 0x616161),
}
