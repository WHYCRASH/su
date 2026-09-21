# Conversation input drafts

Text input state is held per conversationId by ConversationDrafts in AgentAppState.
Home, conversation page, settings return, and conversation switching share one editing
state, including in-process cursor and selection. A not-yet-sent new conversation uses
a standalone draft slot that migrates to the conversation ID when the conversation is
first created.

Input flows through snapshotFlow into a standalone SharedPreferences store; rebuilding
AppState restores the text. Typing never touches the message list, conversation order,
or the chat database, and never triggers a full transcript save. Attachments keep the
existing conversation state; attachment cross-process persistence is not extended.

Deleting a conversation also cancels its draft observation and deletes its storage;
deleting all conversations clears drafts. Pre-send validation failure keeps the input;
after sending starts, only that conversation's draft is cleared. Returning to a
conversation that is still streaming does not clear the input. After an appended message
receives the Runtime acceptance event, the input clears only if the text still equals
the submitted content, so later edits are never overwritten. Editing a history message
keeps the original draft, and cancelling the edit restores it.

Regression cases cover per-conversation independent editing and cursor, text
persistence, late updates after deletion, new-conversation draft migration, and
send-time cleanup. Android unit tests and compilation have not been run yet; on-device
acceptance after a build must verify settings round trips, A/B conversation switching,
typing mid-stream, send-validation failure, and restart recovery.

## Build verification

2026-09-20: commit `00c3849`, GitHub Actions `35505059621` green, all 1,759 unit tests
passed (no failures, errors, or skips), including this doc's regression cases. The signed
5.3.0 APK was delivered to the phone's download folder; it was not auto-installed and
no post-install UI acceptance was performed.
