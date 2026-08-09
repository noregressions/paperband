---
id: renderers
oneliner: "Playwright is the only HTML-to-PDF renderer paperband ships with."
---

# Renderers

Paperband renders HTML to PDF through a pluggable `HtmlToPdfRenderer`, discovered via
`ServiceLoader`. `playwright` — real Chromium, driven headless — is the only renderer
paperband ships with. Select it with `--renderer` (`-r`, default `playwright`) and check
availability with `paperband renderers`. Renderer choice is CLI-only — there is no
`renderer:` key in `paperband.yaml`.

## Why only one

Two earlier renderers, `openhtmltopdf` (pure Java, no external deps) and `weasyprint`
(shelled out to a Python binary), were removed. Both read page geometry from `@page` CSS
rules; Playwright instead treats `PageSpec.size`/`PageSpec.margins` as the sole authority
and ignores a theme's own `@page` rule. Having two disagreeing geometry authorities meant a
book could look right under one renderer and wrong under another. Standardising on
Playwright removed that whole class of bug, at the cost of the ~300 MB first-run Chromium
download and losing the zero-dependency pure-Java fallback.

## What Playwright gives you

- Highest CSS fidelity: modern layout (flexbox, grid, `color-mix`) renders as it would in
  a real browser.
- Named destinations in the output PDF — every anchor paperband plants (cover, back,
  dividers, cards) becomes a real PDF destination, which is what powers page-count
  reporting and enforcement (see Page Enforcement in the Advanced section).
- `PageSpec.size`/`PageSpec.margins` as the sole geometry authority, so a book's page
  dimensions and margins never depend on which renderer happened to build it.

## Setup notes

Playwright reports itself as always available — a missing Chromium only surfaces at render
time, with a hint telling you how to install it (`playwright install chromium` via the
module's exec goal, or point `PLAYWRIGHT_BROWSERS_PATH` at an existing download). Browsers
cache under `~/.cache/ms-playwright/`.

## Watch Out

`paperband renderers` shows an `AVAILABLE yes/no` column, but `build` does **not** fall
back automatically: a missing Chromium install fails the build rather than silently
degrading fidelity. Pre-cache the browser download for offline/CI builds.

## Check

```bash
paperband renderers
```

```
NAME              AVAILABLE  DESCRIPTION
playwright        yes        Headless Chromium via Playwright. Honours PageSpec.size and PageSpec.margins.
```
