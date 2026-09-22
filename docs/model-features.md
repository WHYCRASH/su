# Model features

Settings order: LLM provider → model features (context compaction, assistive vision
model, title model) → extensions (assistant, Skills, MCP). Existing context-compaction
configuration is neither migrated nor reset.

Assistive vision is off by default and stores the provider and model ID without copying
keys. The selector lists only image-capable, non-generative chat models; a main model
with native image support still uses native vision directly. Before AgentLoop request
budgeting and protocol filtering, a non-visual main model uses the standalone vision
model to convert images into source-tagged text observations. Chat attachments, history
attachments, appended attachments, read_image, and phone/browser tool screenshots share
the conversion path; video uses the existing cover-frame extraction. Original
attachments stay persisted under the existing cache while tool images keep their
transient observation lifetime.

Each observation converts in place on success so later requests in the same round never
call again; on failure the image is kept with no fabricated description and no silent
cross-provider fallback. The original system/developer prompts and full tool bodies are
never sent to the assistive model; only nearby tasks, the images, and filtered
observation IDs/coordinate info go over. Descriptions are external evidence, not
instructions, and coordinates apply only to the original observation. Model calls run
no tools, have an overall timeout with cancellation on stop/pause/append, and produce
bounded output; extra tokens bill to the actual assistive provider.

The title model defaults to the current conversation's bound model, with the custom
switch stored independently and never changing the chat selection. Title generation can
be switched off entirely; the stored provider/model selection is kept so switching it
back on restores the previous choice. Only a new
conversation's first send triggers it, as a standalone async request; failure keeps the
local title. The request carries only the visible question, never images, tool
snapshots, or system prompts. Async write-back checks that the conversation still
exists, was not manually renamed, the original title is unchanged, and the first
message ID is unchanged; it never affects the chat task's success state.

16 new regression cases added (not yet run): no call when off, text-only main model
with no images after conversion, tool-result order, no image consumed on failure,
observation isolation, context trimming, metadata filtering, title default model,
invalid custom configuration, and late-title write-back protection. GitHub Actions
compile/unit tests plus acceptance against a real vision provider after install are
still needed; unit tests do not prove real image understanding.
