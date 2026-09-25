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

The names are arbitrary. A target is an identifier, not a bundle of settings: it does not
set the page size (that is the separate `<pageSize>` parameter, default `a4`) or pick a
renderer. It reaches the build as a string that content can branch on.

## Selecting a target

```bash
mvn paperband:build -Dpaperband.input=mybook -Dpaperband.output=out.pdf -Dpaperband.target=pdf-6x9 -Dpaperband.pageSize=BOOKLET_6X9
```

The default is `pdf-a4`. The value is not validated against the declared list, so a
misspelt `<target>` matches no conditions. The declared list is documentation, not an
enforced enum.

## Page sizes

`<pageSize>` and `page.size` (yaml, case-insensitive) share the same slugs:

| Slug | Dimensions | Notes |
|---|---|---|
| `a4` | 210×297mm | Default |
| `a5` | 148×210mm | Zero margins — full-bleed card/booklet themes |
| `letter` | 8.5×11in | |
| `legal` | 8.5×14in | `page.size` only — no `<pageSize>` slug yet |
| `6x9` | 6×9in | Standard trade-paperback trim |
| `packt`, `7.5x9.25` | 7.5×9.25in | Packt Publishing's paperback trim: close to A4's width, shorter |
| `16x9`, `slide` | 13.333×7.5in | PowerPoint's widescreen slide (960×540pt). Zero margins, like `a5`; the theme supplies the inset. See [Slides](card:slides) |

A size outside this list works too, via `page.size: { width, height, unit }` — see
[Book Configuration](card:book-configuration) and [Configuration Cascade](card:configuration-cascade) for the full `page:` block (margins, orientation,
fontScale, measure) and for why `size` and `margins` are **book scope**: they are read from
the book's own `paperband.yaml` only, and a folder that sets them fails the build. The named
presets above, except `packt`, have per-theme `font-size` rules. Any other size gets an
automatic scale derived from its width relative to A4.

This is why `16x9` is a named preset. At 13.3in wide the automatic scale is 1.61×, which
multiplies a deck theme's type scale that was already chosen for a 16:9 sheet, and every
slide overflows. Presets are recognised by value, so the equivalent `{width, height}` map
also works, but only at exactly those dimensions (`13.333`, not `13.33`). Use the slug.

The size name that reaches the CSS is the resolved one. PDF pages stamp `size-{slug}` on
`<html>` from the sheet actually used, not from the `<pageSize>` the build was launched
with, and the `Built book …` log line reports the same canonical name (`7.5x9.25` reports
`packt`; a sheet no preset covers reports its dimensions, `200x150mm`). A book whose yaml
says `6x9` is therefore typeset by the theme's `html.size-6x9` rule even though
`<pageSize>` defaults to `a4`. A custom `{width, height}` sheet matches no `size-*` rule,
which lets its automatic font scale apply: a theme's `html.size-a4 { font-size: 11pt }`
would otherwise outrank the `html` rule that reads the scale.

The bundled themes carry `size-a4`, `size-letter` and `size-6x9` rules only. Any other sheet
is typeset from the theme's `html` baseline, multiplied by `--pw-font-scale` where set. See [Themes](card:themes#what-theme-css-can-target).

## Where a page's insets come from

Four layers push content away from the paper edge, and only the first is a page margin.
When a full-bleed build still shows a margin, the cause is usually the last one:

| Layer | Set by | Repeats per page? |
|---|---|---|
| Page margin | `page.margins`, or the plugin's `<margins>` | Yes — it's the physical printable area |
| Body safety padding | Built in: `max(0mm, 8mm - page margin)`, horizontal only | Yes |
| Text measure | The theme's `--card-max-width`, retunable per book with `page.measure` | Yes |
| Card padding | The theme, repeated per page via `box-decoration-break: clone` | Yes |

The measure looks like a margin but is a line length. `classical` sets 38rem in print, which
on A4 is 147mm of text on a 210mm page, leaving 23mm either side that no margin setting
removes. Set it from the book:

```yaml
page:
  margins: { top: 0, right: 0, bottom: 0, left: 0 }
  measure: 58rem        # or a length in mm, or `none` for the full width
```

`measure` is stamped inline on `<html>` as `--card-max-width`. Theme CSS is inlined after a
book's own stylesheet, so setting that property in book CSS loses to the theme's `:root`
rule; the inline value outranks both.

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

Plain-string entries are always included; only map entries carry conditions. To gate
prose inside a card rather than the whole card, branch in the card body: it sees `target`
(the raw build target) and `output` (`print` or `site`), as in
`{% if output == 'print' %}`. See [Tables from Data](card:tables-from-data) for an example.

## Watch Out

`<target>` accepts any string, so a mismatch with the string in a `where:` predicate
(`pdf-6x9` vs `pdf_6x9`) produces no error; the guarded content is omitted. When an
expected card is missing, check the walker's stderr output and the predicate spellings.
