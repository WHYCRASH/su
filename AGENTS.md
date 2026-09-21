# Repository Guidelines

## Project Overview

`su` (namespace `io.github.mangi.eta`, v5.3.1) is a system-level Android AI assistant, fork of Eta, BYOK (user supplies OpenAI-compatible/Anthropic key). Chat + agent loop drives Android APIs, accessibility GUI agent, WebView browser, terminals (Android + Alpine/Debian via PRoot/chroot), on-device data, Skills, Streamable-HTTP MCP, `MEMORY.md`, voice/assistant entry points. Android 14+ (`minSdk 34`); root + LSPosed unlock privileged access.

## Architecture & Data Flow

Single-module app; two-process agent system:

```
Entry (MainActivity SEND/VIEW | ASSIST voice | ModuleMain hooks)
 -> UI (AgentAppRoot / AgentAppState, collectAsState over repos/DataStore)
 -> AgentRuntimeClient/Connection (Messenger IPC, AgentRuntimeWire bundles + FD transfer)
 -> AgentRuntimeService -> AgentRuntimeRunExecutor (single exception boundary)
   -> AgentLoop (pure orchestration) <-> Providers (Chat/Responses/Anthropic SSE)
   -> serial tool batch (terminal/browser/device/memory/skill/MCP + validator)
   -> AgentRunController (cancel/pause/steer) + AgentRuntimeSession (RUNNING->COMMITTING->TERMINAL)
 -> checkpoints (Room in-flight) / archives / ResultStore -> UI drain
Hook path: ModuleMain -> SystemServer/SystemUI/Google hooks; read-only RemotePreferences, never executes loop.
```

Key rules:

- `agent/model/AgentLoop.kt` is pure (no Android/Room/Compose). Turn = provider response + serial tool batch. No fixed turn cap; ends on natural stop, cancel, unrecoverable error. Only explicit `tool_calls`/`tool_use` stop reasons execute; `finish_reason=length` with tool calls is rejected.
- Provider projection: Chat merges `system` into one leading message; Responses projects to `instructions` + replays opaque outputs in-memory only. Retries ≤3 (2/4/8s, per-turn budget) for timeouts/EOF/transient 5xx only; auth/quota/protocol never retry (`agent/model/AgentModelRetry.kt`, `AgentSseClient.kt`, `AgentHttpClient.kt` 15s/30s/5min).
- State: UI `collectAsState(initial=...)` over `StateFlow`/repo `Flow`s. Durable: Room `eta.db` + `SettingsDataStore` + `MEMORY.md` (AtomicFile, SHA-256 revision). Redact at persist time (`AgentTraceFormatter`, `AgentConversationCodec`).
- No DI framework. Manual `internal object` singletons (`ProviderRepository`, `McpServerRepository`, ...), `EtaDatabase.get(ctx)` double-checked lock, `SettingsDataStore.init(ctx)`, constructor-injected loop/executor.
- Capabilities: `AgentToolRequirements` (NONE/PARTIAL/REQUIRED root + a11y/ROM) x per-turn `AgentToolCapabilities.capture(ctx)`; executor re-reads `Prefs` toggles before every browser/terminal/device/memory call.

## Key Directories

Base: `app/src/main/kotlin/io/github/mangi/eta/`

- `agent/model/` — `AgentLoop`, `AgentModelClient`, prompt/codec, 3 provider impls (`OpenAiChatCompletionsProvider`, `OpenAiResponsesProvider`, Anthropic), `AgentModelFailure/Retry`, tool catalogs, SSE/HTTP clients.
- `agent/runtime/` — `AgentRuntimeService`, `AgentRuntimeRunExecutor`, `AgentRuntimeSession`, `AgentRunController`, `AgentRuntimeWire/Client/Connection`, checkpoint/archive/result stores, transcript/image FD transfer.
- `agent/tool/` — executable local tools, `AgentToolRequirements/Capabilities`, `ForegroundExclusiveGate`.
- `agent/terminal/` — `TerminalRuntime`, `ShellProcessSupervisor`, PRoot/chroot installers, PTY buffer, session/task supervisors.
- `agent/browser/` — `AgentBrowserSession`, navigation, DOM scripts, cookie offload (+`ported/`).
- `agent/device/` + `agent/accessibility/` — `RootAccess`, bounded root executors, location/display/gesture contracts; a11y service/keeper/health-provider.
- `agent/skill/` + `agent/mcp/` + `agent/memory/` — skill index/runtime/installer; `McpServerManager/RunContext/HttpClient`; `AgentMemoryContext`.
- `agent/voice/` (`tts/`, `offline/`), `agent/overlay/`, `agent/delegation/`, `agent/media/` — voice session/recognition, overlay, subagents, image hydration.
- `data/db/` + `data/repository/` + `data/datastore/` + `data/provider/` — Room entities/DAOs, object repos, `SettingsDataStore`, provider/model JSON, `BuiltinProviders`.
- `ui/` (`app/`, `screens/`, `pages/`, `components/`, `navigation/`, `markdown/`, `haptics/`) — `MainActivity`, `AgentAppRoot/Shell/State/ViewModel`, `AppRoute/AgentNavigator`.
- `hook/system/` + `hook/google/` + `core/` + `config/` + `systemizer/` — LSPosed hooks, `AndroidAgentLogger`, `LogSafety`, `Prefs`, `PowerAssistantTarget`.
- Non-Kotlin: `app/src/main/cpp/` + `jniLibs/`, `assets/agent/workspace.py`, `res/`, `app/src/test/{kotlin,python}/`.
- `module/` (repo root) — ReSukiSU NoMount module inputs: `customize.sh`, `service.sh`, `boot-completed.sh` (permissions/app-ops/root-profile provisioning), `uninstall.sh`, `action.sh`, `system/etc/permissions/privapp-permissions-io.github.mangi.eta.xml`. `module.prop` and the whitelist are generated into `app/build/ksu-module/staging/` by `:app:assembleKsuModule`; never hand-edit the shipped list.

## Development Commands

```bash
# Full gate (per docs/AGENT_RUNTIME.md)
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

# CI-identical unit tests
./gradlew --no-daemon --no-configuration-cache :app:testDebugUnitTest
./gradlew :app:testDebugUnitTest --tests "io.github.mangi.eta.agent.model.AgentPromptBuilderTest"
./gradlew :app:testDebugUnitTest --tests "io.github.mangi.eta.agent.model.*"

# Python workspace contract (CI runs first)
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s app/src/test/python -v

# Assemble / verify release APK (signed if ETA_RELEASE_* or keystore.properties present)
./gradlew --no-daemon --no-configuration-cache :app:assembleRelease
apksigner verify app/build/outputs/apk/release/app-release.apk

# ReSukiSU NoMount module (privileged su.apk + generated privapp whitelist + root-profile helper)
./gradlew --no-daemon --no-configuration-cache :app:assembleKsuModule
unzip -l app/build/outputs/ksu-module/su-5.3.1-arm64.zip

# Lint
./gradlew :app:lintDebug

# Native rebuild (opt-in, NDK r29, Linux/macOS only)
ANDROID_NDK_HOME=/path/to/android-ndk-r29 scripts/build-terminal-native.sh
python3 scripts/prepare-speech-runtime.py <out>  # pinned sherpa-ncnn v2.1.15; also auto-runs on preBuild

# SDK bootstrap (as CI)
yes | sdkmanager --licenses >/dev/null || true
sdkmanager --channel=3 "platforms;android-37.0"

# Release tag (CI builds, human publishes Release manually)
git tag v5.3.1 && git push origin v5.3.1
```

Release authority: `.github/RELEASING.md`. Never auto-publishes; version bump (`versionCode`/`versionName` in `app/build.gradle.kts`) only for official releases. Never commit `keystore.properties`, `*.jks`, `google-services.json`.

## Code Conventions & Common Patterns

- **Packages/imports:** `package io.github.mangi.eta.<area>.<topic>`; absolute imports, androidx/compose -> libxposed -> `io.github.mangi.eta.*` -> kotlinx -> okhttp/org.json. No wildcards. Ex: `agent/model/AgentModelClient.kt`, `EtaApp.kt`.
- **Visibility/naming:** `internal` by default for runtime/data/hook symbols (`internal class AgentLoop`, `internal object Prefs`); public only for manifest-referenced (`EtaApp`, `MainActivity`, services) and UI. One top-level class/object per file, `UpperCamelCase.kt` matches symbol. Constants `UPPER_SNAKE` in `Prefs.Keys`; pref keys `agent_*` slash feature (`agent_terminal_tools`).
- **StateFlow/Compose:** `private val _x = MutableStateFlow(...); val x: StateFlow = _x.asStateFlow()` + `.update{}` (`ui/app/ConsoleStore.kt`, `UserTerminalStore.kt`); UI `by Repo.flow().collectAsState(initial=...)` + `remember`/`LaunchedEffect`/`DisposableEffect`. Repos expose `Flow` + `suspend` getters + `init(context)`.
- **Async:** `CoroutineScope(SupervisorJob() + Dispatchers.IO)` app scope (`EtaApp.kt:40`); `withContext(Dispatchers.IO)` for root/probe/IO; `Mutex` + `withLock` for repo mutation; `@Volatile` + `synchronized` double-checked singletons; `Handler(Looper.getMainLooper())` for service-listener dispatch.
- **Error handling:** `runCatching{}.getOrNull()/getOrDefault()/onFailure{log}` at IO/IPC boundaries; typed `AgentModelFailure(code, retryable, message, diagnostic)` with `CONTEXT_WINDOW_EXCEEDED` special-case; `DeviceControlUnavailableException`, `AgentRunCancelledException` — never swallow loop control as generic `Exception`.
- **Logging:** `AndroidAgentLogger.warn/error/debug{}` + `warnThrottled(key){}`; never log bodies/keys — use `safeLogType()` (`core/LogSafety.kt`) and `AgentHttpFailureDiagnostics.safe(...)`.
- **Serialization/persistence:** `kotlinx.serialization Json{ignoreUnknownKeys=true}` for provider/runtime JSON; `org.json` at provider wire + `AgentRuntimeWire` bundles; Room `@Entity/@Dao/@Database(exportSchema=false)` with explicit `MIGRATION_x_y`; DataStore-preferences for settings.
- **Comments:** KDoc on runtime boundaries stating ownership/invariants (`AgentLoop.kt`, `AgentModelFailure.kt`, `Prefs.kt`); protocol-compat notes inline.

## Important Files

| Path | Role |
|---|---|
| `app/src/main/kotlin/io/github/mangi/eta/EtaApp.kt` | App init (Prefs, TerminalRuntime, RootAccess, Room/DataStore, XposedService); `AppProcessPolicy` guard |
| `app/src/main/kotlin/io/github/mangi/eta/ModuleMain.kt` | LSPosed entry |
| `app/src/main/kotlin/io/github/mangi/eta/ui/MainActivity.kt` | Compose entry, `AgentAppRoot` host |
| `app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentLoop.kt` | Pure orchestration loop |
| `app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentModelClient.kt` | Model facade, `loadConfig()` |
| `app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentToolCatalog.kt` | Tool schemas + validation (never executes) |
| `app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentPromptBuilder.kt` | Prompt assembly |
| `app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentRuntimeService.kt` | Lifecycle/IPC/overlay entry |
| `app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentRuntimeRunExecutor.kt` | Single exception boundary |
| `app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentRuntimeSession.kt` + `AgentRunController.kt` + `AgentRuntimeWire.kt` | Commit protocol, steer/cancel, wire format |
| `app/src/main/kotlin/io/github/mangi/eta/config/Prefs.kt` | Config hub (`UPPER_SNAKE_KEYS`, `isEnabled()`) |
| `app/src/main/kotlin/io/github/mangi/eta/data/db/EtaDatabase.kt` | Room v28 `eta.db` + migrations |
| `app/src/main/kotlin/io/github/mangi/eta/data/datastore/SettingsDataStore.kt` | Settings/provider-selection store |
| `app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentAppViewModel.kt` + `AgentAppState.kt` | Activity-scoped state |
| `app/src/main/AndroidManifest.xml` | Manifest (MainActivity singleTask, ASSIST, services) |
| `build.gradle.kts` / `app/build.gradle.kts` / `gradle/libs.versions.toml` | KGP force, app config (compileSdk 37/min 34/target 36, v5.3.1), version catalog |
| `settings.gradle.kts` / `gradle.properties` / `gradle/wrapper/gradle-wrapper.properties` | Repos, flags, Gradle 9.6.1 |
| `app/proguard-rules.pro` | R8 keeps (Xposed entry, Miuix) |
| `.github/workflows/build-debug.yml` / `android-release.yml` / `.github/RELEASING.md` | CI + release process |
| `scripts/build-terminal-native.sh` / `scripts/prepare-speech-runtime.py` / `scripts/native/` | Native PTY/PRoot build, speech JNI fetch |
| `docs/AGENT_RUNTIME.md` / `docs/TECHNICAL.md` / `docs/ROOTLESS_SUPPORT.md` / `docs/TERMINAL_NATIVE.md` | Runtime, tools, device support, native build |

## Runtime/Tooling Preferences

- **Runtime:** Kotlin 2.4.x (KGP forced 2.4.0 in root `buildscript`; AGP 9.3.2 bundles 2.2.10), JDK 25 toolchain (Temurin in CI, Foojay resolver), `kotlin.code.style=official`. No Node/Bun/pnpm/yarn; Python 3 is scripts/tests only.
- **Build:** Gradle 9.6.1 wrapper, single module `:app`. No `package.json`/`Dockerfile`/`Makefile`. No Detekt/ktlint/Spotless/`.editorconfig`; only Android Lint (`abortOnError=true`, `checkReleaseBuilds=false`).
- **Android:** `compileSdk 37`, `minSdk 34`, `targetSdk 36`, `ndkVersion 29.0.14206865`, native api 34. `versionCode 2026092001` + `versionName 5.3.1` manual.
- **Libs (pins in `gradle/libs.versions.toml`):** Miuix 0.9.4-rc01, Compose Material3, Room 2.8.4 + KSP, OkHttp/SSE 5.4.0, DataStore-prefs 1.2.1, kotlinx-serialization/coroutines 1.11.0, libxposed api/service 102.0.0 (`compileOnly`), hiddenapibypass 6.1.
- **Native/JNI:** hash-verified, never bundled models. `prepareSpeechRuntime` Exec runs `python3` on every `preBuild` (sherpa-ncnn v2.1.15, 4 ABIs). `useLegacyPackaging=true`.
- **Signing/env:** `ETA_RELEASE_STORE_FILE/_PASSWORD/_KEY_ALIAS/_KEY_PASSWORD` env or root `keystore.properties`; `ETA_DISABLE_RELEASE_SIGNING=true` forces debug. CI restores cert from `ETA_RELEASE_KEYSTORE_BASE64`. Repos locked `google()+mavenCentral()` (`FAIL_ON_PROJECT_REPOS`); CI uses `--no-daemon --no-configuration-cache`.

## Testing & QA

- **Frameworks:** JUnit 4.13.2 only (`org.junit.Test`, `Assert.*`, `@Before/@After`, `@Rule TemporaryFolder/Timeout`). No JUnit5/MockK/Mockito/Truth/`coroutines-test`. Robolectric 4.16.1 for Context/Room: `@RunWith(RobolectricTestRunner)` + `@Config(sdk=[34|36])` + `RuntimeEnvironment.getApplication()`; `@GraphicsMode(NATIVE)` on Compose tests. Compose `ui-test-junit4` via `createComposeRule()`. Room testing via `Room.databaseBuilder(...).allowMainThreadQueries().addMigrations(...)` (see `data/db/EtaDatabaseMigrationTest.kt`). Python stdlib `unittest` only for `assets/agent/workspace.py` via `importlib`.
- **Layout:** `app/src/test/kotlin/io/github/mangi/eta/**` mirrors `main` package-by-package (~300 `*Test.kt`); `app/src/test/python/test_workspace.py` sole Python test. No `app/src/androidTest/`, no `test/resources/`, no coverage plugin (no JaCoCo/Kover, no threshold).
- **Fakes:** no mock framework. `TemporaryFolder` / `tempfile.TemporaryDirectory` + real `git init` fixture; fake HTTP via `OkHttpClient.Builder().addInterceptor{}`; async via `runBlocking` / `CountDownLatch` + timeouts; per-test reset via `SettingsDataStore.init(ctx)`, `ProviderRepository.init(ctx)`, `deleteDatabase("eta.db")`.
- **Gates:** local `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`; CI `build-debug.yml` runs pytests -> `:app:testDebugUnitTest` (12-min timeout) -> uploads `app/build/test-results/testDebugUnitTest/` + `app/build/reports/tests/testDebugUnitTest/` -> `:app:assembleRelease`. Tag workflow builds/signs only, zero tests.
