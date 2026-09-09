package dev.isachivka.agtermremote.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The corner radii the design draws, not Material 3's defaults.
 *
 * M3's scale is 4 / 8 / 12 / 16 / 28. The design's is 10 / 16 / 20 / 24 / 28 — noticeably rounder
 * everywhere except the largest card, which is the whole visual character of the thing. Taking the
 * default scale would have meant a `RoundedCornerShape(24.dp)` written out at every card, which is
 * the hex-literal problem wearing a different hat.
 *
 * Buttons are pills and use `CircleShape` at the call site: M3 has no shape slot for them, and a
 * 100 dp radius is a pill by arithmetic rather than by intent.
 */
/**
 * The same radii as numbers.
 *
 * [AppShapes] is what almost everything uses. This exists for the cases that need the radius as a
 * NUMBER rather than as a shape — a border drawn by hand, an outline traced onto a canvas. Reading
 * it from here rather than writing `24.dp` at the call site is what stops the two from rounding
 * differently.
 */
object AppCorners {
    val ExtraSmall = 10.dp
    val Small = 16.dp
    val Medium = 20.dp
    val Large = 24.dp
    val ExtraLarge = 28.dp
}

val AppShapes = Shapes(
    // Chips. The design draws two of them a radius apart, at 10 and at 12; that is drawing noise
    // rather than two decisions, so both are 10.
    extraSmall = RoundedCornerShape(AppCorners.ExtraSmall),
    // A text field's top corners. Its bottom two are 4 dp, because the field is drawn as a
    // filled box sitting on an underline - that asymmetry belongs at the call site, not here.
    small = RoundedCornerShape(AppCorners.Small),
    // Explanatory panels and the error card.
    medium = RoundedCornerShape(AppCorners.Medium),
    // List containers: the settings list, and the pairing card inside it.
    large = RoundedCornerShape(AppCorners.Large),
    // Cards that carry a state: the pairing card, and the failure card the terminal falls back to.
    extraLarge = RoundedCornerShape(AppCorners.ExtraLarge),
)
