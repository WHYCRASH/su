# Pause-resume: streaming render, body integrity, and logical turns

Status: source fixes complete; the user has authorized committing and building via GitHub Actions; unit tests, builds, and on-device verification at commit time are still pending — the corresponding CI and on-device records are authoritative. Version number unchanged.

## User constraint

Pause, resume, appended instructions, and stop all belong to the same user logical turn (turnId). Internal request rounds, execution runIds, and UI block indexes are different concepts; compression-protected turns must not be redefined just to dodge display conflicts.

## Issues confirmed in source and their handling

- Pause was treated as a non-streaming, finished Markdown target while the parent's resume animation raced lifecycle-layout restore. isPaused and isStreaming are now separated; resume animation is gated jointly by the lifecycle baseline and user pause; incremental reveal and haptics stay off until historical layout catches up.
- The first render pinned isStreaming, so resumed generation could never reliably switch back to the streaming path. Finished blocks may now re-enter the streaming path; live generation is never downgraded to whole-segment display by a stale revealComplete flag.
- Resumed requests restarted the Provider block index at zero, so authoritative replacement reusing old blocks could overwrite earlier text. New AgentContinuationBlocks: the continuation's first body block keeps the original identity with replacement scoped to new fragments; blocks after new thinking or tool boundaries take independent indexes without changing round or turnId.
- The final Result.content held only the last resume fragment, so a single-body-block UI finish overwrote the full visible text. Accumulated pause fragments now feed that request round's final result; tool, appended, and transport-retry fragments move to different display turns and clear the prefix so old blocks are never shown twice.
- Spaces/newlines at resume seams are never trimmed when a block ends temporarily; trailing whitespace is still tidied when the final turn/task ends.
- Pauses with no body text also suppress optional thinking on the next request; custom request-body thinking overrides are cleaned even when the config is Off. Forced-reasoning models keep their constraints — no promise that every model can disable thinking.
- Runtime Wire gains an independent logical turnId; legacy entry points without one still default to runId. Appends across executor runs and post-compression continuations all pass the original turnId, and protection boundaries trace back to that logical turn's start.
- Stop-and-save body text keeps its turnId instead of replacing a different historical answer or tool call with the new fragment. Legacy internal continuation prompts count as supplementary instructions in migration and kept-turn accounting.

## Verification boundaries

15 new tests, plus expanded existing appended-task and IPC loopback tests: repeated pauses with authoritative replacement, thinking/tool boundaries, empty pauses, complete final results, event-replay consistency, whitespace seams, background-layout restore gating, thinking-override cleanup, stop history retention, and compression-turn identity.

`git diff --check`, test-name dedup checks, and targeted static checks of IPC/render wiring run. No Kotlin unit tests, Android builds, or on-device reproductions run, so UI streaming and haptics cannot be claimed accepted. Recent log searches for the pause marker came back empty; the diagnosis rests on the user's description plus the source paths above.

Post-pause appends resume once Runtime accepts the input; continuations use new block IDs with the supplementary bubble between the old answer and the new output. Plain continue reuses the original block.

Suggested on-device regression: at least three consecutive pause-resume cycles; append after pause; stop-and-save; switch conversations and back; background/foreground switches; append mid-tool-run; check unified continuous-execution compression and the auto/manual independent endpoints.
