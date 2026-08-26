---
id: organising-content
oneliner: "Discovery, `order:`, `include:`, and `sections:` — how much of the book's shape you declare."
---

# Organising Content

By default a book's shape is **discovered**: every folder under the book root is walked and
every `.md` file becomes a card, alphabetically. Filename prefixes (`01-`, `02-`) are the
whole ordering mechanism, and a file dropped into a folder appears in the next build.

## The conventional layout

A book laid out at `src/main/paperband` needs no `<input>` at all — every goal defaults
to it, the way `src/main/java` never needs declaring:

```
src/main/paperband/
  paperband.yaml     ← book config (title, theme, axes, vars, css, sections)
  content/           ← the cards; the walk descends here
  layouts/           ← Pebble templates and snippets
  styles/            ← the css chain's files
```

When the book root contains a `content/` directory, only `content/` is walked for cards —
`layouts/` and `styles/` sit beside it as non-content. A book without the wrapper keeps
working: the whole root is walked as before, except that root-level `layouts/` and
`styles/` directories are always skipped (a markdown snippet in `layouts/` is a template
asset, not a card). This guide is itself laid out this way.

## `ignore:` — keep files out of the book

Any folder's `paperband.yaml` can declare files the walk should skip, gitignore-style:

```yaml
ignore:
  - "*.tmp.md"      # no slash: matches the basename at any depth below here
  - drafts/**       # with a slash: a path glob relative to this folder
  - scratch         # a bare name: that file or folder (and its subtree)
```

An `ignore:` applies to the subtree beneath the yaml that declares it, so a folder can
hide its own drafts without the book root knowing. It filters discovery *and* declared
lists — an `order:`/`include:` entry that an `ignore:` also matches is skipped with a
warning naming the contradiction. (POM-declared books have `<excludes>` for the same
job.)

That's the right default for a book whose folders are already in reading order, and the
wrong one as soon as you want a card list that doesn't match the disk. Four keys move the
dial from discovery towards declaration, and because they all answer the same question —
*what does this directory emit, and in what order* — exactly one applies per folder:

| Key | Meaning | Unlisted files |
|---|---|---|
| `sections:` | Titled groups of subfolders | Discovered and appended after the declared ones |
| `include:` | **Exclusive** list — only these, in this order | **Excluded** |
| `order:` | **Additive** list — these first, then the rest | Appended (alphabetically, or per `sort:`) |
| `sort:` | Order by frontmatter field instead of filename | Sorted, not dropped |

Precedence runs top to bottom: `sections:` wins over `include:`, which wins over `order:`.
Declaring a losing key alongside a winning one is a mistake rather than a merge, and warns
on stderr.

Each folder decides independently, so a book root can declare `sections:` over its folders
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

## `sections:` — declare the book's top-level structure

Without `sections:`, top-level grouping is discovered too: each top-level folder becomes its
own section, labelled from that folder's own `title:`. `sections:` instead names several
folders and gives the group one title, so a single divider page fronts a run of folders
that belong together:

```yaml
# book root paperband.yaml
title: "My Book"
sections:
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

A declared section behaves as one group everywhere a discovered one would: one PDF divider page,
one site landing page (`<id>.html`), one nav and sidebar entry. `id` defaults to a slug of
the title (`"Foundations"` → `foundations`); declare it explicitly when you want a stabler
URL than the title gives. `landing.template` accepts the same presets and paths a section
folder's own override does (see Book Configuration).

A declared section can also opt out of having a page at all with `landing: false` — it still
groups and orders its folders, and still labels its cards in the nav and sidebar, but no
PDF divider fronts its first card and no `<id>.html` is written (the Maven plugin's
equivalent is `<section><landingPage>false</landingPage></section>`):

```yaml
sections:
  - title: "Appendix"
    folders: [99-appendix]
    landing: false
```

Declaration order also drives folder order, ahead of any unclaimed content. A `where:` on a section
skips every folder it claims, the same way it works on an `order:` entry:

```yaml
sections:
  - title: "Interactive"
    where: "target == 'web'"
    folders: [demos]
```

## Mixing declaration and discovery

The two dials are independent, so the common shape is a declared skeleton over discovered
cards — the root says which folders group together, each folder decides how much of its
own content it spells out:

```
paperband.yaml            sections: → Foundations [01-getting-started, 02-authoring]
                                   Reference   [03-configuration]
01-getting-started/
  paperband.yaml          include: → exactly two cards, quickstart first
  01-introduction.md
  02-quickstart.md
  99-scratch.md           ← excluded
02-authoring/             ← no yaml: cards discovered alphabetically
  01-cards.md
  02-frontmatter.md
99-appendix/              ← claimed by no declaration: still its own discovered section
  01-glossary.md
```

Folders no declaration claims keep behaving as discovered sections, so adding `sections:` doesn't
force you to enumerate the whole book — declare the grouping that matters and leave the
rest alone.

## Declaring it outside the book

Every key above lives in a `paperband.yaml`, so the declaration travels with the content.
The Maven plugin can instead declare the whole structure in the POM and select each section's
cards by glob — `services/*/TRACE.md` and friends — which reaches shapes no directory
layout expresses, like two sections drawing different files out of one folder. See the Maven
Plugin page in the Advanced section.

## Watch Out

Declared and discovered sections share one id namespace, because a declared id is used exactly
where a discovered one's would be. Two declarations can't claim the same folder (its cards would have no
single group to report), and duplicate section ids are rejected — both fail the build at config
parse time rather than silently reshaping the book.

A declared section's `folders:` are resolved relative to the folder that declares `sections:`, like any
`order:` entry. In a book that keeps its cards under a `content/` wrapper, put `sections:` in
`content/paperband.yaml`.

## Check

```bash
mvn paperband:structure -Dpaperband.input=path/to/book
```

The structure dump shows the resolved grouping and card order — dividers, sections, and
the cards under each — so you can confirm what a declaration produced without rendering a
PDF.
