---
id: renderers
oneliner: "Playwright is the only HTML-to-PDF renderer paperband ships with."
---

# Renderers

Paperband renders HTML to PDF through a pluggable `HtmlToPdfRenderer`, discovered via
`ServiceLoader`. `playwright` — real Chromium, driven headless — is the only renderer
paperband ships with. Select it with `--renderer` (`-r`, default `playwright`) and check
availability with `mvn paperband:renderers`. Renderer choice is a build setting — there is no
`renderer:` key in `paperband.yaml`.

## Why only one

Two earlier renderers, `openhtmltopdf` (pure Java, no external deps) and `weasyprint`
(shelled out to a Python binary), were removed. Having two disagreeing geometry authorities
meant a book could look right under one renderer and wrong under another. Standardising on
Playwright removed that whole class of bug, at the cost of the ~300 MB first-run Chromium
download and losing the zero-dependency pure-Java fallback.

## What Playwright gives you

- Highest CSS fidelity: modern layout (flexbox, grid, `color-mix`) renders as it would in
  a real browser.
- Named destinations in the output PDF — every anchor paperband plants (cover, back,
  dividers, cards) becomes a real PDF destination, which is what powers page-count
  reporting and enforcement (see Page Enforcement in the Advanced section).
- One geometry authority. The resolved `PageSpec` fixes the sheet: its size is passed to
  Chromium directly, and its margins are emitted as the book's `@page` rule. Chromium
  honours a CSS `@page` margin over `Page.pdf()`'s own margin options, so emitting them
  from the same `PageSpec` the renderer is handed is what keeps the two agreeing — a book
  stylesheet that declares its own `@page { margin }` would win on cascade order while
  `--pw-content-height` kept describing the resolved one, and the content box would then
  describe a page that isn't the one being printed. Set margins in `page:`, not in CSS.
- Per-card rotation, via a named `@page` rule: a card whose folder declares
  `page.orientation` gets every sheet it occupies rotated, inside the same single render
  pass. See Config Cascade.
- Page JavaScript runs before the snapshot. The renderer waits for network-idle, then
  `document.fonts.ready`, then every promise a page script has pushed into
  `window.paperbandPending` — the settle contract behind ` ```mermaid ` diagrams
  reaching the PDF fully rendered (see Card Structure in the Authoring section). A
  rejected promise fails the render with the script's own error, so a diagram that
  doesn't parse is a build failure, not a half-drawn page.

## Setup notes

Playwright reports itself as always available — a missing Chromium only surfaces at render
time, with a hint telling you how to install it (`playwright install chromium` via the
module's exec goal, or point `PLAYWRIGHT_BROWSERS_PATH` at an existing download). Browsers
cache under `~/.cache/ms-playwright/`.

## Watch Out

`mvn paperband:renderers` shows an `AVAILABLE yes/no` column, but `build` does **not** fall
back automatically: a missing Chromium install fails the build rather than silently
degrading fidelity. Pre-cache the browser download for offline/CI builds.

## Check

```bash
mvn paperband:renderers
```

```
NAME              AVAILABLE  DESCRIPTION
playwright        yes        Headless Chromium via Playwright. Honours PageSpec.size and PageSpec.margins.
```
