# Main agent and subagents

Settings → Model features → Subagents configures three worker agent models and one
review/summary model. The existing slot 1 and 2 model references and duties are
unchanged; new slots 3 and 4 are workers; the UI orders them worker 1, 2, 3, then
review. Different slots may share one model, including the main agent's model. The
current chat model schedules and performs final acceptance. Long-pressing the model
selector opens the per-conversation collaboration switch, on by default, taking effect
on the next run.

## Delegation and roles

delegate_task's role is research (default), implementation, review, or summary.
implementation auto-picks the currently least-busy worker slot, review/summary
auto-picks the review slot; an explicit worker must still match the duty. Unconfigured
duties return ROLE_NOT_CONFIGURED instead of silently switching models.
get_task_result returns status, result, role, project, workspace_id, and workspace_path.
At most two parallel tasks, at most 16 items per round, 180 seconds of execution budget
per item (including setup and teardown), plus a separate cumulative 180-second budget
for compaction. cancel_task or parent-task end cancels subtasks. Original delegation
context and results are treated as sensitive tool content and never enter the persisted
conversation.

## One project, one directory

Project tasks must name an explicit /workspace/<project-name>; neither su nor bare
/workspace or mounts is ever the default. The project must be Git-initialized with at
least one commit, with no uncommitted source changes before an implementation worktree
is created. Switching projects means passing a new project; the old project's
workspace ID is unusable in the new project.

.agent/worktrees/<ID> holds a standalone detached worktree; .agent/results/<ID>.json
records only the baseline, status, and delivery commit, never keys or task bodies. No
temporary branches are created or pushed. .agent belongs in the Git ignore rules. The
selected Settings Linux is used, requiring Python and Git; when unavailable, return an
error instead of falling back to Android Shell. Only dependency probing without
Python/Git returns WORKSPACE_LINUX_PYTHON_GIT_REQUIRED; script launch or transport
failure returns WORKSPACE_EXECUTION_FAILED, while environment permission errors keep
their original code.

1. implementation passes project and creates a standalone worktree. Subagents read and
   write sources only through workspace_file.
2. On completion the runtime commits and freezes; the main agent receives the worktree
   path and ID and runs builds/tests at that path.
3. review passes the same project and workspace_id and inspects the pinned commit
   read-only.
4. The main agent reads the review verdict, checks the diff and tests, then explicitly
   calls manage_agent_workspace's merge. reviewed only means the review flow completed,
   not that the review is clean. Fast-forward merge requires a clean main repo still at
   the baseline with the delivery unchanged.
5. The worktree is deleted after merge; failures and cancellations keep the scene for
   inspection via list/inspect, discarding only on explicit abandon.

## Permissions and recovery

workspace_file offers list_files/read/diff/write/delete, bound to the project and ID at
runtime; models cannot override ownership. Relative paths reject .., Git metadata,
.agent, symlinks, hard links, and special files. Files cap at 64 KiB with paged output.
Review roles read only. Subagents cannot run arbitrary Shell, operate the phone, send
messages, or create subagents. research/summary roles' read_file and list_directory
support the selected Linux's /workspace, /var/minis, and minis:// paths by mapping to
the host location first, then reading under the existing file permissions;
implementation/review still use only the bound worktree's workspace_file. The main
agent keeps its existing permissions; directory isolation is not a system sandbox for a
main-agent root shell.

A cross-process project lock serializes file operations, freezing, merging, and
reclamation. No merge or delete happens during review. Legacy task leases mark
inspect as pending on expiry while keeping the scene. At most 8 unreclaimed worktrees,
a 16 MiB cumulative write budget per task, and a 512 MiB free-space check before
creation; unmerged changes are never auto-deleted. Restarts do not resume model loops,
but deliverables stay viewable and actionable in the original project. Auto-initializing
Git-less projects, auto-rebase, and arbitrary subagent command execution are currently
unsupported.

## Verification

Python integration tests cover real-Git isolated writes, freezing, review,
fast-forward merge, reclamation, cross-project ownership, out-of-bounds/symlink/hardlink
rejection, dirty main repos, baseline changes, failure preservation, expiry recovery,
and paging. Kotlin tests cover duty routing, missing roles, workspace parameter
binding, read-only enforcement, and cancellation. Settings icons use a robot
(assistant) and an eye (assistive vision) to stay distinct from title/reasoning
features.

## Automatic subagent compaction

Subagents use independent contexts with automatic compaction on by default; the user's
explicit on/off choice in the context-compaction settings wins. Pressure is computed
against that subagent model's configured context window, reusing the shared compaction
model selection, interface mode, and reasoning policy; without a custom compaction
model, the subagent's own model compacts. With no valid configured context window,
compaction is not enabled on a guessed capacity. Compaction triggers at about 80%
window pressure keeping the latest complete tool batch; subagent compaction still bills
to the parent conversation. Subagent summaries live only in the standalone execution
context: no permanent compaction archive is created and no subagent tool permission is
added. If the limit still binds and no further compaction is possible, return
SUB_AGENT_CONTEXT_LIMIT to the main agent and keep the implementation worktree instead
of pausing without interaction until timeout.

This conversation's collaboration popup uses a unified WindowDialog: the auto-delegate
switch, the current models of the three worker slots and one review slot, and a single
done button. Long-press entry and conversation-switch scope are unchanged; changes take
effect on the next run.

## Context stats and concurrent compaction

Long-press the context ring to open the stats object selector; a tap still shows usage
details. Selection only switches the viewed object, never the request model. The main
agent and launched subtasks reuse AgentLoop usage events: billed-input tokens from the
interface first, then incremental projection against the billing baseline after tool
batches, falling back to the shared local estimate for first requests and post-
compaction states without a bill. Unknown windows stay unknown. Multiple tasks on one
model are told apart by task ID. get_task_result's context_usage returns the model,
window, current occupancy, whether estimated, cumulative input/output, the compacting
flag, compaction counts, and pre/post-compaction occupancy. The UI receives stats
directly through runtime events, so it still updates while the main agent compacts or
waits. Compaction state shows per model/task at the message bottom, one line each, and
any finished or cancelled lane clears its line. Each AgentLoop pauses only its own
later requests; main-agent compaction never stops launched subagents, whose results the
coordinator keeps until the parent run ends. Execution and cumulative compaction
budgets tick on a monotonic clock separately, with compaction timeout returning
SUB_AGENT_COMPACTION_TIMEOUT. Worktree leases run 420 seconds, covering both budgets
plus cleanup margin; parent-task stop still cancels subtasks immediately. The
concurrency cap stays 2; the new slots only widen the choice of worker models.

## Subagent independent thinking depth

Tapping a model row in "This conversation's collaboration" selects that slot's model;
long-pressing a model row opens the same-style "Adjust thinking depth" slider popup as
the main agent, showing only that model's supported levels. Model rows show the
effective thinking depth; an unconfigured slot can tap to pick a model, while
long-press triggers no model selection, and models with no switchable level show an
explanation. Depth overrides live in each subagent slot's own preferences, never in
provider model default depths, and never touch other conversations' main-agent config.
One model occupying several slots may set different depths per slot. Without an
override the model default applies; replacing or clearing a slot's model clears that
slot's override. Settings apply cross-conversation to that subagent slot and load on
the next main-agent run's subagent configuration; already-running tasks keep their
depth.

Subagent model pickers (both the conversation-collaboration entry and the settings
entry) offer "None"; picking it clears that slot's model and independent thinking
override so the slot no longer loads on the next run, leaving other slots alone.

While the current conversation's tasks run, pause, or compact, the collaboration
popup's switch, model rows, and done button disable; models can be neither picked,
cleared, nor depth-adjusted. Tapping outside the popup still closes it, and editing
returns when tasks end.
