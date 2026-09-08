---
id: targets
oneliner: "Targets name output profiles and gate target-scoped content via where: predicates."
---

# Targets

A build target names a concrete output profile — for example `pdf-a4`, `pdf-6x9`, or `web`.
Targets are declared in the book's `paperband.yaml` and selected with `<target>`;
they also drive conditional inclusion, so a subtree can be marked web-only or print-only.

## Declaring targets

`targets:` in the root `paperband.yaml` is a flat list of names:

```yaml
title: "Complete Book"
targets:
  - pdf-a4
  - pdf-6x9
  - web
```

The names are yours to choose — a target is an identifier, not a bundle of settings. It
does **not** set the page size (that's the separate `<pageSize>` parameter, default `a4`) or
pick a renderer. What it does is flow into the build as the string your content can
branch on.

## Selecting a target

```bash
mvn paperband:build -Dpaperband.input=mybook -Dpaperband.output=out.pdf -Dpaperband.target=pdf-6x9 -Dpaperband.pageSize=BOOKLET_6X9
```

The default is `pdf-a4`. The value is not validated against the declared list — a typo'd
`<target>` silently selects nothing's conditions, so the declared list serves as
documentation and a checklist rather than an enforced enum.

## Page sizes

`<pageSize>` and `page.size` (yaml, case-insensitive) share the same slugs:

| Slug | Dimensions | Notes |
|---|---|---|
| `a4` | 210×297mm | Default |
| `a5` | 148×210mm | Zero margins — full-bleed card/booklet themes |
| `letter` | 8.5×11in | |
| `legal` | 8.5×14in | `page.size` only — no `<pageSize>` slug yet |
| `6x9` | 6×9in | Standard trade-paperback trim |
| `packt`, `7.5x9.25` | 7.5×9.25in | Compact tech-book trim (Packt Publishing's paperback size) — wide like A4 but noticeably shorter, so code samples get more room per line without the page feeling oversized |
| `16x9`, `slide` | 13.333×7.5in | PowerPoint's widescreen slide (960×540pt) — zero margins, like `a5`, because a slide is full-bleed and the theme supplies its own inset. See Slides |

A size outside this list works too, via `page.size: { width, height, unit }` — see
Book Configuration / Config Cascade for the full `page:` block (margins, orientation,
fontScale, measure) and for why `size` and `margins` are **book scope**: they are read from
the book's own `paperband.yaml` only, and a folder that sets them fails the build. Named presets above (except `packt`, a deliberately plain trim with no
curated per-theme type scale yet) have hand-tuned `font-size` rules per bundled theme;
anything else gets an automatic scale derived from page width relative to A4's.

That last sentence is why `16x9` is a *named* preset rather than something you spell out
as a `{width, height}` map: at 13.3in wide, the automatic scale is 1.61×, which lands on
top of a deck theme's type scale that was already chosen for a 16:9 sheet — and every
slide overflows. Recognition is by value, so the longhand map works too, but only at those
exact dimensions (`13.333`, not `13.33`). The slug is the safer spelling.

Whichever way the sheet is chosen, the name that reaches the CSS is the resolved one. PDF
pages stamp `size-{slug}` on `<html>` from the sheet actually used — not from the
`<pageSize>` the build was launched with — and the `Built book …` log line reports that same
name, canonically (`7.5x9.25` reports `packt`; a sheet no preset covers reports its
dimensions, `200x150mm`). So a book whose yaml says `6x9` is typeset by the theme's
`html.size-6x9` rule even though `<pageSize>` still defaults to `a4`, and a custom
`{width, height}` sheet matches no `size-*` rule at all — which is exactly what leaves its
automatic font scale free to apply, since a theme's `html.size-a4 { font-size: 11pt }`
outranks the `html` rule that reads the scale.

Worth checking when you change sheet: the bundled themes carry `size-a4`, `size-letter` and
`size-6x9` rules and no others, so any other sheet is typeset from the theme's bare `html`
baseline (times `--pw-font-scale`, where there is one). See Themes / Stable CSS hooks.

## Where a page's insets come from

Four separate things push content away from the paper edge, and only the first is a page
margin. On a full-bleed build that still looks margined, it's usually the last one:

| Layer | Set by | Repeats per page? |
|---|---|---|
| Page margin | `page.margins`, or the plugin's `<margins>` | Yes — it's the physical printable area |
| Body safety padding | Built in: `max(0mm, 8mm - page margin)`, horizontal only | Yes |
| Text measure | The theme's `--card-max-width`, retunable per book with `page.measure` | Yes |
| Card padding | The theme, repeated per page via `box-decoration-break: clone` | Yes |

The measure is the one that surprises people, because it reads as a margin but isn't: it's
a line-length decision. `classical` sets a Tufte-narrow 38rem in print, which on A4 is
147mm of text in a 210mm page — 23mm of gutter either side that no margin setting will
remove. Retune it from the book:

```yaml
page:
  margins: { top: 0, right: 0, bottom: 0, left: 0 }
  measure: 58rem        # or a length in mm, or `none` for the full width
```

`measure` is stamped inline on `<html>` as `--card-max-width`, which is deliberate: theme
CSS is inlined *after* a book's own stylesheet, so a book that sets that property in its
css loses to the theme's `:root` rule. The inline value outranks both, which makes this the
one reliable way to override a themed measure.

## Target-scoped content

A folder's `order:` list accepts map entries with a `where:` predicate evaluated against
the current target. When the predicate is false, that card or subtree is skipped entirely:

```yaml
# content/paperband.yaml
order:
  - intro
  - { id: interactive-demos, where: "target == 'web'" }
  - { id: print-appendix, where: "target != 'web'" }
  - reference
```

Plain-string entries are always included; only map entries carry conditions. Note the
`target` variable is scoped to these `where:` predicates — card *bodies* see only the
`vars` map (see Vars and Conditionals in the Authoring section), so gate prose with a
`vars:` flag and gate whole cards with `where:`.

## Watch Out

Because `<target>` accepts any string, a mismatch between it and the exact string
in a `where:` predicate (`pdf-6x9` vs `pdf_6x9`) fails silently — the guarded content
just never appears. When a card you expect is missing from the PDF, check the walker's
stderr output and the predicate spellings first.
