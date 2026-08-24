---
id: organising-content
oneliner: "Discovery, `order:`, `include:`, and `parts:` — how much of the book's shape you declare."
---

# Organising Content

By default a book's shape is **discovered**: every folder under the book root is walked and
every `.md` file becomes a card, alphabetically. Filename prefixes (`01-`, `02-`) are the
whole ordering mechanism, and a file dropped into a folder appears in the next build.

That's the right default for a book whose folders are already in reading order, and the
wrong one as soon as you want a card list that doesn't match the disk. Four keys move the
dial from discovery towards declaration, and because they all answer the same question —
*what does this directory emit, and in what order* — exactly one applies per folder:

| Key | Meaning | Unlisted files |
|---|---|---|
| `parts:` | Titled groups of subfolders | Discovered and appended after the declared ones |
| `include:` | **Exclusive** list — only these, in this order | **Excluded** |
| `order:` | **Additive** list — these first, then the rest | Appended (alphabetically, or per `sort:`) |
| `sort:` | Order by frontmatter field instead of filename | Sorted, not dropped |

Precedence runs top to bottom: `parts:` wins over `include:`, which wins over `order:`.
Declaring a losing key alongside a winning one is a mistake rather than a merge, and warns
on stderr.

Each folder decides independently, so a book root can declare `parts:` over its folders
while one folder pins an exact card list and its sibling just lets its cards be found.

## `order:` — declare the front, discover the rest

```yaml
# content/paperband.yaml
order:
  - introduction
  - quickstart
```

Those two come first, in that order; everything else in the folder is appended
alphabetically, and a warning names the leftovers — drift between the disk and a declared
order is usually an oversight rather than an intent. Entries resolve to a subdirectory of
that name first, then `<name>.md`.

## `include:` — declare everything

`include:` is the exclusive form: exactly these entries, in this order, and nothing else.

```yaml
# 01-getting-started/paperband.yaml
title: "Getting Started"
include:
  - 02-quickstart
  - 01-introduction
```

The folder emits two cards, quickstart first, and a `99-scratch.md` sitting beside them
stays out of the book entirely — no warning, because exclusion is the declared intent. A
file added to the folder later stays out until it's listed, which is the point: the card
list is a decision, not a consequence of what's on disk.

`sort:` has nothing left to order under `include:` and is ignored.

## `parts:` — declare the book's top-level structure

Without `parts:`, top-level grouping is discovered too: each top-level folder becomes its
own "section", labelled from that folder's own `title:`. `parts:` instead names several
folders and gives the group one title, so a single divider page fronts a run of folders
that belong together:

```yaml
# book root paperband.yaml
title: "My Book"
parts:
  - title: "Foundations"
    folders:
      - 01-getting-started
      - 02-authoring
  - id: reference
    title: "Reference"
    folders:
      - 03-configuration
    landing:
      template: minimal
```

A part behaves as one group everywhere a discovered section would: one PDF divider page,
one site landing page (`<id>.html`), one nav and sidebar entry. `id` defaults to a slug of
the title (`"Foundations"` → `foundations`); declare it explicitly when you want a stabler
URL than the title gives. `landing.template` accepts the same presets and paths a section
folder's own override does (see Book Configuration).

Part order also drives folder order, ahead of any unclaimed content. A `where:` on a part
skips every folder it claims, the same way it works on an `order:` entry:

```yaml
parts:
  - title: "Interactive"
    where: "target == 'web'"
    folders: [demos]
```

## Mixing declaration and discovery

The two dials are independent, so the common shape is a declared skeleton over discovered
cards — the root says which folders group together, each folder decides how much of its
own content it spells out:

```
paperband.yaml            parts: → Foundations [01-getting-started, 02-authoring]
                                   Reference   [03-configuration]
01-getting-started/
  paperband.yaml          include: → exactly two cards, quickstart first
  01-introduction.md
  02-quickstart.md
  99-scratch.md           ← excluded
02-authoring/             ← no yaml: cards discovered alphabetically
  01-cards.md
  02-frontmatter.md
99-appendix/              ← claimed by no part: still its own discovered section
  01-glossary.md
```

Folders no part claims keep behaving as discovered sections, so adding `parts:` doesn't
force you to enumerate the whole book — declare the grouping that matters and leave the
rest alone.

## Declaring it outside the book

Every key above lives in a `paperband.yaml`, so the declaration travels with the content.
The Maven plugin can instead declare the whole structure in the POM and select each part's
cards by glob — `services/*/TRACE.md` and friends — which reaches shapes no directory
layout expresses, like two parts drawing different files out of one folder. See the Maven
Plugin page in the Advanced section.

## Watch Out

Parts and discovered sections share one id namespace, because a part id is used exactly
where a section id would be. Two parts can't claim the same folder (its cards would have no
single group to report), and duplicate part ids are rejected — both fail the build at config
parse time rather than silently reshaping the book.

Part `folders:` are resolved relative to the folder that declares `parts:`, like any
`order:` entry. In a book that keeps its cards under a `content/` wrapper, put `parts:` in
`content/paperband.yaml`.

## Check

```bash
mvn paperband:structure -Dpaperband.input=path/to/book
```

The structure dump shows the resolved grouping and card order — dividers, sections, and
the cards under each — so you can confirm what a declaration produced without rendering a
PDF.
