buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9 bundles KGP 2.2.10, but miuix 0.9.3 and Compose Multiplatform 1.11.1 require
        // Kotlin 2.4.0, so a buildscript classpath entry forces the newer KGP.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
