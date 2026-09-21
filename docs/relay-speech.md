# Relay speech synthesis

Add or fetch CosyVoice2 and MOSS-TTSD models through a regular OpenAI-compatible provider,
then select them in the read-aloud settings. The standalone "add speech synthesis" entry
is removed; models saved through the old entry keep working without re-entering the model,
endpoint, or credentials, and no longer depend on the unpersisted compatible_speech type.
The old navigation enum stays deserializable for compatibility but now opens the regular
configuration form.

Read-aloud settings must select the engine and voice by the selector-resolved modelId
(the API model name), never by the database record UUID. Speech models on regular
providers are available for reading aloud but still do not appear in the chat model list.

The previously used longxiaochun_v2 and default aliases returned HTTP 400 Invalid voice
on the user's relay. Per https://docs.siliconflow.cn/docs/userguide/capabilities/text-to-speech ,
preset voices need the upstream model prefix: CosyVoice2 uses presets such as
FunAudioLLM/CosyVoice2-0.5B:alex (eight presets), MOSS-TTSD uses presets such as
fnlp/MOSS-TTSD-v0.5:alex (eight presets). Full model names keep their own prefixes.
Both short-alias models were tested against the existing endpoint and credentials with
the short test utterance "hello", and each returned HTTP 200 with audio/mpeg and an MP3 header.
Credentials and generated audio are not stored in the repo. Whether other relays use
the same naming is determined by their own API documentation.

Regression coverage: entering the read-aloud list after a database round trip, UUID/model-name
separation, old-entry configuration compatibility, and requests going to the configured
/audio/speech endpoint with the model alias and correct voice fields preserved.
