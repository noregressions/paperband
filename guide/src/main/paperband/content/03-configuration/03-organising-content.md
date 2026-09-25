---
id: organising-content
oneliner: "Discovery, `order:`, `include:`, and `sections:` — how much of the book's shape you declare."
---

# Organising Content

By default a book's shape is **discovered**: every folder under the book root is walked and
every `.md` file becomes a card, in alphabetical order. Filename prefixes (`01-`, `02-`)
control ordering, and a file added to a folder appears in the next build.

## Where the book lives: the POM decides

The POM decides *where* the book's pieces are; `paperband.yaml` declares *what* the
book is (title, theme, axes, vars, sections) and never moves a root. Explicit parameters
win, and the convention supplies whatever the POM doesn't set:

```filetree
src/main/paperband/        ← <home>: the default for everything below
  paperband.yaml           ← book config (title, theme, axes, vars, css, sections)
  content/                 ← <content>: the cards — .md, .html, .yaml-with-schema
  layouts/                 ← <layouts>: Pebble templates and snippets
  styles/                  ← the css chain's files (css: paths resolve against home)
```

A book laid out like this needs no `<configuration>`: every goal defaults to it, as
`src/main/java` needs no declaration. This guide uses this layout, and every build logs
the resolved geography in one line
(`book geography: home=…, content=…, layouts=…`).

For an **existing project** whose content lives elsewhere, override the pieces
individually: `<content>docs</content>` walks that directory as the book (everything
there is content by declaration, so `.html` files are cards), while `home` keeps the
book's own assets in `src/main/paperband`. For content *scattered* across the
project — one `TRACE.md` per service, say — use `<book>` with glob `<sections>`
instead of `<content>`; the home still supplies `paperband.yaml`, `layouts/` and
`styles/`. `target/` and `node_modules/` are never matched by a glob, whatever the
pattern.

Legacy spellings still work: `<input>` walks a directory the old way (a `content/`
wrapper inside it is detected, root-level `layouts/`/`styles/` are skipped), and a
self-contained book whose yaml sits in its content root keeps its own config; an empty
home does not override it.

## `ignore:` — keep files out of the book

Any folder's `paperband.yaml` can declare files the walk should skip, gitignore-style:

```yaml
ignore:
  - "*.tmp.md"      # no slash: matches the basename at any depth below here
  - drafts/**       # with a slash: a path glob relative to this folder
  - scratch         # a bare name: that file or folder (and its subtree)
```

An `ignore:` applies to the subtree beneath the yaml that declares it, so a folder can
exclude its own drafts. It filters discovery and declared lists: an `order:`/`include:`
entry that an `ignore:` also matches is skipped with a warning. (POM-declared books have `<excludes>` for the same
job.)

Discovery suits a book whose folders are already in reading order. To declare a card
list that differs from the disk, use one of four keys. Each answers the same question
(what this directory emits, and in what order), so exactly one applies per folder:

| Key | Meaning | Unlisted files |
|---|---|---|
| `sections:` | Titled groups of subfolders | Discovered and appended after the declared ones |
| `include:` | **Exclusive** list — only these, in this order | **Excluded** |
| `order:` | **Additive** list — these first, then the rest | Appended (alphabetically, or per `sort:`) |
| `sort:` | Order by frontmatter field instead of filename | Sorted, not dropped |

Precedence runs top to bottom: `sections:` wins over `include:`, which wins over `order:`.
Declaring a lower-precedence key alongside a higher one does not merge them; it logs a
warning on stderr.

Each folder decides independently, so a book root can declare `sections:` while one folder
lists its cards exactly and a sibling uses discovery.

## `order:` — declare the front, discover the rest

```yaml
# content/paperband.yaml
order:
  - introduction
  - quickstart
```

Those two come first, in that order; everything else in the folder is appended
alphabetically, and a warning names the unlisted files. Entries resolve to a subdirectory
of that name first, then `<name>.md`.

## `include:` — declare everything

`include:` is the exclusive form: exactly these entries, in this order, and nothing else.

```yaml
# 01-getting-started/paperband.yaml
title: "Getting Started"
include:
  - 02-quickstart
  - 01-introduction
```

The folder emits two cards, quickstart first. A `99-scratch.md` beside them is excluded
without a warning, and a file added later stays out until it is listed.

A listed entry that resolves to nothing (no subdirectory of that name, no card file) fails
the build, naming the entry and the folder. `order:` entries only warn, because discovery
still emits every file that exists.

`sort:` has nothing left to order under `include:` and is ignored.

## `sections:` — declare the book's top-level structure

Without `sections:`, each top-level folder becomes its own section, labelled from that
folder's `title:`. `sections:` groups several folders under one title, so one divider page
precedes them:

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
the title (`"Foundations"` → `foundations`); declare it to keep the URL stable if the
title changes. `landing.template` accepts the same presets and paths a section
folder's own override does (see Book Configuration).

`landing: false` removes a declared section's page. The section still groups and orders
its folders and labels its cards in the nav and sidebar, but no PDF divider precedes its
first card and no `<id>.html` is written (the Maven plugin's
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

Section grouping and card listing are independent. A common setup declares sections at the
root and lets each folder decide how much of its content to list:

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

Folders no declaration claims remain discovered sections, so `sections:` need not list the
whole book.

## Declaring it outside the book

Every key above lives in a `paperband.yaml`, alongside the content. The Maven plugin can
instead declare the structure in the POM and select each section's cards by glob
(`services/*/TRACE.md`), which supports layouts a directory tree cannot express, such as two
sections drawing different files from one folder. See the Maven Plugin page in the Advanced
section.

## Watch Out

Declared and discovered sections share one id namespace. Two declarations cannot claim the
same folder, and duplicate section ids are rejected; both fail the build at config parse
time.

A declared section's `folders:` are resolved relative to the folder that declares `sections:`, like any
`order:` entry. In a book that keeps its cards under a `content/` wrapper, put `sections:` in
`content/paperband.yaml`.

## Check

```bash
mvn paperband:structure -Dpaperband.input=path/to/book
```

The structure dump shows the resolved grouping and card order (dividers, sections and the
cards under each) without rendering a PDF.
