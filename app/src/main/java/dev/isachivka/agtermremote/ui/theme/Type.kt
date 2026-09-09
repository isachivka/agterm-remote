package dev.isachivka.agtermremote.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The type scale the design draws.
 *
 * Roboto is Android's default and needs no font file, so `FontFamily.Default` is Roboto on the
 * device this ships to. Only the sizes and weights are adjusted, and only where the design and the
 * M3 scale disagree — a scale bent to fit one screen is a scale that fits nothing else.
 */
val AppTypography = Typography(
    // The app title on the launcher. Large, light, and tightened, which is the design's one piece
    // of real typographic character.
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.4).sp,
    ),
    // Every other screen's title: Updates, GitHub token, Modules, and a module's own name.
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.3).sp,
    ),
    // "Not built yet", inside the placeholder card.
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    // Tile titles and the headline of a status card.
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 23.sp,
    ),
    // Module tile names, and the title of a row that leads somewhere.
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    // Paragraphs the owner is expected to actually read - the token explanation above all.
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 24.sp,
    ),
    // Subtitles, and the body of a status card.
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    // Buttons.
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    // Chips.
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    // The SCREENSHOTS BLOCKED chip, which is the smallest text in the app on purpose.
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.5.sp,
    ),
)

/**
 * The same size, in the monospaced face.
 *
 * The design sets six things in Roboto Mono: the size chip, the release notes, the token how-to, the
 * download progress figures and the token field. What they have in common is that they are *data* —
 * a version, a byte count, whatever GitHub sent — rather than prose, and the monospace says so.
 *
 * An extension rather than a parallel scale. `MaterialTheme.typography.bodySmall.mono()` keeps one
 * type scale with two faces; a second `Typography` would have been two scales free to drift.
 *
 * [FontFamily.Monospace] resolves to whatever the device calls monospace rather than to Roboto Mono
 * specifically. Bundling the real face is ~170 KB and is deferred until it has been looked at on a
 * device — see PLAN-0004.
 */
fun TextStyle.mono(): TextStyle = copy(fontFamily = FontFamily.Monospace)
