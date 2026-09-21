# Kimi / Ark / Builtin-provider inventory (read-only)

All paths repo-rooted at `/home/dautist/Projects/su`. Package `io.github.mangi.eta`.

---

## (a) Kimi Web inventory + callers + UI entry points

### A1. Feature files and symbols

**`app/src/main/kotlin/io/github/mangi/eta/ui/app/KimiWebLauncher.kt:19-104`** — Linux-daemon launcher. Not a model provider; runs `kimi web` inside the app-managed Linux env and opens the resulting loopback URL in the system browser.
- `KimiWebRuntimeStatus` (`:19-24`): `taskId, running, url, code`.
- `KimiWebLaunchResult` (`:26-29`): `Opened(url)` / `Failed(code)`.
- `KimiWebLauncher(context, daemonSupervisor)` (`:32-35`): owns a `KimiWebSession` (`:36-55`) wired to `DetachedTaskSupervisor.list/start/readLogs/stop` (`:38-43`) and to an `ACTION_VIEW` browser intent (`:45-54`).
- `launch(environment)` (`:57-74`): guards `linuxDistribution==null` -> `INVALID_ENVIRONMENT` (`:59`), `rootfsReady` -> `LINUX_ENVIRONMENT_NOT_READY` (`:61`), `AgentExecutionService.acquire(LAUNCH_ID="kimi-launch")` (`:64`, `:102-103`) for `user` identity, then `session.launch(...)` (`:69`).
- `status(environment)` (`:76-92`): re-derives distribution/rootfs/identity (`:77-81`), `ROOT_REQUIRED` for root-without-root (`:81`), matches daemon tasks by `environment+identity+backend+command in {KimiWebSession.COMMAND, "kimi web"}` (`:82-86`), `KIMI_EXITED` if not running (`:89`), else `addressFromLogs` (`:91`).
- `stop(environment)` (`:94-99`): `status()` then `daemonSupervisor.stop(taskId)`.
- `launchMutex`, `LAUNCH_ID` (`:101-104`). Chinese comment at `:31`.

**`app/src/main/kotlin/io/github/mangi/eta/ui/app/KimiWebSession.kt:11-60`** — reuse/transaction boundary.
- `KimiWebSession(tasks, openUrl, waitAttempts=30, waitIntervalMs=500)` (`:11-16`) + `Tasks` interface (`:17-22`).
- `launch(environment, identity, backend)` (`:24-52`): reuse running `{COMMAND, "kimi web"}` task (`:28-31`), else `tasks.start` (`:32-35`); poll up to 30x500ms for `addressFromLogs` (`:36-47`); `Opened` iff `openUrl` true else `BROWSER_UNAVAILABLE` (`:43-44`); `KIMI_EXITED` (`:38`), `LOGS_UNAVAILABLE` (`:40`), `URL_TIMEOUT` (`:48`); cleanup only the task this call created (`:49-51`, `createdTaskId`).
- `COMMAND="kimi web --no-open"` (`:55`), `addressFromLogs` + `WEB_URL_REGEX=http://127.0.0.1:\d+/#token=[A-Za-z0-9_-]+` (`:56-58`).

**`app/src/main/kotlin/io/github/mangi/eta/ui/app/KimiWebUiState.kt:6-45`** — menu state.
- `KimiWebPhase {CHECKING, NOT_INSTALLED, READY, STARTING, RUNNING, FAILED}` (`:6`).
- `KimiWebUiState(phase=CHECKING, errorCode)` (`:8-11`), `canStop = STARTING||RUNNING` (`:12`).
- `actionLabel(context)` (`:14-21`) maps phases to `R.string.capability_kimi_checking/install/preparing/open/retry` + `action_launch_kimi_web`.
- `observedKimiWebState(installed, running)` (`:29-33`): `!installed->NOT_INSTALLED`, `running->RUNNING`, else `READY` (never synthesizes FAILED from idle).
- `KimiWebLaunchResult.Failed.message(context)` (`:35-45`) maps `ROOT_REQUIRED / BACKGROUND_START_NOT_ALLOWED / KIMI_EXITED|PROCESS_EXITED / LINUX_ENVIRONMENT_NOT_READY|PROFILE_NOT_INSTALLED / PROOT_UNAVAILABLE / LOGS_UNAVAILABLE / URL_TIMEOUT / BROWSER_UNAVAILABLE` to string resources, else `linux_kimi_web_failed_start`.

### A2. Install profile (Kimi Code npm package)

- `app/src/main/kotlin/io/github/mangi/eta/agent/terminal/LinuxPackageProfiles.kt:101-122`: Kimi comment (`:102-105`, notes npm distribution, Node dependency, `--prefix /usr/local`, China mirror first); `KIMI_INSTALL_SCRIPT` (`:106-109`) = `npm install -g --prefix /usr/local --registry=https://registry.npmmirror.com @moonshot-ai/kimi-code@latest || npm install -g --prefix /usr/local @moonshot-ai/kimi-code@latest`; `KIMI` profile (`:111-120`): `id="kimi"`, `markerName=KIMI_TOOLS_MARKER`, `revision=KIMI_TOOLS_REVISION`, `dependsOn=NODE`, both distros use `KIMI_INSTALL_SCRIPT` (`:117-118`); `ALL=[PYTHON,NODE,SSH,KIMI]` (`:121`).
- `LinuxPackageProfiles.kt:209-216`: installer appends `kimi --version` + `kimi web --help` verification only when `profile==KIMI` (`:212`).
- `app/src/main/kotlin/io/github/mangi/eta/agent/terminal/AlpineEnvironmentPaths.kt:14,21`: `KIMI_TOOLS_MARKER=".eta-kimi-tools-ready"`, `KIMI_TOOLS_REVISION=1`.
- `LinuxPackageProfiles.kt:124-128`: `linuxPackageProfileReady(rootfs, profile)` marker check (used for Kimi gating).

### A3. Every inbound caller (Kotlin)

- `ui/app/AgentAppViewModel.kt:31-43`: constructs `KimiWebLauncher` with `DetachedTaskSupervisor`.
- `ui/app/AgentAppViewModel.kt:45-47`: holds `KimiWebUiState` + `kimiWebJob`.
- `ui/app/AgentAppViewModel.kt:49-61`: `refreshKimiWeb()` — `linuxPackageProfileReady(..., KIMI)` + `kimiWebLauncher.status` + `observedKimiWebState`; skips status when not installed (`:56`).
- `ui/app/AgentAppViewModel.kt:63-80`: `launchKimiWeb(onFinished)` — guard re-entry (`:64`), set STARTING (`:65`), call launcher (`:69`), map `Opened->RUNNING`, `Failed->READY` (`:73-77`).
- `ui/app/AgentAppViewModel.kt:82-92`: `stopKimiWeb()` — cancel prep, `status` + `stop` (`:88-89`).
- `ui/app/AgentAppRoot.kt:178`: `refreshKimiWeb()` on `ON_RESUME`.
- `ui/app/AgentAppRoot.kt:373-392`: `onLaunchKimiWeb` wiring; `NOT_INSTALLED` routes to `AppRoute.LinuxEnvironment` (`:385-387`), else launches with Toast on `Failed` (`:376-383`); passes `kimiWebLabel/canStop/onStop/onRefresh` (`:389-392`).
- `ui/app/AgentAppRoot.kt:846`: `refreshKimiWeb()` after backup import.
- `ui/app/AgentAppShell.kt:70-74,132-136,213-217,263-267`: five Kimi params pass-through (two shell overloads).
- `ui/app/TopBarOverflowMenu.kt:53-57,82-83,167-175,232-246`: menu entries (see A4).
- `ui/screens/terminal/LinuxEnvironmentScreen.kt:75-79,120-125`: `InstallTarget.KIMI` + `LinuxPackageProfiles.KIMI` card.
- `ui/screens/terminal/LinuxEnvironmentScreen.kt:179-195`: second `KimiWebLauncher` instance scoped to Linux screen.
- `ui/screens/terminal/LinuxEnvironmentScreen.kt:202-205`: `LaunchedEffect` running poll.
- `ui/screens/terminal/LinuxEnvironmentScreen.kt:290-302`: `launchKimiWeb()` action (comment `:289`).
- `ui/screens/terminal/LinuxEnvironmentScreen.kt:414-483,450-454`: per-card Kimi launch/open/stop buttons.
- `ui/app/AgentAppState.kt:4562`: comment only (stream/data layering analogy), no code dep.

No other Kotlin files reference `KimiWeb*`/`observedKimiWebState` (exhaustive grep over `app/src/main/kotlin`).

### A4. Reachable UI entry points

1. **Home overflow menu** (`TopBarOverflowMenu.kt:164-176`): always-visible item with `text=kimiWebLabel` (`:167`), `ic_kimi_code` icon (`:169-173`), `onClick->onLaunchKimiWeb()` (`:175`). Menu anchor refresh calls `onRefreshKimiWeb()` (`:80-84`). Conditional **Stop** item only when `canStopKimiWeb` (`:232-246`) with `capability_kimi_stop` (`:237`) -> `onStopKimiWeb()` (`:245`).
2. **Linux Environment -> Optional tools -> Kimi Code card** (`LinuxEnvironmentScreen.kt:406-506`): card from `packageProfileUis` entry (`:119-125`); when `isKimi && ready` (`:460,473,480`) button shows `linux_kimi_web_launch` / `linux_kimi_web_starting` / `action_open` (`:459-468`) and calls `launchKimiWeb()` (`:480-482`); when `isKimi && kimiWebRunning` (`:442`) red Stop `action_stop` (`:443-456`) calls `kimiWebLauncher.stop(...)` (`:452`). Status shows `linux_kimi_web_starting` while launching (`:327`).
3. **No settings/provider-catalog entry**: Kimi Web has no `AppRoute.ModelProvider*` destination; the model provider `builtin-kimi`/Moonshot is a separate stack (sec c).

### A5. Kimi model-provider symbols (distinct from Kimi Web)

- `data/provider/BuiltinProviders.kt:14`: `KIMI_ID="builtin-kimi"` (no instance; `PROVIDERS=emptyList()`, `:21`).
- `data/model/Provider.kt:24`: `ProviderSourceTypes.MOONSHOT="moonshot"`.
- `data/provider/ProviderSourceRegistry.kt:16,63,79-80`: `MOONSHOT` known type; `KIMI_ID->MOONSHOT`; hosts `api.moonshot.cn` + `api.moonshot.ai -> MOONSHOT`.
- `data/provider/OfficialModelCatalog.kt:102-120 (BAILIAN kimi entries), 142-198 (MOONSHOT: kimi-k3/k2.7-code/highspeed/k2.6/k2.5)`.
- `agent/model/ProviderReasoning.kt:33,77-78,129-137,221-246,354-355`: `MOONSHOT->applyMoonshot`; `kimi-k2-6` thinking toggle; `kimi-k3/k2-6/k2-5` effort mapping; `supportsReasoning: MOONSHOT->hasPrefix kimi-`.
- `data/provider/ReasoningCapabilityResolver.kt:104,117`: `hasPrefix kimi-k3` mandatory caps; `hasPrefix kimi- -> true`.
- `ui/components/ProviderBranding.kt:20,53`: `MOONSHOT->provider_logo_kimi`; model pattern `kimi|moonshot->provider_logo_kimi`.
- `data/model/VideoGenerationModels.kt:6` comment notes `kimi` video-understanding chat models stay in chat (not an endpoint dep).

---

## (b) Ark inventory + callers

### B1. File

**`app/src/main/kotlin/io/github/mangi/eta/agent/model/ArkContentsGenerations.kt:8-57`** — comment `:8`: `火山方舟 Seedance：内容生成任务，不是 OpenAI /videos`.
- `matches(baseUrl)` (`:10-17`): host must be `*.volces.com` (`:12`), `volces.com` (`:13`), `*.volcengine.com` (`:14`), `volcengine.com` (`:15`), or start with `ark.` (`:16`). Pure host predicate.
- `apiV3Root(baseUrl)` (`:19-23`): null unless `matches`; else `{scheme}://{host}/api/v3`.
- `tasksUrl(baseUrl)` (`:25-26`): `{root}/contents/generations/tasks` or null.
- `taskUrl(baseUrl, taskId)` (`:28-32`): trimmed non-empty id -> `{tasksUrl}/{id}`, else null.
- `createBody(model, prompt, images)` (`:34-56`): Ark contents schema `{model, content:[{type:text,text:prompt}, {type:image_url,image_url:{url:dataUrl}}...]}`; non-empty images only (`:43`); default mime `image/png` (`:44`).

### B2. Is it Doubao/Volcengine-only?

Yes. `tasksUrl/taskUrl/apiV3Root` return non-null only when `matches()` passes, and `matches()` accepts only Volcengine hosts (`volces.com`, `volcengine.com` subdomains) plus `ark.*`. No generic/OpenAI host matches. The create URL is `{host}/api/v3/contents/generations/tasks`, not any `/v1/videos` route. Callers gate on it, and the error at `AgentVideoGenerationClient.kt:182` (`当前地址不是火山方舟内容生成接口`) confirms the branch is Ark-exclusive. `Seedance` (`:8`) and `doubao-seedance-*` test model (`ArkContentsGenerationsTest.kt:31`) tie it to the ByteDance/Volc Seedance family; the predicate itself is host-based, not model-name-based. Related: `agent/voice/tts/SpeechEngine.kt:38` excludes `volces.com/volcengine.com` from the Doubao-TTS branch — same host family.

### B3. Every inbound caller

Exhaustive `ArkContentsGenerations` grep hits exactly two source files + one test:
- `agent/model/AgentVideoGenerationClient.kt:49`: `if (matches(config.baseUrl)) add(Attempt.ArkContents)` — Ark attempt first but only for matching hosts; generic attempts always follow (`:50-54`).
- `AgentVideoGenerationClient.kt:128`: poll — `taskUrl(...)?.let { add(get(it, headers)) }` alongside generic OpenAI video-status URLs (`:129-130`); non-Volc bases skip silently.
- `AgentVideoGenerationClient.kt:181-185`: execute — `Attempt.ArkContents -> url=tasksUrl(...) ?: error(...)`, POST `createBody(...)` (`:184`). Enum value at `:79`.
- `app/src/test/.../agent/model/ArkContentsGenerationsTest.kt:13-23,30-33`: `ark.cn-beijing.volces.com/api/coding/v3 -> matches true`, maps to `.../api/v3/contents/generations/tasks[/cgt-1]`; `example.com/v1 -> false`; `createBody(doubao-seedance-2-0-260128, ...)` schema test.

No other production caller. Safe removal = delete `ArkContentsGenerations.kt` + `Attempt.ArkContents` (`:79`) + three call sites above; generic video attempts untouched.

---

## (c) Builtin provider table + vendor classification

### C1. BuiltinProviders state

`app/src/main/kotlin/io/github/mangi/eta/data/provider/BuiltinProviders.kt:5-25`: `DEFAULT_SYSTEM_PROMPT` (`:6-8`, Chinese, mentions 代鱼); ten ID constants (`:10-19`): `builtin-openai`, `builtin-anthropic`, `builtin-dashscope`, `builtin-deepseek`, `builtin-kimi`, `builtin-mimo`, `builtin-minimax`, `builtin-stepfun`, `builtin-siliconflow`, `builtin-openrouter`; **`PROVIDERS = emptyList()` (`:21)** — no builtin instance exists today, so display names/base URLs are reconstructed from registry + catalog + fixtures (see table). `providerById` (`:23-24`) always returns null today. Consequence: `ProviderRepository.ensureBuiltInsMerged/resetBuiltIn/seeding` (`ProviderRepository.kt:116-166,222-226`) are stale-cleanup-only; `stale` builtins deleted (`:146-150`); empty-DB path inserts nothing (`:152-158`).

### C2. Source types

`app/src/main/kotlin/io/github/mangi/eta/data/model/Provider.kt:18-32`: `custom, openai, anthropic, bailian, deepseek, moonshot, mimo, minimax, stepfun, siliconflow, openrouter, doubao_speech, compatible_speech`. Removable China-vendor: `bailian, deepseek, moonshot, mimo, minimax, stepfun, siliconflow` (+ `doubao_speech`, separate voice scope). Retained generic: `openai, anthropic, openrouter, custom, compatible_speech`.

### C3. Provider table

- `builtin-openai` (`BuiltinProviders.kt:10`) | `openai` | `GPT-5.6 Sol/Terra/Luna, GPT-5.5`, ownedBy `openai` (`OfficialModelCatalog.kt:13-56`) | `https://api.openai.com/v1` (`AgentModelClient.kt:62,65`; registry `api.openai.com->OPENAI`, `ProviderSourceRegistry.kt:75-76`) | **Retain (US generic)**. Default config `AgentModelClient.kt:55-69`.
- `builtin-anthropic` (`:11`) | `anthropic` | `Claude Fable 5 / Opus 4-8 / Sonnet 5`, ownedBy `anthropic` (`:57-88`) | `https://api.anthropic.com` (`ModelProviderDetailScreen.kt:128`; registry `:77`) | **Retain (US generic)**.
- `builtin-dashscope` (`:12`) | `bailian` | `Qwen3.7 Plus` (ownedBy `qwen`), `Kimi K2.7 Code / K2.6` (ownedBy `moonshot`) (`:89-121`) | `https://dashscope.aliyuncs.com/compatible-mode/v1` (fixtures `RemoteModelFetcherTest.kt:79,142`; `AgentRuntimeWireTest.kt:342`; registry `dashscope.aliyuncs.com` + `*.maas.aliyuncs.com -> BAILIAN`, `:85-86`) | **Remove (China: Alibaba Bailian)**. `aliyuncs.com` = Alibaba Cloud; `THIRD_PARTY_NOTICES.md:132` names `阿里云百炼`; TTS `Qwen Audio` (`SpeechSynthesisModels.kt:70-73`).
- `builtin-deepseek` (`:13`) | `deepseek` | `DeepSeek V4 Pro / V4 Flash`, ownedBy `deepseek` (`:122-141`) | `https://api.deepseek.com/v1` (fixture `ProviderComponentsTest.kt:76`; registry `api.deepseek.com->DEEPSEEK`, `:78`) | **Remove (China: DeepSeek)**. `THIRD_PARTY_NOTICES.md:132`; logo rule `ProviderBranding.kt:55`.
- `builtin-kimi` (`:14`) | `moonshot` | `Kimi K3 / K2.7 Code (+HighSpeed) / K2.6 / K2.5`, ownedBy `moonshot` (`:142-198`); plus Bailian-hosted `kimi-k2.7-code/k2.6` (`:101-120`) | `https://api.moonshot.cn/v1` or `https://api.moonshot.ai/v1` (registry `:79-80`; npm `@moonshot-ai/kimi-code`, `LinuxPackageProfiles.kt:106-109`) | **Remove (China: Moonshot AI / Kimi)**. `THIRD_PARTY_NOTICES.md:55,100-112,132` (`kimi.webp`, Kimi Code MIT (c) Moonshot AI).
- `builtin-mimo` (`:15`) | `mimo` | `MiMo V2.5 Pro / V2.5`, ownedBy `xiaomi` (`:199-221`) | `https://api.xiaomimimo.com/v1` (fixtures `MimoCloneTest.kt:20,51`; `SpeechEngineTest.kt:20`; `RemoteModelFetcherTest.kt:104`; registry `:81`) | **Remove (China: Xiaomi MiMo)**. `THIRD_PARTY_NOTICES.md:56,132` (`xiaomimimo.webp`, `Xiaomi MiMo`); TTS `小米语音` (`SpeechSynthesisModels.kt:58-61`); `SpeechEngine.kt:40`.
- `builtin-minimax` (`:16`) | `minimax` | `MiniMax M3`, ownedBy `minimax` (`:222-233`) | `https://api.minimaxi.com/v1` (fixture `:116`) / `https://api.minimax.io/v1` (registry `:82-83`) | **Remove (China: MiniMax)**. `THIRD_PARTY_NOTICES.md:57,132`; `SpeechEngine.kt:42-43`.
- `builtin-stepfun` (`:17`) | `stepfun` | `Step 3.7 Flash`, ownedBy `stepfun` (`:234-246`) | `https://api.stepfun.com/v1` (fixture `RemoteModelFetcherTest.kt:128`; registry `:84`) | **Remove (China: StepFun)**. `THIRD_PARTY_NOTICES.md:58,132`; `SpeechEngine.kt:44-45`.
- `builtin-siliconflow` (`:18`) | `siliconflow` | **No catalog entry** — `modelsByCatalogId` keys are only OPENAI/ANTHROPIC/BAILIAN/DEEPSEEK/MOONSHOT/MIMO/MINIMAX/STEPFUN (`OfficialModelCatalog.kt:12-246`) | `https://api.siliconflow.cn/v1` (registry `api.siliconflow.cn->SILICONFLOW`, `:87`; `https://docs.siliconflow.cn/...`, `docs/relay-speech.md:12`) | **Remove (China: SiliconFlow / 硅基流动)**. `.cn` host; `THIRD_PARTY_NOTICES.md:59,132` (`siliconcloud.webp`, `硅基流动`).
- `builtin-openrouter` (`:19`) | `openrouter` | **No catalog entry** (same key grep) | `https://openrouter.ai` (registry `:88`) | **Retain (generic aggregator)**. `OpenAiChatCompletionsProvider.kt:93` skips `stream_options` for OPENROUTER; `ProviderReasoning.kt:36` `applyOpenRouter`.

BAILIAN uniquely hosts rival `moonshot`-owned Kimi models (`OfficialModelCatalog.kt:101-120`) — deleting BAILIAN must also delete those two entries. `SILICONFLOW`/`OPENROUTER` survive only in constants + registry + logo map; only OPENROUTER is retained.

### C4. RemovedProviderPolicy — what it does + absent-provider tolerance

`app/src/main/kotlin/io/github/mangi/eta/data/model/RemovedProviderPolicy.kt:5-36`: tombstone-only, never network (`:5`). `isRemoved(baseUrl, endpointMode, authMode)` (`:9-17`): true for `endpointMode==antigravity`, `authMode==oauth_antigravity|REMOVED`, hosts `cloudcode-pa.googleapis.com / daily-cloudcode-pa(.sandbox).googleapis.com`. No China vendor tombstoned yet. `isRemoved(provider)` (`:19-27`) adapts sealed subtypes. `requireSupported(...)` (`:29-35`) with Chinese message `:7`.

Already tolerant of absent providers: `ProviderRepository.providersFlow/allProviders/providerById/providerByModelId` filter removed (`ProviderRepository.kt:41-47,55-65`); `updateProvider/replaceProvider` reject removed (`:75-77,203-204`); `ensureBuiltInsMerged` deletes stale builtins (`:143-151`); `removeRetiredProviders()` hard-deletes retired rows + clears OAuth + selection on startup/restore (`:128-140`); `repairSelection()` repoints to first enabled (`:168-187`). Enforced at `RemoteModelFetcher.fetch` (`RemoteModelFetcher.kt:32-41`), `RuntimeConfigRepository` (`:106`, `:187`), `ProviderClientFactory.getClient` (`ProviderClientFactory.kt:9-11`), `ProviderRequestHeaders` (`:16`), `AgentCompressionBoundary` (`:15`), `OpenAiCodexOAuth.withResolvedAuth` (`OpenAiCodexOAuth.kt:104`), `ModelProviderDetailScreen.test` (`ModelProviderDetailScreen.kt:662`). Test `RemovedProviderPolicyTest.kt:10-24` pins retired-host rejection + Gemini/Codex/Anthropic exemption. Cutover: adding China hosts to `isRemoved` auto-hides/blocks/GCs legacy rows without migrations.

### C5. Generic model-kind files in scope (all KEEP)

- `data/model/ImageGenerationModels.kt:8-75`: ID-substring + modality heuristic. Chinese-vendor substrings only as generic markers: `wanx` (`:21`), `kolors` (`:23`), `qwen-image` (`:27-28`), `cogview` (`:29`), `hunyuan-image` (`:30-32`), `seedance` in VIDEO_MARKERS (`:43`), `cosyvoice/sambert` (`:47`). No provider ID/URL/key. KEEP.
- `data/model/VideoGenerationModels.kt:8-61`: markers incl. `seedance` (`:14`), `kling-video` (`:15`), `hailuo` (`:17`), `cogvideox` (`:18`), `hunyuan-video` (`:19-20`), `minimax-video` (`:26`); `:6` notes kimi/step video-understanding chat stays in chat. No endpoint. KEEP.
- `data/model/VisionChatModels.kt:8-43`: default-vision-true; NEGATIVE incl. `deepseek-*`, `qwq`, `qwen3-coder`, `qwen2.5-coder` (`:10-20`). No endpoint. KEEP.
- `data/model/Reasoning.kt:12-84`: `ReasoningEffort` enum + capabilities; no vendor strings (display names generic: 关闭/默认/最小/低/中/高/超高/极高, `:19-40`). KEEP (translate labels, keep wire values).
- `data/repository/RemoteModelFetcher.kt:28-152`: generic OpenAI/Anthropic `/models` fetch + `enrich` (`:80,100`); chat filter (`:127-136`) with generic markers (`:138-151`). KEEP.

---

## (d) Logo resolution + fallback

### D1. Assets on disk (exact)

- `app/src/main/res/drawable-xxxhdpi/`: 10 provider logos `provider_logo_openai/anthropic/bailian/deepseek/kimi/mimo/minimax/stepfun/siliconflow/openrouter.webp`; 12 model logos `model_logo_chatglm/claude/doubao/gemini/gemma/grok/hunyuan/meta/mistral/qwen/yi/zai.webp` (glob).
- `app/src/main/res/drawable/ic_kimi_code.xml`: Kimi Web menu icon (`TopBarOverflowMenu.kt:170`). Single `xxxhdpi` + single `drawable` hit; no other densities.
- `docs/THIRD_PARTY_NOTICES.md:51-69`: each webp -> upstream file (`provider_logo_kimi->kimi.webp`, `mimo->xiaomimimo.webp`, `siliconflow->siliconcloud.webp`, etc.); `:100-112` attributes `ic_kimi_code.xml` to Moonshot AI kimi-code MIT (`Copyright (c) 2026 Moonshot AI`); `:132` brand disclaimer lists Bailian/Qwen/DeepSeek/Kimi/MiMo/MiniMax/StepFun/豆包/混元/硅基流动/OpenRouter.

### D2. Resolution code (exact)

`ui/components/ProviderBranding.kt:9-40`: `providerBrandLogoRes(provider)` (`:10-11`) -> `providerBrandLogoRes(resolve(provider))`; `providerBrandLogoRes(sourceType)` (`:14-28`): `OPENAI->openai` (`:16`), `ANTHROPIC` (`:17`), `BAILIAN` (`:18`), `DEEPSEEK` (`:19`), `MOONSHOT->provider_logo_kimi` (`:20`), `MIMO` (`:21`), `MINIMAX` (`:22`), `STEPFUN` (`:23`), `SILICONFLOW` (`:24`), `OPENROUTER` (`:25`), `DOUBAO_SPEECH->model_logo_doubao` (`:26`), else null (`:27`); unknown/CUSTOM/COMPATIBLE_SPEECH -> null. `modelBrandLogoRes(modelId)` (`:31-35`): empty->null, else first `MODEL_BRAND_RULES` (`:47-78`): openai-family (`:48-51`), `claude` (`:52`), **`kimi|moonshot->provider_logo_kimi` (`:53)**, `qwen|qwq|qvq` (`:54`), `deepseek` (`:55`), `mimo` (`:56`), `minimax|abab` (`:57`), step-pattern (`:58-61`), `glm-4/5->zai` (`:62-64`), `glm|chatglm` (`:66`), gemini/gemma/grok/llama/mistral/doubao/hunyuan/yi (`:67-77`). First-match wins, so `kimi-k2.6` on BAILIAN still shows Kimi (pinned `ProviderBrandingTest.kt:63-67`). `modelOrProviderBrandLogoRes` (`:38-40`): **model wins; provider is fallback only**; both null -> null. Thin re-export `ProviderComponents.kt:75-81`.

### D3. Fallback for unknown providers (exact)

- `ProviderComponents.kt:51-57` `ProviderBrandIcon`: `?: return` — renders nothing when unknown.
- `ProviderComponents.kt:85-105` `ProviderIcon`: brand image if known (`:89-93`); else generic `CustomProviderSetting->Icons.Rounded.Dns` (`:96-99`), else `Icons.Rounded.Language` (`:100-103`). Comment `:83`.
- `AgentChatModelControls.kt:376-407` `ModelBrandMark`: brand image if known (`:382-391`, clipped circle); else primaryContainer circle + `Icons.Rounded.Dns` (`:392-406`).
- Removal outcome: deleting the six China arms + Kimi/model rules with their webp files demotes legacy rows to generic Dns/Language fallback — no crash, no dangling reference, if code + files go together.

### D4. Logo callers

- `AgentChatModelControls.kt:382` (`modelOrProviderBrandLogoRes`) — chat model chip.
- `ProviderComponents.kt:55,89` — provider list/detail icons.
- `ModelProviderListScreen.kt:180,189,198` — `ProviderBrandIcon(OPENAI/ANTHROPIC/DOUBAO_SPEECH)` for the three new-provider cards (Kimi has no card; only named in `ui_support_chatgpt_deepseek_kimi_glm_qwen_etc_b31d02`, `:178`).
- Tests pinning China logos (must update): `ProviderBrandingTest.kt:17-19,24-25` (`kimi-k2.6`, `kimi/kimi-k3`, `moonshot-v1-128k`, `MiniMax-M3`, `step-3.7-flash`), `:63-67`; `ProviderComponentsTest.kt:49-58` (BAILIAN/DEEPSEEK/MOONSHOT/MIMO/MINIMAX/STEPFUN/SILICONFLOW/OPENROUTER map).

---

## (e) Docs / resources / tests / assets references

### E1. Android resources

- `app/src/main/res/values/strings.xml:49,446,1080-1088,1311`: `action_launch_kimi_web=Launch Kimi Web`; `ui_support_chatgpt_deepseek_kimi_glm_qwen_etc_b31d02=Support ChatGPT, DeepSeek, Kimi, GLM, Qwen, etc.`; `linux_kimi_tools=Kimi Code` + summary/ready; `linux_kimi_web_launch/starting/failed_start/failed_url/failed_browser`; `data_backup_include_linux_summary=Includes Kimi...`.
- `values-b+zh+Hans/strings.xml:78,457,1096-1104,1323` + `values-b+zh+Hant/strings.xml:78,457,1096-1104,1323`: same Kimi keys in Chinese.
- `values/root_capabilities.xml:73-83`: `capability_kimi_checking/install/preparing/open/retry/stop/root_required/exited/not_installed/proot_unavailable/logs_unavailable` (all Chinese).
- Consumers: `ModelProviderListScreen.kt:178`; `TopBarOverflowMenu.kt:237`; `LinuxEnvironmentScreen.kt:122-124,327,442-468`.

### E2. Docs / README

- `README.md:71,74,76` (Chinese) + `README_EN.md:71,74,76` (English): dev-tools `Kimi Code`, Kimi Web paragraph (`MoonshotAI/kimi-code`, home-screen launch, `kimi` command, separate sign-in).
- `docs/AGENT_RUNTIME.md:124`: `kimi web --no-open` reuse semantics.
- `docs/ROOTLESS_SUPPORT.md:17,31,35,37,56`: capability table, installer coverage, lifecycle, QA checklist.
- `docs/TECHNICAL.md:177`: Linux tooling paragraph names Kimi install.
- `docs/THIRD_PARTY_NOTICES.md:53-59,100-132`: logo table, `## Kimi Code` icon section (`:100-112`, SVG link + MIT), brand disclaimer (`:132`).
- Non-Kimi China docs: `docs/mimo-personal-voices.md:1-11` (MiMo voice), `docs/relay-speech.md:12-15` (siliconflow TTS `docs.siliconflow.cn`), `docs/compaction-harness-alignment.md:3,21,38` + `docs/reviews/context-compression-*.md:4,7` (deepseek-harness third-party citations, not endpoints — keep or reword).
- `.omp/plans/SU_AMERICANIZATION_SU_PLAN.md:9,26,30,34,50,57` is the plan itself (lists Kimi Web among removal targets) — not shipped surface.
- No `kimi|moonshot` hits under `app/src/main/assets/` or `.github/` product surfaces; `skills/` path does not exist.

### E3. Tests

- `ui/app/KimiWebLauncherTest.kt:17-76`: reuse (`:18-23`), `BROWSER_UNAVAILABLE` (`:29`), `KIMI_EXITED` cleanup (`:36-37`), `URL_TIMEOUT` cleanup (`:43-44`), cancel-safety (`:52-55`), `FakeTasks` with `COMMAND` (`:61-74`). Delete with feature.
- `ui/app/KimiWebUiStateTest.kt:17-40`: `canStop`, failure messages, `observedKimiWebState`. Delete with feature.
- `agent/terminal/AlpineEnvironmentInstallerTest.kt:121-130`: pins `KIMI.dependsOn==NODE` + npm script (`@moonshot-ai/kimi-code@latest`, `--prefix /usr/local`, `registry.npmmirror.com`). Delete/update block.
- `agent/terminal/DetachedTaskSupervisorTest.kt:213`: `command="kimi web"` fixture — generic supervisor test; keep, swap sample command.
- `ui/components/AnsiTextTest.kt:99-101`: `Kimi server ready` banner — ANSI parser fixture; keep, swap text.
- `agent/model/OpenAiChatCompletionsProviderTest.kt:576-590`: `completeBuildsKimiPreservedThinkingRequest` (`moonshot/kimi-k2.6`). Delete/retarget.
- `agent/model/ProviderReasoningTest.kt:15-18,163-174`: `MOONSHOT/kimi-k3`. Delete/retarget.
- `data/provider/ReasoningCapabilityResolverTest.kt:42-43`: `mandatoryKimi...` (`MOONSHOT/kimi-k3`). Delete/retarget.
- `data/repository/RemoteModelFetcherTest.kt:77-135,140-145,173-214,394-399`: Bailian (`dashscope...`, `:79`), `qwen3.7-plus/kimi-k2.7-code/kimi-k2.6` vision (`:86-95`), MiMo (`api.xiaomimimo.com`, `:102-111`), MiniMax (`api.minimaxi.com`, `:112-122`), StepFun (`api.stepfun.com`, `:124-135`), Kimi metadata (`:173-214`), `kimi-k2.7-code` list (`:396`). Delete/retarget China blocks; keep generic mechanics.
- `data/repository/ProviderBalanceFetcherTest.kt:86-90`: `dashscope.aliyuncs.com` URL-join fixture. Swap host.
- `ui/pages/providers/ProviderComponentsTest.kt:74-77`: `https://api.deepseek.com/v1` copy fixture. Swap host.
- `agent/voice/mimo/MimoCloneTest.kt:20,51-52` + `agent/voice/tts/SpeechEngineTest.kt:20`: `api.xiaomimimo.com` fixtures — update only if MIMO engine removed (voice scope).
- `agent/runtime/AgentRuntimeWireTest.kt:342`: `https://dashscope.aliyuncs.com/compatible-mode/v1`. Swap host.
- `data/model/RemovedProviderPolicyTest.kt:10-24`: no China coverage; extend with new tombstones.
- `ui/components/ProviderBrandingTest.kt` + `ui/pages/providers/ProviderComponentsTest.kt` (see D4): pin China logos; update.

### E4. Assets

No Kimi/Moonshot in `app/src/main/assets/` (verified: `builtin_skills/manifest.json`, 3 skills, `agent/workspace.py`, native tarballs, licenses — grep no-match). Only Kimi asset is `res/drawable/ic_kimi_code.xml` + `provider_logo_kimi.webp` (see D1).

---

## MUST-KEEP symbols (shared with retained Google/Gemini/generic behavior)

- `ProviderSourceTypes.OPENAI/ANTHROPIC/OPENROUTER/CUSTOM/COMPATIBLE_SPEECH` (`Provider.kt`), registry arms (`ProviderSourceRegistry.kt:75-77,88`) + `resolve/normalize` (`:26-55`), `provider_logo_openai/anthropic/openrouter` (`ProviderBranding.kt:16-17,25`), `OfficialModelCatalog` OPENAI/ANTHROPIC (`:13-88`), `AgentModelClient.kt:55-69` default OpenAI config — retained generic/BYOK path.
- `ImageGenerationModels`, `VideoGenerationModels`, `VisionChatModels`, `ReasoningEffort` — generic heuristics (see C5).
- `RemoteModelFetcher` (`:32-152`), `ProviderRepository` flows/filter/repair (`:41-65,128-187`), `RemovedProviderPolicy` mechanics (`:9-35`) — retain mechanism, grow match list.
- `AgentVideoGenerationClient` generic attempts `VideosMultipart/Json/Generations`, `VideoGenerations`, `ChatCompletions` + generic poll URLs (`:50-54,129-130,186-211`) — retained video path once Ark excised.
- `DetachedTaskSupervisor`, `LinuxEnvironmentPaths`, `TerminalRuntime`, `AgentExecutionService`, `LinuxPackageProfiles.PYTHON/NODE/SSH`, `LinuxEnvironmentScreen` non-Kimi cards — shared Linux infra; remove only `KIMI` profile, its card, and the three Kimi files.
- `ProviderIcon`/`ModelBrandMark` fallbacks (`ProviderComponents.kt:83-105`; `AgentChatModelControls.kt:392-406`) and non-China `MODEL_BRAND_RULES` — retained.
- Test mechanics (supervisor, ANSI, fetcher parsing, branding fallback) — keep tests, swap China fixtures.

---

## Removal cut list (this ticket's scope)

1. `ui/app/KimiWebLauncher.kt`, `KimiWebSession.kt`, `KimiWebUiState.kt` + `AgentAppViewModel.kimiWeb*` (`:31-92`), `AgentAppRoot` Kimi wiring (`:178,373-392,846`), `AgentAppShell` five Kimi params (4 sites), `TopBarOverflowMenu` Kimi item + stop + icon (`:53-57,82,167-175,232-246`), `LinuxEnvironmentScreen` Kimi card + launcher + `launchKimiWeb()` (`:75-79,120-125,179-205,290-302,414-483`), `LinuxPackageProfiles.KIMI` + script (`:101-122,212`), `AlpineEnvironmentPaths.KIMI_*`, `ic_kimi_code.xml`, all Kimi strings (E1), Kimi docs (E2), Kimi tests (E3).
2. `agent/model/ArkContentsGenerations.kt` + `Attempt.ArkContents` + 3 call sites (`AgentVideoGenerationClient.kt:49,79,128,181-185`) + `ArkContentsGenerationsTest.kt`.
3. China provider scaffolding: `BuiltinProviders` `BAILIAN/DEEPSEEK/KIMI/MIMO/MINIMAX/STEPFUN/SILICONFLOW_ID`, `ProviderSourceTypes` `BAILIAN/DEEPSEEK/MOONSHOT/MIMO/MINIMAX/STEPFUN/SILICONFLOW` + `ProviderSourceRegistry` ID arms (`:59-68`) + host arms (`:78-87`), `OfficialModelCatalog` BAILIAN/DEEPSEEK/MOONSHOT/MIMO/MINIMAX/STEPFUN maps (`:89-246`, incl. Bailian-hosted Kimi pair), `ProviderReasoning` MOONSHOT/MIMO/MINIMAX/STEPFUN/BAILIAN/SILICONFLOW arms + `kimi-*` branches, `ReasoningCapabilityResolver` `kimi-*` prefixes, provider/model logo arms + vendor-named model rules + `provider_logo_bailian/deepseek/kimi/mimo/minimax/stepfun/siliconflow.webp`, China string `ui_support_chatgpt_deepseek_kimi_glm_qwen_etc_b31d02`, China test blocks (E3). Add tombstones in `RemovedProviderPolicy` so legacy DB rows filter/GC via the existing mechanism.
