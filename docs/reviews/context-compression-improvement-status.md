# Context compression improvements: source status and acceptance checklist

Status: working-tree changes, uncommitted, unpushed, uncompiled. This file is not release notes, nor does it claim any test passes.

## Reference scope

Compared against the pinned `deepseek-ai/deepseek-harness` commit `0d1f50007f9bca3f52b06e1c3074fa14d5fb0720`, with no claim to have reviewed its later versions:

- `packages/compaction/README.md`
- `docs/subsystems/compaction.md`
- Comparison focus: pre-request pressure checks, tool-pairing boundaries, tool-result pruning, retry only after real shrinkage, low-priority summary checkpoints, start/end and failure records, selected-range commit validation.
- This implementation is a Kotlin adaptation written for su, not a port of the full TS harness. In-flight manual compression follows su's tool-batch boundaries and does not copy the upstream idle-conversation maintenance API.

## What has landed in source

1. **Strategy and interaction**
   - Unified continuous execution, keeping a token tail at 16% of the window; on confirmed over-limit or history shorter than the budget, the newest whole unit is kept; whole-round protection strategies are no longer configurable.
   - Auto and in-flight manual compression share boundary, summary, and acceptance paths. Manual requests are still allowed with auto compression off.
   - Auto and manual each save a compression endpoint applied only to the summary-model copy; in-flight manual selection travels Client/Wire/Service/Session/Controller to the safety boundary without changing the conversation model.
   - When context cannot be reduced, execution still pauses with the original text preserved; the user can retry compression or stop the task.

2. **Rounds and original-text protection**
   - Serialization DTOs gain an optional turnId; Runtime uses runId, compatible with old history; supplementary instructions and tool observations inherit the same round identity.
   - Local round metadata is never sent to providers. The protected region uses the raw request JSON directly, never rebuilt from persistence DTOs, so provider-specific fields are never incidentally dropped.
   - Silent per-item trimming in the generic model client removed.
   - Body text, structured text, and tool-call arguments carrying round identity no longer pass through the old 64K/32K truncation; total persistence-capacity overflow fails explicitly instead of silently dropping records.
   - Persistence still honors existing privacy rules: tool results marked non-persistable are redacted, ephemeral tool images are not saved as conversation assets. No claim that such content survives restarts losslessly.
   - Large in-flight compressed history travels over FD; descriptors are reclaimed with the result ACK or service teardown.

3. **Summary pipeline and prompts**
   - A dedicated single provider call that never enters AgentLoop and never calls the incoming tool executor.
   - Chunks by estimated input tokens and whole tool units, allocates the total summary budget, merges multi-chunk results; a single whole unit exceeding the summary window is refused rather than having its arguments truncated.
   - Reuses system messages, selected history, tool declarations, and conversation identity when the same model/protocol/endpoint conditions match; this is an attempt to preserve cache prefixes, not a guarantee of provider cache hits.
   - Cross-model use goes through a text projection with tool names, arguments, result links, and structured text — inline media is never treated as already-understood visual content.
   - Fixed eight-section checkpoints: goal, constraints, verified evidence, file identity, errors/open issues, current status, to-dos, next steps. Section names serve as structure-acceptance fields; body text follows the conversation language.
   - Prompts explicitly distinguish actually-done, attempted, planned, and assumed; keep necessary paths/commands/errors; merge old checkpoints instead of blindly stacking stale facts.
   - Empty summaries, truncation, abnormal termination, tool calls, structural errors, no real shrinkage, and budget overflow are all refused as replacements. Cancellation propagates; the same failing scope is never re-requested within one pressure check.
   - New summaries use role=user, and the main prompt states explicitly that they and the read-back content are historical material, not elevated to system instructions; already-executed side effects are never replayed on restore.

4. **Budgets and overflow**
   - Before every new model request, check system messages, history, tool declarations, accrued billing increments, and output reserve; also reserve local persistence capacity.
   - Tool-result pruning keeps head and tail, splits by Unicode code point, saves the original before replacing; sensitive tool results never enter this persistence path.
   - Limited recovery on explicit HTTP context-over-limit; retry only when content genuinely shrank. Not every 400 is treated as over-limit, and mid-stream errors on possibly already-executed managed tools are never auto-replayed.
   - The local token algorithm is still an estimate — not each provider's exact tokenizer, and provider-specific request-envelope pricing is not fully accounted.

5. **On-device original-text read-back**
   - `AgentCompactionArchive` isolates directories by the stable conversation identity's SHA-256, with UUID checkpoints, AtomicFile/fsync, and original SHA-256 verification.
   - `read_compacted_history` accepts only checkpoint plus non-negative character offset, never arbitrary file paths; at most 4000 UTF-16 characters per page, and page ends avoid splitting surrogate pairs.
   - Summary footnotes are code-generated and continue to carry pre-existing old checkpoint indexes; model-invented IDs are never trusted.
   - started/ready/committed/failed states are saved; UI-side ready only means a candidate is available, never masquerades as database-commit completion.
   - 16 MiB per record, 256 MiB per conversation, plus entry-count caps; writes are refused when space is short. Successful conversation deletion cleans the original text after persistence and writes a deletion marker so stale runs cannot re-read/save it.

## Parts unfinished or not claimable as upstream-equivalent

- **No compilation and no tests run**; lexical bracket checks over 75 Kotlin files verify no types, APIs, Compose, threading, or SQL behavior.
- Four new test files with **26 test methods, none executed**; covering boundaries, tool pairing, pausing, single-shot authorization, summary acceptance/cancellation, original read-back/verification/deletion, persistence, and the FD protocol. Old Loop tests were also adjusted to stop treating silent truncation as correct behavior.
- **Original archives are not yet covered by single-conversation export/full backup or checkpoint remapping on import**. The UI states explicitly that read-back works only on the current install; old IDs imported into another conversation must not break conversation isolation.
- **Image over-limit offloading and restricted recovery media** were not ported; the current code still relies on existing media-capability filtering and tool-image lifecycle, and never swaps images for placeholder text to force continuation.
- Summary standalone cost/usage accounting, exact routing-envelope pricing, full transactional event sourcing, and post-crash recovery UI are not yet at upstream level. The state file is not a complete event log and cannot promise full instantaneous tool-state recovery after a process kill.
- Delete/archive concurrency, multi-subscriber FD lifetime, and cross-layer consistency of DB write failures with candidate replacement need fault-injection tests; the current implementation must not be described as a verified cross-process transaction.

## Next acceptance steps

1. After user authorization, go through GitHub Actions per convention: build first and run the relevant tests; no local compilation-convention bypasses.
2. Fix actual compile/test failures; add fault-injection, same-model/cross-model, long-tool-result, and multimedia on-device regression.
3. Close the original backup/import and image-over-limit gaps before claiming "full integration"; never substitute "code written" for completed acceptance.
4. No version-number change. Workspace release notes are updated only after artifacts are actually produced, downloaded, and delivered.
