package dev.isachivka.agtermremote.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Every colour in the app, read out of `design/v0/Homelab Monoapp.dc.html`.
 *
 * These are raw values and nothing outside this package should reach for them — screens use
 * [androidx.compose.material3.MaterialTheme.colorScheme] and [AppTheme.colors], which is what makes
 * "the card background" a decision made once rather than a hex literal repeated eleven times.
 *
 * They are named for the role they fill, not for the colour they are. `Sand40` would tell a reader
 * what it looks like, which they can already see; what they cannot see is what it is *for*.
 */

// The screen itself, and the hairline between rows inside a list container - the design separates
// rows by letting the background show through rather than by drawing a line.
internal val DesignBackground = Color(0xFF15120C)

// Three container depths, and the design uses all three on one screen: a module tile sits lower
// than the Updates tile, which sits lower than the icon circle inside it.
internal val DesignSurfaceLow = Color(0xFF1D1A13)
internal val DesignSurface = Color(0xFF211E16)
internal val DesignSurfaceHigh = Color(0xFF2C2820)

// Three text weights, in descending emphasis. The third is the one M3 has no slot for - see
// AppColors.onSurfaceSubtle.
internal val DesignTextPrimary = Color(0xFFEBE1D3)
internal val DesignTextSecondary = Color(0xFFD0C5B3)
internal val DesignTextSubtle = Color(0xFF9A8F7C)

// Dashed module-tile borders, outlined buttons, the download progress track.
internal val DesignOutline = Color(0xFF4D4639)

// The divider inside the release card. Lighter than the outline, because it separates rather than
// bounds.
internal val DesignOutlineVariant = Color(0xFF37332A)

// The one accent: Update, Install, Save and check, and the Updates tile's icon.
internal val DesignPrimary = Color(0xFFF7BF52)
internal val DesignOnPrimary = Color(0xFF422C00)

/**
 * "There is an update", as a filled container: the home banner and the available status card.
 *
 * Mapped to M3's `primaryContainer` rather than to a role of its own. The design's four amber
 * values are a single M3 tonal ramp — `#422C00` / `#5E4100` / `#F7BF52` / `#FFDEA8` are exactly what
 * the M3 theme builder produces for `onPrimary` / `primaryContainer` / `primary` /
 * `onPrimaryContainer` from one seed — so using the slot is recovering the design's intent, not
 * overriding it. Inventing an `updateAvailableContainer` beside it would have been a second name for
 * a role Material already has.
 */
internal val DesignPrimaryContainer = Color(0xFF5E4100)
internal val DesignOnPrimaryContainer = Color(0xFFFFDEA8)

// The design's one accent-container pair: the Planned chip, the download size chip, and the
// no-token status card. Both M3 accent-container slots point here - see AppColorScheme.
internal val DesignAccentContainer = Color(0xFF4E452F)
internal val DesignOnAccentContainer = Color(0xFFF0E0BF)

// The M3 dark error triple, unchanged from the baseline, which is what the design uses.
internal val DesignError = Color(0xFFFFB4AB)
internal val DesignErrorContainer = Color(0xFF93000A)
internal val DesignOnErrorContainer = Color(0xFFFFDAD6)

/**
 * Success, which Material 3 does not have.
 *
 * Two forms, the same distinction M3 draws everywhere else: [DesignSuccess] is a foreground for a
 * plain surface — the Updates tile's "up to date" subtitle — and the container pair fills a card, as
 * on the up-to-date status and the token-connected card.
 */
internal val DesignSuccess = Color(0xFFA8D0A3)
internal val DesignSuccessContainer = Color(0xFF33502F)
internal val DesignOnSuccessContainer = Color(0xFFC3EDBD)

// Derived rather than designed. The design never puts text on a filled secondary or tertiary
// surface, but every M3 slot has to hold something legible or a stock component will fall through
// to the baseline purple - see AppColorScheme.
internal val DesignOnAccent = Color(0xFF372F1E)
internal val DesignInversePrimary = Color(0xFF6B5100)
