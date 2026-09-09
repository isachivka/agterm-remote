package dev.isachivka.bewareofsugar.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
private fun Swatch(name: String, background: Color, foreground: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Aa",
            style = MaterialTheme.typography.labelMedium,
            color = foreground,
            modifier = Modifier
                .size(44.dp, 26.dp)
                .background(background, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall.mono(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Every colour role in the app, on one screen, each shown as a foreground on its own background.
 *
 * This exists to be looked at. A scheme has thirty-odd slots, the design supplies values for about
 * half of them and the rest are derived — and a derived value that came out wrong stays invisible
 * until some component nobody previewed reaches for it. Material's baseline purple against this
 * background is unmistakable in a grid and easy to miss one slot at a time.
 *
 * Not a test, because what it catches is "that looks wrong", and no assertion says that better than
 * an eye does. The assertion a test *could* make — that no slot equals a known baseline value —
 * would pass happily for a slot set to a plausible wrong colour, which is the likelier mistake.
 *
 * Public so `AppIconsTest` can capture it as a PNG for the pull request: the same picture, taken
 * rather than described.
 */
@Composable
fun ColourRoles(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val app = AppTheme.colors
    Column(
        modifier = modifier
            .background(scheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Material roles", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
        Swatch("primary", scheme.primary, scheme.onPrimary)
        Swatch("primaryContainer", scheme.primaryContainer, scheme.onPrimaryContainer)
        Swatch("secondary", scheme.secondary, scheme.onSecondary)
        Swatch("secondaryContainer", scheme.secondaryContainer, scheme.onSecondaryContainer)
        Swatch("tertiary", scheme.tertiary, scheme.onTertiary)
        Swatch("tertiaryContainer", scheme.tertiaryContainer, scheme.onTertiaryContainer)
        Swatch("background", scheme.background, scheme.onBackground)
        Swatch("surface", scheme.surface, scheme.onSurface)
        Swatch("surfaceVariant", scheme.surfaceVariant, scheme.onSurfaceVariant)
        Swatch("surfaceContainerLowest", scheme.surfaceContainerLowest, scheme.onSurface)
        Swatch("surfaceContainerLow", scheme.surfaceContainerLow, scheme.onSurface)
        Swatch("surfaceContainer", scheme.surfaceContainer, scheme.onSurface)
        Swatch("surfaceContainerHigh", scheme.surfaceContainerHigh, scheme.onSurface)
        Swatch("surfaceContainerHighest", scheme.surfaceContainerHighest, scheme.onSurface)
        Swatch("surfaceDim", scheme.surfaceDim, scheme.onSurface)
        Swatch("surfaceBright", scheme.surfaceBright, scheme.onSurface)
        Swatch("inverseSurface", scheme.inverseSurface, scheme.inverseOnSurface)
        Swatch("inversePrimary", scheme.inversePrimary, scheme.onPrimary)
        Swatch("error", scheme.error, scheme.onError)
        Swatch("errorContainer", scheme.errorContainer, scheme.onErrorContainer)
        Swatch("outline", scheme.outline, scheme.onSurface)
        Swatch("outlineVariant", scheme.outlineVariant, scheme.onSurface)
        Swatch("surfaceTint", scheme.surfaceTint, scheme.onPrimary)
        Swatch("scrim", scheme.scrim, scheme.onPrimary)

        Text(
            "App roles",
            style = MaterialTheme.typography.titleSmall,
            color = scheme.onSurface,
            modifier = Modifier.padding(top = 8.dp),
        )
        Swatch("success", scheme.surfaceContainer, app.success)
        Swatch("successContainer", app.successContainer, app.onSuccessContainer)
        Swatch("onSurfaceSubtle", scheme.background, app.onSurfaceSubtle)
    }
}

/**
 * The type scale, at the sizes the design draws.
 *
 * Public for the same reason as [ColourRoles]. The last two lines are the ones with a question
 * hanging over them: [FontFamily.Monospace] resolves to whatever the device calls monospace, and
 * whether that is close enough to the Roboto Mono the design specifies is a judgement to make by
 * looking at it on hardware — see PLAN-0004.
 */
@Composable
fun TypeScale(modifier: Modifier = Modifier) {
    val type = MaterialTheme.typography
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("headlineLarge 32", style = type.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
        Text("headlineMedium 30", style = type.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        Text("titleLarge 20", style = type.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Text("titleMedium 17", style = type.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text("titleSmall 15", style = type.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text("bodyLarge 15", style = type.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("bodyMedium 14", style = type.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("bodySmall 13", style = type.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("labelLarge 15", style = type.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Text("labelMedium 12", style = type.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        Text("labelSmall 11", style = type.labelSmall, color = AppTheme.colors.onSurfaceSubtle)
        Text(
            "bodySmall.mono()  0123456789  v0.4.0  24.2 MB",
            style = type.bodySmall.mono(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "bodyLarge.mono()  isachivka/beware-of-sugar",
            style = type.bodyLarge.mono(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(name = "Colour roles", widthDp = 412, heightDp = 1180)
@Composable
private fun ColourRolesPreview() {
    AppTheme { ColourRoles() }
}

@Preview(name = "Type scale", widthDp = 412, heightDp = 620)
@Composable
private fun TypeScalePreview() {
    AppTheme { TypeScale() }
}
