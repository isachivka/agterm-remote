package dev.isachivka.bewareofsugar.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The corner radii the design draws, not Material 3's defaults.
 *
 * M3's scale is 4 / 8 / 12 / 16 / 28. The design's is 10 / 16 / 20 / 24 / 28 — noticeably rounder
 * everywhere except the largest card, which is the whole visual character of the thing. Taking the
 * default scale would have meant a `RoundedCornerShape(24.dp)` written out at every tile, which is
 * the hex-literal problem wearing a different hat.
 *
 * Buttons are pills and use `CircleShape` at the call site: M3 has no shape slot for them, and a
 * 100 dp radius is a pill by arithmetic rather than by intent.
 */
/**
 * The same radii as numbers.
 *
 * [AppShapes] is what almost everything uses. This exists because a dashed border has to be drawn by
 * hand — Compose has no dashed `Modifier.border` — and drawing it needs the corner radius as a
 * value rather than as a shape. Reading it from here rather than writing `24.dp` at the call site
 * is what stops the dash and the fill from rounding differently.
 */
object AppCorners {
    val ExtraSmall = 10.dp
    val Small = 16.dp
    val Medium = 20.dp
    val Large = 24.dp
    val ExtraLarge = 28.dp
}

val AppShapes = Shapes(
    // Chips. The design draws the size chip at 10 and the Planned chip at 12; that is drawing
    // noise rather than two decisions, so both are 10.
    extraSmall = RoundedCornerShape(AppCorners.ExtraSmall),
    // The token field's top corners. Its bottom two are 4 dp, because the field is drawn as a
    // filled box sitting on an underline - that asymmetry belongs at the call site, not here.
    small = RoundedCornerShape(AppCorners.Small),
    // The home banner, the "How do I make one?" panel, the error card.
    medium = RoundedCornerShape(AppCorners.Medium),
    // Tiles and list containers: the Updates tile, module tiles, the settings list.
    large = RoundedCornerShape(AppCorners.Large),
    // Cards that carry a state: status, release, download, the module placeholder.
    extraLarge = RoundedCornerShape(AppCorners.ExtraLarge),
)
