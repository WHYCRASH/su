# Technical implementation

The APK is a privileged system app shipped by the KernelSU/ReSukiSU NoMount module (`:app:assembleKsuModule`), plus an accessibility service, a notification listener, and a VoiceInteractionService for the ASSIST role. There is no Xposed module: no hooks, no scopes, no RemotePreferences, no framework channel. System integration happens through the plain Android digital-assistant setting and the privileged permissions the module grants (notably `WRITE_SECURE_SETTINGS`).

## Logging and Release trimming

su uses one four-level logging semantic backed by logcat via `AndroidAgentLogger`. Business code must never call `android.util.Log` directly.

| Level | Scope | Release |
| --- | --- | --- |
| `DEBUG` | Frequent normal flows, retry details, sizes and counts | Stripped entirely |
| `INFO` | Infrequent lifecycle events, structured summaries of privileged actions | Kept |
| `WARN` | Recoverable degradation, fallbacks, exhausted retries | Kept; high-frequency events must be throttled |
| `ERROR` | Current request or feature definitely cannot complete, critical invariant violated | Kept |

Cancellation and disabled features are never logged as `ERROR`. `debug` only accepts a lazy supplier, and the supplier must be pure observation code: no reflective writes, state changes, or other business side effects, because R8 in Release deletes the whole call.

No level may log prompts, request or response bodies, API keys, auth headers, cookies, tool arguments or results, raw commands, stdout/stderr, URIs, file paths, image contents, app manifests, or raw runtime identifiers. Exceptions log only their type by default and never string-interpolate `Throwable.message`; externally or model-generated names must first be converted into length- and charset-constrained safe tokens. Only exceptions confirmed to carry no user data may attach a full stack trace.

Release trimming treats `app/proguard-rules.pro` as the single executable source of truth, with the following rule boundaries:

- `-maximumremovedandroidloglevel 3 class io.github.mangi.eta.** { *; }` only removes Android `VERBOSE`/`DEBUG` in su's own code, not in dependencies.
- Precise `-assumenosideeffects` on `AgentLogger.debug(Function0)` and `AndroidAgentLogger.debug(Function0)`.
- No side-effect-free declarations for `INFO`/`WARN`/`ERROR`, and no wildcard trimming rules over `*Logger` or global `android.util.Log`.
- After every rule change, build both Debug and Release, and inspect the R8 configuration/usage, DEX log calls, and representative log strings.

This policy follows Android's official [R8 additional rule types](https://developer.android.com/topic/performance/app-optimization/additional-rule-types), [log information-disclosure protection](https://developer.android.com/privacy-and-security/risks/log-info-disclosure), AOSP [logging level conventions](https://source.android.com/docs/core/tests/debug/understanding-logging), the OWASP [runtime logging test](https://mas.owasp.org/MASTG/tests/android/MASVS-STORAGE/MASTG-TEST-0203/), and [CWE-532](https://cwe.mitre.org/data/definitions/532.html).

## Native assistant entry

The manifest registers a `VoiceInteractionService`, a separate-process `VoiceInteractionSessionService`, a full-screen `TYPE_APPLICATION_OVERLAY` assistant overlay, and the `RecognitionService` required for Android assistant-role eligibility. The settings page only opens the system digital-assistant picker; the current overlay does not request microphone permission.

The `VoiceInteractionSession` only serves the system entry point and closes its own UI; `EtaAssistantOverlayService` owns the full-screen window, the colored edge animation, and keyboard input. The window draws behind the status bar, navigation bar, and display cutout via `setFitInsetsTypes(0)`, while interactive content stays reachable through `WindowInsetsRulers.SafeDrawing` and `Ime`, avoiding truncating the edge-to-edge background by adding insets to the root container. Submitted text goes to `AgentRuntimeClient`; requests, streaming results, foreground-tool collapse, cancellation, and archiving reuse the existing runtime protocol. Before a foreground tool runs, su's own entry removes the window on the main thread and notifies the system session via `hide()`, and the runtime only continues once the view has actually detached. This flow runs no speech recognition or speech playback.

The `:voice`, `:voice_session`, and `:recognition` processes only initialize local preferences and never warm up the database or skills. `RecognitionService` keeps only the declaration needed for Android digital-assistant-role eligibility and is not called by the current overlay.

## Accessibility protection

"Force-keep accessibility" defaults to off. When on, `agent/accessibility/AccessibilityProtectionClient` enforces the service from inside the app process: it writes `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` / `Settings.Secure.ACCESSIBILITY_ENABLED` directly, using `android.permission.WRITE_SECURE_SETTINGS` held as a privileged system app. A plain APK install without the module cannot enforce it, and the toggle reports failure (`ControlResult` with `UNAVAILABLE`/`REJECTED`) instead of pretending protection is on. It only maintains su components, leaves other accessibility services alone, needs no app self-start, and never polls on a timer.

Turning the toggle off only stops protection; it never disables the user's current service for them. GUI tools still confirm a live service connection before executing; when protection is off or enforcement fails, the action fails explicitly instead of quietly rewriting accessibility settings via root or shell.

The toggle state is also mirrored in the Global setting `eta_accessibility_protection_enabled`, which stays the documented ADB kill switch. When the app control entry is unavailable, stop protection over ADB first, then turn the service off in system settings:

```bash
adb shell settings put global eta_accessibility_protection_enabled 0
```

Deleting that setting restores the default-off state.

At boot, `module/boot-completed.sh` re-applies the accessibility service (and the notification listener) via root `settings put secure`, so the service entry survives reboots even before the app process runs.

## Configuration

The app UI uses Miuix, with component and icon dependencies pinned centrally in `gradle/libs.versions.toml`. Feature icons use Material Icons Rounded; settings and management lists use uniform-size monochrome icons, brand icons keep their native colors, and status and selection actions follow theme semantics. Settings, permissions, skills, MCP, backup, and provider lists are grouped with cards and row spacing, and empty lists share the same lightweight hint. Provider type, model count, and built-in source appear as wrappable helper text, with the current choice marked by a check icon. Skill rows ellipsize the blurb by line count, the "more" menu offers the full description and a delete entry, and deletion still asks for confirmation. Tool cards clearly label actions that jump somewhere; pure intro cards open the full description on tap, and jumpable cards expose the description through an info button. The thinking indicator always uses the local Atom orbit icon, and shows the matching tool icon while a too…

The Linux environment page is organized into current environment, environment setup, files and directories, and extended tools. The top status card shows version, runtime mode, install stage, and result, with the primary action below the description; distro and runtime mode switch through a compact floating menu with explanations, or a static status when only the plain mode exists. Pop-up menus keep the Miuix default background scrim and plain-option highlight, closing on selection or outside tap. The workspace entry is always available, while shared folders and file browsing appear once the base environment is ready and extended tools once the base tools are ready. When root authorization lapses, the previous chroot choice is kept, and the user may read the authorization notes or switch to the standalone PRoot environment.

The configuration chain works as follows:

Root navigation uses the Miuix `NavDisplay` with a savable route stack; the swipe-back toggle drives `swipeDismiss` on each route live, while the predictive-back toggle applies through the `ApplicationInfo` system switch without rebuilding the main activity with a transition. The home session list stays under the chat stage and is revealed or tucked away by a dual-anchor horizontal drag state; gestures reuse Compose direction arbitration and child-component priority instead of stealing message scrolling, horizontal code blocks, the attachment bar, or text selection. The settings page and standard second-level pages share one adaptive scaffold: phones show a collapsible large title, wide screens switch to a small title with content constrained to a centered maximum width. Interface scaling overrides the main app's Compose density, but wide-screen detection keeps the pre-scale density so scaling never flips the phone/wide layout by mistake; the system-assistant overlay ignores interface scaling.

Appearance settings live in the existing `eta_settings` DataStore. The theme root uniformly resolves follow-system, light, dark, Monet dynamic color, accent color, and pure-black background, and bridges the system bars and Markdown to the same Material colors. The top bar captures scrolling content with the Miuix `LayerBackdrop` in either gaussian or progressive blur; with blur off, the top bar and chat input fall back to the plain theme surface. Page scrolling keeps Miuix overscroll bounce and edge haptics, and landscape safe areas are jointly constrained by the display cutout and navigation-bar insets.

- **su runtime configuration**: default thinking, web browsing, on-device direct access, sensitive-information reads, sensitive device operations, and terminal/file tools are stored in the app's private configuration. The runtime reads the current values at request start and before every tool call, so later runs follow the current configuration.

## On-device personal data

Personal-data retrieval reuses the "sensitive device information read" toggle; the model may read raw results when a tool is called, but tool arguments and results are never persisted into the session record. Each capability is pinned to a verified provider, projection, and sort order, and only accepts length-bounded keywords and result counts — arbitrary URIs, table names, or SQL are never exposed to the model.

Runtime prompts require the model to proactively call an exposed read-only tool when the user's goal would clearly benefit from on-device context. For broad tasks such as "about me", recent activity, habits and preferences, or personalized advice, the model should sample across several relevant sources by recency and representativeness before summarizing; an exposed tool means the user has already enabled that capability, so the model must not re-ask for authorization nor stop just because one source came back empty. When a dedicated tool does not exist, results are thin, or a data source is unavailable, and root shell, file, or terminal tools are exposed, the model keeps locating and read-checking the relevant app-private files and databases — identifying format and schema first, then running bounded queries — without modifying source data.

- Standard Android providers: gallery images, audio, shared files, calendar, contacts, call log, SMS, and download history.
- Personal context: location reads the latest system fix on demand; app activity and usage duration depend on the user's usage-access grant; alarms, timers, IME clipboard history, and Health Connect aggregates are queried through fixed-database read-only snapshots. Health tools only return aggregates for the requested window, never raw measurement series.
- Notification history: the system keeps no usable history of its own, so old records are never fabricated. After the user grants notification access, su stores titles, bodies, source packages, and timestamps in a standalone on-device database starting from the grant point, kept for 7 days and at most 1000 entries; query results are still stripped from the persisted session under the sensitive-tool rules.
- When the device lacks the app or a provider contract changes, tools return a structured unavailable error instead of guessing by traversing other apps' private directories.

## File vision

`read_image` is a general file-vision capability, exposed with the "terminal/file tools" toggle and independent of personal-data access. It accepts any local absolute path, file URI, or system gallery URI the user or another tool has explicitly provided; on-device paths are read via root. Root copies a single size-bounded file into the su temp cache, following symlinks to their real targets; before reaching the model, the image is scaled and compressed for that vision request only, so multiple full-size originals cannot bloat an OpenAI-compatible request body, and the original file is never modified. The temp file is deleted as soon as the current turn ends.

Runtime prompts and tool descriptions jointly require the model to call `read_image` at most once per turn. To look at several images, the model must consume the current image's vision result first and read the next image on the next turn, avoiding multiple tool images in one request that leave some OpenAI-compatible services hanging with no response for a long time.

## Built-in browser

`browser_use` is an agent browser running inside su on top of a shared off-screen WebView — not a thin call to the system `ACTION_VIEW`. It can load JavaScript pages without stealing the foreground, extract body text preserving title/paragraph/list/link structure, find and operate on page elements, submit forms, scroll, and screenshot; when the user wants to watch, the app mounts the same WebView for direct takeover. Opening links externally stays with the separate `open_uri` tool, and the two capabilities never mix.

su adds no extra URL, DNS, IP, host-count, method, redirect, or Service Worker interception to browser requests; pages load directly through the system WebView. The browser allows local content, mixed content, third-party cookies, autoplay media, and form submission; the system WebView and the Android platform's own protocol support, TLS verification, and permission behavior stay unchanged. Web tools can be turned off in settings.

## Terminal and files

With user authorization, `user` or `root` shell commands run to read and write files, list directories, run scripts, inspect logs, and edit configuration. Session shells keep cwd and environment variables, async tasks run in the background with segmented output reads. Terminal tool cards in chat expand to show and copy the full command actually executed; run logs still record only controlled summaries such as lengths, never the command body.

Long-lived background services (listening ports, web panels, scheduled jobs) are hosted through the terminal's `daemon_start`: the process leaves its command session's process group via setsid, output goes to a workspace log file, the task record is persisted, and nothing is reclaimed when a run, session, or page ends. After an app restart, live processes are claimed back by PID and ownership mark, `daemon_list`, `daemon_logs`, and `daemon_stop` inspect status, read logs, and stop tasks, and the user may also manage them from the terminal page's task panel. Daemon count and single log reads are capped; every task expires on device reboot.

Terminals come in three environments by purpose:

- `android` is the native Android shell for system, app, log, Magisk, and device-file work. Root sessions auto-discover the BusyBox provided by Magisk, KernelSU, or APatch, and backfill applets missing from the system PATH with a standalone `ash`.
- The Linux userland is either Alpine musl or Debian Trixie glibc; the choice persists and is shared by model tools, the block terminal, and the console, with a uniform `environment=linux` model-visible protocol. The base rootfs and base tools install in stages, and the base toolset excludes Python and Node.js; the Python profile installs uv first, then the latest stable Python globally via uv, while the Node.js profile installs the latest available stable release. SSH uses each distro's latest stable package, and APK analysis installs separately on either distro. The app only reads the completion marker written by the installer instead of re-checking symlinks, binaries, or execute bits inside the rootfs from a non-root process. su runs the selected environment via an isolated mount namespace plus root chroot; Linux executes in `/workspace`, mapped to su's Android work directory, with shared storage at `/sdcard`. It is not a security sandbox and does not replace the Android environment.
- On networks in mainland China, the Alpine APK index tries only the Alibaba Cloud and official CDN mirrors; the Debian main archive tries only the Tsinghua TUNA and official Debian mirrors, while security updates always use the official Debian source. The winning mirror is written back into the rootfs for later profile and tool installs; APT also disables HTTP pipelining, which tends to trigger connection resets, and enables retries. GitHub artifacts try a single fixed HTTPS download entry point before falling back to the official address, and every rootfs/artifact must still pass fixed size and SHA-256 checks.

The home overflow menu's "Open terminal" is the user-operated terminal, a block terminal by default that can switch to a PTY console mode when the BusyBox `script` applet is available: `script` allocates a pseudoterminal for the shell (grid size set via stty at startup, TERM advertised as xterm-256color), and the output byte stream is maintained as a character grid by a VT-subset screen buffer — supporting SGR colors and styles, cursor addressing, line/screen erase, scroll regions, the alternate buffer, and wide-character cells, with bounded scrollback; soft-keyboard input is captured through a hidden input field and written straight to stdin, Esc/Ctrl/Tab/arrows are covered by a key bar, and Ctrl combinations produce real control bytes. Each mode supports up to 6 concurrent sessions, and the status-bar session list uniformly offers create, switch, restart, and close; switching environments or leaving the page never reclaims live sessions, which the ViewModel holds until manually closed or the process dies. Both modes explicitly source `/etc/profile` and `~/.profile` at session start, so user CLIs installed on PATH by the installer run directly.

The block terminal is organized by command, output, and exit code, and only switches between Android and the currently selected Linux distro. It uses a dedicated persistent shell session, never shared with agent tool-call sessions; commands keep running in the background after leaving the page, output keeps accumulating, and returning shows the rest. ANSI SGR sequences in output render as colors and bold/italic/underline, `\r` is handled as line overwrite (progress bars keep only their final state), and other control sequences are dropped; copy and log scenarios use the escape-stripped plain text. The input box stays usable while a command runs and its contents go to the session's stdin — the status-line protocol has the shell itself print the exit-code marker merged into the same logical line as the command, so interactive commands (REPLs, `read`, and the like) never eat the marker while reading stdin.

Shared folders present a user-selected Android directory inside the Linux environment at `/workspace/mounts/<name>`, managed on the Linux tool-environment page. Mounts are never persisted as Android-global binds: each Linux session bind-mounts the currently configured source directories when its own mount namespace is created, and they are reclaimed with the namespace when the session ends, so configuration changes take effect on the next command or new session and no leftover mounts survive a reboot. Source paths and mount names go through normalization and forbidden-zone checks (critical system trees, the workspace, and the rootfs itself cannot be sources); emulated storage such as `/sdcard` does not support permission-bit changes inside Linux.

Files inside the rootfs are root-owned, and the Linux tool-environment page additionally offers read-only file browsing: directory listing and file reads run through one-shot root shells on the host path, paths are only lexically normalized and symlinks never resolved (link targets only gain Linux meaning inside the chroot); file preview is capped at 256 KB, and files containing NUL bytes are treated as binary with no preview.

The chat input bar can reference any regular file or folder under a local absolute path, shown after sending as the attachment name separate from the original request. su only writes the root-resolved canonical absolute path into the model context and never uploads, copies, or caches the original file; the model then reads it per task through the file or terminal tools. The system file picker resolves internal-storage documents and "recent" files convertible to local media-library paths; cloud drives and other sources that only offer `content://` URIs are never downgraded into uploads.

## Long-term memory

Cross-conversation long-term memory lives in a single `MEMORY.md` in the app's private directory, with no extra extraction model or background tidying jobs. The current conversation's primary model calls `memory_get` and `memory_write` as needed and owns deduplication, conflict correction, and expiry.

- **On-demand injection**: each turn only auto-provides the budgeted core-memory section (the `#`-level heading named by `CORE_HEADING` in `AgentMemoryContext.kt`), the heading index, and the file revision; detailed chapters are fetched by the model per task instead of stuffing the whole file into context every turn
- **Dynamic budget**: the core injection size derives from the current model context window; unknown windows are treated as 128K, always reserving room for history, tools, images, and replies
- **Atomic updates**: the file caps at 1 MiB UTF-8 bytes, the model updates sections with revisions and must re-read on conflict; the whole file is overwritten through `AtomicFile`, with the old content preserved on failure
- **User control**: the settings page shows usage and supports editing the full Markdown, clearing, or disabling memory; disabling deletes nothing, but the runtime immediately stops injecting and rejects new memory tool calls

## Per-session Thinking Effort

A chat session stores its own `ReasoningEffort`, and the input bar shows the effective subset of `Thinking · Off / Default / Low / Medium / High / XHigh / Max` for the current provider, endpoint, and model capability. Models without reasoning show no entry; forced-reasoning models with no adjustable level show only a non-clickable `Thinking · Default`. After a model switch or remote capability refresh, a saved level that is no longer legal is clipped down to the nearest valid level, falling back to `Default` when no comparable level exists.

Capability resolution tries precise remote metadata first, then the built-in model catalog, then provider and model-family rules, and finally degrades safely. `Default` preserves the vendor or advanced-custom-body default behavior; an explicit level applies after the request body merge, so the session choice is the final override. Room, the runtime bundle, and external archives simultaneously keep the legacy `thinkingEnabled` boolean projection, with legacy `true`/`false` interpreted as `Default`/`Off`; a forced-reasoning model receiving `Off` reports a configuration error directly.

## Chat streaming render

The model's SSE text deltas first merge in the app state layer on a 50 ms cadence, cutting high-frequency list-state writes; thinking, tool-call, and chunk-boundary events still flush immediately with order preserved. Chat rendering uses an incremental Markdown AST but never hands a large not-yet-shown network backlog to layout at once: the parser appends at most 12 Unicode graphemes per batch, yielding one beat for reflow between batches, decoupling supply pace from reveal pace; message height always follows reveal progress, and leading parsing never stretches the answer early. The input uses state-based `TextFieldState` to hold the edit buffer inside the component, so per-keystroke edits never write back to chat-page state nor pass through the legacy `CoreTextField` pipeline.

Markdown blank lines only shape block structure and never accumulate visible height by source count; stable and streaming rendering share one block-level layout that assigns spacing by the relation between body, headings, lists, quotes, code, and tables, with no extra push-down before the first block. Block gaps use typography units that scale with the system font, keeping roughly one body character between plain paragraphs, widening before headings and tightening after, so hierarchy stays legible at large font sizes. The runtime also requires the final answer to use restrained, valid GFM: normal exchanges prefer short paragraphs, using headings, lists, and tables only where they help grouping, steps, or comparison, keeping each table row independent and never styling a whole bolded sentence as a heading.

The typewriter effect runs on a single answer-level frame clock. Paragraphs, headings, code blocks, and table cells first complete one real layout, then a `DrawModifierNode` clips the `TextLayoutResult` at grapheme boundaries; per-frame advance only invalidates drawing and never rebuilds the Markdown AST, `AnnotatedString`, or text layout. Reveal speed adapts with backlog (48–240 graphemes/sec), allowing several graphemes per frame under backlog so stable output never lags the model. When inline syntax closing (bold, inline code, folded links) shortens the rendered text, reveal progress still advances monotonically and never re-types shown text. The prefix path accumulates by grapheme deltas and only triggers one measurement plus message-height growth when reveal crosses into a new line, so hidden text never pushes the current answer out of view early. Emoji ZWJ sequences, skin-tone modifiers, combining marks, flags, and surrogate pairs always render as whole graphemes, never split mid-UTF-16.

Bottom-follow only disengages on a real user drag and only scrolls when the message actually grows and the bottom sentinel leaves the viewport; pure network status updates never retrigger scrolling. After the network ends, bottom-follow keeps covering parsing, text reveal, and the stable-layout switch, finishing once the trailing action row lays out and the list reaches the bottom; dragging into history stops follow immediately, and manually expanding a finished message never triggers follow-to-bottom. Once parsing catches up and the reveal queue drains, the answer switches to stable Markdown with full link parsing and text selection restored. Token-usage events only touch the usage field and never the message's streaming flag, avoiding streaming/static view flapping at turn boundaries.

Returning to the chat page replays from the full text present at restore as the reveal baseline; follow-up incremental animation only starts after that baseline's Markdown finishes layout instead of guessing parse completion by frame count. Finished replies, thinking, and tool records render stable content directly; the work log of a finished task starts collapsed on first open, and the user's manual expand/collapse choice persists per entry. Off-layout text nodes catch up immediately without blocking other nodes' reveal queues. Render-complete callbacks must match the current full text and terminal state and must never let a stale snapshot finish newer streaming content early.

## Power and overhead

Stay minimal and never add extra load to the system:

- No polling, no continuous log writing
- Accessibility protection defaults to off; when on, it only writes Secure Settings to re-assert su's own service entry, with backoff on contention
- High-frequency success paths use `DEBUG`; debuggable in Debug builds, deterministically stripped by R8 in Release

## Expected behavior

Setting su as the digital assistant is the plain Android setting (Settings -> Apps -> Default apps -> Digital assistant); the power button follows that. With su as the default digital assistant, the assistant entry opens the edge-to-edge full-screen overlay and auto-focuses the keyboard input; before the overlay and IME appear, the entry prepares a screenshot that is only sent as the next message's image context after the user selects it. After the user submits text, tool execution, streaming results, and archiving stay with the agent runtime in the main process; this flow runs no ASR or TTS.

The configuration UI keeps agent and local-tool toggles in the app's private configuration; the runtime re-reads them before execution, so later runs follow the current configuration.
