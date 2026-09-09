package dev.isachivka.agtermremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.isachivka.agtermremote.agterm.AgtermHost
import dev.isachivka.agtermremote.pairing.PairedLaptop
import dev.isachivka.agtermremote.pairing.PairingHost
import dev.isachivka.agtermremote.settings.StyledScreenStore
import dev.isachivka.agtermremote.ui.settings.SettingsScreen
import dev.isachivka.agtermremote.ui.nav.BackStack
import dev.isachivka.agtermremote.ui.nav.Screen
import dev.isachivka.agtermremote.ui.nav.startDestination
import dev.isachivka.agtermremote.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // targetSdk is 37, and Android has drawn apps edge-to-edge without asking since
                    // API 35 - so the window already extends under the status bar and the gesture
                    // area whether or not this app opted in. This is where that is paid for: one
                    // inset padding at the root, rather than every screen discovering it separately
                    // by putting a title under the clock.
                    //
                    // `safeDrawing` includes the IME, so this is also what lifts the content above
                    // the keyboard. **It is the ONLY thing that accounts for the keyboard** - the
                    // window itself is never resized, measured on API 37: with the IME up the window
                    // frame is still the full display and the keyboard arrives purely as an inset.
                    // An `enableEdgeToEdge()` call was added here on the theory that the window WAS
                    // being resized as well, and removed again when the measurement showed both
                    // builds reporting the same full-screen frame. The jump had another cause - see
                    // windowSoftInputMode in the manifest.
                    App(modifier = Modifier.safeDrawingPadding())
                }
            }
        }
    }
}

/**
 * The two screens, one back stack across them, and the fact that decides which one opens.
 *
 * The launcher this used to open on is gone with the module grid, and so are the update and
 * reachability destinations it led to. What is left is the terminal and the settings screen that
 * pairing lives in.
 *
 * **Which of the two the app opens on is conditional**, and the port left it unconditional. A phone
 * with no laptop opened on a terminal that cannot connect, with the one thing it needed to do behind
 * a settings button. [startDestination] is that rule and it is a pure function of one stored fact -
 * see its own note on why the fact is "is a laptop paired" and not "has this owner seen the tour".
 *
 * [BackStack] holds where the owner is as a fact about the journey rather than one hardcoded per
 * screen. That was worth a file of its own when there were five destinations reachable two ways;
 * with two it is nearly free, and it is kept because it is what `rememberSaveable` writes down when
 * the process is killed in the background.
 */
@Composable
fun App(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // One store, read by the start rule below and handed to the pairing screen, so "is a laptop
    // paired" cannot be answered two different ways in the same composition. The pairing screen does
    // not write it either - the enrolment does, on success, and nothing else does at all.
    val pairedLaptop = remember(context) { PairedLaptop(context) }

    // rememberSaveable so a rotation does not bounce the owner out of settings mid-pair - and note
    // that this initialiser runs ONCE, on the first composition and never after a restore, which is
    // exactly the behaviour wanted: pairing completing, or the process being killed on the terminal,
    // must not send the owner back to where the app happened to open.
    //
    // The store is read here, on the main thread, once per launch. It is a file of a few hundred
    // bytes in the app's own directory, opened before the first frame - the same shape as the styled
    // screen preference read below it. If either ever becomes a reason the first frame is late, both
    // move together.
    var stack by rememberSaveable(stateSaver = BackStack.StateSaver) {
        mutableStateOf(BackStack.initialFor(startDestination(hasPairedLaptop = pairedLaptop.isPaired)))
    }

    // Enabled only when there is somewhere to go back to, so that back on the terminal exits the
    // app the way it always has rather than being quietly swallowed.
    BackHandler(enabled = stack.canPop) { stack = stack.pop() }

    when (stack.current) {
        Screen.Agterm -> AgtermHost(
            // Pairing lives in the settings screen, so an identity failure sends the owner there
            // rather than offering a retry that cannot succeed.
            onPair = { stack = stack.push(Screen.Settings) },
            // The same destination, reached deliberately rather than by failing. Both push rather
            // than assigning, so back from Settings returns to the terminal either way.
            onOpenSettings = { stack = stack.push(Screen.Settings) },
            modifier = modifier,
        )

        Screen.Settings -> {
            // The store is the truth and this is a mirror of it for the switch; the terminal reads the
            // store itself on every poll, so nothing here needs to reach the ViewModel.
            val styledStore = remember { StyledScreenStore(context.filesDir.path) }
            var styledScreen by remember { mutableStateOf(styledStore.read()) }
            SettingsScreen(
                onBack = { stack = stack.pop() },
                modifier = modifier,
                pairingSection = { PairingHost(store = pairedLaptop) },
                styledScreen = styledScreen,
                onStyledScreen = { on ->
                    styledStore.write(on)
                    styledScreen = on
                },
            )
        }
    }
}

/**
 * What the screen shows for the app's own version.
 *
 * Read from [BuildConfig], never hardcoded, so it cannot disagree with the `.apk` it is part of.
 */
fun versionLabel(): String = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
