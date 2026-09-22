# Rootless and rooted devices

su ships one APK that enables capabilities based on the actual grants. Base features
never wait for root probing; the root grant and the privileged-system-app install show
separately, and denied grants never rewrite the user's saved switches.

Force-keep accessibility is enforced from inside the app process via
`WRITE_SECURE_SETTINGS` (privileged system app only); a plain APK install reports
enforcement failure instead of pretending protection is on. See
[Technical Implementation](TECHNICAL.md#accessibility-protection).

## Capability boundaries

| Feature | Rootless device | Extra requirement |
| --- | --- | --- |
| Chat, models, memory, Skills, MCP, built-in browser | Available, each running under its own switches and configuration | None |
| GUI screenshots, nodes, gestures, input, and waits | Enable the su accessibility service | The privileged module's force-keep accessibility can re-assert the service entry |
| Launching apps, opening links, alarms, and timers | Android foreground Intents | Accessibility is not a precondition |
| Current and historical notifications | Notification-access grant; current notifications need the listener service connected | Root users keep the existing system source |
| App usage, location | The matching Android access grants | Background location needs allow-all-the-time |
| Android Shell, files, and images | App UID, private workspace or granted sources | Root users keep the privileged paths |
| Alpine, Debian, PTY | Run through PRoot | A chroot can be installed additionally |
| System modification, app freezing, private-data reads | Not offered to the model | Root required; some data additionally needs the matching ROM |

The tools page defaults to "current device". Features still missing a plain Android
grant stay discoverable; "all capabilities" shows extra introductions and their actual
requirements, and viewing them requests no permission and grants the model nothing.
Root users' existing configuration locations are unchanged; while disconnected,
the used configuration is kept and no reconnect prompt card appears at the top of the
settings page.

This change adds no unprivileged reads of contacts, SMS, calendar, or similar data.

## Files and Linux data

New rootless workspaces live at `filesDir/terminal-user/workspace`, and PRoot
environments at `filesDir/terminal-user/proot/<distro>`, avoiding the `filesDir/terminal`
parent directory that older versions may have created as root. Existing rootless
workspaces and PRoot environments in the old layout keep their location with no
automatic migration or ownership change; directory picking never depends on the root
grant. Workspaces still map to `/workspace` inside Linux. A file-picker URI that is not
usable as an app-UID-readable path is first imported into the workspace within bounds
and then referenced; directories that cannot be imported directly get an explicit
explanation. Public-directory sharing requests "all files access" on demand; declining
still leaves the private workspace plus import/export.

PRoot and chroot use independent rootfs trees. Old chroots, `/data/local/tmp/eta`, and
privileged shared mounts are not migrated; running sessions and tasks pin the backend
and paths chosen at creation. Root-state changes never delete environments, change
ownership, or auto-switch existing sessions. Simulated root inside PRoot has no Android
root privileges.

The rootless installer reuses the pinned-rootfs download verification, streams and
unpacks in a temp directory, handles archive paths, links, cancellation, and failure
cleanup, and writes the done marker only after the run check passes. Base tools,
Python/uv, Node.js, SSH, and APK analysis install and verify through the selected
backend.

## Running and stopping

Rootless terminals and PRoot tracers hold their lifetime with task references owned by
the execution foreground service. Leaving the page stops none of these tasks; they can
be stopped explicitly through the task entry or the execution notification. The
foreground service is released after the last task ends. The root daemon keeps its
original standalone lifetime and is not bulk-reclaimed by the rootless task service.

Denied notification permission never directly blocks a legitimate launch. Foreground
services still face Android's
[background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
and vendor process management; a rejected launch returns to su for retry. Force-stop or
reboot never auto-replays commands.

## Verification limits and device checklist

Source regression covers tool-metadata completeness, the rootless capability
projection, per-round schema and validation consistency, mid-run permission revocation,
accessibility disconnects, archive unpacking, PRoot command quoting, backend selection,
task references, and routing round trips. Builds and unit tests are not equivalent to
successfully running the native binaries on Android.

The dev environment passed `:app:compileDebugKotlin`, `:app:assembleDebug`, and the
full `:app:testDebugUnitTest` run: 785 passed, 1 skipped for macOS missing `/proc`
(the daemon ownership token's accidental-kill protection check). The APK's 8 new native
ELFs were checked for 16 KiB alignment, four install ABIs, system dynamic dependencies,
and matching source-bundle consistency; `git diff --check` passed.

The directory-isolation fix additionally passed 32 targeted tests covering unwritable
old parent directories, in-place reads of old rootless data, file import, install
unpacking, backend selection, and rootless terminals. On an Android 16 arm64 device, it
was verified that with the old `terminal` owned by root and unwritable, the new
workspace can be created by the app UID, the Debian PRoot base environment installs,
the workspace page reads normally, and host/guest read/write the test file in both
directions. The old chroot directory's inode, owner, permissions, size, mtime, and
ready-marker hash stayed identical before and after verification. Full tool installs on
that device, long background runs, and the complete root-grant switching matrix are not
yet covered.

Full acceptance still needs an Android 14+ device:

- No-root, root-only, privileged-module-only, both: inspect the tool catalog,
  verify deny, 30-second timeout, revoke, disconnect, and recovery;
  confirm switches never reset and viewing all capabilities pops no grant.
- With accessibility on, complete screenshot, node tap, Chinese input, and wait; a
  mid-run disconnect must report an error immediately, and stale nodes or uncertain
  action results must never blindly replay.
- After declining public file access, import files, read/write in the private workspace,
  and export; after granting, check two-way shared-directory reads/writes.
- Install Alpine and Debian PRoot on arm64 and x86_64 environments; verify download
  failure, low space, cancellation, reinstall, and base plus optional tool installs.
  Rooted devices also regress old chroots and shared mounts.
- Run PTY, multi-session, and background commands; verify window resize, Ctrl-C,
  natural exit, stop notification, process reclamation, and logs. Stopping rootless
  tasks never affects the root daemon.
- Tool-view switching and detail-back restore the reading position; re-entering lands
  on the current device by default. Verify narrow screens, large fonts, landscape,
  dark/light themes, system bars, and IME.
- Force-stop and reboot never auto-replay commands; state refreshes and restarts
  manually; saving model configuration pops no grant and shows no sync prompt that a
  rootless user cannot act on.
