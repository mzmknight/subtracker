// Declared without applying so each module picks what it needs, while one
// version of each is pinned across the whole build.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    // SQLDelight is deliberately NOT declared here. Putting it on the root
    // buildscript classpath makes Gradle's embedded-Kotlin pin of
    // org.jetbrains:annotations (strictly 13.0) collide with AGP's 23.0.0 and
    // the whole build fails to configure. Only :shared needs it, so it is
    // applied there directly.
}
