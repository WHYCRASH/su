# Third-party notices

## Native terminal components

su's rootless Linux backend runs [PRoot](https://github.com/termux/proot) as a standalone
process and statically links [talloc](https://talloc.samba.org/) and
[libandroid-shmem](https://github.com/termux/libandroid-shmem). PRoot sources use
GPL-2.0-or-later; talloc uses LGPL-3.0-or-later; libandroid-shmem uses BSD-3-Clause. The
bundled combined PRoot executable is distributed under GPL-3.0-or-later; each
component's copyright and license are retained.

The APK's `assets/native-sources` carries the verified pristine sources plus the
script, PTY sources, and patch bundle auto-generated from the actual build entry;
`assets/licenses` carries the full license texts. Build scripts and instructions live
under [Native terminal components](TERMINAL_NATIVE.md). These standalone third-party
programs keep their open-source license rights, unaffected by the su main project's
additional non-commercial license terms.

## Miuix

su's app UI uses [Miuix](https://github.com/compose-miuix-ui/miuix), which uses
[Apache License 2.0](https://github.com/compose-miuix-ui/miuix/blob/main/LICENSE).

## Material Icons

su's feature icons use the Rounded set of AndroidX Compose Material Icons, provided
through the `material-icons-extended` dependency. The icons and their AndroidX
implementation use
[Apache License 2.0](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt);
see [Material Design Icons](https://github.com/google/material-design-icons) for origin.

## Lucide Atom

The thinking icon uses [Lucide Atom](https://github.com/lucide-icons/lucide/blob/main/icons/atom.svg),
stored as a local VectorDrawable resource with no dependency on the Lucide icon library.
The icon uses the ISC License:

```text
ISC License

Copyright (c) 2026 Lucide Icons and Contributors

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
```

## Android Hidden API Bypass

su uses [Android Hidden API Bypass](https://github.com/LSPosed/AndroidHiddenApiBypass) to
apply the user's predictive-back setting. The library uses
[Apache License 2.0](https://github.com/LSPosed/AndroidHiddenApiBypass/blob/main/LICENSE).

## Lobe Icons

Model and provider brand icons come from
[Lobe Icons](https://github.com/lobehub/lobe-icons),
`@lobehub/icons-static-avatar` 1.13.0. The original 1280×1280 WebP assets were losslessly
scaled to 128×128 without changing colors or proportions, then bundled locally with su.

| su resource | Lobe Icons Avatar |
| --- | --- |
| `provider_logo_openai.webp` | `openai.webp` |
| `provider_logo_anthropic.webp` | `anthropic.webp` |
| `provider_logo_bailian.webp` | `bailian.webp` |
| `provider_logo_deepseek.webp` | `deepseek.webp` |
| `provider_logo_kimi.webp` | `kimi.webp` |
| `provider_logo_mimo.webp` | `xiaomimimo.webp` |
| `provider_logo_minimax.webp` | `minimax.webp` |
| `provider_logo_stepfun.webp` | `stepfun.webp` |
| `provider_logo_siliconflow.webp` | `siliconcloud.webp` |
| `provider_logo_openrouter.webp` | `openrouter.webp` |
| `model_logo_claude.webp` | `claude.webp` |
| `model_logo_qwen.webp` | `qwen.webp` |
| `model_logo_zai.webp` | `zai.webp` |
| `model_logo_chatglm.webp` | `chatglm.webp` |
| `model_logo_gemini.webp` | `gemini.webp` |
| `model_logo_gemma.webp` | `gemma.webp` |
| `model_logo_grok.webp` | `grok.webp` |
| `model_logo_meta.webp` | `meta.webp` |
| `model_logo_mistral.webp` | `mistral.webp` |
| `model_logo_doubao.webp` | `doubao.webp` |
| `model_logo_hunyuan.webp` | `hunyuan.webp` |
| `model_logo_yi.webp` | `yi.webp` |

Lobe Icons uses the MIT License:

```text
MIT License

Copyright (c) 2023 LobeHub

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Optional language runtimes

su does not bundle uv or Node.js in the APK. When the user installs the matching
profile, su downloads the current pinned latest stable artifacts: Debian uses the
official Node.js Linux glibc release, Alpine uses `nodejs-current` from its stable
repositories, and uv downloads the official glibc or musl artifacts for the selected
distribution.

| Tool | Source | License |
| --- | --- | --- |
| uv / uvx | [astral-sh/uv](https://github.com/astral-sh/uv) | Apache License 2.0 / MIT |
| Node.js / npm / npx | [nodejs/node](https://github.com/nodejs/node) | MIT plus third-party licenses inside the release |

## Optional APK analysis tools

su does not bundle the following tools in the APK. When the user installs "APK
analysis" from the Linux tool environment page, su downloads and verifies artifacts
from the pinned official releases; tools live in the user's currently selected Alpine
or Debian environment under their own licenses:

| Tool | Source | License |
| --- | --- | --- |
| JADX | [skylot/jadx](https://github.com/skylot/jadx) | Apache License 2.0 |
| Apktool | [iBotPeaches/Apktool](https://github.com/iBotPeaches/Apktool) | Apache License 2.0 |
| smali / baksmali | [google/smali](https://github.com/google/smali) | BSD 3-Clause License |

JADX's release license travels with the required CLI files; Apktool's, smali's, and
baksmali's licenses and third-party notices stay inside their JAR artifacts. su only
provides verified installation, command entry points, and capability boundaries, and
does not relicense these tools.

When GitHub's actual artifact domains are unreachable or slow, the installer may request
the same public Release URL through `gh-proxy.com`; Node.js artifacts may first be
requested through `cdn.npmmirror.com`. The download endpoint learns the user's network
address and the requested public artifact; su sends it no accounts, cookies, API keys,
or other su data, and keeps verifying the built-in official artifact size and SHA-256
before writing to disk. Users who do not want a download endpoint involved can simply
not install the matching optional archive.
