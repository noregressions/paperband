---
id: slides
oneliner: "One card per 16:9 slide — a printable deck, and optionally a real .pptx."
index: [slides, decks, PowerPoint]
---

# Slides

A slide is a page with a fixed size and a hard height budget, and paperband already
addresses cards rather than pages, starts a new page per card, and can fail a build when a
card runs long. So a deck is a page geometry, a theme, and optionally a different renderer
— not a different pipeline. The same markdown that prints as a chapter can print as a
talk.

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

`16x9` is 13.333×7.5in — 960×540pt, exactly what PowerPoint calls widescreen. Zero margins
because a slide is full-bleed and Chromium paints nothing into a page margin; the `deck`
theme supplies its own inset instead, sized against `--pw-page-margin-*` so it still looks
right if a book sets margins anyway.

State those margins rather than assuming the preset brings them. `page.size:` replaces the
**sheet only** — margins keep whatever was already in force, which with no `<pageSize>` is
A4's 20mm. Leave them off and the deck lays out against 150.5mm of height instead of
190.5mm, so every slide's content rides high with a band of dead space beneath it. Nothing
errors; it just looks wrong.

```command
mvn paperband:build -Dpaperband.output=target/deck.pdf
```

That is a complete slide deck as PDF. Everything below is refinement.

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

Three regions, and no ceremonial headings for the common case. Text **before the first
heading** is the `intro` block, which the theme places as the slide body — so a plain
bulleted card needs no `## Body`. `## Aside` (or `## Figure`) opens a second column.
`## Notes` is speaker notes: hidden by the theme, never printed, and lifted into the notes
pane when you export to PowerPoint.

## Slots, and the stray-heading rule

The deck template does its placement through `card.slots` (see Extending), and touching
that at all switches on the layout engine's placement invariant: every top-level block must
be consumed by a named slot, or the build fails naming the card and the block.

The bundled theme softens that with a catch-all — a block no slot claimed is routed to the
speaker notes and warned about, rather than failing the build:

```output
WARN  Card 'typo': a block matched no slot and was routed to speaker notes —
      "Notez This heading is a typo for Notes." Name it in the deck template's
      slot skeleton if it belongs on the slide.
```

That is the safety net, not the notes mechanism: authored notes belong in `## Notes`. A
theme that wants the strict behaviour instead deletes the `card.slots.rest()` block from
its `_card-body.html`, and an unrecognised heading then fails the build outright.

## The one-page budget

`maxPagesPerCard: 1` is the pairing that earns the geometry. Paperband measures each card's
real height and fails the build when one exceeds its budget — so an overstuffed slide is a
build error naming the card and the region that crossed the boundary, before anyone sees
it on a projector:

```output
[ERROR] Page-count check failed: 1 card(s) exceeded their page limit.
[ERROR]   free: pages 3-4 (2 pages, limit 1, from paperband.yaml) — page 4 overflow
[ERROR]       first crossed by "slide-body" (starts page 5)
```
{.fs--1}

The check measures the DOM rather than the finished file (see Page Enforcement in the
Advanced section), so it works the same whether you are producing a PDF or a `.pptx`.

## PowerPoint output

The `render-pptx` module turns the same book into a deck of native, editable PowerPoint
shapes. It is not a plugin dependency by default — it pulls Apache POI, twelve jars of it —
so a book that wants it opts in:

```xml
<plugin>
  <groupId>dev.noregressions.paperband</groupId>
  <artifactId>paperband-maven-plugin</artifactId>
  <version>0.1.2</version>
  <dependencies>
    <dependency>
      <groupId>dev.noregressions.paperband</groupId>
      <artifactId>render-pptx</artifactId>
      <version>0.1.2</version>
    </dependency>
  </dependencies>
</plugin>
```

```command
mvn paperband:build -Dpaperband.renderer=pptx \
  -Dpaperband.output=target/deck.pptx
```

Two things make the result a deck rather than an import. Each card's **slot signature**
picks a stock PowerPoint layout — `title+body` becomes *Title and Content*,
`title+body+aside` becomes *Two Content* — so cards of one shape share one `slideLayout`
and PowerPoint's outline view, layout switching and theme recolouring all work. And the
regions fill **typed placeholders** rather than free-floating text boxes, which is what
those features key off.

Content lands in one of two tiers. Prose, headings and bullets become native editable text,
carrying the font, size, weight and colour the theme resolved. A diagram, table or code
fence has no text-placeholder equivalent, so it becomes a picture of itself captured from
the same page at 2× and placed at the rect it occupied — pixel-exact, and not editable in
PowerPoint. Change the markdown and rebuild.

There is no CSS parser anywhere in this. The renderer reads `getComputedStyle` from the
Chromium paperband already drives, so the cascade, `var()`, `calc()`, `@media` and
flex/grid are all resolved by the browser and read back as settled numbers.

## Watch Out

Declare the page as the **`16x9` slug**, not an equivalent `{width, height}` map. A size the
resolver does not recognise is treated as having no curated theme rules, so it derives a
font scale from the page width — 1.61× for a 13.3in sheet — and multiplies the deck theme's
type scale by it until every card overflows its budget. An exact `{13.333, 7.5, inch}` map
does resolve to the same recognised value, but `13.33` does not, and the failure reads like
a theme problem rather than a spelling one.

Writing your own deck theme: give the card `min-height`, never `height`. A fixed height
makes an overflowing card clip silently while the page measurer still reports one page, so
`maxPagesPerCard` passes and the slide quietly loses its last bullets. With `min-height`
the overflow becomes a second page and the budget check catches it by name. For the same
reason, avoid `vh` units — both measuring passes use a very tall viewport, so `vh` caps
nothing; size against `--pw-content-height`, which is the printable height the build
actually resolved.

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
