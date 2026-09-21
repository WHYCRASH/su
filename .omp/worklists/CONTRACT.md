# su americanization — translation contract

Repo root: `/home/dautist/Projects/su` (Android app, Kotlin, package `io.github.mangi.eta`).

The product has been americanized: it is named `su` (lowercase), maintained by `dautist`.
Every piece of repository-authored Chinese prose must be replaced with natural, idiomatic English.

## What to translate
1. Line comments (`//`) and block comments (`/* */`), including KDoc.
2. String literals that are prose the user, the model, or logs can see: UI text, error and status
   messages, tool descriptions, system prompts, trace/summary text, README-ish prose inside `"""…"""`.
3. Prose inside resources, assets, or docs assigned to you.

Translate meaning, not words. Write the English a native engineer would write: short, concrete,
no marketing filler. Keep the original tone (imperative for messages, descriptive for comments).

## What MUST NOT change (leave byte-for-byte identical)
- Identifiers of every kind: package, class, function, variable, parameter, constant, file names.
- Keys and schema names: Kotlin constant names, JSON field names, XML attribute/resource names,
  DataStore/Room/SharedPreferences keys and column names, MCP/HTTP header names, Xposed entry names.
- Wire/protocol values, model IDs, provider IDs, Android package names, component names, URIs, URLs,
  file paths, CLI flags, environment variable names, regex syntax, prompt-marker tags.
- Placeholders and interpolation: `%1$s`, `%2$d`, `$var`, `${expr}`, `\"`, `\n`, `\\` — copy exactly,
  with the same count, order, and numbering of positional arguments.
- Test fixture data whose purpose is to exercise non-ASCII/Unicode handling, and functional
  keyword/regex matching used to classify or match real input (e.g. a matcher that recognises the
  Chinese word for "open" in user text). Leave those literals alone and list them in your report.
- Third-party/vendored text and legal notices.

## Hard rules
- Never reorder, add, or delete code. Replace only the Chinese text in place.
- Never rename a symbol, even if the name looks Chinese-derived (transliterate nothing).
- Never run `./gradlew`, formatters, linters, or the test suite.
- If a Chinese literal is user-visible and a test asserts its exact text, update the test too —
  but only if that test file is in your assigned list. Otherwise report it instead of editing.
- Comments that document persisted/legacy data may be reworded but must not change any literal value.

## Definition of done
You have no Han characters left in your assigned files except the documented exclusions
(functional keyword matchers, Unicode test fixtures, legal notices you were told to keep).

Run this to verify (adjust the file list path):

```bash
python3 - <<'PY'
import re
from pathlib import Path
han = re.compile(r'[\u3000-\u303f\u4e00-\u9fff\u3400-\u4dbf\uf900-\ufaff\uff00-\uffef]')
root = Path("/home/dautist/Projects/su")
files = [l.strip() for l in open("<YOUR-WORKLIST>.txt") if l.strip()]
for f in files:
    t = (root / f).read_text(encoding="utf-8")
    if han.search(t):
        print("REMAINING", f)
        for i, line in enumerate(t.splitlines(), 1):
            if han.search(line):
                print(f"  {i}: {line.strip()[:160]}")
PY
```

Report: files changed, anything you intentionally left in Chinese with a one-line reason,
and any test outside your list that asserts a literal you changed.
