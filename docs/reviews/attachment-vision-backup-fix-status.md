# Attachment, vision capability, and backup fix status

Status: working-tree fix draft; not yet compiled or fully accepted. Do not treat this file as release notes.

## Changed paths

- Models gain an independent `visionOverride`; the Room 23→24 migration preserves explicit choices on old attachments; remote refresh preserves the override.
- Automatic vision detection no longer guesses from model ID substrings; metadata and user overrides are handled separately. Editing to a different model ID does not inherit the previous model's capabilities.
- Each round's ProviderRequest is copied and media-filtered; follow-up persistent file references are hydrated on the way out; text-only models never receive disabled image blocks.
- The follow-up path carries requestId and imagesJson, preserved through Binder, receive events, handoff, and replay; queue entries contain the body text plus its attachments.
- Drafts are cleared only after a receive event arrives; failures/timeouts keep them; ordinary sends reject partial attachment-to-disk failures. Old history no longer guesses original image sources from image-list order.
- ZIP full staging, central-directory checks, CRC, path/duplicate-entry/entry-count/total-size/manifest-cap, and space-reservation checks.
- File undo log, metadata rollback snapshots, boot-time recovery entry; originals are restored via rename, repeat recovery is detected via SHA-256, and failures are never silently swallowed.
- Only explicitly owned attachment-structure fields are migrated; body text and other apps' private paths are never globally replaced.
- Export strips API keys, MCP tokens, custom headers/bodies, and balance-auth config. The UI states explicitly: archiving other content may still contain secrets; this is not an encrypted backup.
- Managed execution leases and backup maintenance state are mutually exclusive; residual daemon records block import.

## Incomplete or unproven items

1. **Full Linux-environment safe import/export is not implemented.** The dangerous delete-before-extract path has been removed; the current entry point is closed and refuses imports containing an environment archive. This is a safe deactivation, not a completed feature fix.
2. **Encrypted backup is not implemented.** Excluding credentials is not the same as archive encryption, nor does it guarantee that conversations, scripts, or URLs contain no secrets.
3. **The global consistency maintenance lock is still incomplete.** It currently covers the main entry points for archive operations, managed tasks, new messages, and conversation persistence; arbitrary settings-page writes and all root-shell/external-mount writes are not yet routed through it. Cross-repository snapshots cannot be claimed strongly atomic.
4. **The attachment protocol is still a compatibility extension.** requestId+imagesJson are now passed through, but there is still no unified full protocol with stable attachmentIds, MIME types, checksums, and a dedicated attachment table. Recovery of unacknowledged drafts on process death, re-confirmation of retried duplicate requests, and follow-up questions on finished runs still need dedicated acceptance.
5. **Crash consistency is still untested in practice.** File/directory fsync, per-item intent records, original-file checksums, same-filesystem prechecks, rename rollback, and retire-before-cleanup log directories have been added, plus new interruption-point tests — but the tests have not been executed. Power loss, mount changes, POSIX permissions/SELinux, and database/settings/file-boundary behavior still need real-device fault verification.
6. **Snapshot resource budgets moved earlier but the format is still not streaming metadata.** New Room full-DTO-load SQL aggregation budgets (8 MiB/50,000 rows), pre-read caps for avatars/skills/memories, streaming directory traversal, and ZIP export aligned to the import budgets. Bounded DTO/JSON construction remains; assistant config/Settings/MCP and other sources still need review, and export consistency is still limited by item 3.
7. **Backup reference completeness still needs verification.** It has not been fully verified that every historical attachment matches its original file, nor that file references in old-format body text are fully migrated.
8. **No compilation, JUnit/Robolectric, or real-device test results.** None of the fixes can be claimed working, fully safe, or releasable.

## Current verification

- `git diff --check` passes.
- Lexical bracket matching over the 53 changed Kotlin files passes; that check does not parse Kotlin types and does not substitute for compilation.
- 47 new test methods in total: media filtering 3, follow-up/recovery 4, archive boundaries 5, path migration 2, undo log 7, blob budgets 6, ZIP export 5, database budgets 2, single-conversation archiving 13.
- Updated vision-capability tests, the migration test chain, and backup-credential expectations. None of the test code has been executed.
- versionName/versionCode untouched; nothing committed, pushed, or run through GitHub Actions; no production backup or database on a device touched.

## Boundaries and limits of this round

- Directories from successful or rolled-back transactions are first atomically renamed to `backup-retired-<random ID>` and only then cleaned recursively; interrupted cleanup will not mistake leftover logs for active transactions. Failed cleanup may leave private directories behind; secure erasure is not claimed.
- A commit marker whose write failed no longer enters the rollback branch of the normal apply phase; maintenance state is held until the post-restart check completes, so a published commit marker and rollback cannot contradict each other.
- Intent plus original/new checksums are persisted before applying each item. Untried items are not rolled back; on detecting external modification the process stops instead of blindly deleting files.
- File rollback preserves the original's inode via a same-filesystem atomic rename instead of requesting a second copy's worth of space by file size; it can still fail on filesystem metadata errors or a completely full disk, so recovery on a full disk is not guaranteed.
- Cross-filesystem targets are rejected in the precheck; replacing originals owned by another UID is refused rather than pretending to preserve their ownership. Basic access bits on new files are copied from the original; special permissions/SELinux still need testing.
- The new avatar budget is 4 MiB per file, 4 MiB total, at most 1,000 files; skills are 4 MiB per file, 8 MiB total, at most 2,000 files; assistant memories total 4 MiB.
- These safety budgets make over-limit backups fail explicitly; streaming restore of large archives is not done.

## Next steps

First close the remaining isolation/persistence and protocol gaps and review them, then request GitHub Actions build authorization; per standing convention, no local packaging, no automatic commits or releases.

## Follow-up single-conversation fixes

The single-conversation manifest, import isolation, UI save confirmation, and reliable export path were implemented separately; see `conversation-archive-fix-status.md`. Full backup and unverified boundaries are still handled under the limits listed in this file.
