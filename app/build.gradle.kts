import com.android.build.api.artifact.SingleArtifact
import groovy.json.JsonSlurper
import java.util.Properties
import java.util.zip.ZipFile

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
        versionCode = 2026092101
        versionName = "5.3.2"
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

// ---------------------------------------------------------------------------
// ReSukiSU NoMount module
// ---------------------------------------------------------------------------
// assembleKsuModule turns the release APK into one flashable module per 64-bit ABI.
// The payload is the APK staged as a privileged system app, the privapp whitelist generated
// from the merged release manifest, the native libraries unpacked beside it (a bundled system
// app never gets them extracted from the APK), and the KernelSU profile helper that grants the
// global-namespace root profile after boot.
// Nothing in the module mounts, patches or replaces an installed app, and no
// skip_mount marker is written: NoMount's metamount.sh reads the staging tree in
// place, and the marker would make a mounting metamodule skip the module entirely.
val ksuModuleAbis = mapOf("arm64" to "arm64-v8a", "x64" to "x86_64")
val ksuModuleScripts = listOf("customize.sh", "service.sh", "boot-completed.sh", "uninstall.sh", "action.sh")
val ksuModuleHelperName = "libeta_ksu_profile.so"
val ksuApplicationId = android.defaultConfig.applicationId!!
val ksuModuleVersionName = android.defaultConfig.versionName!!
val ksuModuleVersionCode = android.defaultConfig.versionCode!!
val ksuModuleAuthor = "mangi"
val ksuModuleDescription =
    "Installs su as a privileged system app and grants global-namespace root through ReSukiSU NoMount."
val ksuStagingRoot = layout.buildDirectory.dir("ksu-module/staging")
val ksuModuleOutputDir = layout.buildDirectory.dir("outputs/ksu-module")
val ksuReleaseApkDir = layout.buildDirectory.dir("outputs/apk/release")

abstract class StageKsuModule : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val ksuModuleTree: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val jniLibsRoot: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mergedManifest: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val releaseApkDir: DirectoryProperty

    @get:Input
    abstract val applicationId: Property<String>

    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val versionCode: Property<Int>

    @get:Input
    abstract val moduleAuthor: Property<String>

    @get:Input
    abstract val moduleDescription: Property<String>

    @get:Input
    abstract val abis: MapProperty<String, String>

    @get:Input
    abstract val helperName: Property<String>

    @get:OutputDirectory
    abstract val stagingRoot: DirectoryProperty

    @TaskAction
    fun stage() {
        val applicationId = applicationId.get()
        val apk = resolveReleaseApk(applicationId)
        val permissions = readPrivappPermissions(mergedManifest.get().asFile)
        check(permissions.isNotEmpty()) {
            "The merged release manifest declares no android.permission.* request: " +
                mergedManifest.get().asFile
        }

        val staging = stagingRoot.get().asFile
        staging.deleteRecursively()
        staging.mkdirs()

        abis.get().forEach { (moduleAbi, androidAbi) ->
            val abiStaging = staging.resolve(moduleAbi)
            ksuModuleTree.get().asFile.copyRecursively(abiStaging, overwrite = true)

            abiStaging.resolve("module.prop").writeText(
                """
                |id=su
                |name=su
                |version=${versionName.get()}
                |versionCode=${versionCode.get()}
                |author=${moduleAuthor.get()}
                |description=${moduleDescription.get()}
                |""".trimMargin(),
            )

            // Regenerated on every build from the merged manifest: a missing privileged
            // declaration is a bootloop under ro.control_privapp_permissions=enforce.
            val whitelist = abiStaging.resolve("system/etc/permissions/privapp-permissions-$applicationId.xml")
            whitelist.parentFile.mkdirs()
            whitelist.writeText(
                buildString {
                    append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                    append("<permissions>\n")
                    append("    <privapp-permissions package=\"$applicationId\">\n")
                    permissions.forEach { append("        <permission name=\"$it\"/>\n") }
                    append("    </privapp-permissions>\n")
                    append("</permissions>\n")
                },
            )

            val helper = jniLibsRoot.get().asFile.resolve("$androidAbi/${helperName.get()}")
            check(helper.isFile) {
                "Missing KernelSU profile helper for $moduleAbi: $helper. " +
                    "Run scripts/build-terminal-native.sh with the pinned NDK first."
            }
            val helperTarget = abiStaging.resolve("bin/$moduleAbi/eta_ksu_profile")
            helperTarget.parentFile.mkdirs()
            helper.copyTo(helperTarget, overwrite = true)

            val stagedApk = abiStaging.resolve("system/priv-app/su/su.apk")
            stagedApk.parentFile.mkdirs()
            apk.copyTo(stagedApk, overwrite = true)

            stageNativeLibraries(apk, androidAbi, abiStaging)

            logger.lifecycle(
                "Staged su module ($moduleAbi): ${permissions.size} privileged permission(s), " +
                    "apk ${versionName.get()} (${versionCode.get()})",
            )
        }
    }

    /**
     * Unpacks every `<androidAbi>` shared object of the APK into the native library directories
     * a bundled system app can resolve. `PackageAbiHelperImpl` points a monolithic code path at
     * `<partition>/lib64/<apkname>` and a directory code path at `<codePath>/lib/<isa>`, and
     * neither location is populated on its own: this app is a system app that is not an updated
     * system app, so the installer never extracts the libraries embedded in its APK.
     */
    private fun stageNativeLibraries(apk: File, androidAbi: String, abiStaging: File) {
        val prefix = "lib/$androidAbi/"
        val entryNames = ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith(prefix) && it.name.endsWith(".so") }
                .map { it.name }
                .toSortedSet()
        }
        check(entryNames.isNotEmpty()) { "$apk carries no $androidAbi native library" }

        val isa = instructionSet(androidAbi)
        val libraryDirs = listOf(
            abiStaging.resolve("system/lib64/su"),
            abiStaging.resolve("system/priv-app/su/lib/$isa"),
        )
        ZipFile(apk).use { zip ->
            entryNames.forEach { entryName ->
                val name = entryName.substringAfterLast('/')
                libraryDirs.forEach { dir ->
                    dir.mkdirs()
                    zip.getInputStream(zip.getEntry(entryName)).use { input ->
                        dir.resolve(name).outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
        logger.lifecycle(
            "Staged ${entryNames.size} $androidAbi native libraries into system/lib64/su " +
                "and system/priv-app/su/lib/$isa",
        )
    }

    /** Directory name `VMRuntime.getInstructionSet()` uses for a 64-bit application ABI. */
    private fun instructionSet(androidAbi: String): String = when (androidAbi) {
        "arm64-v8a" -> "arm64"
        "x86_64" -> "x86_64"
        else -> error("Unsupported module native ABI: $androidAbi")
    }

    /** Reads the release APK path from AGP's output metadata, requiring a single matching element. */
    private fun resolveReleaseApk(applicationId: String): File {
        val apkDir = releaseApkDir.get().asFile
        val metadata = apkDir.resolve("output-metadata.json")
        check(metadata.isFile) { "Release output metadata not found: $metadata" }

        @Suppress("UNCHECKED_CAST")
        val parsed = JsonSlurper().parse(metadata) as Map<String, Any?>
        check(parsed["applicationId"] == applicationId) {
            "Release output metadata belongs to ${parsed["applicationId"]}, expected $applicationId"
        }
        val elements = parsed["elements"] as? List<Map<String, Any?>> ?: emptyList()
        check(elements.size == 1) {
            "Expected exactly one release APK element for $applicationId, found ${elements.size}"
        }
        val apk = apkDir.resolve(elements.single()["outputFile"] as String)
        check(apk.isFile) { "Release APK not found: $apk" }
        return apk
    }

    /** Every android.permission.* request of the merged manifest, sorted and deduplicated. */
    private fun readPrivappPermissions(manifest: File): List<String> {
        check(manifest.isFile) { "Merged release manifest not found: $manifest" }
        val requests = Regex("<uses-permission\\b[^>]*?android:name=\"([^\"]+)\"")
            .findAll(manifest.readText())
            .map { it.groupValues[1] }
            .filter { it.startsWith("android.permission.") }
            .toSortedSet()
        return requests.toList()
    }
}

val stageKsuModule by tasks.registering(StageKsuModule::class) {
    group = "build"
    description = "Stages the ReSukiSU NoMount module tree from the release APK."
    dependsOn(tasks.named("assembleRelease"))
    ksuModuleTree.set(rootProject.layout.projectDirectory.dir("module"))
    jniLibsRoot.set(layout.projectDirectory.dir("src/main/jniLibs"))
    releaseApkDir.set(ksuReleaseApkDir)
    applicationId.set(ksuApplicationId)
    versionName.set(ksuModuleVersionName)
    versionCode.set(ksuModuleVersionCode)
    moduleAuthor.set(ksuModuleAuthor)
    moduleDescription.set(ksuModuleDescription)
    abis.set(ksuModuleAbis)
    helperName.set(ksuModuleHelperName)
    stagingRoot.set(ksuStagingRoot)
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        tasks.named<StageKsuModule>("stageKsuModule") {
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
    }
}

val ksuModuleArchives = ksuModuleAbis.map { (moduleAbi, _) ->
    val taskName = "assembleKsuModule" + moduleAbi.replaceFirstChar { it.uppercaseChar() }
    tasks.register(taskName, Zip::class) {
        group = "build"
        description = "Packages the su ReSukiSU module archive for $moduleAbi."
        dependsOn(stageKsuModule)
        from(ksuStagingRoot.map { it.dir(moduleAbi) }) {
            // Scripts and the profile helper have to arrive executable: the installer
            // applies 0644 to every extracted file and only customize.sh can fix that.
            exclude(ksuModuleScripts)
            exclude("bin/$moduleAbi/eta_ksu_profile")
            filePermissions { unix("0644") }
            dirPermissions { unix("0755") }
        }
        from(ksuStagingRoot.map { it.dir(moduleAbi) }) {
            include(ksuModuleScripts)
            include("bin/$moduleAbi/eta_ksu_profile")
            filePermissions { unix("0755") }
            dirPermissions { unix("0755") }
        }
        archiveFileName.set("su-$ksuModuleVersionName-$moduleAbi.zip")
        destinationDirectory.set(ksuModuleOutputDir)
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

tasks.register("assembleKsuModule") {
    group = "build"
    description = "Builds every su ReSukiSU module archive."
    dependsOn(ksuModuleArchives)
}
