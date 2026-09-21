# Single-conversation archive fix status (unreleased)

Working-tree implementation; not yet committed, pushed, compiled, or run under JUnit/Robolectric. Not release notes.

## What this round implements

- The `eta-conversation` manifest moves to schema 2 (not an app version change). Attachment entries carry migratable references, ZIP entry names, sizes, and SHA-256.
- Attachments are collected from user attachment structures, Eta file-reference blocks, user/assistant Markdown link destinations, conversation history, and structured media in context checkpoints; code blocks and ordinary body-text paths get no global replacement. No more packing whole image directories, no more guessing imported files with body-text regexes.
- Missing attachments, directory attachments, local references outside the app attachment directory, and unrecognized legacy references are explicitly refused — a partial archive is never reported as success. External HTTP(S) references keep only the link; their content is never downloaded; inline data URLs stay in the manifest.
- On export, recognized structures and body-text links are replaced with placeholder attachment references and remapped to new paths after import. SHA-256 is verified as actual bytes are written to the ZIP so same-length post-precheck modifications cannot slip silently into the archive.
- Import always copies into a new conversation: conversation ID, message IDs, and the attachment private directory are reallocated; the DAO uses ABORT instead of REPLACE. Original model bindings, folders, pins, and applied runtime run IDs are cleared; conversation body text and model history are kept. The user must pick a model on the target device to continue.
- New files land under persistent imports/<new-conversation-ID>/, never in the image cache the system may clean. Repeat imports never overwrite each other; legacy v1 imports migrate by reference, installing only referenced attachments, with file:// and absolute-path aliases sharing one installed file.
- The single-conversation restore log records only the new conversation ID plus new-file undo — small conversations no longer load all providers, skills, memories, and conversation snapshots. On uncommitted crashes, boot recovery removes only the newly added conversation and files from that attempt.
- The UI freezes conversation-modification entry points during archiving and defers ordinary persistence requests; export forces a save first and awaits the Boolean result. Persistence and archive operations are mutually exclusive; runtime restore and model-capability updates are deferred; import and database re-reads sit in a non-cancellable section so cancellation cannot overwrite the database with stale UI state.
- The file-picker target ID/title become rememberSaveable; scope, privacy, and import-method notes are added. The ZIP is first completed in the app-private directory and re-unpacked for verification before the destination URI is opened for writing; on failure/cancellation the target is deleted where possible, and where deletion is unsupported the user is warned the file may be incomplete. Temp files are cleaned in finally.
- The text-JSON conversation manifest is also distinguished by format; local references with no archive-file support still refuse import.

## Verification evidence

- `git diff --check` passes.
- Lexical bracket checks over the accumulated 53 changed Kotlin files pass. This is not Kotlin compilation or type checking.
- 14 new test methods this round: 13 single-conversation archive cases, 1 ZIP-write verification case. 47 new test methods across the accumulated new test files; none executed.
- New cases cover: spaces/parentheses in file names, body-text paths never mis-collected, history/tool media/checkpoints, Markdown-vs-code-block separation, missing/out-of-scope/directory attachments, repeat imports, bad/extra/missing/tampered manifests, v1 migration and aliases, real-repository round trips, unrelated-conversation over-limit, interruption recovery.
- No real conversation database or backup file on a phone touched; versionName/versionCode unchanged; no new APK produced.

## Still to accept / known boundaries

- JUnit/Robolectric, Room generated code, Compose lifecycle, and full Android compilation all wait on GitHub Actions authorization; static checks cannot stand in for passing tests.
- The file picker survives Activity/process recreation to restore the chosen target, but an in-copy export job is not a persistent background task; killing the process skips finally and may leave temp files or partial target files. No DocumentsProvider commit can be guaranteed atomic.
- Missing or unmigratable legacy attachments cannot be recovered out of thin air. Directories, external absolute paths, unknown media structures, and some special file names are not promised support; errors ask the user to import first or rename rather than silently skipping.
- Archives are not encrypted and do not automatically scrub credentials from conversations/attachments; no Markdown/HTML share formats or model-config packaging.
- Cross-repository locks for full backup, external root/mount writes, and power-loss/full-disk/SELinux fault verification remain open items from the previous round. This round does not claim them resolved.
- SHA-256 is an integrity check, not a digital signature, and does not prove an external archive trustworthy.
