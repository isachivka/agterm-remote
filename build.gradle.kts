// Top-level build file. AGP 9 has built-in Kotlin support, so no Kotlin plugin is declared
// here — see docs/engineering/toolchain.md.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
