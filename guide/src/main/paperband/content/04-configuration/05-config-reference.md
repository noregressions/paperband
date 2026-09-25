---
id: config-reference
oneliner: "Every place config lives, every key it can carry, and what wins when two places disagree."
index: [configuration, precedence, scope]
---

# Configuration Reference

Where configuration can be written, what each place accepts, and which wins when two
places set the same thing. [Config Cascade](card:config-cascade) explains the model; this
page is the full list.

## Where config lives

Six places, outermost first. Later rows are more specific, but "more specific wins" only
holds *within* a scope; see Who wins, key by key, below.

| Where | Carries | Scope |
|---|---|---|
| Plugin defaults | Built-in fallbacks (`a4`, `playwright`, target `pdf-a4`) | — |
| Built-in vars | `build_date`, `build_date_long`, `build_year`, `build_month_year`, `build_iso` | Card |
| **POM** — plugin parameters | Geography, geometry base, output, renderer, theme, skip | Build + book |
| **POM** — `<book>` element | Book-level config declared outside the book | Book |
| **Book root `paperband.yaml`** | The book: title, axes, theme, page, cover, sections… | Book |
| **Folder `paperband.yaml`** | Per-subtree: vars, css, layout, axis, orientation, structure | Card |
| **Card frontmatter** | Per-card: `id`, `title`, `oneliner`, `effort`, `max_pages`, `verify`, `index`, axis values ([Frontmatter Reference](card:frontmatter)) | Card |

Editions are declared in a `publication:` block in the book root's `paperband.yaml`, read
only by `paperband:publish`. It describes the builds to run and does not participate in
this cascade; see [Maven Plugin](card:maven-plugin#the-publish-goal).

## Who wins, key by key

When a key can be set in more than one place, this table gives the order, weakest first.
A key not listed has one source.

| Key | Order (weakest → strongest) |
|---|---|
| `theme` | root yaml `theme:` → `<theme>` (`none` turns theming off) |
| Stylesheets | root yaml `css:` chain → theme CSS → `<stylesheets>` (all are applied; later rules win on equal specificity) |
| Sheet size | `<pageSize>` → root yaml `page.size` |
| Margins | size preset's margins → `<margins>` → root yaml `page.margins` |
| `page.orientation` | root yaml → folder yaml (innermost wins) |
| `title`, `cover`, `back`, `header`, `footer`, `sidebar`, `axes`, `index` | root yaml → `<book>` element |
| `sections` | root yaml → `<book><sections>` (replaces the yaml list, with a warning) |
| `vars` | built-ins → root yaml → `<book><vars>` → folder yamls (innermost wins) |
| `css`, `layout`, `axis` | root yaml → folder yamls (innermost wins); card frontmatter beats a folder `axis:` binding |
| Page budget | `vars.maxPagesPerCard` → `<maxPagesPerCard>` → card `max_pages` |
| Watermark | `vars.watermark` → POM `<watermark>` block → flat `<watermarkText>`, `<watermarkColor>`, … |
| `renderer`, `target`, `output`, geography (`home`, `content`, `layouts`) | POM or `-D` only; the yaml has no equivalent |

Two general rules follow from the table. For book-scope keys, a POM declaration wins over
the root yaml; for card-scope keys, a deeper yaml wins over the POM. A `-D` property fills
a POM parameter only when the `<configuration>` doesn't set it.

Setting a book-scope key in a folder yaml is an error, and the message names the file.

## Book-scope keys

Read from the book's own `paperband.yaml` (or `<home>/paperband.yaml` under a split
geography). The first table is book-only: a folder that sets one of these keys is an
error. The second is read at book level and also cascades, so a folder may extend or
override it.

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
| `css` | Book-wide stylesheet chain, applied first; folders append to it | `<stylesheets>`, applied last, as a separate layer |
| `vars` | Free-form values seeded at the book level; folders override per key | `<book><vars>` |
| `targets` | Declared build targets, for documentation only. The list is not enforced; a `<target>` outside it matches no `where:` condition. | — |

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

Folder-level and outside the value cascade: each folder declares its own. See Organising
Content.

| Key | What |
|---|---|
| `order` | Additive list: these first, then whatever else is discovered |
| `include` | Exclusive list: exactly these, nothing else |
| `ignore` | Glob exclusions applying to the whole subtree beneath the declaring yaml |
| `sort` | Order by frontmatter field instead of filename |
| `where` | Predicate on an `order:`/`include:`/section entry, evaluated against the target |

Precedence: `sections:` beats `include:`, which beats `order:`.

## Vars that behave as configuration

These live in `vars`, so they cascade, but the engine reads them as switches rather than
as template values.

Each is read from the book context, which is the context of the first card the build
walks. Setting one in a folder yaml therefore either has no effect or applies to the whole
book, depending on walk order; set them at the book root.

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
| `icons` | `false` turns off `:name:` icon references for the book (on by default). See [Icons](card:icons) | Book |
| `strapline` | Per-edition strapline, read by `paperband:publish` | Edition |

## Frontmatter keys

Per card, in the `---` block. Everything not listed is free-form metadata reachable as
`card.frontmatter.*`.

| Key | What |
|---|---|
| `id` | Stable identity: `#card-<id>` in the PDF, `cards/<id>.html` on the site. Defaults to a slug of the card's path within the book: `api/endpoints.md` → `api-endpoints`. |
| `title` | Card title, overriding the first H1 |
| `oneliner` | Short summary for site tiles and section landing pages |
| *axis name* | This card's value for a declared axis; overrides the folder's `axis:` binding |

## The `<book>` element's children

The complete set, for the book-scope layer declared in the POM:

`<root>`, `<sections>`, `<includes>`, `<excludes>`, `<sort>`, `<title>`, `<author>`,
`<authors>`, `<index>`, `<cover>`, `<back>`, `<header>`, `<footer>`,
`<sectionLandingTemplate>`, `<vars>`, `<axes>`.

`<sections>`/`<includes>` select cards; the rest is book config. An element that only
carries config (no `<sections>`, no `<includes>`) leaves structure to the directory tree,
so a book can declare its title and cover in the POM and still be walked. The full element reference is in [Maven Plugin](card:maven-plugin).

## POM-only parameters

No yaml equivalent: they describe the build, not the book.

| Parameter | What |
|---|---|
| `<home>`, `<content>`, `<layouts>` | Geography — where the book's pieces live |
| `<input>` / `<book>` | Card selection: walk a directory, or declare it |
| `<output>`, `<outputDirectory>`, `<clean>` | Where output goes |
| `<renderer>` | Renderer id. There is no `renderer:` yaml key. |
| `<target>`, `<siteTarget>` | Build target driving `where:` predicates |
| `<externalIncludeDirs>`, `<externalIncludeFiles>` | Allow-list for includes above the book root |
| `<stylesheets>` | Build-owned CSS, inlined last |
| `<emitHtml>`, `<reportPages>` | Side outputs |
| `<skip>` | Skip the goal |

## The site sidebar

The sidebar is book scope: the site has one on every page or on none:

```yaml
sidebar: true                 # shorthand

sidebar:                      # or, for the open/closed behaviour
  enabled: true               # default true — declaring the map is the opt-in
  collapsed: false            # start the sidebar itself shut
  sectionsCollapsed: true     # start each section's card list shut (default true)
```

`sectionsCollapsed` defaults to `true`, unlike the other two, so each section's card list
starts closed.

In the POM, the element's presence is the opt-in:

```xml
<book>
  <sidebar/>
  <!-- or <sidebar><sectionsCollapsed>false</sectionsCollapsed></sidebar> -->
</book>
```

PDF builds ignore it. A folder yaml declaring it is an error.

The previous spelling (`vars.sidebar`, `vars.sidebar_collapsed`,
`vars.sidebar_sections_collapsed`) still works and is deprecated.

## Unknown configuration is an error

Maven's own reaction to a POM element no parameter matches is a warning:

```
[WARNING] Parameter 'sidebar' is unknown for plugin 'paperband-maven-plugin:site'
```

The element appears configured but nothing reads it. Paperband fails the build instead,
with the correct form:

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
| A boolean that isn't `true`/`false` | Rejected. Plexus converts any other value to `false`, so `<fullPage>yes</fullPage>` would have no effect |

Plugin-level `<configuration>` is checked more leniently than an execution's, because it
is shared by every goal: a `<book>` declared once for `build` and `site` is also passed to
`renderers`, which has no such parameter. An element that any goal accepts is allowed
there; only an element no goal knows is rejected.

Unknown nested elements (inside `<book>`, `<cover>`, `<axis>`) and unparseable numbers are
already errors in Maven's configurator.

## Check

```bash
mvn paperband:scan -Dpaperband.input=path/to/card.md
```

Prints the fully resolved context for one card — book root, title, target, size, layout,
CSS chain in load order, merged vars, axis values, and the resolved sheet:

```
=== CONTEXT ===
book root : .../src/main/paperband
title     : Paperband
target    : pdf-a4
size      : a4
layout    : <none>
page      : 210×297mm portrait, margins 20 18 20 18 (mm), content height 257mm
```

The `page` line is the *card's* effective sheet: the book's geometry, plus this card's own
rotation if its folder declared `page.orientation`.

`scan` resolves the same geography and `<book>` overlay as a build, so a book whose config
is in the POM reports its title, cover and vars from there. Pass the card as
`-Dpaperband.input=`; the POM supplies the rest.
