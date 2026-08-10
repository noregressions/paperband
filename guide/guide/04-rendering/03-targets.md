---
id: targets
oneliner: "Targets name output profiles and gate target-scoped content via where: predicates."
---

# Targets

A build target names a concrete output profile — for example `pdf-a4`, `pdf-6x9`, or `web`.
Targets are declared in the book's `paperband.yaml` and selected with `--target` (`-t`);
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
does **not** set the page size (that's the separate `--page-size` flag, default `A4`) or
pick a renderer. What it does is flow into the build as the string your content can
branch on.

## Selecting a target

```bash
paperband build mybook out.pdf --target pdf-6x9 --page-size BOOKLET_6X9
```

The default is `pdf-a4`. The value is not validated against the declared list — a typo'd
`--target` silently selects nothing's conditions, so the declared list serves as
documentation and a checklist rather than an enforced enum.

## Page sizes

`--page-size` (CLI) and `vars.page.size` (yaml, case-insensitive) share the same slugs:

| Slug | Dimensions | Notes |
|---|---|---|
| `a4` | 210×297mm | Default |
| `a5` | 148×210mm | Zero margins — full-bleed card/booklet themes |
| `letter` | 8.5×11in | |
| `legal` | 8.5×14in | `vars.page.size` only — no CLI enum entry yet |
| `6x9` | 6×9in | Standard trade-paperback trim |
| `packt`, `7.5x9.25` | 7.5×9.25in | Compact tech-book trim (Packt Publishing's paperback size) — wide like A4 but noticeably shorter, so code samples get more room per line without the page feeling oversized |

A size outside this list works too, via `vars.page.size: { width, height, unit }` — see
Book Configuration / Config Cascade for the full `page:` block (margins, orientation,
fontScale). Named presets above (except `packt`, a deliberately plain trim with no
curated per-theme type scale yet) have hand-tuned `font-size` rules per bundled theme;
anything else gets an automatic scale derived from page width relative to A4's.

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

Because `--target` accepts any string, a mismatch between the flag and the exact string
in a `where:` predicate (`pdf-6x9` vs `pdf_6x9`) fails silently — the guarded content
just never appears. When a card you expect is missing from the PDF, check the walker's
stderr output and the predicate spellings first.
