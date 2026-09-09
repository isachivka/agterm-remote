import java.util.Base64

// versionName is the single source of truth for the app's version. versionCode is a pure function of
// it, computed by buildSrc/src/main/kotlin/VersionCode.kt and unit-tested there.
//
// **The trailing `// x-release-please-version` is not a note, it is the mechanism.** release-please
// rewrites this literal only because that marker is on the line, and this file is listed under
// `extra-files` in release-please-config.json. Remove either and the bump does not fail - it does
// NOTHING, silently, and the first anyone hears of it is a release whose app reports the version
// before it. Both halves are asserted, so neither can be dropped by an edit that looks tidy.
//
// It arrived from the private repository reading 0.34.0, which was that project's version and not
// this one's. This repository's .release-please-manifest.json says 0.1.0, and the two must agree or
// the first release here would be a downgrade on any phone that had installed a build of this tree.
val appVersionName = "0.1.0" // x-release-please-version

// Release signing material, resolved once from the environment.
// null on an ordinary local build, which is left completely unsigned and unaffected.
val releaseSigning = releaseSigningFrom { name -> System.getenv(name) }


plugins {
    alias(libs.plugins.android.application)
    // Still required with built-in Kotlin: enabling Compose needs the Compose compiler plugin.
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.isachivka.agtermremote"
    // Verified via the SDK Manager that API 37 is the highest stable platform.
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.isachivka.agtermremote"
        minSdk = 34
        targetSdk = 37
        versionName = appVersionName
        versionCode = versionCodeOf(appVersionName)
        // Set explicitly rather than relying on the AGP 9 default, so the runner used by
        // connectedDebugAndroidTest is visible in the build file.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Only configured when the environment supplies all four secrets. The keystore is
            // decoded into the build directory, which is gitignored - it is never written anywhere
            // tracked, and the passwords are only ever held in memory from environment variables.
            releaseSigning?.let { signing ->
                val decoded = project.layout.buildDirectory.file("signing/release.jks").get().asFile
                decoded.parentFile.mkdirs()
                decoded.writeBytes(Base64.getDecoder().decode(signing.keystoreBase64))
                // Readable only by the user running the build, matching how the keystore is stored
                // outside the repo.
                decoded.setReadable(false, false)
                decoded.setReadable(true, true)

                storeFile = decoded
                storePassword = signing.storePassword
                keyAlias = signing.keyAlias
                keyPassword = signing.keyPassword

                // ONE line, and the two schemes it does NOT produce are the interesting part.
                //
                // v3 is off by default in AGP and has to be asked for. Every release up to and
                // including 0.32.0 therefore carried v2 ALONE - measured with apksigner, not
                // assumed - and it was read as either a decision or a toolchain
                // default. It was neither: nobody had written this line. It matters because v3 is the
                // only scheme carrying a signing certificate LINEAGE, which is Android's sole
                // mechanism for ever replacing a signing key on an app that is already installed.
                //
                // What asking for it actually does, measured on 2026-09-07 with --rerun-tasks:
                //
                //   nothing set          ->  v2 true,  v3 false   (what 0.32.0 shipped)
                //   enableV3Signing      ->  v2 FALSE, v3 true
                //   + enableV2Signing    ->  v2 FALSE, v3 true    <- the flag is ignored
                //
                // So the scheme set is DERIVED, not configured: state any part of it and AGP
                // recomputes the rest from minSdk, and at 34 it decides v2 is dead weight. It is
                // right - v3 covers API 28 and up, and this app cannot run below 34 - but the flag
                // that says otherwise is honoured nowhere, so it is not written here. A line that
                // looks like a setting and changes nothing is worse than its absence.
                //
                // v4 stays off, and that one IS a choice. It is not a stronger v3 - it is a detached
                // .idsig file beside the .apk, for incremental install over adb. Turning it on would
                // put a second file in the release that a phone browser has no idea what to do with,
                // and a single .apk is what an installer is handed.
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Signed only when the environment provided the release key. Without it the release
            // build stays unsigned rather than silently falling back to the debug key, which would
            // produce an .apk that looks fine and can never be installed over a real release
            signingConfig = releaseSigning?.let { signingConfigs.getByName("release") }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // Play splits a bundle into per-device APKs by default. This app turns that off, so what a phone
    // installs from Play is ONE universal APK - the same shape as the .apk on the GitHub Release.
    //
    // The trade was measured on 0.32.0 rather than assumed, because the default is the default for
    // good reasons and turning it off should cost something:
    //
    //   language  - one locale in the whole app (res/values, no res/values-*). Saves nothing.
    //   abi       - 16 .so files, 238 KB across all four ABIs, of a 35 MB .apk. Saves ~180 KB.
    //   density   - the bulk of this app is three .ttf files and three .dex; neither is density-split.
    //
    // So it buys under a percent, and it costs the one property worth having while two channels
    // exist: an install from Play and an install from a GitHub .apk are the same artifact, and any
    // difference in how they behave is a real difference rather than a packaging artefact. When the
    // in-app updater goes and Play is the only channel, this block is the first thing to revisit.
    bundle {
        language { enableSplit = false }
        density { enableSplit = false }
        abi { enableSplit = false }
    }

    // The build runs on JDK 21 and compiles to JVM 11 bytecode.
    // Kotlin's jvmTarget defaults to targetCompatibility under AGP's built-in Kotlin,
    // so it does not need to be set separately.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        // The screen reads its version from BuildConfig, never a hardcoded
        // string, so the displayed value cannot drift from what was actually built.
        buildConfig = true
    }

    // Android Lint is the only static analysis, and lint errors are
    // build failures. Both are AGP defaults; stated explicitly so a future edit that weakens
    // the gate is visible in review. No baseline file - a clean project has nothing to hide.
    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // Holds the token's ciphertext. Async, so the blob is never read on the main thread, and it is
    // where iteration 2's "last checked at" will live rather than in a second mechanism.
    implementation(libs.androidx.datastore.preferences)
    // The token being typed lives in a ViewModel and never in rememberSaveable: savedInstanceState
    // is written to disk by the system and turns up in bug reports. Same version as the
    // lifecycle-runtime-ktx already pinned here.
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // The token is validated against the API on entry, and iteration 3 downloads a release asset.
    // OkHttp rather than HttpURLConnection because GitHub's asset endpoint redirects to S3, and
    // OkHttp drops the Authorization header on a cross-host redirect - handing our token to a
    // third-party host is the exact leak that mattered.
    implementation(libs.zxing.core)
    // The camera, and only the camera. zxing above decodes the frames it produces, so nothing here is
    // a second reader - PlanarYUVLuminanceSource takes the Y plane directly. camera-compose supplies
    // CameraXViewfinder and owns the SurfaceRequest lifecycle; camera-view was measured at 16 more
    // new artifacts than this, with Guava and AppCompat inside the difference.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    implementation(libs.okhttp)
    debugImplementation(libs.androidx.ui.tooling)

    // Unit tests, JVM only.
    testImplementation(libs.junit)
    // Every status code and header combination the validator maps, served over a real socket.
    // Test-only: it is never on the .apk's classpath.
    testImplementation(libs.okhttp.mockwebserver)
    // Certificates minted in-process, so the four TLS states are proven against real
    // handshakes rather than asserted from a reading of the OkHttp source. Test-only.
    testImplementation(libs.okhttp.tls)
    // Only so the JVM tests can exercise the org.json parsing the app does on the platform's own
    // implementation - see the note on ReleaseParser. Never on the .apk's classpath.
    testImplementation(libs.json)

    // Compose UI test on a device/emulator.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    // See the comment in libs.versions.toml: these override the stale versions ui-test-junit4
    // pulls in transitively, which do not work on API 37.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    // Provides the empty activity the Compose test rule hosts content in.
    debugImplementation(libs.androidx.ui.test.manifest)
}

// Unit tests that read a resource from the source tree are not tracked by Gradle unless the
// directory is declared, and an untracked input means the task is UP-TO-DATE and DOES NOT RUN.
//
// That is not a theoretical staleness problem. Two tests in this repository read `src/main/res`
// directly, and both were confirmed to pass on inputs they should reject:
//
//   BackupRulesTest      - remove the backup exclusion, and it still reported success. It guards the
//                          rule that the update token never reaches Google's cloud backup, which is
//                          one of the two properties the updater was built on. Skippable since then.
//   WindowBackgroundTest - change the colour literal, and it still reported success. It guards the
//                          window background that made the app flash white on every cold start in
//
//
// Both report success by not executing, which is indistinguishable from success at the point anyone
// reads the board - the failure this project has now hit six times.
//
// Declared as the DIRECTORY rather than the two files, deliberately. Fixing two call sites would
// leave the identical defect waiting for whoever writes the third such test, and they would have no
// reason to suspect it. Declaring the directory makes every present and future test that reads a
// resource tracked by construction.
//
// The cost is that editing any resource re-runs the unit tests. That is the correct trade: a test
// suite that skips itself is worth less than one that occasionally runs when it did not need to.
tasks.withType<Test>().configureEach {
    // **The shared wire vectors, read from the repository ROOT and never from a copy in here.**
    //
    // `wire/enroll-payload-vectors.json` and `wire/enroll-payload-reject-vectors.json` are the
    // contract between the Go encoder in `bridge/` and the Kotlin decoder that will read the QR
    // code. The two cannot be compiled against each other and nothing else in the build connects
    // them, so the bytes in those files are the only thing that does. A copy inside this module is
    // exactly how they drift: the copy is what the Kotlin test pins, the original is what the Go
    // test pins, and nothing notices they disagree until a phone will not pair. `wire/README.md`
    // states the rule; `scripts/check-wire-vectors-consumed.sh` enforces it.
    //
    // Gradle does not put the repository root on a test's working directory, so it is passed
    // explicitly. `error(...)` at the reading end makes a missing property a failure rather than a
    // silently skipped test.
    systemProperty("wire.dir", rootProject.layout.projectDirectory.dir("wire").asFile.absolutePath)
    // And declared as an input, for the same reason as everything else in this block: a directory
    // Gradle does not know a test reads is a directory whose change leaves the task UP-TO-DATE, and
    // an UP-TO-DATE task reports success by not running. Editing a vector must re-run these tests -
    // that is the entire mechanism.
    inputs.dir(rootProject.layout.projectDirectory.dir("wire"))
        .withPropertyName("sharedWireVectors")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The three files `ReleasePleaseTest` reads, for exactly the reason above: it asserts facts that
    // live in build configuration rather than in the .apk, so without these the release-please
    // wiring could be broken by an edit and the test would report success by not running - which is
    // the same silence the bump itself fails with.
    inputs.file(layout.projectDirectory.file("build.gradle.kts"))
        .withPropertyName("appBuildFileReadDirectlyByTests")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.layout.projectDirectory.file("release-please-config.json"))
        .withPropertyName("releasePleaseConfigReadDirectlyByTests")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.layout.projectDirectory.file(".release-please-manifest.json"))
        .withPropertyName("releasePleaseManifestReadDirectlyByTests")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(layout.projectDirectory.dir("src/main/res"))
        .withPropertyName("appResourcesReadDirectlyByTests")
        // RELATIVE rather than ABSOLUTE: the contents decide the result, not where the checkout is,
        // so a build on another machine or in CI can still reuse the result.
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
