import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use(::load)
}

fun signingValue(envName: String, propertyName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("ETA_RELEASE_STORE_FILE", "storeFile")
val releaseStorePassword = signingValue("ETA_RELEASE_STORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("ETA_RELEASE_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("ETA_RELEASE_KEY_PASSWORD", "keyPassword")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

android {
    namespace = "io.github.mangi.eta"
    compileSdk = 37
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "io.github.mangi.eta"
        minSdk = 34
        targetSdk = 36
        // versionCode scheme: yyyyMMdd + a two-digit same-day sequence (starting at 01); bump it by hand alongside versionName when releasing.
        versionCode = 2026092001
        versionName = "5.3.1"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isPseudoLocalesEnabled = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            signingConfig = when {
                System.getenv("ETA_DISABLE_RELEASE_SIGNING") == "true" -> signingConfigs.getByName("debug")
                hasReleaseSigning -> signingConfigs.getByName("release")
                else -> signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        buildConfig = false
        compose = true
    }

    androidResources {
        localeFilters += listOf("en")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libproot_exec.so", "**/libproot_loader.so", "**/libeta_pty.so")
        }
        resources {
            // Merge the Xposed module declaration so the module entry survives release shrinking.
            merges += "META-INF/xposed/*"
            // Exclude only the signature/version metadata that causes packaging conflicts; do not strip Compose resources.
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.commons.compress)
    implementation(libs.xz)
    compileOnly(libs.libxposed.api)
    // UI-side RemotePreferences write bridge: commits configuration to the LSPosed database
    // through XposedService; the hook side reads the per-process cache with
    // XposedInterface.getRemotePreferences.
    implementation(libs.libxposed.service)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.preference)
    implementation(libs.material.icons.extended)
    implementation(libs.androidx.navigationevent)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)
    // markdown-renderer-m3 declares material3 as compileOnly, so pull it in explicitly for runtime.
    implementation(libs.material3)
    implementation(libs.hidden.api.bypass)

    // DataStore: provider/model structured JSON plus keys such as the selected IDs.
    implementation(libs.datastore.preferences)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // OkHttp: replaces HttpURLConnection and supports SSE.
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    // kotlinx.serialization: provider settings and runtime configuration JSON.
    implementation(libs.kotlinx.serialization.json)

    // Coroutines: pulled in explicitly so the transitive version cannot drift.
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.room.testing)
    testImplementation(libs.robolectric)
    // Match the Compose UI supplied by miuix 0.9.4-rc01; selection needs real gesture coverage.
    testImplementation("androidx.compose.ui:ui-test-junit4:1.12.0-rc01")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.12.0-rc01")
}

// Pin and verify the small JNI runtime; speech model is opt-in at runtime, never bundled.
val speechJniDir = layout.buildDirectory.dir("generated/speech/jniLibs")
val prepareSpeechRuntime by tasks.registering(Exec::class) {
    inputs.file(rootProject.file("scripts/prepare-speech-runtime.py"))
    outputs.dir(speechJniDir)
    commandLine("python3", rootProject.file("scripts/prepare-speech-runtime.py").absolutePath,
        speechJniDir.get().asFile.absolutePath)
}
android.sourceSets.getByName("main").jniLibs.srcDir(speechJniDir.get().asFile)
tasks.named("preBuild").configure { dependsOn(prepareSpeechRuntime) }
