# su

<p><img src="https://img.shields.io/badge/minSdk-34-3DDC84?logo=android&amp;logoColor=white" alt="minSdk 34"> <img src="https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&amp;logoColor=white" alt="Kotlin 2.4.10"> <img src="https://img.shields.io/badge/AGP-9.3.2-3DDC84?logo=android&amp;logoColor=white" alt="AGP 9.3.2"></p>

**A system-level Android AI assistant that steps past the sandbox**

`su` is more than a chat box. Point your own model at the phone: it can call system APIs, see the screen, run a terminal, read and write files, and look up notifications, calendars, photos, and other local data. Asking and doing can happen in the same conversation.

It is a fork of [Eta](https://github.com/Mangi-11/Eta), maintained by [dautist](https://github.com/WHYCRASH). Upstream built the agent runtime, system entry points, and tool layer; this fork keeps iterating on daily use: compression, backup and restore, share-to-open, vision models, the browser, and the Linux environment. **You supply your own API key (BYOK)** and choose the model.

**What it can actually reach:**

- **System:** alarms, volume, media, and device status through Android APIs, without walking through menus.
- **Screen:** an accessibility GUI agent for taps, scrolls, and typing. You can stop or take over at any time.
- **Terminal:** Android shells plus Alpine / Debian Linux, including files, scripts, and daemon tasks.
- **On-device data:** notifications, photos, calendars, SMS, and more. Some sources need root and a matching app.
- **System integration:** a privileged system app shipped by the KernelSU/ReSukiSU NoMount module (`:app:assembleKsuModule`), plus an accessibility service, a notification listener, and a VoiceInteractionService for the ASSIST role.

Requires **Android 14 or later**. The app is not vendor-locked; core features work without root. Root and the privileged-system-app module unlock more system access and assistant integration, depending on permission and ROM support.

[Downloads](https://github.com/WHYCRASH/su/releases) · [Getting started](#getting-started) · [Issues](https://github.com/WHYCRASH/su/issues)

## See it in action

| GUI Agent in action |
| :---: |
| <img src="docs/Screenshots/demo_gui_agent.gif" width="320" alt="su GUI Agent in action"> |

More screenshots: chat, system tools, and settings

| Chat | Direct system API calls |
| :---: | :---: |
| ![Chat](docs/Screenshots/chat_home.jpg) | ![Direct system API calls](docs/Screenshots/chat_device_direct.jpg) |

| Settings | Tools | Skills |
| :---: | :---: | :---: |
| ![Settings](docs/Screenshots/settings.jpg) | ![Tools](docs/Screenshots/tools.jpg) | ![Skills](docs/Screenshots/skills.jpg) |


## Core capabilities

### Execution tools

- **Direct system API calls:** use Android APIs and system intents to set alarms, control media, adjust volume, and read device status without navigating through app screens.
- **GUI Agent:** combine the accessibility UI tree, element targeting, and screenshots taken as needed to tap, scroll, and type. An overlay shows execution status, and you can stop or take over.
- **Built-in browser:** load JavaScript pages in a WebView, extract readable content, interact with the DOM, and capture screenshots. You can open the same browser session to take control.
- **Terminal and files:** use Android `user`/`root` shells, Alpine or Debian Linux, file operations, and scripts, with support for persistent sessions, asynchronous commands, and daemon tasks.

A task can combine these tools: read web sources and then organize files with a script, or find order details in notifications and open the relevant app to check their status.

### Context and extensions

- **Personal context:** retrieve notifications, app usage, and location on demand. Dedicated searches for photos, calendar events, and SMS messages require root; availability also depends on the installed apps.
- **Long-term memory:** store context for future conversations in a local `MEMORY.md`. Core memory is included within a context budget, with the rest retrieved as needed. You can edit, clear, or disable it.
- **Skills:** load task instructions, reference material, and script resources as needed. Install from public GitHub repositories or import a local ZIP. Installation does not run scripts or grant additional permissions.
- **MCP:** connect remote tools over Streamable HTTP, with optional bearer-token authentication. Enable tools individually to use them alongside local tools.

### Agent runtime

The runtime runs inside `su`. Requests from chat and system assistants use the same agent loop: the model selects tools through tool calling, execution results return to its context, and it decides what to do next. Calls are validated against JSON Schema and permissions are checked before execution.

The runtime also manages streaming events, steering, cancellation, and incremental transcripts. Steering messages enter after the current turn completes. Conversations and results are stored locally; after an interruption, `su` attempts to recover existing records without automatically replaying actions. See [Agent Runtime](docs/AGENT_RUNTIME.md) for implementation details.

## A terminal designed for mobile

You can use the terminal yourself or let the agent use it. Each session retains its working directory and environment. The compact view groups input and output by command; the PTY console supports TUIs, keyboard shortcuts, and ANSI rendering. Asynchronous commands and daemon tasks have logs and explicit stop controls.

- **Linux environments:** choose Alpine or Debian. PRoot works without root; rooted devices can also use chroot. The backends have separate installations, with no automatic data migration. PRoot's simulated root identity does not grant Android system privileges.
- **Development tools:** install Python, Node.js, SSH, and APK analysis tools as needed.
- **File management:** import and export files through the private workspace, share accessible Android directories under `/workspace/mounts/` in Linux, and browse Linux files from the app.

`su` itself can read projects, edit code, run commands, and verify results.

## Models and BYOK

The AI features require **your own model-provider API key**. Add OpenAI-compatible or Anthropic Messages services, fetch model lists, or add models by hand. There are no built-in providers; keys and configuration live in your own backup.

The provider layer supports OpenAI-compatible Chat Completions, the Responses API, and Anthropic Messages, including SSE streaming, tool calling, image input, and reasoning content. Configure custom endpoints, headers, and request bodies; fetch model lists or add models manually; and override context windows and reasoning effort. Available features depend on the model and API. Some Responses providers also support server-side web search.

## System assistant entry points

Set su as the digital assistant with the plain Android setting: **Settings -> Apps -> Default apps -> Digital assistant**. The power button follows that choice; there is no power-key takeover inside the app.

The assistant entry opens the text conversation panel, with screen context and follow-up conversations.

## Permissions and data

System tools, sensitive reads, sensitive actions, terminal and file access, browsing, and memory have separate switches, currently enabled by default. The runtime rechecks permissions before execution. Revoked access or a lost connection does not overwrite your saved settings.

- **Model requests:** task-relevant conversation content, images, and tool results are sent to your configured provider. A local runtime does not imply local inference. Custom HTTP endpoints transmit API keys and request content without transport encryption.
- **Local records:** raw arguments and results from sensitive tools and MCP tools are excluded from persistent conversation history; model replies are still saved. Once notification access is granted, `su` retains up to 1,000 notifications for seven days. MCP authentication tokens are stored encrypted.
- **Conversations and backups:** copy or edit messages, delete a conversation from a selected turn onward, and regenerate replies. Import or export conversations, model configurations, and memory. Backups contain API keys.
- **Execution limits:** tasks can be stopped or taken over. Background work remains subject to Android and OEM process management; restart tasks manually after a force-stop or reboot.

## Getting started

1. Download the APK from [Releases](https://github.com/WHYCRASH/su/releases). After installation, open **Model provider** in Settings, enter your API key, and select a model. Task execution requires tool calling; interpreting images also requires image input support.
2. Enable the tools and permissions you need. GUI control requires the accessibility service. Notification access and usage access are granted separately; location tools require **Allow all the time**. The tools page shows what is available on your device.
3. Start a conversation. For Linux, install a distribution, base tools, and any development tools you need under **Linux tool environment**. For assistant integration, set su as the digital assistant (see [System assistant entry points](#system-assistant-entry-points)).

- **Unrooted devices:** Android 14+ supports chat, browsing, memory, Skills, MCP, the ordinary terminal, and a private workspace. GUI control and personal data access need their respective permissions. Linux is available on supported 64-bit devices.
- **Rooted devices:** gain access to protected system settings, app management, privileged files, dedicated personal-data searches, root shells, and chroot.
- **Privileged system app via the KernelSU/ReSukiSU module:** grants `WRITE_SECURE_SETTINGS` and the other privileged capabilities (including force-keep accessibility enforcement). Some features also require root.

Dedicated searches for contacts, SMS messages, and calendar events still require root. See [Device Support](docs/ROOTLESS_SUPPORT.md) for full requirements and validation coverage.

## About upstream

This project is derived from [Eta](https://github.com/Mangi-11/Eta). The original author's design notes remain in the [upstream README](https://github.com/Mangi-11/Eta/blob/main/README.md). They explain why Eta was built this way; they are not this fork's product story.

## Further reading

- [Device support and permissions](docs/ROOTLESS_SUPPORT.md): unrooted and rooted devices, the file workspace, and background execution.
- [Technical implementation](docs/TECHNICAL.md): system tools, data retrieval, browser, terminal, and system integration.
- [Agent Runtime](docs/AGENT_RUNTIME.md): the agent loop, providers, steering, transcripts, and result recovery.
- [Native terminal components](docs/TERMINAL_NATIVE.md): PTY and PRoot components, and rebuilding the bundled source.

## References and acknowledgements

- [Eta](https://github.com/Mangi-11/Eta): the upstream project this fork is based on.
- [Pi Coding Agent](https://github.com/earendil-works/pi): the main reference for the agent runtime, including the agent loop, tool calling, steering, and transcript state management.
- [OmniBot](https://github.com/omnimind-ai/OmniBot): a reference project for AI agents on Android.
- [Miuix](https://github.com/compose-miuix-ui/miuix): the UI component library.

## License

`su` uses the same [PolyForm Noncommercial License 1.0.0](LICENSE) as upstream Eta. Selling, paid installation, and other commercial use require written permission from the [original author](https://github.com/Mangi-11).
