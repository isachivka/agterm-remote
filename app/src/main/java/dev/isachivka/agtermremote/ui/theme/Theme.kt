package dev.isachivka.agtermremote.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The roles Material 3 does not have.
 *
 * Two of them, and both earn their place. Success is a genuine gap in M3 — there is no slot that
 * means "this went well" — and the design uses it on a status that has settled and on the paired
 * card. The third text weight is a gap too: M3 offers `onSurface` and `onSurfaceVariant`, and the
 * design uses three levels, with the faintest carrying the supporting line under almost every
 * heading in the app.
 *
 * Everything else the design needs, Material already models, and is in [AppColorScheme] instead. A
 * custom role is a colour nobody can look up in the M3 documentation, so the bar for adding one is
 * that Material genuinely has nowhere to put it.
 */
@Immutable
data class AppColors(
    /** Success as a foreground on a plain surface — a settled subtitle. */
    val success: Color,
    /** Success as a filled card — the paired-laptop card. */
    val successContainer: Color,
    val onSuccessContainer: Color,
    /**
     * The third and faintest text weight: a label in front of a value, the workspace name beside a
     * session, the summary under a row that leads somewhere. Always subordinate to something
     * directly above it.
     */
    val onSurfaceSubtle: Color,
)

private val AppDarkColors = AppColors(
    success = DesignSuccess,
    successContainer = DesignSuccessContainer,
    onSuccessContainer = DesignOnSuccessContainer,
    onSurfaceSubtle = DesignTextSubtle,
)

/**
 * Deliberately has no sensible default. A composable reading [AppTheme.colors] outside [AppTheme]
 * is a bug, and failing loudly finds it; a plausible fallback would hide it until the screenshot.
 */
private val LocalAppColors = staticCompositionLocalOf<AppColors> {
    error("AppColors requested from outside AppTheme")
}

/**
 * Every Material 3 slot, filled.
 *
 * Every one, including the several the design never uses. An unset slot silently keeps Material's
 * baseline purple, so the first stock component that reaches for `secondaryContainer` would render
 * a colour from a different app entirely — and it would do it in whichever state nobody previewed.
 * Where the design has no opinion, the value is derived from the family it belongs to and said to
 * be derived, which is a decision; leaving it unset is not.
 */
private val AppColorScheme = darkColorScheme(
    primary = DesignPrimary,
    onPrimary = DesignOnPrimary,
    primaryContainer = DesignPrimaryContainer,
    onPrimaryContainer = DesignOnPrimaryContainer,
    inversePrimary = DesignInversePrimary,

    // The design uses exactly one accent-container pair. Both M3 accent families point at it, so
    // that a component reaching for either lands in this palette rather than outside it.
    secondary = DesignTextSecondary,
    onSecondary = DesignOnAccent,
    secondaryContainer = DesignAccentContainer,
    onSecondaryContainer = DesignOnAccentContainer,
    tertiary = DesignOnAccentContainer,
    onTertiary = DesignOnAccent,
    tertiaryContainer = DesignAccentContainer,
    onTertiaryContainer = DesignOnAccentContainer,

    background = DesignBackground,
    onBackground = DesignTextPrimary,
    surface = DesignBackground,
    onSurface = DesignTextPrimary,
    surfaceVariant = DesignSurfaceHigh,
    onSurfaceVariant = DesignTextSecondary,
    surfaceTint = DesignPrimary,
    inverseSurface = DesignTextPrimary,
    inverseOnSurface = DesignSurface,

    // Four depths, and the design used all four on one screen: a card (Low) beside a card
    // (Container) with a circle inside it (High), all on the background (Lowest). The screen that
    // needed all four is gone; the ramp is kept because M3 asks for the whole ramp, and a hole in it
    // would be filled by whoever needs the missing step.
    surfaceDim = DesignBackground,
    surfaceBright = DesignSurfaceHigh,
    surfaceContainerLowest = DesignBackground,
    surfaceContainerLow = DesignSurfaceLow,
    surfaceContainer = DesignSurface,
    surfaceContainerHigh = DesignSurfaceHigh,
    surfaceContainerHighest = DesignOutlineVariant,

    error = DesignError,
    onError = DesignOnAccent,
    errorContainer = DesignErrorContainer,
    onErrorContainer = DesignOnErrorContainer,

    outline = DesignOutline,
    outlineVariant = DesignOutlineVariant,
    scrim = Color.Black,
)

/**
 * The app's theme. Dark, and only dark.
 *
 * No `darkTheme` parameter, no `isSystemInDarkTheme()`, no dynamic colour. The design commits to one
 * palette, and a light theme assembled by inverting it would be a screen nobody has ever looked at,
 * shipped to whoever happens to have the system setting the other way. A light theme is
 * out of scope and the README says so, which is a more honest answer than half-supporting one.
 *
 * Dynamic colour is out for the same reason twice over: the design's amber is the app's identity,
 * and every contrast pair here was chosen against these backgrounds rather than against whatever a
 * wallpaper produces.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppColors provides AppDarkColors) {
        MaterialTheme(
            colorScheme = AppColorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

/** Reached as `AppTheme.colors.success`, mirroring how `MaterialTheme.colorScheme` reads. */
object AppTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalAppColors.current
}
