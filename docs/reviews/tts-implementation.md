# TTS first implementation and acceptance checklist

## Scope and status

Working tree `/workspace/eta-turn-fix`, branch `feat/tts`, based on `ee235a9`.
This round modifies source and tests only; nothing committed, pushed, run through CI, or installed. Version stays 5.3.0 / 2026091802.
Uncommitted `/workspace/Eta` content never mixed in.

## User behavior

- General settings gain "Read aloud", defaulting to system mode with no autoplay.
- Once an answer body finishes and the result action bar appears, a play/stop button is offered; thinking cards, tool logs, user messages, and conversation-reference protocol blocks are never read.
- System mode only picks voices the engine declares local and installed; when missing it prompts instead of downloading or quietly going online.
- Cloud mode requires explicitly picking a provider/model and entering a voice ID, and only uses OpenAI-compatible `audio/speech` MP3 responses. A model catalog is not proof of API compatibility.
- Cloud mode sends cleaned body text, with the UI explicitly disclosing cost, data transmission, and synthesized-speech nature.
- No automatic cross-service fallback or replay; system mode can be re-selected in settings.
- Manually read finished answers are segmented by sentence/length; cloud mode prefetches at most the next segment. Not reading answer text while it generates, and not instant playback of an MP3 byte stream.

## Underlying design of the fixes

- Blocking CountDownLatch replaced: async system-engine init, one engine per session, UUID utterance IDs, per-sentence completion waits; cancel ends the wait immediately plus stop/shutdown.
- Single controller + epoch + Mutex: stale callbacks never update new playback state; a new session starts only after the previous session finishes cleanup.
- HTTP uses enqueue with coroutine cancellation into Call.cancel; 90-second total request deadline, 8 MiB per-segment cap, streaming reads under the same size limit.
- MIME and MP3 markers checked; HTML/JSON error pages refused; raw error bodies and platform exceptions never shown directly (may contain keys/body text).
- Player prepares asynchronously with timeout protection and finally release; private cache files deleted after play/cancel, crash-leftover audio cleaned at next boot.
- Audio focus acquired/released; playback stops on focus loss, headphone unplug, conversation/page switch, backgrounding, or stopping the current task.
- Starting the round-button dictation first cancels read-aloud and waits for audio resources to release before opening the mic; read-aloud refuses to start during dictation.
- Stopping sound never changes the Agent run/turn/history and never triggers model retries.
- Body text extracted with the existing JetBrains GFM AST; no more global deletion of underscores and similar symbols; segmentation by Unicode code point, never breaking emoji; invalid segmentation parameters refused outright.
- Dedicated TTS models excluded from chat/summary pickers, with direct-call protection added in ProviderClientFactory; audio-output modality alone never proves Speech-API compatibility.
- AndroidManifest gains TTS service queries; default English plus Simplified/Traditional Chinese UI resources filled in.

## Automated verification

25 new tests (not yet executed):

- `SpeechSpeakableTextTest`: empty input/code blocks, links/images, literal underscores, parameter boundaries, Unicode segmentation, ordering, long input, HTML.
- `SpeechPlaybackEpochTest`: stale callbacks, stop-cancels-identity, repeated stops.
- `CloudSpeechSynthesizerTest`: request-field isolation, explicit voice, error web pages, HTTP errors without leaking, size cap, real Call cancellation, MP3/MIME, URL.
- `SpeechModelIsolationTest`: chat vs voice picker isolation, direct Agent-call refusal.
- `SpeechSynthesisModelsTest`: dedicated models, generic audio models never misjudged, OAuth/Anthropic excluded.

Done: git diff --check, per-language resource XML parse/duplicate-key/TTS-reference checks, Manifest XML parse.
Not done: Kotlin compilation, unit tests, Release packaging, on-device audio tests. Static checks are not equivalence to passing compilation.

## Required on-device acceptance

1. Default system mode: an installed local Chinese voice plays; with no local voice the error is clear, with no implicit cloud request.
2. Trial listen, bubble playback, stop-while-loading, stop-mid-play, tapping different answers in succession — no cross-talk/residue.
3. Stop mid-init, retry after timeout — stale callbacks never end new playback.
4. Conversation switch/background/headphone-unplug/focus-loss stop promptly; voice input stops sound first.
5. With half a segment already played and the next cloud request failing, playback neither restarts from the top nor auto-switches services.
6. Cloud 401/429/5xx, HTTP-200 HTML/JSON, and oversized audio all exit loading state with no key/body-text leaks.
7. Actual pronunciation of mixed Chinese/English, links, tables, code, emoji; check inter-sentence gaps and voice consistency.
8. Stopping read-aloud never ends the task; stopping the current Agent task may cancel current read-aloud; old conversations/turns unaffected.
9. Actual effect of local TTS without cross-talk, plus legacy voice-input button position and layout regression.

## Official docs checked

- Android TextToSpeech API: async init, stop/shutdown, manifest queries, voice.
- OpenAI Text-to-speech guide: Speech model/input/voice, MP3 format, and synthesized-speech disclosure.
- Markdown dependency stays on the project's existing JetBrains GFM parser; no new runtime libraries.
