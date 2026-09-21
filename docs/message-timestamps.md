# Message timestamps

Settings → Appearance → Message timestamps, off by default. The switch only controls
display; it does not affect time recording.

Timestamps appear only in the action bar at the end of a successfully completed reply
round, on the right side, formatted as yyyy-MM-dd HH:mm in the device time zone.
No timestamp is backfilled for in-progress streaming, tool progress, error notices,
or old messages without a reliable time.

Completion time is recorded from the Runtime's RunFinished event, travels to the UI
through IPC and persisted event JSON, and is stored as generatedAtMillis on the final
reply. Restoring a reply reuses its stored time or the completion event's time; the
restore/page-open time must never stand in. Image/video generation uses the
corresponding task completion time.

Database migration 27 → 28 only adds a nullable generated_at_millis column and does
not backfill old records; conversation save/import/export carries the time through
the message entity. The appearance setting is stored through the existing DataStore
and backup chain.

New tests: completion-time replay, last-reply positioning, leaving older rounds untouched, no
fabricated times for old messages, time-zone formatting, and terminal-state restore.
Existing Room migration, conversation save/load, and appearance-setting persistence
cases are extended. XML, diffs, and the SQLite migration SQL are checked; Kotlin/Android
unit tests and APK compilation have not been run yet.

## Build verification

2026-09-20: commit `00c3849`, GitHub Actions `35505059621` green, all 1,759 unit tests
passed (no failures, errors, or skips), including this doc's regression cases. The signed
5.3.0 APK was delivered to the phone's download folder; it was not auto-installed and
no post-install UI acceptance was performed.
