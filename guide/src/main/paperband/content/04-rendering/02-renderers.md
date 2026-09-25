---
id: renderers
oneliner: "Playwright is the only HTML-to-PDF renderer paperband ships with."
---

# Renderers

Paperband renders HTML to PDF through a pluggable `HtmlToPdfRenderer`, discovered via
`ServiceLoader`. `playwright` — real Chromium, driven headless — is the only renderer
paperband ships *on the plugin's own classpath*, and the only one that produces a PDF.
Select a renderer with `<renderer>` in the POM or `-Dpaperband.renderer` (default
`playwright`), and check availability with
`mvn paperband:renderers`. The renderer is a build setting; there is no `renderer:` key in
`paperband.yaml`.

## Why only one

Two earlier renderers, `openhtmltopdf` (pure Java, no external deps) and `weasyprint`
(shelled out to a Python binary), were removed. With more than one renderer, page geometry
could differ between them, so a book could render correctly under one and incorrectly under
another. The cost of a single renderer is the ~300 MB Chromium download on first run and no
pure-Java fallback.

## What Playwright gives you

- CSS support matches a current browser: flexbox, grid and `color-mix` render as they do
  in Chromium.
- Named destinations in the output PDF. Every anchor paperband writes (cover, back,
  dividers, cards) becomes a PDF destination, which page-count reporting and enforcement
  use (see Page Enforcement in the Advanced section).
- One source of page geometry. The resolved `PageSpec` sets the sheet: its size is passed
  to Chromium, and its margins are emitted as the book's `@page` rule. Chromium applies a
  CSS `@page` margin over `Page.pdf()`'s margin options, so both come from the same
  `PageSpec`. A book stylesheet that declares its own `@page { margin }` wins on cascade
  order while `--pw-content-height` still describes the resolved margins, so the content
  box no longer matches the printed page. Set margins in `page:`, not in CSS.
- Per-card rotation, via a named `@page` rule: a card whose folder declares
  `page.orientation` gets every sheet it occupies rotated, inside the same single render
  pass. See Config Cascade.
- Page JavaScript runs before the snapshot. The renderer waits for network-idle, then
  `document.fonts.ready`, then every promise a page script has pushed into
  `window.paperbandPending`. This is how ` ```mermaid ` diagrams are fully rendered
  before the PDF is written (see Card Structure in the Authoring section). A rejected
  promise fails the render with the script's error, so a diagram that doesn't parse fails
  the build.

## Renderers that don't produce a PDF

The SPI's name is historical: the contract is HTML in, one file out. A renderer whose
output isn't a PDF returns `false` from `producesPdf()`, and the build skips the four passes
that reopen the output with PDFBox: two-pass page-number resolution for a printed
toc/index, the full-page-cover splice, the watermark stamp, and the bookmark outline.
Without this, PDFBox would fail on the non-PDF output after the render had succeeded.

The page-budget check is outside that guard: it measures the DOM rather than the finished
file, so a non-PDF renderer still gets per-card enforcement. `render-pptx` is the only such
renderer in the repo, an optional module rather than a plugin dependency. See Slides.

## Setup notes

Playwright always reports itself as available. A missing Chromium is detected at render
time, with a message explaining how to install it (`playwright install chromium` via the
module's exec goal, or point `PLAYWRIGHT_BROWSERS_PATH` at an existing download). Browsers
cache under `~/.cache/ms-playwright/`.

## Watch Out

`mvn paperband:renderers` shows an `AVAILABLE` column, but `build` has no fallback: a
missing Chromium fails the build. Pre-cache the browser for offline or CI builds.

## Check

```bash
mvn paperband:renderers
```

```
NAME              AVAILABLE  DESCRIPTION
playwright        yes        Headless Chromium via Playwright. Honours PageSpec.size and PageSpec.margins.
```
