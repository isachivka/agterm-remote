package dev.isachivka.bewareofsugar.update

import dev.isachivka.bewareofsugar.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Where a download ended up. Only [Ready] carries a file, and only a whole file is ever [Ready]. */
sealed interface DownloadOutcome {
    data class Ready(val apk: File) : DownloadOutcome
    data class Failed(val reason: DownloadFailure) : DownloadOutcome
}

/**
 * Fetches an asset. The seam for tests, the same shape and for the same reason as [ReleaseSource]:
 * the ViewModel's job is the state machine, and it should be testable without a socket.
 */
interface AssetDownloader {
    suspend fun download(
        token: GitHubToken,
        asset: ReleaseAsset,
        onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): DownloadOutcome

    /** Throws away anything downloaded. Called once an install is known to have happened. */
    fun discard()
}

sealed interface DownloadFailure {
    /** GitHub answered, and the answer was no. Classified exactly as everywhere else. */
    data class Rejected(val reason: TokenValidationResult) : DownloadFailure

    /** The transfer started and did not finish: signal lost, server hung up, socket died. */
    data object Interrupted : DownloadFailure

    /**
     * The bytes that arrived are not the bytes GitHub said the asset has.
     *
     * Its own state rather than one of the above, because handing a short `.apk` to the installer
     * produces a parse error the owner cannot act on — the dead end REQ-0003 exists to avoid.
     */
    data class Truncated(val expectedBytes: Long, val actualBytes: Long) : DownloadFailure

    data class NotEnoughSpace(val requiredBytes: Long, val availableBytes: Long) : DownloadFailure

    /** The release has no `.apk` attached. Nothing to download, and not the owner's fault. */
    data object NoAsset : DownloadFailure
}

/**
 * Fetches a release's `.apk` and puts a whole one on disk, or none at all.
 *
 * Three properties this is built around, in order of how badly they fail when missed:
 *
 * 1. **A partial file is never handed to the installer.** Bytes land in a `.part` file that nothing
 *    else knows about, and it becomes the real file by a rename that only happens after the size has
 *    been checked. The installer is only ever given the renamed file.
 * 2. **A retry never resumes.** There is no `Range` header and no append: every attempt deletes what
 *    was there and starts at zero. Resuming would mean trusting a partial file written by an attempt
 *    that failed for reasons nobody recorded, and concatenating fresh bytes onto stale ones produces
 *    an `.apk` that is exactly the right size and completely wrong.
 * 3. **The token is not handed to a third party.** GitHub answers the asset endpoint with a 302 to
 *    its own downloads host, and OkHttp drops `Authorization` when a redirect crosses hosts. That is
 *    relied on rather than assumed: `ReleaseDownloaderTest` asserts the redirected request arrives
 *    without it.
 */
class ReleaseDownloader(
    private val downloadDir: File,
    private val client: OkHttpClient = GitHubApi.defaultClient(),
    private val baseUrl: HttpUrl = GitHubApi.GITHUB_API.toHttpUrl(),
    private val repoSlug: String = BuildConfig.GITHUB_REPO,
    private val usableSpaceBytes: () -> Long,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : AssetDownloader {

    /**
     * @param onProgress bytes written so far, and the total expected. Called from the IO thread as
     * the transfer runs — real bytes, because a progress bar that is not measuring anything is a
     * spinner with extra steps.
     */
    override suspend fun download(
        token: GitHubToken,
        asset: ReleaseAsset,
        onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit,
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        downloadDir.mkdirs()
        val target = File(downloadDir, "update-${asset.id}.apk")
        val partial = File(downloadDir, "update-${asset.id}.apk.part")

        // Start clean, always. Anything here is from an attempt that did not finish, and appending
        // to it would produce a plausible-sized and entirely invalid .apk.
        partial.delete()
        target.delete()

        if (asset.sizeBytes > 0) {
            val available = usableSpaceBytes()
            if (available < asset.sizeBytes) {
                return@withContext DownloadOutcome.Failed(
                    DownloadFailure.NotEnoughSpace(asset.sizeBytes, available),
                )
            }
        }

        val request = Request.Builder()
            .url(
                baseUrl.newBuilder()
                    .addPathSegments("repos/$repoSlug/releases/assets/${asset.id}")
                    .build(),
            )
            // The bytes, not the JSON description of them. Without this GitHub sends metadata and
            // the "download" succeeds while producing something the installer cannot read.
            .header("Accept", "application/octet-stream")
            .header("Authorization", "Bearer ${token.value}")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "BewareOfSugar/${BuildConfig.VERSION_NAME}")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val classified = classifyResponse(response, nowEpochSeconds)
                if (classified != TokenValidationResult.Valid) {
                    return@withContext DownloadOutcome.Failed(DownloadFailure.Rejected(classified))
                }

                var written = 0L
                response.body.byteStream().use { source ->
                    partial.outputStream().use { sink ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            // Cancellation is a first-class outcome here: the owner can leave the
                            // screen mid-transfer, and the partial file must not outlive them doing so.
                            coroutineContext.ensureActive()
                            val read = source.read(buffer)
                            if (read == -1) break
                            sink.write(buffer, 0, read)
                            written += read
                            onProgress(written, asset.sizeBytes)
                        }
                    }
                }

                // What GitHub said the asset weighs, against what arrived. A short .apk is refused
                // by the installer with an error the owner can do nothing with, so it is caught
                // here where it can be explained and retried.
                if (asset.sizeBytes > 0 && written != asset.sizeBytes) {
                    partial.delete()
                    return@withContext DownloadOutcome.Failed(
                        DownloadFailure.Truncated(asset.sizeBytes, written),
                    )
                }

                if (!partial.renameTo(target)) {
                    partial.delete()
                    return@withContext DownloadOutcome.Failed(DownloadFailure.Interrupted)
                }

                DownloadOutcome.Ready(target)
            }
        } catch (e: CancellationException) {
            partial.delete()
            throw e
        } catch (e: IOException) {
            partial.delete()
            DownloadOutcome.Failed(DownloadFailure.Interrupted)
        }
    }

    /** Removes anything left in the download directory. */
    override fun discard() {
        downloadDir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}
