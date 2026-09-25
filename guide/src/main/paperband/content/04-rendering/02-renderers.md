---
id: renderers
oneliner: "Playwright is the only HTML-to-PDF renderer paperband ships with."
---

# Renderers

Paperband renders a book's HTML through a renderer. `playwright` (headless Chromium) is the only
renderer on the plugin's own classpath, and the only one that produces a PDF; `pptx` is an
optional add-on (see [Slides](card:slides)).
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
  use (see [Page Enforcement](card:page-enforcement)).
- One source of page geometry. The book's `page:` settings give the sheet size passed to
  Chromium and the book's `@page` margin rule. A stylesheet with its own
  `@page { margin }` overrides that rule while `--pw-content-height` still describes the
  configured margins, so the content box no longer matches the printed page. Set margins
  in `page:`, not in CSS.
- Per-card rotation, via a named `@page` rule: a card whose folder declares
  `page.orientation` gets every sheet it occupies rotated, inside the same single render
  pass. See [Config Cascade](card:config-cascade).
- Page JavaScript runs before the snapshot. The renderer waits for network-idle, then
  `document.fonts.ready`, then every promise a page script has pushed into
  `window.paperbandPending`. This is how ` ```mermaid ` diagrams are fully rendered
  before the PDF is written (see [Card Structure](card:card-structure)). A rejected
  promise fails the render with the script's error, so a diagram that doesn't parse fails
  the build.

## Renderers that don't produce a PDF

`pptx` writes a slide deck instead of a PDF, and a book can add other renderers. How a
renderer declares its output, and which PDF-only steps are skipped for it, is covered in
[Extending Paperband](card:extending).

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
