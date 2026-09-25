---
id: slides
oneliner: "One card per 16:9 slide — a printable deck, and optionally a real .pptx."
index: [slides, decks, PowerPoint]
---

# Slides

A slide is a page with a fixed size and a height budget. Paperband already starts a new page
per card and can fail a build when a card runs long, so a deck needs only a page geometry, a
theme and, optionally, a different renderer. It uses the same pipeline as any other book.

## The recipe

```yaml
# paperband.yaml
theme: deck
page:
  size: 16x9
  margins: { top: 0, right: 0, bottom: 0, left: 0 }
vars:
  maxPagesPerCard: 1
```

`16x9` is 13.333×7.5in (960×540pt), PowerPoint's widescreen size. Margins are zero because a
slide is full-bleed and Chromium paints nothing into a page margin. The `deck` theme supplies
its own inset, sized against `--pw-page-margin-*`, so it still lays out correctly if a book
sets margins.

Declare the margins explicitly. `page.size:` replaces the sheet only; margins keep the value
already in force, which with no `<pageSize>` is A4's 20mm. Without them the deck lays out
against 150.5mm of height instead of 190.5mm, leaving empty space at the bottom of every
slide. No error is reported.

```command
mvn paperband:build -Dpaperband.output=target/deck.pdf
```

That produces a complete slide deck as a PDF.

## Writing a slide

The `deck` theme *places* blocks into a fixed skeleton rather than looping them in document
order, so a card's headings decide which region its content lands in:

````markdown
---
title: "What we get for free"
---

- Card-per-slide, enforced by `max_pages: 1`
- Mermaid and PlantUML already render into pages
- Cross-references, includes, targets, themes

## Aside

Slots make each region addressable.

## Notes

Pause on max_pages — it is the bit PowerPoint cannot do.
````

There are three regions. Text before the first heading is the `intro` block, which the theme
places as the slide body, so a plain bulleted card needs no `## Body`. `## Aside` (or
`## Figure`) opens a second column. `## Notes` holds speaker notes: hidden by the theme, not
printed, and placed in the notes pane when exported to PowerPoint.

## Slots, and the stray-heading rule

The deck template places blocks through `card.slots` (see Extending). Using `card.slots`
enables the layout engine's placement check: every top-level block must be consumed by a
named slot, or the build fails naming the card and the block.

The bundled theme adds a catch-all: a block no slot claims is routed to the speaker notes
with a warning instead of failing the build:

```output
WARN  Card 'typo': a block matched no slot and was routed to speaker notes —
      "Notez This heading is a typo for Notes." Name it in the deck template's
      slot skeleton if it belongs on the slide.
```

The catch-all is not the notes mechanism; authored notes belong in `## Notes`. For strict
behaviour, a theme removes the `card.slots.rest()` block from its `_card-body.html`, and an
unrecognised heading then fails the build.

## The one-page budget

`maxPagesPerCard: 1` enforces one slide per card. Paperband measures each card's rendered
height and fails the build when one exceeds its budget, naming the card and the region that
crossed the boundary:

```output
[ERROR] Page-count check failed: 1 card(s) exceeded their page limit.
[ERROR]   free: pages 3-4 (2 pages, limit 1, from paperband.yaml) — page 4 overflow
[ERROR]       first crossed by "slide-body" (starts page 5)
```
{.fs--1}

The check measures the DOM rather than the finished file (see [Page Enforcement](card:page-enforcement)), so it works the same whether you are producing a PDF or a `.pptx`.

## PowerPoint output

The `render-pptx` module renders the same book as a deck of native, editable PowerPoint
shapes. It is not a plugin dependency by default because it pulls in Apache POI (twelve
jars), so a book adds it explicitly:

```xml
<plugin>
  <groupId>dev.noregressions.paperband</groupId>
  <artifactId>paperband-maven-plugin</artifactId>
  <version>0.1.3</version>
  <dependencies>
    <dependency>
      <groupId>dev.noregressions.paperband</groupId>
      <artifactId>render-pptx</artifactId>
      <version>0.1.3</version>
    </dependency>
  </dependencies>
</plugin>
```

```command
mvn paperband:build -Dpaperband.renderer=pptx \
  -Dpaperband.output=target/deck.pptx
```

Each card's slot signature selects a stock PowerPoint layout: `title+body` becomes *Title
and Content*, `title+body+aside` becomes *Two Content*. Cards of one shape share one
`slideLayout`, and the regions fill typed placeholders rather than free-floating text
boxes, so PowerPoint's outline view, layout switching and theme recolouring work.

Prose, headings and bullets become native editable text, with the font, size, weight and
colour the theme resolved. A diagram, table or code fence has no text-placeholder
equivalent, so it becomes a picture captured from the page at 2× and placed at the
rectangle it occupied. Pictures are not editable in PowerPoint; change the markdown and
rebuild.

The renderer does not parse CSS. It reads `getComputedStyle` from the Chromium instance
paperband already drives, so the cascade, `var()`, `calc()`, `@media` and flex/grid are
resolved by the browser.

## Watch Out

Declare the page with the `16x9` slug, not an equivalent `{width, height}` map. An
unrecognised size gets an automatic font scale from the page width (1.61× for a 13.3in
sheet), which multiplies the deck theme's type scale and makes every card overflow its
budget. An exact `{13.333, 7.5, inch}` map resolves to the preset; `13.33` does not.

In a custom deck theme, give the card `min-height`, not `height`. With a fixed height an
overflowing card is clipped while the page measurer still reports one page, so
`maxPagesPerCard` passes and content is lost. With `min-height` the overflow becomes a
second page and the budget check reports it. Do not use `vh` units: both measuring passes
use a very tall viewport, so `vh` constrains nothing. Size against `--pw-content-height`,
the printable height the build resolved.

## Check

```command
mvn paperband:build -Dpaperband.renderer=pptx \
  -Dpaperband.output=target/deck.pptx -Dpaperband.reportPages=true
```

```output
[INFO] Wrote 5 slide(s) at 960x540pt to target/deck.pptx
[INFO]   layout Title and Content  <- title+body x3
[INFO]   layout Two Content  <- title+body+aside x2
[INFO]   3 figure(s) placed as pictures (diagrams, tables, code fences)
[INFO] Renderer 'pptx' does not produce a PDF; skipping the PDF post-passes
[INFO] Page-count check passed: 5 card(s) checked, all within their limits.
```
{.fs--1}
