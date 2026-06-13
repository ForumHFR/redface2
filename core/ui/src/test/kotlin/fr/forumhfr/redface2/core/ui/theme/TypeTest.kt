package fr.forumhfr.redface2.core.ui.theme

import androidx.compose.ui.unit.sp
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure-JVM tests for [scaledForReading] (#287 lot C). No Compose runtime needed: it is a pure
 * transform over [androidx.compose.material3.Typography].
 */
class TypeTest {

    @Test
    fun `FontScalePreference factors are pinned to the shipped reading sizes`() {
        // The scaling tests below derive their expected values from these factors, so they would
        // stay green if someone changed a factor — but the app would ship wrong sizes. Pin the
        // contract here so any enum edit fails loudly (and M must stay exactly 1f for the
        // short-circuit in scaledForReading to keep firing).
        assertEquals(0.9f, FontScalePreference.S.factor, EPSILON)
        assertEquals(1f, FontScalePreference.M.factor, EPSILON)
        assertEquals(1.15f, FontScalePreference.L.factor, EPSILON)
    }

    @Test
    fun `scaledForReading at identity factor returns the same Typography instance`() {
        // M preset short-circuits — no allocation, referentially identical.
        assertSame(RedfaceTypography, RedfaceTypography.scaledForReading(FontScalePreference.M.factor))
    }

    @Test
    fun `scaledForReading at L multiplies font size and line height of a role`() {
        val scaled = RedfaceTypography.scaledForReading(FACTOR_L)

        // bodyMedium is 14 sp / 20 sp at the M3 reference; L = 1.15x.
        assertEquals(14f * FACTOR_L, scaled.bodyMedium.fontSize.value, EPSILON)
        assertEquals(20f * FACTOR_L, scaled.bodyMedium.lineHeight.value, EPSILON)
    }

    @Test
    fun `scaledForReading scales every body role consistently`() {
        val scaled = RedfaceTypography.scaledForReading(FACTOR_L)

        assertEquals(16f * FACTOR_L, scaled.bodyLarge.fontSize.value, EPSILON)
        assertEquals(12f * FACTOR_L, scaled.bodySmall.fontSize.value, EPSILON)
        assertEquals(22f * FACTOR_L, scaled.titleLarge.fontSize.value, EPSILON)
    }

    @Test
    fun `scaledForReading leaves letterSpacing untouched`() {
        val scaled = RedfaceTypography.scaledForReading(FACTOR_L)

        // letterSpacing is conceptually glyph-relative, so it must NOT be multiplied.
        assertEquals(0.25.sp, scaled.bodyMedium.letterSpacing)
        assertEquals(RedfaceTypography.bodyLarge.letterSpacing, scaled.bodyLarge.letterSpacing)
    }

    @Test
    fun `scaledForReading at S shrinks the reading size`() {
        val scaled = RedfaceTypography.scaledForReading(FACTOR_S)

        assertEquals(14f * FACTOR_S, scaled.bodyMedium.fontSize.value, EPSILON)
        assertEquals(20f * FACTOR_S, scaled.bodyMedium.lineHeight.value, EPSILON)
    }

    private companion object {
        val FACTOR_S = FontScalePreference.S.factor
        val FACTOR_L = FontScalePreference.L.factor
        const val EPSILON = 0.001f
    }
}
