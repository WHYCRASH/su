# Assistant / Skills / conversation-model joint fix record

## Status and verification boundaries

The user has approved the joint changes. The code is still in an uncommitted working tree with baseline HEAD `994ad3a`; uncommitted, unpushed, no GitHub Actions triggered, app version number untouched.

This record describes implementation already written into source — not equivalence to passing compilation or on-device reproduction. 18 new Kotlin regression tests this round, none executed. The existing compression, attachment, backup, and other changes are still in the working tree; compiling only this round's files cannot declare everything passing.

## Behavior now in code

### Task identity

- `ModelConfig` and `RunRequest` carry the same `assistantId`; persona and ID are sampled together when the config is built and passed explicitly over IPC. Voice / injection entry points carry identity through the existing config JSON; continued rounds keep identity across request.copy.
- Runtime, memory toggles, memory content, tool operations, and skill-install ownership all use the owning task's ID — never the global active assistant at next-request time.
- Missing / deleted identities never fall back to another assistant; old configs without identity must resync config and start a new task.
- The assistant index is published with a cross-process file lock and AtomicFile, reloading the on-disk index before mutation; Runtime reads published state by ID so stale in-process caches cannot bypass deauthorization.

### Memory editing and deletion

- Memory UI drafts carry assistantId, baseRevision, and edit generation; save / clear both go through CAS, and stale callbacks never update a different edit object.
- The assistant edit page also uses CAS. Conflicts keep the draft instead of overwriting the newest memory with stale full text. When the memory saved but a later profile save failed, the new revision is synced so retries never wedge on the old revision.
- The memory store lock covers different Store instances and processes; delete and write run under the same lock with a deletion marker, so stale handles cannot re-establish deleted memories. Backup restore uses a separate explicit restore path.
- Assistant IDs are strictly validated; lossy substitution / truncation for isolation-key generation is gone; backups validate profiles and memory keys before applying.

### Skills snapshots and private data

- Each Agent run uses an isolated directory `skills/.assistant/<assistant>/.runs/<uuid>/skills`. Prompts, list, read, resource, and the Linux launcher use the same pinned entry set.
- Code is published through a full staging copy under SkillMutationLock protection; newly installed skills never enter old snapshots. On same-ID updates, this round keeps using the old copy; the new version takes effect from the next run.
- Private writable data lives at `.assistant/<assistant>/.data/<skill>`; Linux mounts only each item of that run's authorized data directories instead of exposing the whole private data root.
- Disabling a skill removes visible code but keeps private data. Reads check the task snapshot, the owning assistant's current authorization, and the current install state together; the global installed fallback and empty-snapshot dynamic-widening paths are deleted.
- Deauthorization tightens the snapshot at the next tool / request boundary and closes the ordinary terminals held by that executor plus the daemons it started; this cannot be claimed to have recalled already-executing code, open handles, or already-sent request content.
- Ordinary runs release their snapshot at the end. File leases guard against deleting live runs and let later runs clean ordinary snapshots left by crashed processes. Snapshots used by long-lived daemons are retained so ending a task never breaks a background service.
- 16 MiB / 4096 entries per skill, 128 skills / 256 MiB per snapshot; at most 128 retained or running views per assistant — over-limit fails explicitly, never deletes data silently.
- When install succeeds but enabling for the owning assistant fails, the result is installed_not_enabled with an explicit error — no longer falsely claiming it is enabled.

### Conversation model and attachment prep

- Model selection updates the target conversation's providerId + modelId directly, independent of the global async-restore write-back; the old restoringConversationModel Boolean restore pipeline is removed.
- The model picker control, UI actions, and handlers pass provider + model end to end, avoiding wrong picks on equal internal model IDs across providers.
- On invalid bindings / disabled providers the selected model is empty and new sends are blocked. No cross-provider lookup by equal modelId, no silent switch to the global model.
- Only fully blank legacy bindings get a one-time default migration; partially missing bindings are never spliced with another provider's defaults. Conversation import keeps the original binding; a model missing after import needs an explicit user reselect.
- Attachment sends and regenerations record owning conversation, draft, model generation, and assistant ID; mid-prep changes cancel that send and keep the original draft — never borrowing or clearing a different current conversation.
- Model config, image / video branches, video capability, and this round's budget use the pinned snapshot; foreground / background auto-compression paths estimate windows from the owning conversation's model. An explicitly requested compression model that goes invalid is never secretly swapped for another model.
- Model restore only updates the optional reasoning tier; saved preferences are never rewritten with stale model capabilities. Actual requests are normalized against the pinned model capability at send time.
- Overhead async estimates carry owning conversation and generation; late results never overwrite another conversation; binding refreshes during archiving wait until the archive lock releases.

## Verification record

- `git diff --check` passes.
- Lexical bracket checks over the 96 modified / new Kotlin files in the current working tree pass. The script performs no Kotlin parsing, type checking, Android compilation, or test execution.
- 8 source assertions pass: Runtime never takes active, skills have no global fallback, UI has no legacy versionless save entry, chat has no global config fallback, provider toggle check, IPC identity, strict IDs, Linux snapshot parameters.
- New tests: AssistantIsolationRegressionTest 6 items; SkillRunIsolationTest 7 items; AgentModelPickerProjectorTest 4 new items; AgentRuntimeWireTest 1 new item. The existing assertion that conversation import keeps bindings is also updated.

## Product / authorization boundaries still to state explicitly

1. This round does not turn conversation history into a per-assistant security sandbox. Switching assistants on one conversation never auto-deletes history; if conversations themselves need immutable assistant ownership, that takes a separate data migration plus history-takeover UX — never guess who an old conversation belongs to.
2. The old global `.visible/<skill>/data` has no reliably determinable historical owner; the original directory is kept, never arbitrarily assigned to the current assistant. Only legacy `.assistant/<id>/<skill>/data` with confirmable ownership migrates into private data.
3. Root / arbitrary Shell remains a user-granted device-level capability, not a multi-tenant security sandbox. Deauthorization cannot erase already-sent prompts / summaries, copied files, or handles already held by third-party processes.
4. Daemon-retained views are never auto-deleted by age; after stopping a daemon from another executor, automatic reclamation of retained views still needs follow-up. Caps explicitly stop unbounded growth; long-lived services are never destroyed just to reclaim space.
5. Running requests and the next tool boundary check whether the assistant still exists, but deleting an assistant is not claimed to immediately recall a provider-side already-running request.

## Build and on-device regression checklist

Run unified through the approved GitHub Actions builds / tests, verifying first:

- A/B fast conversation switching with same-modelId different providers, send blocking after delete / disable; next-item auto-select after deleting the current conversation.
- Switching assistant, conversation, model, capability toggles, or editing drafts mid-attachment-send / edit-resend / regeneration; B never mixes into A's messages and A's draft survives.
- Same-model summaries, dedicated compression models, invalid models, legacy round protection, and existing tests for continuous-execution strategy.
- UI save / clear racing tool memory_write; multi-Store CAS; deleting assistants with stale handles; illegal-ID backups refused before modifying anything.
- Empty / non-empty skill boot snapshots, mid-run install, same-ID updates, disable-then-enable, uninstall, plus read/resource/terminal consistency.
- Two assistants running same-ID skills concurrently, private data writes, ordinary terminals vs PRoot / chroot / daemon mounts, lease reclamation after task cancel and process crash.
- Voice / injection entry-point config resync, IPC round trips, task continued rounds, plus all existing compression, attachment, and backup regression tests.
