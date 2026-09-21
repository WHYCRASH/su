# Model switcher and conversation binding audit

## Scope and verification level

Covers the current working tree's AgentAppState, model-switcher projection and input controls, SettingsDataStore, RuntimeConfigRepository, ProviderRepository, conversation persistence, attachment preparation, and Runtime config entry points. This round only audits and adds this report: no production code modified, no Kotlin/Robolectric tests or builds run. The risks below are derived from code paths and async timing analysis — not presented as on-device reproductions.

## Overall conclusion

Conversations do persist providerId + internal modelId, and normal sends prefer resolving that binding; binding is not entirely absent. But the UI is a "global selection -> switcher -> currently selected conversation" two-way write-back structure with no conversation ownership or versioned operation tokens. Conversation restore, manual model switches, send preparation, provider invalidation, and similar paths can show display/binding/actual-request mismatches.

## Confirmed issues

### 1. High: async restore and manual selection pin no operation to a conversation; stale operations can contaminate a new conversation

Evidence:
- `AgentAppState.kt:1104-1130` selectModel takes only a modelId and asynchronously writes the global Settings without capturing the target conversationId/bindingRevision.
- `:1194-1207,4190-4233` conversation switches asynchronously restore the global selection, saving no Job, cancelling no stale restore, and never verifying the restore operation still belongs to the current conversation.
- `:4168-4187` bindCurrentConversationModel uses the selectedConversationId captured when the callback runs, not the conversation that started the operation.
- restoringConversationModel is just a global Boolean; any single bind callback can consume and clear it, never matching a target model or restore request.

Reachable timing: open A to start restoring MA, then immediately open B to start restoring MB; when a stale restore or Settings event arrives late, the UI still accepts it and may write MA back onto B. Picking a model manually on A and instantly switching to B carries the same risk. collectLatest only constrains the collecting task — it cancels neither separately launched restore coroutines nor an already-written global selection.

Fix: treat the conversation model binding as the source of truth; selection events carry conversationId + providerId + modelId + revision/generation. Stale results are ignored; background global-selection changes must never auto-rebind the current conversation.

### 2. High: sends allowed while model switch/restore unfinished; displayed and actual models can diverge

Evidence:
- `AgentChatInputBar.kt:193-200` canSend checks compression, window, and content, with no model-binding readiness condition.
- `AgentAppState.send` has no isChanging/restoringConversationModel check.
- `selectConversation` swaps homeState immediately while modelPickerState waits on the global flow; restoreConversationRuntimeModel never sets picker isChanging.
- Manual selectModel's finally clears isChanging, which still does not mean the target selection has been received and applied.

Impact: sending right after a switch may still use the old binding; when the conversation binding has changed but the switcher has not, the UI shows the old model while the request uses the new conversation's model. Wait until the config and capability snapshot for the same model reference are ready, with double protection on both the button and the send entry point.

### 3. High: silent fallback when the original binding is unavailable may send a conversation to a different provider

Evidence:
- `AgentAppState.kt:4235-4243` runtimeConfigForBoundModel falls back to currentRuntimeConfig when resolving the given binding fails, instead of returning "binding unavailable".
- `:4190-4217` restore binds the switcher's currently selected model straight onto the current conversation when the model is not found.
- ProviderRepository.repairSelection picks another available item for an invalid global selection.

Scenario: original model deleted/deactivated, provider deleted, imported conversation keeping a model ID unknown on this device. The UI/send flow may use a different model or provider with no explicit reselection. Beyond model quality and cost, this also changes where conversation data is sent.

Fix: a clearly old-but-unavailable binding must show "model unavailable" and block sending until the user rebinds; only genuinely unbound new drafts may adopt the default model.

### 4. High: bound-config resolution misses the provider isEnabled check

`RuntimeConfigRepository.kt:164-170` configForProviderAndModel checks provider existence and model.isEnabled but not provider.isEnabled — inconsistent with the global-selection repair, switcher filtering, and setSelectedModelIdIfPresent rules.

So bound conversations/background tasks can still resolve to a disabled provider's address and credentials when parsing config. The fix belongs in the same availability-resolution entry point; list invisibility is not execution-layer deauthorization.

### 5. High: staged attachments returning across conversations are never checked against the send source; another conversation's model and state can be mixed in

Evidence:
- `AgentAppState.kt:1445-1477` send captures A's conversationId/history/attachments and vision capability, then asynchronously stageChatImages.
- Back on the main thread it checks only homeState.isStreaming and compression state — never whether selectedConversationId, draft/version, or model selection are still the values from when the send started.
- `:1489-1569` startPreparedSend starts a task addressed at the old conversationId/history but reads messages, state, and reasoningEffort from the latest homeState; the latter carry the current conversation's providerId/modelId.

Scenario: send an image-bearing message on A, switch to B while images stage; the callback may launch an A-addressed task with B's model/message state, or wipe B's draft. This affects actual send preparation, not just the switcher icon.

Fix: an immutable PendingSendSnapshot binding conversationId, draftRevision, the complete model reference/capability, message and attachment ownership; after a conversation switch, deliver only to the original conversation and never splice in fresh homeState; or abort and keep the original draft.

### 6. Medium-high: model config and capability/request type come from two different sources

`AgentAppState.kt:1975-1988,2040,2085-2099`: the request config comes from the captured conversation state, while the image/video-generation branches, supportsVideo, and some token/compression estimates come from the global modelPickerState; async preparation re-reads the global picker midway. compressionContextWindow also prefers the global picker's window.

Result: the wrong image/video-generation executor may be picked, attachments mishandled, context size misjudged, or compression triggered too early/late. Runtime's final media filtering and budget checks can block some of the errors but cannot restore attachments filtered out after a wrongly chosen execution branch.

Fix: each send resolves exactly one run model snapshot; model, type, modality, window, reasoning config, and provider parameters all derive from that snapshot.

### 7. Medium: switching conversations first rewrites the new conversation's reasoning preference with the old model's capability

`AgentAppState.kt:1195-1205` selectConversation calls target-state withCurrentReasoningCapabilities with the current currentReasoningCapabilities before the target model is restored, then persistConversations. Models differ in disablability/tiers, so the new conversation's saved reasoningEffort may change; the target-model restore then operates on the already-modified value.

Backup-restore reloadConversationsAfterBackup has a similar early normalization. It does not break on every switch — compatible capabilities look fine — but the conversation's reasoning preference is not guaranteed preserved.

Fix: keep the user's raw preference and compute effectiveReasoningEffort once the target model is ready, instead of permanently overwriting with a normalization under old capabilities.

### 8. Medium: deleting the current conversation auto-selects the next without restoring its model

`AgentAppState.kt:1324-1363` deleteConversation auto-selects nextId, updates homeState and the list, but never calls restoreConversationRuntimeModel; it also normalizes the next conversation under old capabilities.

Result: the next conversation's content and providerId/modelId have switched while the switcher may still show the deleted conversation's model. Normal config resolution may use the next conversation's binding, but vision/generation branches still feel the stale picker.

Fix: every "selected conversation changed" entry point reuses the same binding-restore flow — ordinary taps, post-delete auto-select, new drafts, import restore, external archive entry.

### 9. Medium: the global-selection observer has an inconsistent-snapshot window

observeRuntimeSelection combines selectedProviderIdFlow and selectedModelIdFlow separately, then asynchronously reads currentRuntimeConfig for capabilities. SettingsDataStore.setSelection writing the pair in one edit is correct, but combining two independent flows plus an extra config read never guarantees each UI application comes from one selection/provider version; ProviderRepository repair itself also writes the global selection.

The risk is timing-related; no scheduling test was run to reproduce it, so it is not described as certain. The recommended fix is a single Selection(providerId, modelId, revision) flow with config resolved against that exact reference; picker, capability, and binding applied together, with the conversation-operation generation verified.

## Verified fine / not false positives

- AgentConversationStore and ConversationEntity genuinely save and load providerId/modelId, and sends in the stable serial case prefer that binding.
- Model.id is the ProviderModelEntity global primary key; the API model name lives in Model.modelId. Different providers sharing an API name is not an internal ID collision, so "passing only modelId must cross providers" cannot be asserted on that basis. Full-reference bindings with consistency checks are still recommended.
- Ordinary chat RunRequests carry a complete ModelConfig; RuntimeRequestConfigResolver substitutes global config only for voice sources. Once an ordinary chat enters AgentLoop it uses the pinned config — a later global-selection change alone never hot-swaps an already-running request to another model.
- selectModel during pause calls abandonPausedRun: the old task stops, then the model changes — not a lossless hot-swap of the original run. The UI should explain this behavior.
- A running conversation can still be rebound by a global model-pick event from the settings page, so display/subsequent defaults differ from the running pinned config; that is a different matter from "the running request was hot-swapped".
- The existing RuntimeConfigRepositoryTest mostly checks config construction; AgentModelPickerProjectorTest mostly checks list filtering, selection projection, and token display. No regression tests were found covering AgentAppState async restore, fast conversation switching, deletion, invalid bindings, or mid-attachment-prep switching.

## Fix and regression priority

1. Pin conversation-level model references and operation generations; never write sessions back from global listeners.
2. Forbid silent fallback on invalid bindings; check provider/model availability in one place.
3. Pin immutable snapshots for send/attachment prep; forbid sends while a binding is not ready.
4. Share one restore flow across all selected-conversation changes; separate raw reasoning preference from effective capability.
5. Regression: A/B/C fast switching, send-immediately-after-model-switch, switch-conversation-mid-image-send, delete-current-conversation, provider disable/delete, import with missing model, background run racing a settings-page switch, target-model restore failure/cancellation, restart restore, differing reasoning and modality models.

This report is an audit result; it does not claim any of the above is fixed. Commits, pushes, and GitHub Actions builds still need user authorization.
