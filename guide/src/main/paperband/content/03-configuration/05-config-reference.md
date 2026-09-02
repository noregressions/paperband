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
| `sidebar` | The static site's navigation sidebar: a bare boolean, or a map of `enabled`, `collapsed`, `sectionsCollapsed` | `<book><sidebar>` |
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

Each is read from the **book context**, which is the context of whichever card the build
walked first. Setting one in a folder yaml is therefore either a no-op or a whole-book
switch, decided by walk order — so set them at the book root. (`sidebar` used to be in this
list for that reason; it is now a book-scope key of its own.)

| Var | What it does | Read at |
|---|---|---|
| `toc` | Render a printed table of contents | Book |
| `index` | Back-of-book index: `true`, `auto`, or a term list; also `<book><index>` | Book |
| `indexStop` | Terms to veto from an `auto` index | Book |
| `pdfBookmarks` | `false` opts out of the PDF's bookmark tree (on by default) | Book |
| `subtitle`, `series`, `author` | Cover and site-hero lines (a `cover:` block overrides them per line). `author` also comes from `<book><author>`/`<book><authors>`. | Book |
| `page.measure` | Text line-length — overrides the theme's `--card-max-width` | Book |
| `maxPagesPerCard` | Page-count ceiling per card; `<maxPagesPerCard>` wins | Book |
| `watermark` | Watermark text or image, and its appearance; `<watermark>` wins. Marks the PDF and the site alike | Book |
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

## The site sidebar

Structure rather than a setting, so it is book scope — the site has a sidebar on every page
or on none:

```yaml
sidebar: true                 # shorthand

sidebar:                      # or, for the open/closed behaviour
  enabled: true               # default true — declaring the map is the opt-in
  collapsed: false            # start the sidebar itself shut
  sectionsCollapsed: true     # start each section's card list shut (default true)
```

Note `sectionsCollapsed` defaults the *opposite* way to the other two: a sidebar listing
every card of every section at once is a wall of links, so it behaves like a table of
contents that opens what you need.

In the POM, the element's presence is the opt-in:

```xml
<book>
  <sidebar/>
  <!-- or <sidebar><sectionsCollapsed>false</sectionsCollapsed></sidebar> -->
</book>
```

PDF builds ignore it entirely. A folder yaml declaring it is an error.

The previous spelling — `vars.sidebar`, `vars.sidebar_collapsed`,
`vars.sidebar_sections_collapsed` — still works and is deprecated. It put a whole-site
switch on the per-card `vars` channel, where the site only ever read the copy belonging to
the first card walked: a folder that set it either did nothing or changed the entire site,
decided by walk order alone.

## Unknown configuration is an error

Maven's own reaction to a POM element no parameter matches is a warning:

```
[WARNING] Parameter 'sidebar' is unknown for plugin 'paperband-maven-plugin:site'
```

In a build printing hundreds of lines that is indistinguishable from silence — the element
looks configured, nothing reads it, and the symptom arrives much later as "that setting
doesn't work". Paperband fails the build instead, and says what to write:

```
execution 'build-guide-site' <configuration>: <sidebar> is not a Paperband plugin
parameter. It is a book var, not a build setting — declare it inside the book:
<book><vars><sidebar>…</sidebar></vars></book>.
```

Three checks Maven doesn't already make:

| Mistake | What you get |
|---|---|
| An element no goal knows | Rejected, with a `Did you mean <…>?` suggestion or the list of what's valid there |
| A parameter belonging to a **different** goal, on an execution that doesn't run it | Rejected, naming the goal it belongs to |
| A boolean that isn't `true`/`false` | Rejected — Plexus converts anything else to `false` silently, so `<fullPage>yes</fullPage>` would quietly do nothing |

Plugin-level `<configuration>` is checked more leniently than an execution's, deliberately:
it is shared by *every* goal, so a `<book>` declared once for `build` and `site` is also
handed to `renderers`, which has no such parameter. An element some goal accepts is legal
there; only one no goal knows is a typo.

Unknown *nested* elements (inside `<book>`, `<cover>`, `<axis>`) and unparseable numbers
were already hard errors from Maven's own configurator — those need nothing extra.

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

`scan` resolves the same geography and the same `<book>` overlay a build does, so a book
whose config lives in the POM reports its real title, cover and vars rather than only what
the yaml happens to say. Pass `-Dpaperband.input=` the card; the POM supplies the rest.
