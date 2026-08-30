---
id: config-reference
oneliner: "Every place config lives, every key it can carry, and what wins when two places disagree."
index: [configuration, precedence, scope]
---

# Configuration Reference

The complete surface: **where** configuration can be written, **what** each place accepts,
and **which wins** when two of them speak. Config Cascade explains the model; this is the
exhaustive list.

## Where config lives

Six places, outermost first. Later rows are more specific, but "more specific wins" only
holds *within* a scope — see Priority below.

| Where | Carries | Scope |
|---|---|---|
| Plugin defaults | Built-in fallbacks (`a4`, `playwright`, target `pdf-a4`) | — |
| Built-in vars | `build_date`, `build_date_long`, `build_year`, `build_month_year`, `build_iso` | Card |
| **POM** — plugin parameters | Geography, geometry base, output, renderer, theme, skip | Build + book |
| **POM** — `<book>` element | Book-level config declared outside the book | Book |
| **Book root `paperband.yaml`** | The book: title, axes, theme, page, cover, sections… | Book |
| **Folder `paperband.yaml`** | Per-subtree: vars, css, layout, axis, orientation, structure | Card |
| **Card frontmatter** | Per-card: id, title, axis values, oneliner | Card |

A seventh exists for multi-book repos: `publication.yaml`, read by `paperband:publish`.
It is its own file with its own keys and does not participate in this cascade.

## Priority

> **The POM outranks the root yaml. Depth outranks the POM.**

Both halves are needed because the two scopes want opposite things:

| Scope | Rule | Why |
|---|---|---|
| **Book** | POM `<book>` wins over the root yaml, field by field | One book has one title, one cover, one sheet. A declaration beats a default, and the POM is the file you just edited. |
| **Card** | Built-ins → root yaml → POM `<book><vars>` → folder yamls (deepening) → frontmatter | A build-declared var must reach every card *and* stay overridable per folder. |
| **Geography** | POM only; yaml never participates | `paperband.yaml` declares what the book *is*; the POM declares *where* it is. |
| **Geometry base** | `<pageSize>`/`<margins>` seed the base; the book's `page:` block wins | The POM knob exists for books with no yaml to edit. |

Book scope has no depth, so there the two halves coincide and the POM simply wins.

Setting a **book-scope key in a folder yaml is an error**, not a silent override. It names
the offending file.

## Book-scope keys

Read from the book's own `paperband.yaml` (or `<home>/paperband.yaml` under a split
geography). The first block is **book-only** — a folder that sets one is an error. The
second block is read at book level *and* cascades, so a folder may extend or override it.

| Key | What | POM equivalent |
|---|---|---|
| `title` | Book title — cover, PDF metadata, site `<title>` | `<book><title>` |
| `page` | The sheet: `size`, `margins`, `orientation`, `fontScale`, `measure` | `<pageSize>`, `<margins>` (base only) |
| `theme` | Default theme name, or `none` | `<theme>` wins outright. `<themeDir>` is a search path, not an override: a user theme directory checked before the built-ins. |
| `axes` | Categorical axes: `name`, `title`, `dividers`, `landing.template`, and `values` (each `id`, `label`, and its own `landing`) | `<book><axes>` |
| `cover`, `back` | Front/back matter: `image`, `template`, `text`, `title`, `subtitle`, `series`, `author`, `fullPage` (cover only). A bare string is shorthand for `image`. The `site` goal renders `cover` as its index hero. | `<book><cover>`, `<book><back>` |
| `header`, `footer` | Running band templates | `<book><header>`, `<book><footer>` |
| `sections` | Declared top-level structure, and `sections.landing.template` | `<book><sections>`; the book-wide landing default is `<book><sectionLandingTemplate>` |
| `cardSchema` | Transpile `*.yaml` files into cards | — |

Book-level entry points for keys that also cascade:

| Key | What | POM equivalent |
|---|---|---|
| `css` | Book-wide stylesheet chain, applied first; folders append to it | `<stylesheets>`, applied *last* — a different layer, not the same one |
| `vars` | Free-form values seeded at the book level; folders override per key | `<book><vars>` |
| `targets` | Declared build targets. Carried for documentation — nothing enforces the list, so a `<target>` outside it simply matches no `where:` condition. | — |

## Card-scope keys

Cascade from the book root down; the innermost declaration wins.

| Key | Merge rule |
|---|---|
| `vars` | Map merge — innermost wins per key |
| `axis` | Merged into `vars`, so predicates see bindings as plain variables |
| `css` | **Concatenated root-first** — inner CSS wins on equal specificity |
| `layout` | Last non-null wins, resolved against the declaring yaml's directory |
| `targets` | Last non-empty list **replaces** the whole list |
| `page.orientation` | Innermost wins — rotates that folder's cards without changing the book's paper |

`page.size`, `page.margins` and `page.fontScale` are book scope and error here.

## Structure keys

Folder-level, and outside the value cascade entirely — each folder declares its own. Full
treatment in Organising Content.

| Key | What |
|---|---|
| `order` | Additive list: these first, then whatever else is discovered |
| `include` | Exclusive list: exactly these, nothing else |
| `ignore` | Glob exclusions applying to the whole subtree beneath the declaring yaml |
| `sort` | Order by frontmatter field instead of filename |
| `where` | Predicate on an `order:`/`include:`/section entry, evaluated against the target |

Precedence: `sections:` beats `include:`, which beats `order:`.

## Vars that behave as configuration

These live in `vars` — so they cascade — but the engine reads them as switches rather than
as template values. Easy to miss, because nothing else marks them out.

| Var | What it does | Read at |
|---|---|---|
| `toc` | Render a printed table of contents | Book |
| `index` | Back-of-book index: `true`, `auto`, or a term list; also `<book><index>` | Book |
| `indexStop` | Terms to veto from an `auto` index | Book |
| `subtitle`, `series`, `author` | Cover and site-hero lines (a `cover:` block overrides them per line). `author` also comes from `<book><author>`/`<book><authors>`. | Book |
| `sidebar` | Render the site's sidebar | Book |
| `sidebar_collapsed` | Start the site sidebar collapsed | Book |
| `sidebar_sections_collapsed` | Start sidebar section groups collapsed (default true) | Book |
| `page.measure` | Text line-length — overrides the theme's `--card-max-width` | Book |
| `maxPagesPerCard` | Page-count ceiling per card; `<maxPagesPerCard>` wins | Book |
| `watermark` | Watermark text/appearance; `<watermarkText>` wins | Book |
| `strapline` | Per-edition strapline, read by `paperband:publish` | Edition |

## Frontmatter keys

Per card, in the `---` block. Everything not listed is free-form metadata reachable as
`card.frontmatter.*`.

| Key | What |
|---|---|
| `id` | Stable identity — `#card-<id>` in the PDF, `cards/<id>.html` on the site. Defaults to the file basename. |
| `title` | Card title, overriding the first H1 |
| `oneliner` | Short summary for site tiles and section landing pages |
| *axis name* | This card's value for a declared axis — beats the folder's `axis:` binding |

## The `<book>` element's children

The complete set, for the book-scope layer declared in the POM:

`<root>`, `<sections>`, `<includes>`, `<excludes>`, `<sort>`, `<title>`, `<author>`,
`<authors>`, `<index>`, `<cover>`, `<back>`, `<header>`, `<footer>`,
`<sectionLandingTemplate>`, `<vars>`, `<axes>`.

`<sections>`/`<includes>` select cards; the rest is book config. An element that only
carries config (no `<sections>`, no `<includes>`) leaves structure to the directory tree,
so a book can declare its title and cover in the POM and still be walked. Full element
reference in Maven Plugin.

## POM-only parameters

No yaml equivalent: they describe the build, not the book.

| Parameter | What |
|---|---|
| `<home>`, `<content>`, `<layouts>` | Geography — where the book's pieces live |
| `<input>` / `<book>` | Card selection: walk a directory, or declare it |
| `<output>`, `<outputDirectory>`, `<clean>` | Where output goes |
| `<renderer>` | Renderer id. There is deliberately no `renderer:` yaml key. |
| `<target>`, `<siteTarget>` | Build target driving `where:` predicates |
| `<externalIncludeDirs>`, `<externalIncludeFiles>` | Allow-list for includes above the book root |
| `<stylesheets>` | Build-owned CSS, inlined last |
| `<emitHtml>`, `<reportPages>` | Side outputs |
| `<skip>` | Skip the goal |

## Check

```bash
mvn paperband:scan -Dpaperband.input=path/to/card.md
```

Prints the fully resolved context for one card — book root, title, target, size, layout,
CSS chain in load order, merged vars, axis values, and the resolved sheet — so you can see
what the layers actually produced rather than inferring it:

```
=== CONTEXT ===
book root : .../src/main/paperband
title     : Paperband Guide
target    : pdf-a4
size      : a4
layout    : <none>
page      : 210×297mm portrait, margins 20 18 20 18 (mm), content height 257mm
```

The `page` line is the *card's* effective sheet: the book's geometry, plus this card's own
rotation if its folder declared `page.orientation`.
