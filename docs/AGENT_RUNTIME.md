# Agent Runtime

su's Agent Runtime organizes a single user input into model turns, tool execution, and a persistable incremental transcript. It runs in the module's own process; hook processes only recognize the entry point, send the request, and receive the result.

## Code boundaries

- `AgentModelClient`: stable facade, configuration, and cross-process session DTOs.
- `AgentLoop`: the state machine for a single run; no dependency on Android services, Room, or Compose.
- `AgentPromptBuilder`: system constraints, skill index, history, and the current user input.
- `AgentConversationCodec`: conversion between provider JSON and the stable session DTOs.
- `AgentToolCatalog` and its grouping directories: model-visible tool schemas; never executes tools.
- `AgentTraceFormatter`: only produces redacted summaries safe to display and log.
- `AgentProviderClient`: protocol boundary for OpenAI-compatible, Anthropic, and similar providers.
- `AgentRunController`: cancellation, pausing, and the steering queue.
- `AgentRuntimeSession`: holds one reply channel per run and guarantees a single final result.
- `AgentRuntimeRunExecutor`: the single exception boundary from skill/tool initialization through model execution, resource cleanup, and terminal-state commit.
- `AgentRuntimeService`: Android lifecycle, entry-point IPC, and overlay hosting; no longer inlines the agent execution loop.
- `ShellProcessSupervisor`: admission, dedicated process groups, cancellation, and reaping for Android/Alpine/Debian shell processes; the terminal protocol does not own process-ownership details.

## Loop semantics

A turn is "one assistant response plus the complete tool batch submitted by that response". The loop follows this order:

```text
pending steering
→ provider response
→ assistant history
→ tool batch (serial, in model order)
→ contiguous tool results
→ optional image observations
→ next turn / final result
```

Key invariants:

- Steering is queued one item at a time by default. Steering that arrives mid-stream interrupts the current model SSE, keeps already-written content, and is injected in the next turn; the tool batch still runs to completion — running tools are never cancelled.
- All tool results within the same assistant message must be written contiguously; image observations that the provider's native tool-result image support cannot carry are appended afterwards.
- When `finish_reason=length` or `max_tokens` arrives together with tool calls, none of the potentially truncated parameters are executed; a structured error result is written for each call so the model can re-plan.
- Tools run only for explicit `tool_calls` / `tool_use` stop reasons; calls smuggled inside `stop`, content-filter, or unknown stop reasons are rejected as protocol contradictions.
- Tool parameters are validated before execution against the JSON Schema actually issued for that turn, supporting local `$ref`, combinators, conditional schemas, and common object/array/string/number constraints; this checks the call contract only and carries no permission-confirmation or extra security policy.
- The transcript returns only the assistant, tool, and in-flight steering messages added by this run — never old history or the run's initial user message.
- GUI and terminal tools stay serial. Neither Android foreground state nor conversational shells have generally safe-to-parallelize semantics.
- A single run has no fixed turn count or total deadline; it ends when the model stops naturally, the user cancels, or an unrecoverable error terminates it.
- Cancel is a termination signal; pause is a checkpoint block; steering is next-turn input. Steering that arrives while paused only queues — it neither unpauses nor interrupts the current request.
- The cancel fast path on the main thread performs only the atomic terminal transition and resource shutdown: the shared browser verifies ownership by runId; the terminal immediately closes admission of new processes and, in the background, terminates synchronous commands, sessions, and async jobs by dedicated process group before finishing thread and stream reclamation. On Android, if `setsid` or the PID/PGID ownership handshake is unavailable, termination fails closed; only non-Android test environments may fall back to a parent-tree snapshot. Termination also verifies a random ownership token so a stale, reused PGID can never kill unrelated processes.
- The final steering check atomically closes the intake; once the loop has returned, no unconsumed follow-up instruction is misreported as received. Follow-up instructions never lift a pause.
- New-run replacement, user cancellation, and normal completion all compete for a single terminal state through the `AgentRuntimeSession` `RUNNING → COMMITTING → TERMINAL` state machine; the commit winner owns the outbox, archiving, and final delivery exclusively, while clients wait for the final result or a Binder disconnect rather than cancelling the task by wait duration.
- Entry requests can only narrow tool capabilities, never self-authorize. The runtime trims the configuration when a run starts, re-reads the user toggles before every browser, terminal, and device-tool execution, and strips `reasoning`/`thinking` override fields from custom request bodies when thinking is off.
- Device tools are split into direct, sensitive-read, and sensitive-operation tools, all currently on by default. The runtime re-reads the user toggles before every execution; once the toggle allows it and the parameters satisfy the tool schema, execution proceeds — the runtime no longer matches the user's original wording and maintains no key-package, system-app, or Settings-key blocklist.
- Sending messages in chat apps has no dedicated tool, parameter protocol, or extra policy layer; it works entirely through generic GUI tools observing and operating the app UI.
- Notifications, SMS verification codes, Wi-Fi credentials, and logs are ephemeral sensitive tool data. The current model turn may use the raw values, but the persisted transcript redacts the corresponding tool parameters and results together so they never reach the conversation database or subsequent IPC.

## Provider protocols

The default base prompt defines su as an AI assistant running on an Android device: it answers questions, converses with the user, and can inspect the device and perform actions through tools; answers use the user's language and stay concise, direct, and natural. The default body text is `BuiltinProviders.DEFAULT_SYSTEM_PROMPT`; an empty provider prompt falls back to that default, while an existing non-empty configuration keeps its value.

The runtime injects the su identity independently of provider-customized prompts, labelling the actual `ModelConfig.model` value for this run as the "currently configured model" and updating it with the run's configuration. It never infers the current model from the display name or history messages, nor infers deployment version, knowledge cutoff, or capabilities from it. The general conversation rules require direct answers to everyday questions, clarification only when key parameters are missing, detail matched to the user's needs, and honest reporting of tool outcomes; personalized analysis separates fact from speculation and never asserts personality, motives, or mental states from scattered records. System rules for tools, memory, and skills are still appended based on runtime conditions.

An OpenAI-compatible provider can select `Chat Completions` or `Responses API` on its configuration page. Fresh installs and resets default the built-in OpenAI entry to Responses; providers already in the database are never overwritten by the default. Custom providers and other built-in providers still default to Chat Completions.

At the protocol boundary, Chat Completions merges all `system` content from the current context, in order, into a single leading system message, accommodating model chat templates that require the system message first. Responses instead projects the full `system`/`developer` context into `instructions` and rebuilds persisted history as input items with `type: "message"`.

Responses requests always use `stream:true` and `store:false` and never send `previous_response_id`. Within one run, the runtime replays the complete output items returned by the provider verbatim across tool turns; opaque data such as encrypted reasoning and server-side tool state therefore lives only in memory and never enters the IPC transcript, Room, logs, or run archives. Persisted conversations keep only normalized answers, visible reasoning content, and su tool records, and later runs rebuild context from that stable data.

When a compatible API omits `output` in `response.completed` or returns an empty array, the runtime completes the current turn using only the standard text, reasoning summaries, and function-call deltas already received on the same SSE stream; a non-empty terminal state always wins, and a locally recovered result never impersonates the provider's opaque output items.

The reasoning UI shows the reasoning summary returned by the provider; it is not the raw chain of thought and is never fabricated by su. When a compatible provider returns `reasoning_text` under the Responses protocol, the runtime surfaces it as visible reasoning content. Responses backfills reasoning capability only for models that exactly match the official catalog and have not been remotely flagged `reasoning:false`; it never assumes every model supports reasoning from the endpoint type alone.

Chat Completions, Responses, and Anthropic Messages are projected at the provider boundary into a uniform body/thinking/tool-block stream keyed by `round + block index`. Responses additionally uses `item_id/output_index/content_index` to separate multiple output items in one turn; Chat Completions opens a new block when the delta type changes; Anthropic preserves `content_block.index` directly. Once the body/thinking/tool type switches, the previous visible block is finalized immediately, and later same-type content never backfills across a tool card into an older block. The terminal state carries a replacement only when the provider's authoritative content disagrees with already-streamed content, instead of overwriting the last block with the whole turn's aggregated text.

Server-side web search is an independent Responses-provider toggle, off by default. When on, the request only gains the hosted `web_search` tool; search start and finish are projected to the UI as standalone run events and never enter the su local-tool executor. `url_citation` entries in the final answer are deduplicated and converted to clickable Markdown references, degrading to a source list at the end of the answer when offsets are invalid. File search, code interpreter, provider-hosted MCP, and other hosted tools are currently not integrated.

### Model waits and retries

Model streaming uses dedicated HTTP settings: 15 s connect, 30 s write, 5 min read; the read limit guards idle waits for new data, not the whole task. MCP, model listing, and downloads keep their own settings. The model HTTP client disables low-level connection retries; the loop orchestrates the run's bounded retries.

Interrupted connections, timeouts, premature EOF, transient rate limits, and some server errors retry up to 3 times with 2, 4, and 8 s waits; each successful model turn earns a fresh budget. Non-transient failures — authentication, quota, billing, certificates, protocol shape — never retry automatically. Retry waits are cancellable and honor pause checkpoints. Steering arriving mid-stream interrupts the current model request; steering during a tool batch still waits for the batch to finish before injection.

Failed attempts commit no assistant history and execute no local tool calls from that attempt; completed tool results, the current turn's tool schemas, and screenshots stay unchanged across the retry. Retries use a fresh display turn; the failed partial output stays in the run trace marked as retried, later output is never spliced onto the old block, and the final reasoning summary excludes superseded failed attempts. Retry events persist through the existing IPC, checkpoint, and archive encodings, and recovery replay never re-executes tools. If the provider already reported a hosted tool as started, the failed attempt does not retry automatically, avoiding duplicate server-side operations.

## MCP tools

su acts directly as an MCP client connected to remote Streamable HTTP servers, without tying protocol capability to any model provider. It currently prefers the stateless `2026-07-28` protocol while remaining compatible with `2025-11-25` services that require `initialize` and sessions; only `tools/list` and `tools/call` are integrated — resources, prompts, tasks, stdio, OAuth, interactive follow-up input, and provider-hosted MCP are not supported yet.

Tools are off by default, and a server can be disabled as a whole. When a server is added, its tool catalog is discovered and cached first, and the user enables entries one by one; tools not marked read-only need an extra confirmation. Modern service catalogs expire by `ttlMs` and refresh before the next run; legacy catalogs refresh manually. Each run start freezes the enabled catalog together with the bearer token and generates server-namespaced model tool names, so later settings changes cannot alter the schema or account of a running execution. su projects complex schemas to the model as-is — including `$ref`, combinators, and conditional keywords — rather than disabling tools for them, and validates calls against the same schema beforehand; `x-mcp-header` parameters on modern Streamable HTTP are mapped to request headers in sync.

MCP addresses are configured directly by the user; HTTP, HTTPS, LAN, and loopback addresses share one connection path and inherit the shared OkHttp client's default redirect and timeout behavior; HTTP sends tokens, tool parameters, and results in cleartext. Bearer tokens are encrypted with the Android Keystore and stored on-device. Raw MCP parameters and results live only for the current turn; persisted transcripts, run checkpoints, and archives keep only redacted records. Text, structured results, images, page counts, and per-run tool-call budgets apply independently, and unsupported or over-budget results carry an explicit marker. Cancelling a run immediately closes new calls and in-flight HTTP requests; legacy session release is asynchronous best-effort and never blocks the cancelling thread.

## Long-term memory

Long-term memory lives in a single `MEMORY.md` in the app's private directory. The file is UTF-8 with a 1 MiB safety cap; the store applies changes under an in-process lock and overwrites the whole file through `AtomicFile`. Model writes carry the SHA-256 revision of the content they saw; a revision mismatch returns `MEMORY_CONFLICT` instead of clobbering a concurrent update.

Each run injects into the system background only the in-budget content under the literal `# Core Memory` heading, the level-1/level-2 heading index, and the revision. The core budget is `min(32000, max(4000, contextWindow / 16))` characters, assuming a 128K window when the model window is unknown. Without a `# Core Memory` heading, no body text is injected automatically. Everything else is read via `memory_get` by line page or text search, at most 32000 characters per call.

`memory_write` supports line-range replacement, standalone section appends, and clearing; one model-generated write carries at most 3500 characters, while manual user edits on the settings page are exempt from that per-tool limit. Turning memory off deletes nothing; later runs simply stop injecting it or exposing its tools, and an already-started run re-checks the toggle before every memory-tool execution.

Memory content is editable background only and carries no instruction priority. Raw memory-tool parameters and results are available to the current agent loop, but the corresponding tool calls are fully redacted in the persisted transcript; run events keep only the operation type, line/byte counts, and error codes — never body text or query terms.

## Local tool capability contract

`AgentToolRequirements` declares each local tool's `NONE / PARTIAL / REQUIRED` root requirement plus accessibility, normal system-grant, and ROM conditions; a tool with no registered metadata can never enter the model catalog. `AgentToolCapabilities` snapshots device conditions per turn, and the same projected schema backs both provider declarations and parameter validation. Metadata is su-internal and never extends the provider protocol. UI capability cards link to real tool IDs, and "all capabilities" only changes presentation.

Without root, exclusive tools are removed entirely; the hybrid terminal only advertises `identity=user`, and the default device path and model hints adjust in sync. The executor re-checks current root and parameters, and stale calls return `ROOT_REQUIRED`. Ordinary foreground intents need no accessibility; screenshots, nodes, gestures, input, and conditional waits need a live service connection, with a limited repair path kept where system protection is on. Current notifications come from the connected notification-listener service — a disconnect returns an explicit error rather than substituting history. User selections persist through the existing local-agent configuration and RemotePreferences coordination path; capability changes never rewrite saved toggles.

Root probing runs on an IO thread: when `su` exists, it requests once on first sight, waits at most 30 s, and treats only UID 0 as usable; denial and timeout never re-prompt, and the user can retry manually from the system-enhancement settings. The LSPosed connection is judged independently and never substitutes for root authorization.

## Terminal environments

The `terminal` `environment` cleanly separates device control from general Linux tooling and defaults to `android`:

- `android` keeps using the system shell. The `user` identity never escalates; the `root` identity probes for Magisk, KernelSU, APatch, or the system BusyBox inside `su` and prefers a standalone `ash`, so BusyBox applets need no PATH pre-registration. The legacy `run_command`, file read/write, and directory operations keep this environment, preserving existing Android path and command semantics.
- `linux` resolves the user's chosen distribution and backend. chroot keeps the existing rootfs, dedicated mount namespace, `/data/local/tmp/eta` workspace, and privileged mounts. New PRoot environments and plain workspaces use a `filesDir/terminal-user` directory owned exclusively by the app UID, avoiding the ownership restrictions of the old root directory; existing plain environments stay where they are, with all paths resolved uniformly by `TerminalPrivateStorage` and `/workspace` mapped to that private workspace. Only shared directories the app is allowed to access are mapped; imports and exports still work after "all-files access" is denied. Simulated root inside Linux is not Android root, and neither backend is an isolation security sandbox.
- Established sessions and tasks remember their backend and actual rootfs/workspace and never switch automatically when root comes or goes. The backend and host-workspace fields on persisted task records are optional for backward compatibility with old records. Gaining root never migrates PRoot; losing root never deletes chroot or changes file ownership.
- Plain Android shells, file read/write, and image reads use the app UID; the root user keeps the pre-existing privileged paths. Selector files that cannot be accessed directly are imported into the workspace through a bounded copy; directory selection can never impersonate a live-accessible path.

The user picks one current Linux distribution between Alpine and Debian, and models and terminals share it through `environment=linux`. Base-environment installation and base-tool installation are two separate steps: the installer downloads a rootfs pinned by version, size, and SHA-256, unpacks it in a temporary directory, and only writes the base-complete marker after checks pass; PRoot's streaming unpack validates archive paths and links and supports cancellation and failure cleanup; the user then installs a base toolset containing only generic commands. The Python profile installs only uv, after which uv installs the latest stable Python under `/opt/eta/python` and links the global commands into `/usr/local/bin`. The Node.js profile installs the latest stable upstream ARM64/x64 build on Debian and the `nodejs-current` package from the stable branch on Alpine; SSH uses the latest stable package from the selected distribution. The app side reads only the installer completion markers and no longer re-checks symlinks, binaries, or execute bits inside the rootfs. On networks in mainland China, Alpine uses the Alibaba Cloud mirror, Debian's main repository uses the Tsinghua TUNA mirror with security updates from the official Debian source, each keeping only the official main repository as a failure fallback; APT additionally enables retries and disables HTTP pipelining.

APK analysis shows as an optional profile on both Alpine and Debian. JADX, Apktool, smali, and baksmali use pinned official release URLs, sizes, and SHA-256 hashes for the current latest stable versions, entering the app-writable cache staging only after full verification; download or unpack staging directories must never sit inside a root-created Linux management directory. GitHub artifacts try one HTTPS download entry point first, then fall back to the official address, but only bytes fully matching the official manifest SHA-256 are accepted. JADX extracts only the CLI scripts, runtime libraries, and licenses, and the current version switches atomically only after every command verifies. The profile installs `openjdk25-jdk` on Alpine and `openjdk-25-jdk-headless` on Debian, but no global Gradle, Android SDK, or NDK. Because Google's Linux SDK, AAPT2, and NDK host tools only ship x86_64 builds, a phone ARM64 chroot cannot natively form an officially supported complete Android build chain; `apktool build` therefore reliably refuses, while decoding, code viewing, and standalone smali assembly/disassembly are unaffected.

## Background execution lifecycle

`AgentExecutionService` uses the `specialUse` foreground type and holds task references for the current agent run, plain terminals, and PRoot background processes. Leaving the page only detaches the UI; the service is released when the last task ends, and the stop action in the notification reclaims exactly the tasks it holds. Plain background tasks keep their host tracer and output readers and cannot outlive the app lifecycle the way a root daemon can. The root daemon keeps its pre-existing independent lifecycle — cleaning up plain tasks never bulk-stops root daemons. The root user's pre-existing runtime binding path keeps working even when the new foreground service is restricted from starting.

The service uses `START_NOT_STICKY`: it never replays commands automatically after the system force-stops or restarts it. Denied notification permission does not directly block a legitimate foreground start, but system background-start restrictions and device-manufacturer process-reclamation policies still apply.

## Context and continuation

The app writes the current user message into the conversation history before issuing the request, so the transcript returned by the runtime must keep its "incremental" semantics. Follow-up requests to a finished run rebuild context through `AgentContinuationBuilder` in this order:

```text
old history
→ original user message
→ complete incremental transcript
→ new follow-up message
```

Images travel only with the current model turn that needs them; the persisted transcript drops data URLs and writes a stable omission note so screenshot base64 cannot bloat Binder, Room, and follow-up contexts at once. External-entry archives may additionally keep a bounded thumbnail to restore the user-message UI, but thumbnails never re-enter model history. Launch requests are validated against the actual `Parcel` size before sending and are explicitly rejected above 768 KiB with a prompt to reduce image count or resolution. Run-archive transcripts, conversation-context checkpoints, and single-result IPC transcripts are each capped at 1,000,000 characters. Launch-request history and `MSG_RESULT` transcripts travel via read-only file descriptors instead of the Binder transaction buffer; outbox batch drains keep a tighter per-item budget so even 8 queued results in the worst case stay within the Binder transaction budget. Any capacity compaction inserts an explicit su system notice ahead of the retained history rather than passing trimmed transcripts off as complete context. Conversation metadata, per-message display rows, and bounded context checkpoints are stored separately; the conversation-list query never reads context bodies, and startup never blocks full conversation recovery on a single long conversation.

Overlays starting a continuation from a finished result carry only the newly added prompt supplement in the handoff, never accumulating copies of old supplements. When the app returns to the foreground it drains the outbox, writing the user message and the incremental transcript back into history together.

Context auto-compaction and cross-run, cross-provider opaque reasoning state are not implemented yet; Responses output items replay only within the current run and cannot serve as persisted conversation state.

## Skill installation boundaries

Skill installation tools are always offered to the model and are no longer exposed or executed based on fixed keywords in the top-level user input. Web pages, repository READMEs, and installed skills remain data only and cannot change tool parameters or execution boundaries.

- AI installation only visits public GitHub HTTPS addresses; the curated default comes from `skills/.curated` in `openai/skills`. Installed paths must come from the current run's own inspection of that repository and ref, at most 20 entries.
- Local ZIPs are read on the Skills page through the system file picker, without requesting shared-storage permission or copying archives into a public directory; each ZIP may contain exactly one skill.
- GitHub downloads and local ZIPs share a restricted unpack-and-verify pipeline: path traversal, absolute paths, duplicate entries, nested skills, illegal frontmatter, and inputs exceeding the entry-count, single-file, archive, or total-decompression budgets are all rejected.
- Installation fully validates in an app-private temporary directory before committing to the real skills directory. Filesystem and Room changes are coordinated by a durable transaction log that recovers on the next change after an abnormal process exit; any failed step of a batch install rolls back. Same-name user skills stay untouched by default; a single-conflict GitHub replacement binds repository, commit, path, and skill ID and can be retried precisely within the same run; built-in skills can never be overwritten by an import bundle.
- Installation only saves files, registers the index, and enables by default — it never executes `scripts/` and never flips terminal/file-tool toggles. The current turn's skill index is already frozen before the model call, so a newly installed skill becomes available starting with the next conversation turn.

An installed skill's companion text resources are read through a dedicated bounded read tool that re-checks relative paths, canonical roots, UTF-8, and sizes; script and binary assets can neither be executed through it nor smuggled into context as unbounded text.

Pending-confirmation results and external-entry archives persist the transcript to Room together with the run. Database 6 → 7 backfills transcripts for old records with an explicit non-destructive migration, 7 → 8 adds an applied-run marker to conversations, and 10 → 11 moves conversation contexts into standalone bounded checkpoints while cleaning up the old large columns. Recovery idempotence no longer guesses by comparing history tails; save tasks serialize strictly in call order, and the outbox is ACKed only after the snapshot carrying the matching marker lands on disk. Old 6.x results can still synthesize compatible history from their existing assistant content.

The main screen's `AgentAppState` is held by an activity-scoped ViewModel: configuration changes only rebuild the Compose UI without replacing the client waiting on the runtime. User messages commit to Room before launching a run that may cause on-device side effects. The runtime maintains an append-only in-flight checkpoint for the app conversation: text deltas merge within bounds, structural boundaries land on disk before they are published; block ends normally save only boundaries and character counts, saving replacement body text only when the terminal state corrects streamed content. Raw tool-call parameter deltas and raw results never enter this log, while UI-visible parameter summaries, redacted terminal commands, and result summaries persist with the tool state. The terminal state seals the checkpoint before committing the outbox, and outbox and checkpoint are deleted together only after the conversation lands in Room and the result is ACKed; time- or capacity-based outbox trimming deletes the matching terminal checkpoint as a pair.

On recovery, the app reconciles with `checkpoint + outbox + active session` uniformly instead of inferring run state from process changes. With an outbox present, tool traces recover first and the terminal result finalizes; while the runtime is still active, the new UI first restores the in-memory safe events as a whole, then subscribes to live events and the final result; only a run with neither terminal state nor activity is marked interrupted. Recovery never replays tools automatically and never appends a truncated assistant reply into later model history.

Re-subscription reuses the existing attach response as the history-replay end boundary: within one conversation lock, the runtime sends safe history, then the success response, then joins the live subscription — live events and terminal states cannot cross this boundary. The app client buffers history before the response and delivers it to the UI in one shot, and the UI rebuilds the run's message projection in a single state snapshot; only content newer than the boundary enters live updates. If the old service sent the terminal state first, the client delivers the buffered history before the result. Pre-recovery projections that can be rebuilt are discarded, keeping the original user request and supplement content with no matching replay event, so user input is neither duplicated nor lost. Task terminal states are independent of text-rendering states; the final result closes any unclosed text markers, and tool records without results show an unknown state rather than a fabricated success.

## Verification

Core regression tests:

- `AgentModelClientLoopTest`
- `AgentConversationCodecTest`
- `AgentRunControllerTest`
- `AgentContinuationBuilderTest`
- `AgentRuntimePolicyTest`
- `AgentRuntimeSessionTest`
- `AgentRunCheckpointStoreTest`
- `AgentRunMessageProjectorTest`
- `AgentToolCatalogTest`
- `McpProtocolValidationTest`
- `McpRunContextTest`
- `AgentMemoryStoreTest`
- `AgentMemoryContextBuilderTest`
- `EtaDatabaseMigrationTest`

Final verification still runs the project's unified command:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```
