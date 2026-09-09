package dev.isachivka.agtermremote.agterm

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.isachivka.agtermremote.pairing.PairedLaptop
import dev.isachivka.agtermremote.pairing.PhoneIdentity

/**
 * Assembles the module: the paired profile, the phone's own key, the connection, the screen.
 *
 * The connection is opened lazily and **released when this composable leaves for real** - which is not
 * the same as leaving. A rotation disposes and immediately recomposes it, so departure is tested with
 * `isChangingConfigurations` rather than assumed from disposal. State lives in [AgtermViewModel] so a
 * rotation costs nothing; see that file for why it is a ViewModel and specifically not `rememberSaveable`.
 *
 * The connection is not held for the life of the process: an open mTLS session to the owner's laptop
 * is not a thing to keep alive in the background on the chance they come back.
 */
@Composable
fun AgtermHost(
    onPair: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    val model: AgtermViewModel = viewModel(
        factory = viewModelFactory {
            initializer { AgtermViewModel(context.applicationContext) }
        },
    )
    val sessions = model.sessions


    DisposableEffect(model, activity) {
        onDispose {
            // A rotation disposes this composable and immediately recomposes it against the same
            // ViewModel. Releasing here would drop the owner's connection and force a re-handshake -
            // an unlock prompt included - on a gesture that is supposed to be free.
            //
            // isChangingConfigurations is the platform's own answer to "is this a real departure?",
            // and it is set before onDestroy runs.
            if (activity?.isChangingConfigurations != true) {
                model.release()
            }
        }
    }

    val state by sessions.state.collectAsStateWithLifecycle()
    val typing by sessions.typing.collectAsStateWithLifecycle()
    val draft by sessions.draft.collectAsStateWithLifecycle()
    val fit by sessions.fit.collectAsStateWithLifecycle()
    val paneShown by sessions.paneShown.collectAsStateWithLifecycle()
    val recalibrated by sessions.recalibrated.collectAsStateWithLifecycle()
    val link by sessions.link.collectAsStateWithLifecycle()
    val mutation by sessions.mutation.collectAsStateWithLifecycle()

    // **Refresh whenever the list BECOMES VISIBLE**, which is entry and the return from a session as
    // one event rather than two call sites that must both remember. The owner:
    // *"нужно обновлять его каждый раз когда я вижу список сессий - в том числе когда я перехожу из
    // сессии назад к списку"*.
    //
    // This replaced `LaunchedEffect(model)`, which fired once per ViewModel - so returning from a
    // session, which remounts nothing, left them looking at a listing as old as when they opened it.
    //
    // Keyed on the predicate rather than on the state, so the fetch cannot re-trigger itself: see
    // showsList, which stays true across Loading -> Sessions and across a refresh. A rotation does not
    // re-fetch either, because the predicate does not change across one.
    val listOnScreen = showsList(state)
    LaunchedEffect(listOnScreen) { if (listOnScreen) sessions.refresh() }
    val renaming by sessions.renaming.collectAsStateWithLifecycle()

    // The platform's picker, as with pairing: no permission, no dependency, and the owner already knows
    // how it works. `OpenMultipleDocuments` rather than `GetContent` because it gives stable URIs we
    // can read the display name from, which is the basename the bridge sanitises - and *multiple*
    // since REQ-0048, because one pick per photograph was the whole complaint. The list comes back in
    // the order he tapped, and that is the order the paths take in the box.
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            // Read on a background thread: a file is up to sixteen megabytes and the main thread is
            // drawing a terminal. The bytes never touch disk on this side and are not logged.
            scope.launch {
                val picked = withContext(Dispatchers.IO) { uris.mapNotNull { readPicked(context, it) } }
                if (picked.isNotEmpty()) sessions.sendFiles(picked)
            }
        }
    }

    AgtermScreen(
        state = state,
        onRefresh = { sessions.refresh() },
        onOpen = { sessions.watch(it) },
        onCloseSession = { sessions.stopWatching() },
        onFitToPhone = { boxWidthDp, charMilliDp, recalibrate ->
            sessions.fitToPhone(boxWidthDp, charMilliDp, recalibrate)
        },
        onRestoreWindow = { sessions.restoreWindow() },
        fit = fit,
        typing = typing,
        draft = draft,
        onOpenTyping = { sessions.openTyping() },
        onCloseTyping = { sessions.closeTyping() },
        onDraftChange = { sessions.editDraft(it) },
        onSendText = { sessions.type(text = it) },
        onSendKey = { sessions.type(key = it) },
        // Two calls, ordered, the Return only if the text landed - see AgtermSessions.runMacro.
        onRunMacro = { sessions.runMacro(it) },
        onPickFile = { runCatching { pickFiles.launch(arrayOf("*/*")) } },
        onDisconnect = { sessions.disconnect() },
        onPair = onPair,
        onBack = onBack,
        modifier = modifier,
        // The list's remembered position lives in the ViewModel beside the terminal's, so opening a
        // session and coming back finds it where they left it.
        listAnchor = model.sessionListAnchor,
        onListAnchor = { model.sessionListAnchor = it },
        // Folded workspaces live beside the anchor, for the same reason and with the same lifetime.
        // One field in AgtermSessions, read by the poll, the keystrokes and this control alike.
        recalibrated = recalibrated,
        paneShown = paneShown,
        onTogglePane = sessions::togglePane,
        onRefit = sessions::refit,
        closedWorkspaces = model.closedWorkspaces,
        onClosedWorkspaces = { model.closedWorkspaces = it },
        // The address, never a name - the phone holds no name for the laptop. Drawn on their screen
        // and nowhere else.
        laptop = model.laptopAddress,
        // Connection state; the typing state is already passed above. The surface merges them.
        link = link,
        onDismissNotice = { sessions.dismissNotice() },
        // Creating and renaming. The dialog's subject lives in AgtermSessions rather than in the
        // composable, because "creating opens the rename dialog on what was just made" is a rule about
        // the state machine - and there it can be checked on the JVM, where there is no device.
        onCreateWorkspace = { sessions.createWorkspace() },
        onCreateSession = { sessions.createSession(it) },
        renaming = renaming,
        onBeginRename = { sessions.beginRename(it) },
        // **The dialog is NOT closed here.** Whether a rename was dispatched at all is decided in
        // AgtermSessions, and closing from out here unconditionally would discard the owner's text on
        // a name it refused to send - see dispatchRename.
        onRename = { target, label ->
            when (target) {
                is RenameTarget.Workspace -> sessions.renameWorkspace(target.id, label)
                is RenameTarget.Session -> sessions.renameSession(target.id, label)
            }
        },
        onCancelRename = { sessions.cancelRename() },
        // Destroys their work, reached only from the modal a long press opened. The when is exhaustive
        // so a third kind of target cannot ship with no destructive path behind it.
        onDelete = { target ->
            when (target) {
                is RenameTarget.Workspace -> sessions.deleteWorkspace(target.id)
                is RenameTarget.Session -> sessions.closeSession(target.id)
            }
        },
        mutation = mutation,
        onDismissMutation = { sessions.dismissMutationNote() },
        terminalVertical = model.terminalVertical,
        terminalHorizontal = model.terminalHorizontal,
    )
}

/**
 * One connection, or null when there is no paired laptop.
 *
 * Null rather than an exception for the unpaired case: it is not a failure, it is the state the app
 * starts in, and the remedy is a screen rather than a retry.
 */
private fun connect(context: Context): BridgeConnection? {
    val profile = PairedLaptop(context).read() ?: return null
    return BridgeConnection.open(profile, PhoneIdentity.keyManager())
}

/**
 * The picked file's display name and bytes, or null if it cannot be read.
 *
 * **Bounded before it is read, not after.** A four-megabyte cap that is enforced by checking the length
 * of a buffer already in memory is not a cap on memory. The size comes from the provider first, and a
 * file over the bound is never loaded.
 *
 * The name is the provider's display name, which is a caller-supplied string as far as the bridge is
 * concerned - it sanitises it there and refuses anything that could be a path. Nothing is checked twice
 * here, because a second check in a second place is a second thing to get wrong.
 */
private fun readPicked(context: Context, uri: Uri): Pair<String, ByteArray>? = runCatching {
    val resolver = context.contentResolver
    var name = "file"
    var size = -1L
    resolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                ?.let { if (!c.isNull(it)) name = c.getString(it) }
            c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }
                ?.let { if (!c.isNull(it)) size = c.getLong(it) }
        }
    }
    if (size > MAX_FILE_BYTES) return null
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    // A provider that reported no size, or lied about it, is caught here - readBytes is bounded by the
    // stream and this is the check that the bridge's own limit is not reached by surprise.
    if (bytes.size > MAX_FILE_BYTES) return null
    name to bytes
}.getOrNull()

/**
 * Mirrors `dropoff.MaxFileBytes`. The bridge enforces it; this avoids reading a file that will be
 * refused, because a cap checked against a buffer already in memory is not a cap on memory.
 *
 * Sixteen megabytes: a Pixel photograph is routinely five to ten, and the first thing anyone picks with
 * a file picker on a phone is a photograph.
 */
private const val MAX_FILE_BYTES = 16L shl 20

/** The Activity behind a Compose context, or null. Needed because a system prompt has to belong to one. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

