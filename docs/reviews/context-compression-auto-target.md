# Compression target "Auto" option

Status: implementation complete; the user has authorized committing it and building via GitHub Actions; the new tests added with the commit have not been run yet, so the corresponding CI records are authoritative. No version-number change.

## Two entry points

- Settings → auto compress: the target list becomes "Auto / 500 / 1000 / 2000 / 4000".
- Menu → manual compress: uses the same option list and auto-target resolution function, with "Auto" to the left of 500.
- Narrower screens allow the buttons to wrap; no advanced menu is added and no compression-strategy selection is changed.
- The two existing preference keys are reused; the settings auto-compress choice and the menu manual-compress choice each remember the user's selection without overwriting each other. Existing fixed values and the 2000 default are not migrated.

## Computation and plumbing

- 0 means "auto"; the value is preserved through saving, reading, the Runtime Bundle, the Controller, and the Loop — never clamped to 500 early.
- Compute only after trimming completes and the protected region is fixed: target roughly 10% of the pending-to-summarize prefix's estimated tokens, normally 1000–8000; then constrained by 1/32 of the main conversation window and by remaining space, with a 500 floor.
- Remaining space deducts the protected tail, request overhead, reserved model output, a safety margin, plus a 512-token summary-index reserve; double the target is reserved to match the existing summary-length acceptance.
- When there is not enough space, fail explicitly — never silently truncate the protected tail; the estimate is not an exact tokenizer and does not guarantee the provider call will succeed.
- The main-model window governs the summary-retention budget; the summary-model window governs whether the summary-generation request may be sent; the two are never mixed.
- The auto-resolved number applies to that one request only and is never written back to preferences; the next request recomputes. Fixed tiers keep existing behavior.
- Generation-cap and OUTPUT_LIMIT limited retries are preserved; "auto" does not mean unbounded generation or waived length acceptance.

## Verification

13 new Kotlin regression cases: option order, preference compatibility, fixed tiers, content-adaptive sizing, main-window limit, tail and request overhead, no-space refusal, no-window fallback, output reserve, summary-request integration, Bundle/Controller plumbing, the two preference keys, and in-flight manual-compress target resolution.

`git diff --check`, parsing of the three resource XML files plus duplicate-resource-name checks, option/copy wiring checks on both UIs, and a fixed-floor residue scan have been run. No Kotlin unit tests or Android builds have been run, so CI or on-device acceptance cannot be claimed on that basis.
