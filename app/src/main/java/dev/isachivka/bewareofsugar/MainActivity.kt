package dev.isachivka.bewareofsugar

import android.app.Activity
import android.os.Bundle
import dev.isachivka.bewareofsugar.pairing.PairedLaptop
import dev.isachivka.bewareofsugar.pairing.PairingHost
import dev.isachivka.bewareofsugar.pairing.FileHandover
import dev.isachivka.bewareofsugar.pairing.KeystorePhoneHalf
import dev.isachivka.bewareofsugar.pairing.LeftoverScans
import dev.isachivka.bewareofsugar.pairing.PairingSequence
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.isachivka.bewareofsugar.agterm.AgtermHost
import dev.isachivka.bewareofsugar.limits.LimitsViewModel
import dev.isachivka.bewareofsugar.settings.StyledScreenStore
import dev.isachivka.bewareofsugar.reachability.ReachabilityHost
import dev.isachivka.bewareofsugar.reachability.ReachabilityViewModel
import dev.isachivka.bewareofsugar.reachability.tileVerdictOf
import dev.isachivka.bewareofsugar.ui.home.HomeScreen
import dev.isachivka.bewareofsugar.ui.module.ModuleRegistry
import dev.isachivka.bewareofsugar.ui.module.SettingsScreen
import dev.isachivka.bewareofsugar.ui.nav.BackStack
import dev.isachivka.bewareofsugar.ui.nav.Screen
import dev.isachivka.bewareofsugar.ui.theme.AppTheme
import dev.isachivka.bewareofsugar.update.TokenScreen
import dev.isachivka.bewareofsugar.update.TokenViewModel
import dev.isachivka.bewareofsugar.update.play.PlayUpdateScreen
import dev.isachivka.bewareofsugar.update.play.PlayUpdateViewModel
import dev.isachivka.bewareofsugar.update.play.homeNoticeTagOf
import dev.isachivka.bewareofsugar.update.play.homeUpdateStatusOf
import dev.isachivka.bewareofsugar.update.play.playStoreIntent
import dev.isachivka.bewareofsugar.update.play.playStoreWebIntent
import dev.isachivka.bewareofsugar.update.UpdateStatus

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A phone that ran a build before REQ-0019 may be holding a photograph of the laptop's
        // pairing code in app storage. Deleting the code that wrote it does not delete the picture,
        // so this does - once a start, quietly, and never as a reason the app fails to open.
        LeftoverScans.remove(filesDir)
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
 * The screens, and one back stack across all of them.
 *
 * Back used to be a `BackHandler` per screen naming its parent, which is correct exactly while every
 * screen has one way in. The Updates screen now has two - the launcher's tile and the update banner
 * above it - so back is a fact about the journey rather than about a screen, and [BackStack] holds
 * it. All the behaviour worth arguing over lives there as a pure function, tested on the JVM; what
 * is left here is the wiring.
 *
 * The launch check lives here, at the top, because REQ-0003 asks for a check on launch - one that
 * only ran when the owner opened the update screen would be a check they had to think of first.
 * It is also why the update state is hoisted to this level: the launcher reads it too.
 */
@Composable
fun App(modifier: Modifier = Modifier) {
    // rememberSaveable so a rotation does not bounce the owner back to the launcher mid-paste. It
    // holds where they are - never the token, which lives in a ViewModel precisely to stay out of
    // savedInstanceState.
    var stack by rememberSaveable(stateSaver = BackStack.StateSaver) { mutableStateOf(BackStack.Initial) }
    val context = LocalContext.current

    // Enabled only when there is somewhere to go back to, so that back on the launcher exits the
    // app the way it always has rather than being quietly swallowed.
    BackHandler(enabled = stack.canPop) { stack = stack.pop() }

    // Constructed here, so its init-time launch check runs when the app opens rather than when a
    // particular screen is first composed.
    // REQ-0050. The GitHub UpdateViewModel is no longer constructed here, and that is the whole of
    // "the old path is hidden": nothing navigates to its screen, and nothing runs its check. The
    // class, its screen and its tests are all intact and pending removal.
    val playUpdateViewModel: PlayUpdateViewModel = viewModel(factory = PlayUpdateViewModel.factory(context))
    val playStatus by playUpdateViewModel.status.collectAsStateWithLifecycle()

    // A ModulesViewModel used to live here, hoisted so the launcher's tiles and the settings screen's
    // switches read one copy of "which modules are on". There are no switches now: the launcher draws
    // ModuleRegistry.all, always, and there is nothing to hold in common.

    // The launch check. Held in a ViewModel so it survives navigating launcher <-> module, driven
    // from the LIFECYCLE and never from viewModelScope - a ViewModel outlives onStop, so a check
    // started there would keep running with the app off screen, which is exactly what REQ-0005
    // forbade and what LaunchCheckInstrumentedTest asserts cannot happen.
    //
    // Not a schedule. repeatOnLifecycle re-enters when the owner brings the app forward, and nothing
    // fires while backgrounded - no timer exists and no work outlives onStop.
    //
    // Unconditional since REQ-0007: the owner asked to reload on every return, so there is no
    // freshness window to consult. Two consequences they were told about rather than left to find -
    // the tile shows Checking on every return, and rotating the phone recreates the Activity and so
    // rebuilds the verdict. The fix for the second, if it is ever wanted, is ON_STOP; it is NOT a
    // flag saying this process already checked, because a latch added to suppress a re-check on
    // rotation is one edit away from suppressing the re-check on return that this exists to add.
    val reachabilityViewModel: ReachabilityViewModel = viewModel(factory = ReachabilityViewModel.factory())
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            reachabilityViewModel.results.check()
        }
    }
    // REQ-0045. The same arrangement as reachability: results in a ViewModel, the lifecycle as the
    // trigger. Asked on every return to the foreground, but only ANSWERED over the network when
    // nothing is held or what is held is older than thirty minutes - LimitsResults.refreshIfStale
    // decides, so a rotation or a quick app switch costs no request.
    val limitsViewModel: LimitsViewModel = viewModel(factory = LimitsViewModel.factory(context))
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            limitsViewModel.results.refreshIfStale()
        }
    }
    val limitsState by limitsViewModel.results.state.collectAsStateWithLifecycle()
    // REQ-0009. Plain `remember`, not a ViewModel: the sequence holds no coroutines and no state of
    // its own - every transition is a pure function of what it was handed, and what persists is the
    // file underneath it. A ViewModel here would add a lifecycle to something that has none.
    val pairingSequence = remember(context) {
        PairingSequence(
            store = PairedLaptop(context),
            // The identity is generated when pairing BEGINS rather than lazily at first connect: at
            // first connect the owner is away from the laptop, and a certificate produced then has
            // nowhere to go and nobody to explain a failure to.
            phone = KeystorePhoneHalf(),
            handover = FileHandover(java.io.File(context.filesDir, PairedLaptop.DIRECTORY)),
        )
    }

    val reachabilityScope = rememberCoroutineScope()
    // The launcher draws immediately and this fills in when results land - it never waits for the
    // check. Null-safe by construction: an empty map is "not checked", which is a different thing
    // from "nothing available" and must not look like it.
    val reachabilityResults by reachabilityViewModel.results.results.collectAsStateWithLifecycle()

    when (stack.current) {
        Screen.Home -> {
            // Through the adapter rather than directly: HomeScreen still speaks the GitHub path's
            // vocabulary, and translating here was cheaper - and far more reviewable - than
            // rewriting that screen and its tests inside a change about update channels.
            val noticeTag = homeNoticeTagOf(playStatus)

            HomeScreen(
                // The whole registry, always. The owner's words on 2026-07-31: *"Все модули которые
                // есть в приложении должны отображаться на главной странице."*
                modules = ModuleRegistry.all,
                installedVersion = versionLabel(),
                updateStatus = homeUpdateStatusOf(playStatus),
                // Arriving from the banner asks Play again. Play caches its answer, so the tile may
                // be repeating something it learned a while ago, and the screen the owner is about
                // to look at should not be repeating it too.
                onOpenUpdates = {
                    playUpdateViewModel.checkNow()
                    stack = stack.push(Screen.Updates)
                },
                // One place decides where a module goes, shared with Screen.fromRoute, so a tap
                // and a restored back stack cannot disagree about which tiles are real.
                // A module with no screen cannot be tapped into one. That case is unreachable — every
                // registry entry resolves, asserted by ModuleRegistryTest — and it is handled rather
                // than forced, because `!!` on the launcher's tap handler would turn a registry
                // mistake into a crash in the owner's hand instead of a red test on ours.
                onOpenModule = { module -> Screen.screenFor(module)?.let { stack = stack.push(it) } },
                onOpenSettings = { stack = stack.push(Screen.ModulesSettings) },
                modifier = modifier,
                noticeTag = noticeTag,
                verdict = tileVerdictOf(reachabilityResults.values.map { it.reachability }),
                // The same round the foreground trigger runs, on the same scope. One path, so a pull
                // and a return cannot behave differently - and check() ignores a second call while a
                // round is in flight, which is what stops a swipe-on-return from racing it.
                onRefresh = { reachabilityScope.launch { reachabilityViewModel.results.check() } },
                limits = limitsState,
                // The long press. On the Activity's scope like the pull above, so leaving cancels it.
                onForceRefreshLimits = { reachabilityScope.launch { limitsViewModel.results.refresh(fresh = true) } },
            )
        }

        Screen.Updates -> {
            PlayUpdateScreen(
                status = playStatus,
                onCheckNow = playUpdateViewModel::checkNow,
                onUpdate = {
                    // Play needs the Activity, not the application Context: its update flow is a
                    // full-screen activity started for a result, and there is nothing to start it
                    // from otherwise.
                    (context as? Activity)?.let(playUpdateViewModel::startUpdate)
                },
                onOpenStore = {
                    // The Store app first, the web page when there is no Store app to answer. A
                    // device with neither leaves the owner where they were rather than crashing.
                    runCatching { context.startActivity(playStoreIntent(context.packageName)) }
                        .recoverCatching { context.startActivity(playStoreWebIntent(context.packageName)) }
                },
                onOpenToken = { stack = stack.push(Screen.Token) },
                onBack = { stack = stack.pop() },
                modifier = modifier,
            )
        }

        // The route literal is still "modules" while the screen is Settings, and that is deliberate:
        // a route is persisted state, so renaming one is a migration for no benefit.
        Screen.ModulesSettings -> {
            // The store is the truth and this is a mirror of it for the switch; the terminal reads the
            // store itself on every poll, so nothing here needs to reach the ViewModel.
            val styledStore = remember { StyledScreenStore(context.filesDir.path) }
            var styledScreen by remember { mutableStateOf(styledStore.read()) }
            SettingsScreen(
                onBack = { stack = stack.pop() },
                modifier = modifier,
                // REQ-0009 called this placement provisional because pairing configured a feature that
                // did not exist yet. Deleting the modules section settles it: pairing is not a section
                // of this screen any more, it is the screen.
                pairingSection = { PairingHost(sequence = pairingSequence) },
                styledScreen = styledScreen,
                onStyledScreen = { on ->
                    styledStore.write(on)
                    styledScreen = on
                },
            )
        }

        Screen.Agterm -> AgtermHost(
            // Pairing lives in the settings screen, so an identity failure sends the owner there
            // rather than offering a retry that cannot succeed.
            onPair = { stack = stack.push(Screen.ModulesSettings) },
            onBack = { stack = stack.pop() },
            modifier = modifier,
        )

        Screen.Reachability -> ReachabilityHost(
            results = reachabilityViewModel.results,
            scope = reachabilityScope,
            onBack = { stack = stack.pop() },
            modifier = modifier,
        )


        Screen.Token -> {
            val tokenViewModel: TokenViewModel = viewModel(factory = TokenViewModel.factory(context))
            val state by tokenViewModel.state.collectAsStateWithLifecycle()

            TokenScreen(
                state = state,
                onInputChange = tokenViewModel::onInputChange,
                onSave = tokenViewModel::onSave,
                onReplace = tokenViewModel::onReplace,
                onRemove = tokenViewModel::onRemove,
                onBack = { stack = stack.pop() },
                modifier = modifier,
            )
        }
    }
}

/**
 * What the screen shows for the app's own version.
 *
 * Read from [BuildConfig], never hardcoded, so it cannot disagree with the `.apk` it
 * is part of. It exists so the upgrade check — which ends "opens an app that is now the new
 * version" — can be completed on the phone, without digging through Settings on a laptop. On the
 * launcher it is the Updates tile's subtitle.
 */
fun versionLabel(): String = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
