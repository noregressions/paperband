---
id: page-enforcement
oneliner: "Fail the build when a card overflows its allotted page budget."
---

# Page Enforcement

Page enforcement checks how many pages each card spans after rendering and can fail the
build when a card runs long. Use `--report-pages` for a per-card page-count table, or set a
ceiling with `--max-pages-per-card` (or a per-card `max_pages` in frontmatter).

## Setting limits

```bash
pagewright build mybook out.pdf --max-pages-per-card 2 --report-pages
```

`--max-pages-per-card` is the global ceiling. A card can carry its own budget in
frontmatter, which **wins over the flag** for that card:

```yaml
---
id: gc-deep-dive
max_pages: 5
---
```

Cards with neither limit are reported but never enforced. On violation the build prints
each offender with its count, limit, and where the limit came from, then exits with code
`3` — distinct from `2` (bad input / unknown renderer), so CI can tell "book too long"
apart from "build broken".

## The report

`--report-pages` prints a table after a book build:

```
KIND          ID              START  PAGES  LIMIT
cover         (cover)             1      1
axis-divider  Tier 1              2      1
card          gc-deep-dive        3      4      5
card          quick-fix           7      3     2*
(* = global limit from --max-pages-per-card)
```

A starred limit came from `--max-pages-per-card`; an unstarred one from that card's own
frontmatter. Axis dividers, section dividers, the cover, and the back page each get a row
of their own kind, so the table doubles as a map of the book's physical structure. The same table is available any time from an already-built PDF, without
rebuilding:

```bash
pagewright pages out.pdf --by-pages      # sort longest-first
pagewright pages out.pdf --cards-only    # hide cover/divider/back rows
```

## How counting works — and what it requires

Page counts come from the PDF's **named destinations**. The book template plants a hidden
anchor for the cover, the back page, every axis and section divider, and every card;
when the renderer turns those anchors into PDF destinations, each element's span is the
distance to the next anchor's page.

This is the catch: **the renderer must emit named destinations.** Chromium does, so the
only renderer pagewright ships with (`playwright`) supports the whole feature. If a PDF
somehow has no destinations, `build` prints a warning and **skips the checks without
failing** — so a passing build means "not checked", not "within budget", in that case.

## Check

```bash
pagewright pages out.pdf
```

If it reports no recognised anchors, the renderer didn't emit destinations and any
`--max-pages-per-card` on your build was a no-op.
