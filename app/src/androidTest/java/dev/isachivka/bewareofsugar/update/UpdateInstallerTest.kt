package dev.isachivka.bewareofsugar.update

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The handoff to Android's installer, on a device, because every claim here is about manifest wiring
 * and platform behaviour that no JVM test can see.
 *
 * The one that would fail silently in production: the `FileProvider` authority is written down twice
 * — in the manifest and in [UpdateInstaller] — and if they ever disagree, granting the installer
 * access throws at exactly the moment the owner taps Install.
 */
@RunWith(AndroidJUnit4::class)
class UpdateInstallerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var apk: File

    @Before
    fun setUp() {
        apk = File(UpdateInstaller.downloadDir(context), "update-1.apk")
        apk.parentFile?.mkdirs()
        apk.writeBytes(ByteArray(16))
    }

    @After
    fun tearDown() {
        apk.delete()
    }

    @Test
    fun theProviderAuthorityMatchesTheManifest() {
        // getUriForFile throws IllegalArgumentException when no provider is declared for the
        // authority, or when the file is outside every configured path.
        val uri = UpdateInstaller.installIntent(context, apk).data

        assertNotEquals(null, uri)
        assertEquals(UpdateInstaller.AUTHORITY, uri!!.authority)
        assertEquals("content", uri.scheme)
    }

    @Test
    fun theIntentTellsAndroidItIsAnApkAndGrantsOneReadOfIt() {
        val intent = UpdateInstaller.installIntent(context, apk)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertTrue(
            "the installer is a different process and needs the grant",
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    /**
     * Where the `.apk` lives is a security property, not a filesystem preference. Anywhere
     * world-readable and another app could swap the file between the download finishing and the
     * installer opening it — with the owner having read the right version number a second earlier.
     */
    @Test
    fun downloadsGoToInternalStorageAndNowhereElse() {
        val dir = UpdateInstaller.downloadDir(context)

        assertTrue(
            "expected ${context.filesDir} to contain $dir",
            dir.canonicalPath.startsWith(context.filesDir.canonicalPath),
        )
        // getExternalFilesDir can be null, which is not a pass on its own - the assertion above is
        // what carries this. This one only rules out the obvious mistake.
        context.getExternalFilesDir(null)?.let { external ->
            assertTrue(!dir.canonicalPath.startsWith(external.canonicalPath))
        }
    }

    /**
     * The two halves joined on a real device: bytes fetched over a socket, written to real internal
     * storage, and then handed to the provider. Each half is covered elsewhere; this is the seam
     * between them, which is where a wrong directory or a mismatched authority would show up.
     */
    @Test
    fun aDownloadedFileIsTheFileTheInstallerIsGiven() {
        kotlinx.coroutines.runBlocking {
        val payload = ByteArray(32 * 1024) { (it % 251).toByte() }
        val server = mockwebserver3.MockWebServer()
        server.start()
        try {
            server.enqueue(
                mockwebserver3.MockResponse.Builder()
                    .code(200)
                    .body(okio.Buffer().write(payload))
                    .build(),
            )
            val downloader = ReleaseDownloader(
                downloadDir = UpdateInstaller.downloadDir(context),
                baseUrl = server.url("/"),
                repoSlug = "isachivka/beware-of-sugar",
                // The real one, on the real device: the same StorageManager answer the app uses.
                usableSpaceBytes = {
                    UpdateInstaller.allocatableBytes(context, UpdateInstaller.downloadDir(context))
                },
            )
            val asset = ReleaseAsset(490736218L, "beware-of-sugar-0.3.0.apk", payload.size.toLong())

            val outcome = downloader.download(GitHubToken("not-a-real-token"), asset)

            assertTrue("expected a completed download, got $outcome", outcome is DownloadOutcome.Ready)
            val file = (outcome as DownloadOutcome.Ready).apk
            assertTrue(file.readBytes().contentEquals(payload))

            // And that exact file is what the installer would be handed.
            val uri = UpdateInstaller.installIntent(context, file).data
            assertEquals(UpdateInstaller.AUTHORITY, uri!!.authority)
            context.contentResolver.openInputStream(uri).use { stream ->
                assertTrue(
                    "the installer must read back exactly what was downloaded",
                    stream!!.readBytes().contentEquals(payload),
                )
            }
            file.delete()
        } finally {
            server.close()
        }
        }
    }

    @Test
    fun theUnknownSourcesIntentPointsAtThisAppsSettings() {
        val intent = UpdateInstaller.allowUnknownSourcesIntent(context)

        assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
    }

    @Test
    fun anApkOutsideTheDownloadDirectoryIsNotShareable() {
        // The provider exposes files/updates and nothing else, so a path traversal or a stray file
        // elsewhere in filesDir cannot be handed out.
        val stray = File(context.filesDir, "not-an-update.apk").apply { writeBytes(ByteArray(4)) }
        try {
            val threw = runCatching { UpdateInstaller.installIntent(context, stray) }.exceptionOrNull()
            assertTrue(
                "expected the provider to refuse a file outside files/updates, got $threw",
                threw is IllegalArgumentException,
            )
        } finally {
            stray.delete()
        }
    }
}
