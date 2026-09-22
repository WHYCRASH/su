# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# The R8 default rules already cover the Compose runtime; Miuix icons are plain
# Kotlin code, so R8 may strip unused icons.
# -dontwarn only suppresses the optional-platform warnings that KMP dependencies
# may emit on the Android side; it does not block shrinking.
-dontwarn top.yukonga.miuix.**

# Configuration keys are string constants accessed through static calls;
# no need to keep class or member names.

# ── Release logging policy ────────────────────────────────────────────────────────
# Only strip Android VERBOSE/DEBUG calls in su's own code; INFO/WARN/ERROR must be kept,
# and third-party dependencies keep their own logging policy.
-maximumremovedandroidloglevel 3 class io.github.mangi.eta.** { *; }

# The debug supplier is a pure observation API; never perform business side effects inside a supplier.
-assumenosideeffects interface io.github.mangi.eta.core.AgentLogger {
    public abstract void debug(kotlin.jvm.functions.Function0);
}
-assumenosideeffects class io.github.mangi.eta.core.AndroidAgentLogger {
    public void debug(kotlin.jvm.functions.Function0);
}

# ── Serialization and networking dependencies ──────────────────────────────────────
# DataStore, kotlinx.serialization, OkHttp, and Okio each ship precise consumer rules;
# do not keep whole classes or packages at the app layer, which would block shrinking,
# inlining, and obfuscation.
# Keep the source and line-number attributes so release mappings can restore production stacks.
-keepattributes SourceFile,LineNumberTable

# JNI looks up configuration fields/classes by their upstream names.
-keep class com.k2fsa.sherpa.ncnn.** { *; }
