plugins {
    alias(libs.plugins.android.application) apply false
    // Also puts KGP 2.4.21 on the build classpath (AGP 9 built-in Kotlin would otherwise use 2.2.10).
    alias(libs.plugins.kotlin.compose) apply false
}
