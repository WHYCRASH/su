# Joint fixes re-audit (source)

The audit target is the current uncommitted working tree; the baseline is still `994ad3a`. Read-only check of implementation against the original audit items; nothing compiled, no tests run, no production code modified. "Confirmed" below means the source path exists — not that anything reproduced on a real device.

## Conclusion

Most of the primary leak paths from the original audit are now closed fail-closed: tasks no longer rebind to the global assistant mid-run, Skills no longer share one writable `.visible`, and conversation models no longer fall back across providers on equal `modelId`.

"Assistants/Skills fully isolated" still cannot be claimed, nor can this batch be called releasable. The hot path introduces new correctness risks (empty-index deauthorization, lock ordering), a few identities still fall back to `active()`, and test and production behavior do not fully agree.

## Primary paths genuinely closed

1. Runtime takes the assistant from `request.assistantId` / `config.assistantId`; missing identity never falls back to `active()`. Memory reads/writes, install ownership, and permission checks use the pinned ID.
2. Memory UI / assistant-edit saves carry `assistantId + revision`; stale drafts can no longer `replaceAll` unconditionally. Deletion writes a tombstone; stale Store handles cannot recreate memories.
3. Assistant IDs are strictly validated, no more lossy substitution. Backups validate profile / memory keys before applying.
4. Agent runs use isolated `.runs/<uuid>/skills` snapshots; private data lives at `.assistant/<id>/.data/<skill>`; Linux mounts data per skill instead of the whole data root.
5. `skills_read` / resource drop the global `findInstalledSkill` fallback. Disabling removes the code from that assistant's run snapshot and interrupts that executor's terminal / self-built daemons.
6. Model selection passes `providerId + modelId`; the projector no longer looks up the same ID across providers; on invalid bindings `selectedModel == null` and sends are blocked. Attachment prep records conversation / draft / generation / assistant and cancels the send on change.

## High: the hot path may mistake a transient read failure for full deauthorization

`AgentLocalTools.liveSkillEntries()` on the production path (`runSkillsRoot != null`) performs on every call:

- `AssistantRepository.currentProfile` (cross-process file lock + full index deserialization)
- `listSkillsForManagement(forceRefresh = true)` (holds SkillMutationLock, clears the cache, and reseeds built-in skills)

and then uses `entries.size != runSkillEntries.size` to decide `interruptAll`, `stopOwnedDaemons`, `pruneRunSkills`.

`installed` is `emptySet()` when `skillIndexService == null` or the list is empty. Index jitter, seed failure, lock timeout, or an exception swallowed upstream turning into an empty list would be read as "this round's skills all uninstalled", shutting down terminals and daemons and deleting snapshot code. That is more dangerous than the old 400ms cache fallback. Only the subset "explicitly still enabled with snapshot files still present" should keep running; read failure should keep the previous snapshot and report an error — never equal empty authorization.

`liveSkillCache` is still a field, but the new implementation no longer uses it.

## High: SkillMutationLock vs assistant-index lock order reversed

The install-success callback runs: already holding `SkillMutationLock` → `AssistantRepository.enableSkills` → `withIndexLock`.

`liveSkillEntries` / per-round `skillContextProvider` run: `withIndexLock` (`currentProfile`) → `listSkillsForManagement` → `SkillMutationLock`.

Two concurrent Agent runs (one installing, one listing/reading or assembling the next round's prompt), or UI `select/update` overlapping an install, open a deadlock window. The UI's own `update` is assistant-lock-then-skill-lock — the reverse of the install path. This is not an item from the original audit; this fix round introduced it.

## High: compression and some config construction still use the current assistant

`RuntimeConfigRepository.configForProviderAndModel` still calls `buildRuntimeConfig(provider, model)` defaulting to `AssistantRepository.active()`. Background compression, manual-compression fallback, and binding-config reads then carry **this moment's global assistant** `systemPrompt` / `assistantId` instead of the assistant that started the conversation task. The main chat send path already passes `runAssistant` explicitly, so this is compression/config-side residue, not a send-main-path fallback.

## Medium-high: the memory page stays on the stale draft after switching assistants

`selectAssistant` only does `select + sync + refreshRequestOverhead` — it neither bumps `memoryEditGeneration` nor calls `refreshMemory()`. Saves are correctly blocked as "draft belongs to another assistant"; but toggles may still land on the **draft's old assistant**, and the UI keeps showing stale content. After switching to B the user sees A's memories — an easy misclick.

## Medium: test and production disagree on "this round's changes" visibility

Tests without `runSkillsRoot` filter `mutatedSkillIds` out of `currentSkillEntries`, so post-install/overwrite `skills_read` yields `NEXT_TURN_REQUIRED`. Production with `runSkillsRoot` never filters, so a same-ID update still reads **this round's old snapshot**. That is closer to "this run's approved version stays fixed", but the existing `AgentLocalSkillInstallIntegrationTest` asserts the old semantics — assume nothing passes before tests run.

## Medium: other residue and regression points

- `SkillRuntime.visibleSkillsDirectory()` still uses `AssistantRepository.active()`. User terminals / default daemon mounts follow the current assistant, not any particular run. Documented, but a different root set from the Agent snapshot.
- `AssistantRepository.create()` publishes no skill view. Between creating an assistant and its first `select/update`, user terminals may mount an empty directory; Agent runs are unaffected since they go through `createRunSkills`.
- Deleting an assistant still cancels no owned runs. The next tool call / next round fails on `currentProfile == null`; already-issued model requests and started processes are not recalled by the delete.
- `observeRuntimeSelection` bumps `modelBindingGeneration` when providers content changes, cancelling attachment prep. Conservative, but a balance/model-list refresh can cause "send failed without switching conversations".
- `AgentMemoryStore.replaceAll(content)` without revision still exists; the repository takes that path when defaulting `revision = null`. The UI no longer uses it; import/copy still do. Do not rewire other call sites back to the UI.
- An old index containing previously legal, now illegal IDs such as `.` / `..` makes `readIndex`/`validateProfiles` fail `init` outright. That is the price of strict validation; it needs a migration or a clearer boot error rather than silent ID rewriting.
- Conversation history still has no assistant-ownership field. Switching assistants on one conversation never clears history. Consistent with the original audit; not yet done.
- `enabledFlow()` is still the global `SettingsDataStore.memoryEnabledFlow()`, not the same source as per-assistant `memoryEnabled`.

## Test coverage (none executed)

New cases cover: CAS conflicts, cross-Store locks, delete tombstones, ID validation, two-assistant data separation, updates never touching old snapshots, disable-keeps-data, empty snapshots never widening, release never following data pointers, models never falling back across providers, IPC identity defaulting to empty.

Not covered: concurrent-run lock ordering, empty-index deauthorization, compression-config assistant identity, post-switch memory UI, `forceRefresh` hot path, real Linux/PRoot mounts, deleting assistants with in-flight runs, attachment prep cancelled by providersFlow.

Lexical bracket checks and `git diff --check` substitute for none of this.

## Handled after re-audit (still uncompiled)

1. Hot-path deauthorization: `SkillRunAuthorization` separates "read failure" from "confirmed deauthorization". Index/assistant-profile read failures keep the previous snapshot; the set shrinks and terminals close only when the assistant is deleted or a disable/uninstall query succeeds. No more `forceRefresh`. 7 new decision tests.
2. Lock ordering: `AssistantRepository.update/select/delete/create/import/saveAvatar` commit the index first, then publish the skill directory — no longer taking `SkillMutationLock` inside the assistant-index lock. `currentProfile` uses the in-lock refreshed memory snapshot to avoid re-reading disk.
3. Compression config: `buildRuntimeConfig` / `configForProviderAndModel` no longer default to `active()`. Custom summary models only reuse the `assistantId` and systemPrompt already pinned on the fallback; binding reads and background compression pass null with no conversation assistant instead of borrowing the current one.
4. Assistant switching: `selectAssistant` now calls `refreshMemory()`. With an unsaved draft on the old assistant, it warns of unwritten content and swaps the editor to the new assistant's content.
