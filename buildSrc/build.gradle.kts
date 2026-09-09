plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// Run these tests as part of building buildSrc itself, which means as part of *every* build of this
// project — `./gradlew assembleDebug` included.
//
// Why this rather than a second documented command: Gradle does not run buildSrc's tests on its own,
// so without this line `VersionCodeTest` would never execute in CI, and a test that never runs is
// worse than no test — it reads as coverage while providing none. Adding a separate
// `./gradlew -p buildSrc test` step to CI would also break the one thing we guarantee:
// that CI and the documented local command are literally the same invocation.
//
// The cost is that broken version arithmetic fails every build rather than only a test task. That is
// the intended behaviour: a wrong versionCode makes the .apk un-installable over the previous one,
// so it should stop the build that would have produced it.
// `finalizedBy` rather than `dependsOn`: the kotlin-dsl plugin already makes the test *compilation*
// depend on `jar`, so `jar dependsOn test` closes a cycle. A finalizer runs after `jar` and still
// fails the build if it fails, which is exactly the semantics wanted here.
tasks.named("jar") {
    finalizedBy(tasks.named("test"))
}
