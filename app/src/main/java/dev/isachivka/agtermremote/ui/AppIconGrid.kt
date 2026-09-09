package dev.isachivka.agtermremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.isachivka.agtermremote.ui.theme.AppTheme
import dev.isachivka.agtermremote.ui.theme.mono

/**
 * Every bundled glyph, at the size the design draws it, in the circle the design draws it in.
 *
 * This exists to be looked at, and it is the whole verification strategy for 26 hand-converted
 * vector paths. The failure mode is not a crash — a wrong viewport renders a glyph tiny, or clipped,
 * or upside down, and a dropped subpath renders one that is merely subtly wrong. Checking that one
 * file at a time is slow and unreliable; checking all of them side by side takes a glance, because
 * anything wrong stops looking like its neighbours.
 *
 * Public because `AppIconsScreenshotTest` renders it to a PNG for the pull request. That is the same
 * image, captured rather than described.
 */
@Composable
fun AppIconGrid(modifier: Modifier = Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(AppIcons.all) { (name, icon) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // The same 40 dp circle the launcher's module tiles use, so the grid shows the
                // glyphs at the size and contrast they will actually be seen at.
                Column(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        painter = painterResource(icon),
                        // Decorative: the label underneath already names it, and this composable
                        // exists only for a human to look at.
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall.mono(),
                    color = AppTheme.colors.onSurfaceSubtle,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Preview(name = "Icons", widthDp = 412, heightDp = 800)
@Composable
private fun AppIconGridPreview() {
    AppTheme {
        AppIconGrid()
    }
}
