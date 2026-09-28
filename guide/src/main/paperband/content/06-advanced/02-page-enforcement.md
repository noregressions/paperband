---
id: page-enforcement
oneliner: "Fail the build when a card overflows its allotted page budget."
---

# Page Enforcement

Page enforcement checks how many pages each card spans after rendering and can fail the
build when a card runs long. Use `<reportPages>` for a per-card page-count table, or set a
ceiling with `<maxPagesPerCard>` (or a per-card `max_pages` in frontmatter).

## Setting limits

```bash
mvn paperband:build -Dpaperband.input=mybook -Dpaperband.output=out.pdf -Dpaperband.maxPagesPerCard=2 -Dpaperband.reportPages=true
```

`<maxPagesPerCard>` is the global ceiling. A card can carry its own budget in
frontmatter, which overrides the flag for that card:

```yaml
---
id: gc-deep-dive
max_pages: 5
---
```

Cards with neither limit are reported but never enforced. On violation the build prints
each offending card with its count, its limit and the limit's source, then exits with code
`3`. Code `2` means bad input or an unknown renderer, so CI can distinguish the two.

## The report

`<reportPages>` prints a table after a book build:

```
KIND          ID              START  PAGES  LIMIT
cover         (cover)             1      1
axis-divider  Tier 1              2      1
card          gc-deep-dive        3      4      5
card          quick-fix           7      3     2*
(* = global limit from <maxPagesPerCard>)
```

A starred limit came from `<maxPagesPerCard>`; an unstarred one from that card's own
frontmatter. Axis dividers, section dividers, the cover, and the back page each get a row
of their own kind. The same table can be produced from an already-built PDF, without
rebuilding:

```bash
mvn paperband:pages -Dpaperband.pdf=out.pdf -Dpaperband.byPages=true # sort longest-first
mvn paperband:pages -Dpaperband.pdf=out.pdf -Dpaperband.cardsOnly=true # hide cover/divider/back rows
```

## How counting works

Page counts come from the PDF's **named destinations**. The book template plants a hidden
anchor for the cover, the back page, every axis and section divider, and every card;
when the renderer turns those anchors into PDF destinations, each element's span is the
distance to the next anchor's page.

The renderer must emit named destinations. Chromium does, so the bundled `playwright`
renderer supports the feature. If a PDF has no destinations, `build` prints a warning and
skips the checks without failing; in that case a passing build means the cards were not
checked.

## Check

```bash
mvn paperband:pages -Dpaperband.pdf=out.pdf
```

If it reports no recognised anchors, the renderer didn't emit destinations and any
`<maxPagesPerCard>` on your build was a no-op.
