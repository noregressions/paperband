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
