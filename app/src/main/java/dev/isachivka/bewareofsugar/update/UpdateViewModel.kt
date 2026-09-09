/*
 * ============================================================================================
 * HIDDEN, PENDING REMOVAL — REQ-0050.
 *
 * Nothing navigates here any more. The app's update screen is
 * `update/play/PlayUpdateScreen.kt`, and updates arrive through Google Play.
 *
 * This file still compiles and its tests still pass, because it still does exactly what it says.
 * It is kept rather than deleted for one reason: deleting it is a requirement of its own, with
 * consequences to decide rather than discover. Going with it, when that happens:
 *
 *   - the token screen, the token store and its hardware-backed cipher
 *   - the downloader, `UpdateInstaller`, and REQUEST_INSTALL_PACKAGES from the manifest
 *   - `update/play/PlayHomeStatus.kt`, the adapter that exists only while both types do
 *   - the app's only two-screen-deep journey, which three instrumented tests use to measure the
 *     back stack and its survival through a rotation. That coverage needs somewhere else to live
 *     BEFORE this goes, which is the decision that made removal its own requirement.
 *
 * Do not extend this path. Do not fix bugs in it. Bugs here are reported by nobody, because
 * nothing reaches it.
 * ============================================================================================
 */

package dev.isachivka.bewareofsugar.update

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.isachivka.bewareofsugar.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * The update check: once a day on launch, and whenever the owner asks.
 *
 * Lives at the top of the app rather than on the update screen, because REQ-0003 asks for the check
 * to happen on launch — a check that only runs once the owner opens the update screen is a check
 * they had to think of first.
 */
/** What the download is doing, separately from what the check found. */
sealed interface DownloadState {
    data object Idle : DownloadState

    /** @param totalBytes 0 when GitHub did not say, which it always does in practice. */
    data class Running(val bytesWritten: Long, val totalBytes: Long) : DownloadState {
        /** Null when the total is unknown, so the UI shows motion rather than a fake percentage. */
        val fraction: Float? get() = if (totalBytes > 0) (bytesWritten.toFloat() / totalBytes) else null
    }

    /**
     * A whole, size-checked `.apk` is on disk.
     *
     * This state persists after the installer is launched, on purpose. Android's install screen
     * reports nothing back, so the owner declining it is indistinguishable from them confirming and
     * changing their mind - and both leave the app exactly here, with a file ready and a button
     * that says so. Declining is a normal outcome, so it must not look like a failure.
     */
    data class ReadyToInstall(val apk: File, val launched: Boolean = false) : DownloadState

    data class Failed(val reason: DownloadFailure) : DownloadState

    /** The owner has not allowed this app to install apps; Android settings is the only way through. */
    data object NotAllowedToInstall : DownloadState
}

class UpdateViewModel(
    private val store: TokenStore,
    private val api: ReleaseSource,
    private val downloader: AssetDownloader? = null,
    private val installed: AppVersion? = AppVersion.parse(BuildConfig.VERSION_NAME),
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scope ?: viewModelScope

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download.asStateFlow()

    /** What the last successful check found. See [UpdateNotice] for why it is never trusted alone. */
    private val _rememberedTag = MutableStateFlow<String?>(null)

    /**
     * When GitHub last accepted the stored token, or null when there is none.
     *
     * Only so the update screen's token row can say "connected, checked on the 27th" without
     * holding the token or a second copy of its state. Re-read on entering that screen, because the
     * owner may have just saved one on the screen it leads to.
     */
    private val _tokenValidatedAt = MutableStateFlow<Long?>(null)
    val tokenValidatedAt: StateFlow<Long?> = _tokenValidatedAt.asStateFlow()

    /** Whether a stored token failed to read on the last look — REQ-0047. The token row says so. */
    private val _tokenUnreadable = MutableStateFlow(false)
    val tokenUnreadable: StateFlow<Boolean> = _tokenUnreadable.asStateFlow()

    /**
     * The tag the launcher announces, or null for silence.
     *
     * Combined here rather than in the composable so that the rule about what may be announced is
     * one testable function and not a condition spread across a screen.
     */
    val noticeTag: StateFlow<String?> = combine(_status, _rememberedTag) { status, tag ->
        UpdateNotice.tagToAnnounce(status, tag, installed)
    }.stateIn(this.scope, SharingStarted.Eagerly, null)

    private var running: Job? = null
    private var downloading: Job? = null

    init {
        // this.scope, not the constructor parameter of the same name, which is nullable.
        this.scope.launch { _rememberedTag.value = store.lastFoundTag() }
        refreshToken()
        check(manual = false)
    }

    /** The owner pressed Check. Always runs, whatever the schedule says. */
    fun checkNow() = check(manual = true)

    /** Re-reads whether a token is stored. Cheap, and the token screen can have changed it. */
    fun refreshToken() {
        scope.launch {
            val stored = store.read()
            _tokenValidatedAt.value =
                (stored as? StoredToken.Present)?.validatedAtEpochSeconds?.takeIf { it > 0L }
            _tokenUnreadable.value = stored is StoredToken.Unreadable
        }
    }

    private fun check(manual: Boolean) {
        if (running?.isActive == true) return
        running = scope.launch {
            val stored = store.read()
            val token = (stored as? StoredToken.Present)?.token
            if (token == null) {
                // Nothing to check with either way, but the two are different sentences: a token
                // that was never pasted, and one that is there and would not read - REQ-0047.
                _status.value =
                    if (stored is StoredToken.Unreadable) UpdateStatus.TokenUnreadable else UpdateStatus.NoToken
                _tokenUnreadable.value = stored is StoredToken.Unreadable
                return@launch
            }

            if (!CheckSchedule.isDue(store.lastCheckedAt(), nowEpochSeconds(), manual)) return@launch

            _status.value = UpdateStatus.Checking
            val result = api.releases(token)
            val now = nowEpochSeconds()
            _status.value = UpdateCheck.evaluate(
                result = result,
                installed = installed,
                nowEpochSeconds = now,
                // The token was stored, which means GitHub accepted it once. That is what makes
                // "expired" an honest word here rather than a guess.
                hasWorkedBefore = (stored as StoredToken.Present).validatedAtEpochSeconds > 0L,
            )

            // Only a real answer counts against the once-a-day budget. Recording a failed check
            // would mean a day of silence after a single tunnel, and - worse - a day of silence
            // after the owner fixes an expired token.
            if (result is ApiResult.Success) {
                store.recordChecked(now)
                // And only a real answer is remembered. A failure leaves the previous answer
                // standing rather than erasing it or inventing one - being offline says nothing
                // about whether a release exists.
                val found = (_status.value as? UpdateStatus.Available)?.release?.tag
                _rememberedTag.value = found
                store.recordFound(found)
            }

            // Being up to date is the signal that an install happened - Android never reports one -
            // so this is where a downloaded .apk stops being worth keeping. Several megabytes of
            // internal storage, in an app that otherwise stores one encrypted token.
            if (_status.value is UpdateStatus.UpToDate && _download.value !is DownloadState.Running) {
                downloader?.discard()
                _download.value = DownloadState.Idle
            }
        }
    }

    /**
     * @param canInstall whether the owner has allowed this app to install apps. Passed in rather
     * than read here, because it is a property of the phone's settings and the ViewModel holds no
     * Context.
     */
    fun startDownload(canInstall: Boolean) {
        val available = _status.value as? UpdateStatus.Available ?: return
        val downloader = downloader ?: return
        if (downloading?.isActive == true) return

        if (!canInstall) {
            _download.value = DownloadState.NotAllowedToInstall
            return
        }

        // The same asset the screen quoted a size for - see Release.updateAsset.
        val asset = available.release.updateAsset
        if (asset == null) {
            _download.value = DownloadState.Failed(DownloadFailure.NoAsset)
            return
        }

        downloading = scope.launch {
            _download.value = DownloadState.Running(0L, asset.sizeBytes)
            val stored = store.read()
            val token = (stored as? StoredToken.Present)?.token
            if (token == null) {
                _status.value = UpdateStatus.NoToken
                _download.value = DownloadState.Idle
                return@launch
            }

            val outcome = downloader.download(token, asset) { written, total ->
                _download.value = DownloadState.Running(written, total)
            }
            _download.value = when (outcome) {
                is DownloadOutcome.Ready -> DownloadState.ReadyToInstall(outcome.apk)
                is DownloadOutcome.Failed -> {
                    // A token that stopped working mid-feature is the same fact the check reports,
                    // so it is reported in the same place and with the same words.
                    if (outcome.reason is DownloadFailure.Rejected) {
                        _status.value = UpdateCheck.evaluate(
                            ApiResult.Failure(outcome.reason.reason),
                            installed,
                            nowEpochSeconds(),
                            hasWorkedBefore = (stored as StoredToken.Present).validatedAtEpochSeconds > 0L,
                        )
                    }
                    DownloadState.Failed(outcome.reason)
                }
            }
        }
    }

    /** Cancels an in-flight download. The partial file is deleted by the downloader as it unwinds. */
    fun cancelDownload() {
        downloading?.cancel()
        downloading = null
        _download.value = DownloadState.Idle
    }

    /** Records that Android's installer has been launched, so the screen can say what is happening. */
    fun onInstallerLaunched() {
        val ready = _download.value as? DownloadState.ReadyToInstall ?: return
        _download.value = ready.copy(launched = true)
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = context.applicationContext
                UpdateViewModel(
                    store = tokenStore(application),
                    api = GitHubApi(),
                    downloader = ReleaseDownloader(
                        downloadDir = UpdateInstaller.downloadDir(application),
                        usableSpaceBytes = {
                            UpdateInstaller.allocatableBytes(
                                application,
                                UpdateInstaller.downloadDir(application),
                            )
                        },
                    ),
                )
            }
        }
    }
}
