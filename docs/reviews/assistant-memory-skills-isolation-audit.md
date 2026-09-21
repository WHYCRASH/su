# Assistant memory and Skills isolation / mid-run change audit

## Scope and verification level

Audits the current uncommitted working-tree source, including Runtime, tool execution, assistant edit UI, memory storage, Skills publish directories, Linux mounts, and existing tests. No real user MEMORY.md, skill data, or chat content read. This round only adds the audit document — no production code modified; nothing compiled, no Kotlin/Robolectric tests executed.

"Confirmed" below means the source contains the corresponding path — not that a leak reproduced on a device. File-level isolation must not be equated with a Root/Shell-bearing security sandbox.

## Conclusion

Ordinary memories with explicit assistantIds live in separate files; tool writes also carry revision conflict checks. But the runtime assistant identity is inconsistent, and the Skills globally visible directory plus hot-update permissions have definite design gaps. "Fully isolated across assistants" or "all mid-run changes safe" cannot currently be guaranteed.

### 1. High: in-flight memory injection / toggle checks and read/write targets are not the same assistant

Evidence:
- `agent/runtime/AgentRuntimeRunExecutor.kt:96,154,194,220-245`: captures the assistant at boot and passes `assistant.id` as memoryAssistantId; later memoryToolsEnabled, skillContextProvider, and memoryContextProvider call `AssistantRepository.active()` instead.
- `agent/tool/AgentLocalTools.kt:299-345`: permissions decided by a dynamic callback while read/mutate use the pinned memoryAssistantId.
- `agent/runtime/AgentRuntimeWire.kt:165-173`: RunRequest carries no assistantId; a task queued for preparation may also mismatch the persona prompt pinned in the request with later-selected memories.
- `ui/app/AgentAppState.kt:2715`: the chat page only guards the currently selected conversation's unpaused runs — not identity bindings for all global tasks.
- `ui/screens/assistants/AssistantsScreen.kt:97`: a separate direct-select path exists; the Repository itself has no Runtime mutual exclusion.

Scenario: while A's task still runs, the globe switches to B; the next request may inject B's memories while memory_get/write still operate on A; with A's memory off, B's memoryEnabled=true may even keep passing the permission check. Revisions validate content only, not assistant identity; they cannot substitute for isolation.

Fix principle: capture and persist assistantId at entry and pass it explicitly through the whole request/restore/tool chain; refresh only that ID's profile. A deleted assistant must fail closed, never fall back to active().

### 2. High: Skills share .visible; skill data lingers, crosses assistants, or gets deleted

Evidence:
- `agent/skill/SkillRuntime.kt:660-687`: all assistants publish to the same `skills/.visible`; cleanup keys on skill ID only.
- `agent/skill/SkillRuntime.kt:771-784`: sync meeting data only mkdirs — never copies that assistant's data nor switches the data pointer.
- `agent/terminal/ProotCommandBuilder.kt:38`, `ShellProcessSupervisor.kt:303-316`: Linux /var/minis/skills mounts the same global directory; legacy terminals bind to no owning assistant.
- `agent/skill/SkillRuntime.kt:692-704`: disabling a skill deletes `.assistant/<assistant>/<skill>` outright, never separating disable from data deletion.

Scenario: A and B both enable alpha; A's terminal writes records under `.visible/alpha/data`; after switching to B the alpha directory survives on equal ID; sync skips data, so B keeps seeing A's leftover files. If the new assistant never enables alpha, the directory is deleted outright and A's data may be lost with it. Nothing in the code writes .visible/data back to its owning assistant.

Fix principle: separate package code from assistant-private writable data; bind terminals / runs to assistant-dedicated roots instead of reusing a global writable directory in place; disabling hides capability but keeps data. Cross-assistant sharing must be an explicit option.

### 3. High: a Skill disabled mid-round is still readable through dedicated tools

Evidence:
- `agent/tool/AgentLocalTools.kt:947-995`: resolveRunSkill still falls back to indexService.findInstalledSkill (the global installed set) on failure.
- `agent/tool/AgentLocalTools.kt:1248-1262`: isVisibleInCurrentRun with a non-empty initial snapshot only consults the snapshot, never intersecting with the current enabled set.
- Same file's `liveSkillEntries` also has a 400ms cache; but this issue is not only 400ms: global fallback + the initial allowlist keep permitting reads indefinitely.

Scenario: alpha enabled when the task starts; disabled midway; the newest prompt and skills_list may stop showing alpha, yet skills_read(alpha) / skills_read_resource(alpha, ...) still find the global package and pass the initial-snapshot check.

Fix principle: execution must satisfy both the pinned assistant's current authorization and this run's approved version. Remove the global fallback; never treat the round-start snapshot as post-revocation authorization.

### 4. High: UI memory saves carry no revision; stale drafts can overwrite an assistant's newer writes

Evidence:
- `data/repository/AgentMemoryRepository.kt:85-101`: mutate checks revision; replaceAll writes unconditionally.
- `ui/screens/assistants/AssistantEditScreen.kt:88,139`: load keeps content only; save calls replaceAll(memoryDraft, assistantId).
- `ui/app/AgentAppState.kt:539-650`: the generic memory page's snapshot/save/clear use the default active ID; async tasks pin neither the draft's owning assistantId nor a revision.

Scenario: the user opens the memory edit page; the model then memory_writes appended content; the user's save of the stale draft overwrites that append with no conflict; the generic memory page additionally risks writing the old assistant's draft into the new assistant after a switch.

Fix principle: edit state carries assistantId and baseRevision; UI saves also use CAS, with conflicts prompting compare/merge instead of blind whole-file overwrite retries. Callbacks verify they still own the same edit object before updating the UI.

### 5. Medium-high: new, installed, and updated Skills take effect at inconsistent times

Evidence:
- Runtime dynamically injects the currently enabled skills on every model request and republishes.
- AgentLocalTools.isVisibleInCurrentRun: with a non-empty initial set, new IDs stay unavailable; with an empty initial set it degrades to the live set instead.
- AgentLocalTools.installResult:1287 calls AssistantRepository.enableSkills, which completes against the global active assistant rather than the task's owning assistant.
- installResult returns next_turn, but enableSkills -> update -> publishVisibleSkills publishes to the terminal directory immediately; the next model request's prompt also never filters this round's mutatedSkillIds.
- Same-ID updates have no run-level content-hash binding; .visible sync overwrites files in place.

Consequences: "prompt says enabled, skills_read refuses" is possible; so is "tool says next-turn-only, current terminal already readable/executable". Switching assistants mid-install may even enable the new skill for the wrong assistant.

Fix principle: one versioned capability snapshot serving prompts, list/read/resource, and terminals together. New/updated versions should take effect on the next user run; revocation intercepts immediately at execution. Never conflate a post-tool model request with the next user turn.

### 6. Medium-high: Skills publish copies are not transactional; updates and revocations can meet half-written files

Evidence:
- `SkillRuntime.publishVisibleSkills/bindSkillsToAssistant/syncSkillPackage/copySkillTree` never take the SkillMutationLock that install and read already share.
- Per-file overwrite with copy errors swallowed by runCatching; ordinary files removed from the source package are never cleaned.
- AssistantRepository.update refreshes only after config publish, swallowing refresh exceptions.

Consequences: enabled state and directory may disagree, scripts overwritten mid-run, stale files lingering. SkillLoader/ResourceReader boundaries and locks cannot protect a directory copier outside the same lock; already-running scripts are not revoked by file deletion either.

Fix principle: validated version directories + full staging publish + atomic pointer/binding; failures keep the old version and report explicitly. Data directories stand alone and are never deleted with code syncs.

### 7. Medium-high: deleting an assistant never closes its running tasks

Evidence: AssistantRepository.delete removes memory and skill directories but never cancels/waits for that assistant's runs; AgentMemoryRepository.storeFor never checks the profile still exists, and stale tools still hold memoryAssistantId; isEnabled/setEnabled on a missing ID fall back to active.

Consequences: stale tasks may recreate deleted memory directories or judge old-assistant tool permissions with the new assistant's toggles. The memory lock is a store-instance lock, so delete and reads/writes holding stale instances are not in one transaction.

Fix principle: assistant-lifecycle lock / deletion marker blocking new operations, cancelling or winding down owned runs, then deleting while refusing later recreation.

### 8. Conditionally high risk: imported assistant-ID path validation and normalization collisions

Evidence: `data/model/Assistant.kt:54-60` only substitutes characters and truncates, keeping `.`/`..`, and different inputs can normalize to the same name; `EtaBackupRepository.kt:608-613` checks profile IDs only for non-emptiness and equality with the original. The value feeds memory and `.assistant` directories, some paths with recursive deletion.

Normal new UUIDs never trigger this; externally imported / hand-edited indexes could land different assistants in the same directory or outside the expected assistant subtree. Validate legal IDs at import and repository entry, check post-normalization uniqueness plus canonical containment; never generate isolation keys with lossy substitution. No malicious backup crafted or imported for verification.

## Verified fine, with boundaries

- Ordinary memories with explicit assistantIds store in separate directories; the existing AgentMemoryIsolationTest only verifies that static scenario — not hot-switch safety.
- Within one assistant with no concurrent switches, tool memory_write uses the current revision and returns MEMORY_CONFLICT on conflict; new memories inject on the next model request and never rewrite already-sent provider requests.
- PromptBuilder returns a placeholder system message with memory/skills off alike, so toggling just those two switches never changes the system-message count; "pinned systemCount overwriting history" was not misjudged as their necessary consequence this round.
- Turning memory or skills off cannot recall already-issued requests; already-included material in old tool results, model answers, or summaries never vanishes via toggle either. skills_read body text enters ordinary conversation history; memory_get raw results have the existing sensitive-tool redaction policy, while answers/summaries the model wrote itself are another matter.
- ConversationEntity and RunRequest have no assistant-ownership field; switching assistants on one conversation never auto-clears history. Conversation-level isolation needs its own design — memory directories alone cannot do it.
- Root/arbitrary Shell is not an assistant-level security sandbox: even with dedicated tools fixed, the actual authorized readable scope of terminals still needs stating.

## Recommended fix / regression order

1. Pin assistantId on runs + edit drafts; unify permissions, memory injection/reads/writes, and install ownership; never fall back to active.
2. Fix Skills private data and terminal binding; revoking a Skill immediately revokes new reads; installs and updates take effect next run.
3. UI revision CAS; deletion lifecycle; atomic directory publish and content versions.
4. Regression: A/B concurrent tasks, switches while queued, memory UI/tool conflicts, list/read/resource/terminal consistency after Skill disable, initially-empty/non-empty skill sets, mid-run install/update, disable-then-enable keeping data, assistant deletion, illegal-ID imports.
5. Builds and test runs keep honoring the user's GitHub Actions authorization convention; this report claims no test run and no issue fixed.
