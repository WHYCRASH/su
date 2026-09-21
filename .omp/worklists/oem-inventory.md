# China/OEM integration-surface inventory (`io.github.mangi.eta`)

Conventions: `path:line` = exact location. Inbound = references from outside the file's own package in main source; tests listed separately in section 4. All claims grounded by reads/greps this session.

## (a) Removable-symbol table

### A1. ModuleMain.kt - OEM-only routing (entry point itself is kept)

- Imports `hook.aimemory.ColorOsMemoryHooks` (:13), `hook.breeno.BreenoHooks` (:14), `hook.colordirect.ColorDirectHooks` (:15), `hook.hyperos.HyperOsLauncherHooks` (:18), `HyperOsScreenSearchHooks` (:19), `hook.xiaoai.XiaoAiHooks` (:22): all OEM-only. Kept imports: google (:16-17), system (:20-21).
- `in ModuleConfig.XIAOMI_LAUNCHER_PACKAGES ->` + `HyperOsLauncherHooks.install` (:58-62): OEM-only, no inbound (Xposed callback).
- `ModuleConfig.COLOR_DIRECT_PACKAGE ->` + `ColorDirectHooks.install` (:84-88): OEM-only.
- `ModuleConfig.BREENO_PACKAGE ->` + `BreenoHooks.install` (:90-94): OEM-only.
- `ModuleConfig.COLOROS_MEMORY_PACKAGE ->` + `ColorOsMemoryHooks.install` (:96-100): OEM-only.
- `ModuleConfig.XIAOAI_PACKAGE ->` (:102-115): hosts TWO installs, `HyperOsScreenSearchHooks.install` (:103-105) and `XiaoAiHooks.install` (:106-114). Both OEM-only.
- `shouldKeepLifecycleCallbacks` OEM disjuncts (:132 XIAOMI_LAUNCHER_PACKAGES; :135-138 COLOR_DIRECT, BREENO, COLOROS_MEMORY, XIAOAI): OEM-only, called only from `onModuleLoaded` (:33).
- `isCurrentXiaoAiProcess()` (:141-145, incl `:core` process check): OEM-only helper, called only from :106.
- Kept: GOOGLE_PACKAGE branch (:70-82), SYSTEM_UI branch (:64-68), system-server passthrough (:52-54, :130), generic `isPackageProcess` (:147-148).

### A2. core/ModuleConfig.kt - constant-by-constant split

- `BREENO_PACKAGE` (:9) REMOVE. Inbound: `ModuleMain.kt:90-91`; `ModuleConfig.kt:16` (entry set). (`EntrySurfaceGuard.kt:138` is a separate duplicate literal, not this symbol.)
- `COLOROS_MEMORY_PACKAGE` (:10) REMOVE. Inbound: `ModuleMain.kt:96-97,137`; test `core/ModuleConfigEntryPackagesTest.kt:15`.
- `XIAOAI_PACKAGE` (:11) REMOVE (shared CTS-policy use, see section 5). Inbound: `ModuleMain.kt:102-103,138`; `hook/system/ContextualSearchCallerPolicy.kt:14`; `ContextualSearchCallerPolicyTest.kt:23,31-36,42,52,58-66`.
- `XIAOMI_LAUNCHER_PACKAGE` (:12), `XIAOMI_GLOBAL_LAUNCHER_PACKAGE` (:13), `XIAOMI_LAUNCHER_PACKAGES` (:14) REMOVE (see section 5). Inbound: `ModuleMain.kt:58,132`; `ContextualSearchCallerPolicy.kt:14`; tests.
- `XIAOAI_CORE_PROCESS` (:15) REMOVE. Inbound: `ModuleMain.kt:144`; `ModuleConfigEntryPackagesTest.kt:14`.
- `AGENT_RUNTIME_ENTRY_PACKAGES` (:16) REMOVE set (gate semantics, see section 5). Inbound: `agent/runtime/AgentRuntimeService.kt:1083`; `ModuleConfigEntryPackagesTest.kt:11-12`.
- `COLOR_DIRECT_PACKAGE` (:25) REMOVE (see section 5). Inbound: `ModuleMain.kt:84-85,135`; `hook/system/ContextualSearchHooks.kt:155`.
- `OCR_BUSINESS_CLASS` (:35-36) REMOVE (Oplus-only; sole user `hook/system/SystemUiHooks.kt:35`).
- `COLOR_DIRECT_COLLECT_ACTIVITY_CLASS` (:37-38), `COLOR_DIRECT_START_INFO_CLASS` (:39-40) REMOVE. Inbound: `hook/colordirect/ColorDirectHooks.kt:46,58`.
- `COLOR_DIRECT_EXTRA_START_INFO` (:47), `COLOR_DIRECT_EXTRA_DIRECT_EXT` (:48), `COLOR_DIRECT_DOUBLE_FINGER_COUNT` (:49) REMOVE. Inbound: `ColorDirectHooks.kt:126,132,143,149`.
- `OP_LUS_SPEECH_HANDLER_CLASS` (:43-44), `OP_LUS_ASSIST_MESSAGE_WHAT` (:50) REMOVE iff PowerHooks Oplus branch goes (sole users `hook/system/PowerHooks.kt:49,68`); file itself is KEEP per section 5.
- `CIRCLE_TO_SEARCH_ENTRYPOINT` (:46) SHARED - keep (default arg of retained `hook/system/CircleToSearchInvoker.kt:57`; `HyperOsSearchTrigger.kt:20` passes explicit 1).
- `INTERCEPT_DEDUP_WINDOW_MS` (:51) SHARED - keep while either consumer lives (`ColorDirectHooks.kt:97`; `PowerHooks.kt:145`).
- Kept unconditionally: `GOOGLE_PACKAGE` (:7), `ETA_PACKAGE` (:8), `GOOGLE_ASSISTANT_COMPONENT` (:17-18), `ETA_VOICE_INTERACTION_COMPONENT` (:19-20), `ASSISTANT_ROLE` (:21), `SECURE_*` (:22-23), `SYSTEM_UI_PACKAGE` (:24), `CONTEXTUAL_SEARCH_*` (:27-30), `TIMINGS_*` (:31), `VOICE_INTERACTION_*` (:32-34), `SYSTEM_SERVER_CLASS` (:41), `PHONE_WINDOW_MANAGER_CLASS` (:42), `SPOOF_*` (:53-57, used only by retained `GoogleAppHooks.kt:41-45`), `TAG` (:4), `HOT_PATH_LOG_WINDOW_MS` (:5).

### A3. OEM hook packages

breeno/ - whole package removable, zero outside-package inbound in main source:

- `BreenoHooks.kt:45` (`internal object BreenoHooks`, 2384 lines), `install` entry. Installs Heytap message-queue/DM/CDM/AIChat hooks; builds `AgentRuntimeWire.EntryHandoff(source=breeno` (:89, :1020); filters/drains by source (:869,1085,1172); reads `Prefs.Keys.AGENT_CUSTOM_MODEL` (:634), `AGENT_REQUIRE_PREFIX` (:834). Inbound: `ModuleMain.kt:92` only.
- Nested helpers defined in `BreenoHooks.kt` and used only there: `PendingAckState` (:2189, used :156,1100-1101), `BoundedRunIdSet` (:2250, used :147-163), `breenoRequestDedupKey` (:2310, used :966), `BoundedRetryBudget` (:2325, used :170,1099), `TaskAdmissionGate` (:2354, used :1327). Otherwise tests only.
- `BreenoRequestImages.kt:11` (`internal object BreenoRequestImages`, `BREENO_MULTI_IMAGE_TYPE = 104`). Inbound: `BreenoHooks.kt` only (:135-145,411,416,647-650,788-813,1072,1262-1272,1896) + tests.
- `BreenoConversationHistory.kt:5` (`internal object BreenoConversationHistory`). Inbound: `BreenoHooks.kt:1892` only + tests.

xiaoai/ - whole package removable, zero outside-package inbound in main source:

- `XiaoAiHooks.kt:32` (`internal object XiaoAiHooks`), `SUPPORTED_VERSION_CODE = 507013032L` (:33), `install` (:83-97). Installs VAApplication bootstrap to OperationManager/ASR/TTS/float/card hooks; builds `XiaoAiHandoff` runs; filters drains by `XiaoAiHandoff.SOURCE` (:650); reads `AGENT_CUSTOM_MODEL`/`AGENT_REQUIRE_PREFIX` (:494-495,542-543). Inbound: `ModuleMain.kt:108` only (`XiaoAiHookState.kt:17` intra-package read of SUPPORTED_VERSION_CODE).
- `XiaoAiHandoff.kt:7` (`internal object XiaoAiHandoff`), `SOURCE = xiaoai` (:8), `create` (:11), `dialogIdFrom` (:28). Inbound: `XiaoAiHooks.kt:650,686,744` only + tests.
- `XiaoAiHookState.kt`: `XiaoAiTakeoverPolicy` (:6), `XiaoAiRunSlot` (:55), `XiaoAiRendererSlot` (:65), `XiaoAiQueryCache` (:73), `XiaoAiTurnTracker` (:173), `XiaoAiRecentIds` (:229). Inbound: `XiaoAiHooks.kt:57-62,122,432,491,539` only + tests.
- `XiaoAiImages.kt:7`, `XiaoAiStreamRenderer.kt:18`. Inbound: `XiaoAiHooks.kt:484-503,598-618,659,721` only + tests.

hyperos/ - all 8 files removable as a unit (intra-package cross-refs only, except HyperOsPowerHooks):

- `HyperOsLauncherHooks.kt:12`, `install` (:13) tries gesture-manager, omni, event-helper, legacy fallback (:17-20). Inbound: `ModuleMain.kt:60` only.
- `HyperOsScreenSearchHooks.kt:12`, `install` (:15) intercepts `com.xiaomi.voiceassistant.VoiceService.onStartCommand` (:13). Inbound: `ModuleMain.kt:104` only.
- `HyperOsScreenSearchRequest.kt:5`, `matches` (:12-18: ACTION_ASSIST + start_screen_recognition + NavLongPress + nav sources :6-10). Inbound: `HyperOsScreenSearchHooks.kt:51` (same package) + tests.
- `HyperOsSearchTrigger.kt:9`, `isEnabled` (:10 reads `Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH`), `trigger` (:12, CTS entryPoint=1). Callers all intra-package: `HyperOsLauncherHooks.kt:36,52,67`, `HyperOsScreenSearchHooks.kt:48,60`, `HyperOsLegacyGesture.kt:31-32`.
- `HyperOsLegacyGesture.kt:11`, `install` (:12, NavStubView.onTouchEvent). Inbound: `HyperOsLauncherHooks.kt:20` (same package).
- `HyperOsLongPressGesture.kt:10` (`internal class HyperOsLongPressGesture`). Inbound: `HyperOsLegacyGesture.kt` (same package) + tests.
- `HyperOsPowerHooks.kt:14`, `install` (:15) intercepts `com.miui.server.input.util.ShortCutActionsUtils.triggerFunction` (:18-30), gated by `HyperOsPowerPolicy` (:38), routes via `Prefs.powerAssistantTarget()` (:41) to `AssistantManager.showAssistantSession` (:44). Sole outside-package inbound: `hook/system/SystemServerHooks.kt:23` (drop that list element).
- `HyperOsPowerPolicy.kt:3`, `isAssistantShortcut` (:4-6). Inbound: `HyperOsPowerHooks.kt:38` (same package) + tests.

colordirect/: `ColorDirectHooks.kt:19` (`internal object ColorDirectHooks`), `install` (:28-37) hooks `CollectInfoActivity.M(Intent)` (:61), gated by `Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH` (:77), reroutes double-finger intents to `CircleToSearchInvoker` (:95-113). Inbound: `ModuleMain.kt:86` only. Uses `ModuleConfig.COLOR_DIRECT_*` (:46,58,97,126,132,143,149). `SOURCE` (:20) file-private.

aimemory/: `ColorOsMemoryHooks.kt:16` (`internal object ColorOsMemoryHooks`), `install` (:17-66) hooks `DataShareProvider.call` (class from `ColorOsMemoryBridgeProtocol.PROVIDER_CLASS` :24-27), serves `METHOD` (:57), queries via `ColorOsMemoryDatabaseQuery.execute` (:90), UID-gated to root (`ROOT_UID` :72,114). Inbound: `ModuleMain.kt:98` only.

### A4. core/ColorOsMemoryBridgeProtocol.kt:8 - REMOVE whole object with consumers

Members: `PACKAGE_NAME` (:9), `PROVIDER_CLASS` (:10), `PROVIDER_URI` (:11), `METHOD` (:12), `RESULT_KEY` (:13), `DATABASE_NAME` (:14), `OPERATION_SEARCH/ORDERS/PLACES` (:16-18), `encodeRequest/decodeRequest/encodeResponse/decodeShellResponse/buildRootCommand` (:24-73). Inbound: `AgentColorOsMemoryTools.kt:8,45,48,51,62,65,73,131-132`; `ColorOsMemoryDatabaseQuery.kt:5,16-23`; `ColorOsMemoryHooks.kt:8,26,57,76,80,99,101,106`; test file section 4.

### A5. Tool files

- `agent/tool/AgentColorOsMemoryTools.kt` REMOVE whole file: `buildColorOsMemorySnapshotCommand` (:16-35), `class AgentColorOsMemoryTools` (:40-43), `search/searchOrders/searchSavedPlaces` (:44-51), hook-first + root-snapshot fallback (:53-74,76-112). Inbound: `AgentStructuredDeviceTools.kt:40,50-52,86`; test section 4.
- `agent/tool/ColorOsMemoryDatabaseQuery.kt` REMOVE whole file: `quoteColorOsMemoryIdentifier` (:10-11), `object ColorOsMemoryDatabaseQuery` (:14), `execute` (:15-25). Inbound: `AgentColorOsMemoryTools.kt:56`, `ColorOsMemoryHooks.kt:90`, test (:58).
- `agent/tool/AgentPersonalDataTools.kt` MIXED (strip methods, keep file): removable dispatch arms (:28-32) + `searchColorOsNotes` (:124-132, `content://com.nearme.note/rich_notes`), `searchColorOsRecordings` (:134-142, `content://com.coloros.soundrecorder.provider/records`), `searchRecordingSummaries` (:144-152, `.../soundrecorder.provider/summary`), `searchQqChatImages` (:154-162), `searchWechatChatImages` (:164-172), companions `QQ_CHAT_IMAGES_DIRECTORY` (:309, `.../com.tencent.mobileqq/...`), `WECHAT_CHAT_IMAGES_DIRECTORY` (:310, `.../com.tencent.mm/...`). Generic media/audio/files/calendar/contacts/calls/messages/downloads stay. File inbound: `AgentStructuredDeviceTools.kt:39` + dispatch :46.
- `agent/tool/AgentPersonalContextTools.kt` KEEP whole file (notifications, UsageStatsManager, location, device-env; shared `hasUsageAccess` used by `AgentToolCapabilities.kt:67`, `AgentAppState.kt:5110`).
- `agent/tool/AgentPrivateDatabaseTools.kt` MIXED: removable `CLOCK_DATABASE` (:260-264, `.../com.coloros.alarmclock/databases/alarms.db`) + `list_alarms/listTimers` (:18-19,25-59), `CLIPBOARD_DATABASE` (:265-269, `.../com.sohu.inputmethod.sogouoem/databases/clipboard_db`) + `searchClipboard` (:20,61-76); keep `HEALTH_DATABASE` (:270-274, healthconnect.db) + `get_health_summary` (:21,78-129). File inbound: `AgentStructuredDeviceTools.kt:42` + dispatch :48.

### A6. Runtime wire / entry guard

- `AgentRuntimeWire.kt:154` `LEGACY_BREENO_HANDOFF_SOURCE = breeno` + default `source == LEGACY_BREENO...` in `entryHandoffFromBundle` (:470-476): REMOVE compat shim. Inbound: tests section 4.
- `EntrySurfaceGuard.kt:126-127` BREENO/XIAOAI branches + consts (:137-140): REMOVE branches+consts; keep ETA_VOICE (:128,141). Callers `AgentRuntimeRunExecutor.kt:84`, `AgentRuntimeService.kt:403,465` are source-agnostic. Tests section 4.
- `AgentRuntimeService.kt:1083` (`packages.any { it in ModuleConfig.AGENT_RUNTIME_ENTRY_PACKAGES }`, `isMessageSenderAllowed` :1077-1084): REMOVE set; gate needs keep/replace decision (section 5). Private fun, no other callers.
- `BreenoHooks.BREENO_HANDOFF_SOURCE` (`BreenoHooks.kt:89`) + source filters (:869,1020,1085,1172); `XiaoAiHandoff.SOURCE` + `XiaoAiHooks` filters (:650,686,744): REMOVE with their files (intra-package only).

### A7. Catalog / requirements / capabilities / prefs / settings

- `AgentToolRequirements.kt:53-55` REQUIRED names `search_coloros_notes/recordings/summaries/memories/places`, `search_qq_chat_images/wechat`: REMOVE names.
- `AgentToolRequirements.kt:43` `search_personal_orders` (PARTIAL): MIXED - orders survives as notification-history tool; `AgentStructuredDeviceTools.searchPersonalOrders` (:72-131) merges memory half (:75-89) + notification half (:90-121). See section 5.
- `AgentToolRequirements.kt:71-74` `colorOs=true` list (notes, recordings, summaries, memories, places): REMOVE list. Readers: `AgentToolCapabilities.kt:30` (DEVICE_UNSUPPORTED), `:38` (personal_orders root bypass).
- `AgentToolRequirements.kt:75-78` Lsposed OPTIONAL list (memories, places, orders, comment :75 hook-bridge rationale): REMOVE entries. Reader: `AgentToolCapabilities.kt:29`.
- `LocalToolRequirement.colorOs` flag (`AgentToolRequirements.kt:16`) + `AgentToolCapabilities.colorOs` (:24), `isColorOsDevice()` (:57-58), `capture colorOs` (:72): REMOVE flag+plumbing once no tool sets colorOs=true.
- `AgentDeviceToolCatalog.kt:200-207` registrations (coloros notes/recordings/summaries/memories/places/orders, qq/wechat): REMOVE puts.
- `AgentLocalTools.kt:1488-1495` DEVICE_SENSITIVE_READ_TOOL_NAMES OEM entries (same 8 names): REMOVE names (gating key `AGENT_DEVICE_SENSITIVE_READ_TOOLS` generic, keep).
- `AgentSensitiveToolPolicy.kt:37-44`, `AgentTraceFormatter.kt:616-623`, `SubAgentTools.kt:18`, `AgentAppState.kt:5062-5069` (ToolItemUi), `ToolIcons.kt:109,116,119,124-126`: REMOVE per-tool entries (UI/policy only).
- Private-DB catalog `AgentDeviceToolCatalog.kt:149-164` (`list_alarms`, `list_active_timers`, `search_clipboard_history` with ColorOS descriptions :150,160; keep `get_health_summary`): REMOVE alarm/timer/clipboard puts. Mirrors in `AgentLocalTools.kt:1473-1476`, `AgentSensitiveToolPolicy.kt:22-25`, `AgentTraceFormatter.kt:601-604`, `SubAgentTools.kt:14-16`, `AgentAppState.kt:5037-5040`, `ToolIcons.kt:95,123`.
- `AgentStructuredDeviceTools.kt:680` `COLOROS_CLOCK_PACKAGE = com.coloros.alarmclock`: REMOVE vendor literal (check setAlarm/setTimer readers of the line first).
- `Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH` (`Prefs.kt:34,72`): SHARED - keep (retained readers `ContextualSearchHooks.kt:159`, `SystemUiHooks.kt:54` besides `HyperOsSearchTrigger.kt:10`; UI `SettingsScreen.kt:669`; test `PrefsDefaultsTest.kt:15`).
- `Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH` (`Prefs.kt:35,73`): REMOVE key iff ColorDirect goes (sole hook reader `ColorDirectHooks.kt:77`; UI `SettingsScreen.kt:677`; test `PrefsDefaultsTest.kt:16`).
- `Prefs.Keys.AGENT_CUSTOM_MODEL` (:38,76) + `AGENT_REQUIRE_PREFIX` (:39,77): REMOVE keys + BOOLEAN_DEFAULTS entries (sole hook readers `BreenoHooks.kt:634,833-834`, `XiaoAiHooks.kt:494-495,542-543`; UI `SettingsScreen.kt:581-603` OEM section titled `ui_xiaobu_xiaoai_compatible_entrance_ae918a` :584).
- `Prefs.powerAssistantTarget()` (:177-188), `POWER_KEY_ASSISTANT_TARGET` (:29), `POWER_KEY_TAKEOVER` (:31 legacy): KEEP (retained readers `PowerHooks.kt:72,299`, `AssistantManager.kt:370,404,431,502,657,686`, `HyperOsPowerHooks.kt:41`, `SettingsScreen.kt:250-259,536-567`, `EnhancementSettingsHistory.kt:21,27`).
- `PowerAssistantTarget` enum (`PowerAssistantTarget.kt:3-9` incl OEM): KEEP incl OEM variant (means restore-OEM; `AssistantManager.assistantSelectionAction` to RESTORE_OEM; `AssistantBindingTest.kt:13-76`).
- `SettingsScreen.kt`: remove OEM-compat section (:581-603) + QQ group row (:939-970) + WeChat/Alipay donate (:1299-1330) per plan; keep power dropdown (:536-567) + CTS switches (:660-682, shared).

## (b) Must-keep table

- `hook/google/GoogleAppHooks.kt:22` + `GoogleEligibilityHooks.kt:11`: Gemini/CTS foundation (Build spoof `GoogleAppHooks.kt:41-45` from `ModuleConfig.SPOOF_*`; Floaty voice-command; `ro.opa.eligible_device`/feature eligibility). Installed from retained `ModuleMain.kt:70-82`.
- `hook/system/SystemServerHooks.kt:9` shell: retained combinator; only its `HyperOsPowerHooks` element (:23) is removed.
- `hook/system/AssistantManager.kt:29`: generic GEMINI/ETA/OEM-restore routing; `showAssistantSession` reused by retained power path.
- `hook/system/PowerHooks.kt:19`: power-key to Gemini/ETA adapter on Oplus (hooks `OP_LUS_SPEECH_HANDLER_CLASS` :49 but routes to generic `assistantBindingFor(target)` :72-76, honors OEM passthrough :299). Plan keeps OEM-specific adapters that launch Google functionality.
- `hook/system/SystemUiHooks.kt:14`: Oplus-OCR to Circle-to-Search adapter (same rationale), gated by shared GESTURE_BAR key (:54).
- `hook/system/ContextualSearchHooks.kt:142-160`, `CircleToSearchInvoker.kt:57`, `AssistantBinding`, `HotwordSelfHealHooks`, Accessibility hooks: retained CTS/system plumbing; vendor allowances edited in place (section 5).
- `hook/system/ContextualSearchCallerPolicy.kt:8` (`allowsHyperOsCaller`): retained CTS permission helper; predicate shrinks, file stays. Pinned by `ContextualSearchCallerPolicyTest.kt` (keep, update).
- `AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE` (:30), `ETA_VOICE_HANDOFF_SOURCE` (:31), `EntryHandoff/toBundle` (:454-462): retained sources; only Breeno-legacy default (:154,470-476) removed.
- `EntrySurfaceGuard` ETA branch (:128) + mechanics: retained Eta-voice dismissal; only breeno/xiaoai branches (:126-127,137-140) go.
- `AgentPersonalContextTools.kt` whole file: no OEM surface.
- `AgentPersonalDataTools` generic methods + `AgentPrivateDatabaseTools` health branch + `search_personal_orders` notification half (`AgentStructuredDeviceTools.kt:90-121`): retained behavior; orders stays as notification-history tool.
- `AgentToolCapabilities` minus colorOs + `AgentToolRequirements` minus OEM entries: retained gating for generic tools.
- `Prefs` power-target machinery + GESTURE_BAR key + `PowerAssistantTarget` incl OEM: retained routing/restore semantics.
- `ModuleConfig` Google/Eta/CTS/system-server/spoof constants (section A2 keep-list): retained wiring identifiers.
- `AndroidManifest.xml` (243 lines): no Xposed/OEM entry - app class `.EtaApp` (:49), no meta-data naming ModuleMain; only generic queries entry is `com.google.android.googlequicksearchbox` (:38). Nothing to remove.

## (c) Manifest / Xposed metadata references

- `app/src/main/resources/META-INF/xposed/java_init.list:1` = `io.github.mangi.eta.ModuleMain`: the single Xposed entry class. Kept; only its OEM branches are removed. (`proguard-rules.pro:23-29` keeps this class and rewrites this list via `-adaptresourcefilecontents`.)
- `app/src/main/resources/META-INF/xposed/scope.list:1-9`: `system`, `com.android.systemui`, `com.google.android.googlequicksearchbox`, `com.coloros.colordirectservice`, `com.heytap.speechassist`, `com.oplus.aimemory`, `com.miui.voiceassist`, `com.miui.home`, `com.mi.android.globallauncher`. Lines 4-9 are the six removable OEM scopes; lines 1-3 retained. (`app/build.gradle.kts:103-105` merges `META-INF/xposed/*` into the APK.)
- `app/src/main/resources/META-INF/xposed/module.prop:1-4`: minApiVersion/targetApiVersion 102, staticScope=true, exceptionMode=protective. No class/package names; nothing to remove.
- `AndroidManifest.xml`: zero `ModuleMain`/xposed references (grep over `app/` for `xposed|ModuleMain` hits only `build.gradle.kts`, `proguard-rules.pro`, `ModuleMain.kt`, `EtaApp.kt` service wiring). No `xposed_init` file exists (libxposed uses `java_init.list`).

## (d) Exclusive tests (delete with feature) vs shared tests (keep, update)

Exclusive - each tests only a removable package/symbol:

- `hook/breeno/BreenoHookStateTest.kt`: `PendingAckState`, `BoundedRunIdSet`, `breenoRequestDedupKey`, `BoundedRetryBudget`, `TaskAdmissionGate`, all defined in `BreenoHooks.kt:2189-2354` and used only there.
- `hook/breeno/BreenoRequestImagesTest.kt`: `BreenoRequestImages.*` only.
- `hook/breeno/BreenoConversationHistoryTest.kt`: `BreenoConversationHistory.build/Entry` only.
- `hook/xiaoai/XiaoAiHandoffTest.kt:14-33`: `XiaoAiHandoff.create/dialogIdFrom` round-trip asserting `source == xiaoai`.
- `hook/xiaoai/XiaoAiHookStateTest.kt`, `XiaoAiImagesTest.kt`: XiaoAI state/image helpers, no retained users.
- `hook/hyperos/HyperOsPowerPolicyTest.kt:7-18`: `HyperOsPowerPolicy.isAssistantShortcut` only.
- `hook/hyperos/HyperOsScreenSearchRequestTest.kt:14-58`: `HyperOsScreenSearchRequest.matches` only.
- `hook/hyperos/HyperOsLongPressGestureTest.kt:25-86`: `HyperOsLongPressGesture` only.
- `agent/tool/AgentColorOsMemoryToolsTest.kt:11-60`: `buildColorOsMemorySnapshotCommand`, `ColorOsMemoryBridgeProtocol` round-trips, `quoteColorOsMemoryIdentifier`; all removable.
- `core/ModuleConfigEntryPackagesTest.kt:7-16`: asserts `AGENT_RUNTIME_ENTRY_PACKAGES == {breeno, xiaoai}`, `XIAOAI_CORE_PROCESS`, `COLOROS_MEMORY_PACKAGE`.

Shared - MUST keep, edit OEM assertions: `agent/runtime/AgentRuntimeWireTest.kt:640-657` (legacyBreeno cases; keep file); `agent/runtime/EntrySurfaceGuardTest.kt:16-47` (breeno/xiaoai guard cases; keep file for ETA-voice cases :50-89); `hook/system/ContextualSearchCallerPolicyTest.kt:22-72` (pins Xiaomi/XiaoAI allowance; keep, narrow); `agent/tool/AgentToolRequirementsTest.kt:57-81` (coloros/memories assertions; keep, re-pin); `agent/model/AgentDeviceToolCatalogTest.kt:20-23`, `AgentSensitiveTranscriptTest.kt:15` (coloros presence; keep, re-pin); `agent/tool/RootlessDeviceToolsTest.kt:55-82` (orders ROOT_REQUIRED/DEVICE_UNSUPPORTED; keep, re-pin to notification-only semantics); `config/PrefsDefaultsTest.kt:9-37` (pins shared GESTURE_BAR:true; keep, drop DOUBLE_FINGER line only if key removed); `config/PowerAssistantTargetTest.kt` (generic; keep); `agent/tool/PersonalDataContentParserTest.kt` (generic parser incl. Chinese body fixture :28; keep per Unicode-fixture rule).

## (e) Breakage risks from naive OEM-branch removal (evidence)

1. XIAOAI branch hosts two installs (`ModuleMain.kt:102-115`): `HyperOsScreenSearchHooks.install` (:103-105) and `XiaoAiHooks.install` (:106-114) share the `XIAOAI_PACKAGE` case with different guards (`isCurrentPackageProcess` vs `isCurrentXiaoAiProcess` :141-145 incl `:core`). Deleting the case header removes both; piecemeal deletion must keep the survivor's guard consistent.
2. CTS permission gate embeds vendor allowances (`ContextualSearchHooks.kt:154-160`): `packages.contains(ModuleConfig.COLOR_DIRECT_PACKAGE)` (:155) and `allowsHyperOsCaller` (:156-160) sit inside retained `isAllowedContextualSearchUid`. Deleting constants alone breaks compilation of a retained file; edit branches in place, and decide whether `allowsHyperOsCaller` (entire predicate is `XIAOMI_LAUNCHER_PACKAGES || XIAOAI_PACKAGE`, `ContextualSearchCallerPolicy.kt:13-15`) becomes dead code.
3. Shared constants: `INTERCEPT_DEDUP_WINDOW_MS` is read by both `ColorDirectHooks.kt:97` and retained `PowerHooks.kt:145`; `CIRCLE_TO_SEARCH_ENTRYPOINT` is the default arg of retained `CircleToSearchInvoker.kt:57`. Keep both while any consumer lives.
4. `SystemServerHooks.kt:15-24` combine list: removing `HyperOsPowerHooks.install` (:23) is a one-element deletion; other five installs retained. The loss itself matters: `HyperOsPowerHooks` is the only power-key to Gemini/ETA path on HyperOS (`HyperOsPowerHooks.kt:41-46`); `PowerHooks` only handles the Oplus handler class (`PowerHooks.kt:49`). Deletion drops power-key takeover on Xiaomi devices with no generic fallback - product decision, not silent deletion.
5. Runtime sender gate (`AgentRuntimeService.kt:1077-1084`): `isMessageSenderAllowed` trusts `AGENT_RUNTIME_ENTRY_PACKAGES`. Emptying the set without replacing the check locks out every hook sender (fail-closed); removing the check opens it. Needs an explicit keep/replace call, not a constant deletion.
6. In-flight/persisted handoffs: `BreenoHooks` (:869,1085,1172) and `XiaoAiHooks` (:650,686) filter drains by source string; `EntrySurfaceGuard.from` returns null for unknown sources (`EntrySurfaceGuard.kt:124-130`); old bundles lacking the dismiss flag default on `source == breeno` (`AgentRuntimeWire.kt:470-476`). After branch removal, persisted Breeno/XiaoAI runs degrade to no-guard/keep-visible, changing drains and screenshot-exclusion (`:109-116`) for already-stored runs.
7. `search_personal_orders` is dual-source (`AgentStructuredDeviceTools.kt:72-131`): ColorOS-memory half (:75-89) + notification-history half (:90-121). Tool deletion kills the retained notification-orders path; `colorOs`-flag deletion flips `AgentToolCapabilities.kt:36-39` gating (`search_personal_orders && colorOs` root bypass) for the retained path. Split the function, keep the notification half.
8. Shared pref keys gate retained triggers: `GESTURE_BAR_CIRCLE_TO_SEARCH` is read by retained `ContextualSearchHooks.kt:159` and `SystemUiHooks.kt:54` besides `HyperOsSearchTrigger.kt:10` - key must stay after `hook/hyperos/` deletion. Same for `powerAssistantTarget()` machinery (retained readers in PowerHooks/AssistantManager); only `AGENT_CUSTOM_MODEL`/`AGENT_REQUIRE_PREFIX` are provably OEM-exclusive (readers: `BreenoHooks.kt:634,833-834`, `XiaoAiHooks.kt:494-495,542-543`, `SettingsScreen.kt:590,598`).
9. `PowerAssistantTarget.OEM` is retained semantics (AssistantManager `assistantBindingFor(OEM) == null`, `assistantSelectionAction` to RESTORE_OEM, `AssistantBindingTest.kt:13-76`): removing the enum variant breaks OEM-restore; the plan removes only the HyperOS power-key adapter, not the restore target.
10. QQ/WeChat/ColorOS-notes/recordings + alarm/timer/clipboard tools are per-tool entries in shared registries (`AgentDeviceToolCatalog.kt:149-164,200-207`, `AgentLocalTools.kt:1473-1495`, `AgentToolRequirements.kt:47-56`, `AgentSensitiveToolPolicy`, `AgentTraceFormatter`, `AgentAppState.kt:5037-5069`, `ToolIcons.kt`): each name must leave every registry or capability projection and UI lists diverge (e.g. `AgentToolRequirementsTest.kt:84` builds via `AgentToolCatalog.build`).

Open verification for execution stage, labeled [INFERENCE]: whether `AgentStructuredDeviceTools.COLOROS_CLOCK_PACKAGE` (:680) backs only display text or a root command path - check its readers before deleting the line.