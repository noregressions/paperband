---
id: maven-plugin
oneliner: "Ten goals: build, site, publish, and the inspection goals around them."
---

# Maven Plugin

Paperband runs as `paperband-maven-plugin`. A book builds as part of `mvn install`, or any
phase its goals are bound to, in the same reactor and CI as the rest of the project.

## Goals

| Goal | What it does | Default phase |
|---|---|---|
| `build` | PDF from a card, a book directory, or a POM-declared book | `process-resources` |
| `site` | Multi-file static HTML site from the same book | `process-resources` |
| `publish` | Every edition declared in the book's `publication:` block | `process-resources` |
| `structure` | Dump the resolved structure — sections, cards, blocks — without rendering | `process-resources` |
| `pages` | Page-span report read from a rendered PDF | `verify` |
| `scan` | One card's parsed frontmatter, blocks and resolved config | *(invoke directly)* |
| `render` | One HTML file straight to PDF, bypassing the card pipeline | *(invoke directly)* |
| `renderers` | List the renderers this build can reach | *(invoke directly)* |
| `themes` | List the themes `<theme>` can name | *(invoke directly)* |
| `blocks` | List the ```` ```type ```` fences this build can render, and what renders each | *(invoke directly)* |

Every goal runs standalone as well as from an execution (`mvn paperband:structure
-Dpaperband.input=book` needs no POM edit), and every parameter has a `-D` property that
fills it when the POM leaves it unset. See Overriding from the command line below for how
`-D` interacts with POM values.

## Add the plugin

The plugin shares the parent's version. The parent POM version:

{% fragment "../../../../../../pom.xml:version-declaration" %}

```xml
<plugin>
  <groupId>dev.noregressions.paperband</groupId>
  <artifactId>paperband-maven-plugin</artifactId>
  <version>0.1.3</version>
  <executions>
    <execution>
      <goals><goal>build</goal></goals>
      <configuration>
        <!-- no <input>: the book sits at the conventional src/main/paperband -->
        <output>${project.build.directory}/guide.pdf</output>
      </configuration>
    </execution>
  </executions>
</plugin>
```

The `build` goal's default phase is `process-resources`; override `<phase>` in the
`<execution>` if you want the PDF built later (e.g. `package`).

## Configuration

| Parameter | Property | Default | Notes |
|---|---|---|---|
| `input` | `paperband.input` | — | Markdown file (single card) or directory (book) — the legacy spelling of `content`, with wrapper detection. Relative paths resolve against the module's basedir. |
| `home` | `paperband.home` | src/main/paperband | Where the book lives: `paperband.yaml`, `layouts/`, `styles/`. Moving it moves the defaults below. See Organising Content. |
| `content` | `paperband.content` | ${home}/content | The content root: everything there is a card (`.md`, `.html`, `.yaml` with a schema). Mutually exclusive with `input` and a card-selecting `book`. |
| `layouts` | `paperband.layouts` | ${home}/layouts | The book's templates: `{% include %}` snippets, `<page>` templates, overrides. |
| `book` | — | — | A book whose structure is declared in the POM and whose cards are selected by glob. Mutually exclusive with `input`; see below. |
| `output` | `paperband.output` | *(required)* | Output PDF file. |
| `renderer` | `paperband.renderer` | `playwright` | See [Renderers](card:renderers). |
| `target` | `paperband.target` | `pdf-a4` | Build target, e.g. `pdf-a4`, `pdf-6x9`. |
| `pageSize` | `paperband.pageSize` | `a4` | Page size slug, e.g. `a4`, `letter`, `6x9`. |
| `margins` | `paperband.margins` | *(the page size's own)* | Page margins, CSS-style shorthand: `0`, `18mm`, `20mm 15mm`, `20 15 25 15`. Units `mm` (default), `cm`, `in`, `pt`. See Full-bleed builds below. |
| `layout` | `paperband.layout` | *(context default)* | Layout template override. |
| `theme` | `paperband.theme` | *(book's `theme:`)* | Named theme; overrides `paperband.yaml`. |
| `themeDir` | `paperband.themeDir` | — | User theme directory, checked before built-ins. |
| `stylesheets` | `paperband.stylesheets` | — | Stylesheets this build contributes, inlined *after* the theme. See Declaring the whole book below. |
| `skip` | `paperband.skip` | `false` | Skip the goal without failing the build. |
| `emitHtml` | `paperband.emitHtml` | — | Also write the rendered HTML here, before the renderer sees it. A book's copy is standalone — local images are inlined as `data:` URIs, and on screen it shows a navigation sidebar (off with the book's `sidebar: false`) — so it can be copied or shared without the project that built it. |
| `reportPages` | `paperband.reportPages` | `false` | Print a per-anchor page-span table after rendering. |
| `maxPagesPerCard` | `paperband.maxPagesPerCard` | *(`vars.maxPagesPerCard`)* | Fail the build if a card runs longer. See Page Enforcement. |
| `select` | `paperband.select` | — | Keep only cards whose `field=value` matches. Book builds only. |
| `watermark` | `paperband.watermark` | *(`vars.watermark`)* | Stamp this text on every page; `<watermarkImage>` stamps a logo instead. See Watermarks for the tuning parameters. |
| `externalIncludeDirs` | `paperband.externalIncludeDirs` | — | Permit `{{#include}}` to read below these directories, outside the book root. |
| `externalIncludeFiles` | `paperband.externalIncludeFiles` | — | Permit `{{#include}}` to read these specific files. |

### Full-bleed builds

`<margins>0</margins>` renders with no page margin. Themes with a coloured page background
(`blueprint`, `dark`, `carded`, `fieldguide`, …) need this: Chromium paints nothing into a PDF
page margin, so any margin appears as a white border around every page. The bundled themes
supply their own insets in that case, including on the continuation pages of a multi-page
card. See [Themes](card:themes#full-bleed-themes).

Like `pageSize`, this parameter sets the base geometry; a `vars.page.margins` block in the
book's own yaml overrides it.

At most one of `content`, `input` and a card-selecting `book` may be configured. The first
two walk a directory tree; the third declares the structure. None is needed for a book at
the conventional `src/main/paperband`.

### Overriding from the command line

A `-D` property fills a parameter the POM doesn't set. It does not replace one the POM does:
Maven gives an explicit `<configuration>` value precedence over the property, so with
`<theme>editorial</theme>` in the POM, `-Dpaperband.theme=dark` has no effect.

To keep a value overridable, declare it as a POM property instead of a plugin parameter:

```xml
<properties>
  <paperband.theme>editorial</paperband.theme>
</properties>
```

The build uses `editorial`, and `mvn package -Dpaperband.theme=dark` gets `dark`, because a
command-line property wins over a POM property of the same name.

## Declare the book in the POM

`<input>` passes a directory to the book walker, and the folder layout determines the
structure (see Organising Content for the `paperband.yaml` keys that control it). With
`<book>`, the sections, their titles and their card lists are declared in the POM, and cards
are selected by glob rather than by location.

```xml
<configuration>
  <output>${project.build.directory}/traces.pdf</output>
  <book>
    <root>${project.basedir}</root>
    <sections>
      <section>
        <title>Execution Traces</title>
        <includes>
          <include>services/*/TRACE.md</include>
        </includes>
        <sort>tier,-id</sort>
      </section>
      <section>
        <id>reference</id>
        <title>Reference</title>
        <landingTemplate>minimal</landingTemplate>
        <includes>
          <include>docs/**/*.md</include>
        </includes>
        <excludes>
          <exclude>docs/draft/**</exclude>
        </excludes>
      </section>
      <section>
        <title>Appendix</title>
        <includes>
          <include>appendix/*.md</include>
        </includes>
        <landingPage>false</landingPage>
      </section>
    </sections>
  </book>
</configuration>
```

This builds a three-section book: one card from each service directory under a single
"Execution Traces" divider, then everything under `docs/` except drafts under "Reference",
then the appendix cards as a group with no divider. The first section cannot be expressed
as a directory layout, because the trace cards' folders don't group them.

| Element | Notes |
|---|---|
| `root` | Book root. Patterns resolve against it. Defaults to the conventional geography: the `content/` wrapper if there is one, else `src/main/paperband`, else the module basedir, so a `<book>` that carries only config doesn't move the book. Declare it only for a book that lives elsewhere. |
| `sections` | Ordered list of `section` elements. |
| `section/id` | Section id — becomes `<id>.html` on the static site. Defaults to a slug of `title`. |
| `section/title` | Shown on the divider and landing page. |
| `section/landingTemplate` | Preset name or template path, exactly as a section folder's own `landing.template`. |
| `section/where` | Pebble predicate over `target`; false skips the whole section. |
| `section/includes` | Glob patterns selecting the section's cards, in emission order. An `.html` file becomes a card only for a pattern that itself ends in `.html` (`pages/*.html`); a broader pattern never claims one, and warns when it would have. |
| `section/excludes` | Glob patterns removing what an include matched. |
| `section/sort` | Comma-separated frontmatter fields, `-` for descending — the `sort:` key's semantics. |
| `section/landingPage` | Whether the section gets a page of its own. `true` by default. See below. |

Order is fully declared: sections in order, then each section's `include` patterns in order,
then matches within one pattern by `sort` or, with no `sort`, by path. A file is emitted
once, by the first section that matches it, so overlapping patterns narrow rather than
duplicate.

Two sections may take different files from the same folder, which `sections:` in a
`paperband.yaml` cannot express: a yaml declaration claims whole folders, while a
POM-declared section claims the individual cards its patterns match.

## Declaring the whole book

A book normally describes itself: a `paperband.yaml` at its root carries the title, cover,
theme and vars, and the directory layout supplies the structure. The POM can declare both,
giving a split of structure in XML, content in markdown, and appearance in CSS:

```xml
<configuration>
  <output>${project.build.directory}/runbook.pdf</output>
  <margins>0</margins>
  <theme>none</theme>
  <stylesheets>
    <stylesheet>css/tokens.css</stylesheet>     <!-- colours, fonts -->
  </stylesheets>
  <book>
    <root>${project.basedir}</root>
    <title>Incident Runbook</title>
    <cover><template>layouts/runbook.html</template></cover>
    <footer><template>layouts/footer.html</template></footer>
    <sectionLandingTemplate>minimal</sectionLandingTemplate>
    <vars>
      <subtitle>Trace-driven investigations</subtitle>
      <author>Platform Team</author>
    </vars>
    <axes>
      <axis>
        <name>tier</name>
        <title>Tier</title>
        <values>
          <value><id>1</id><label>Critical</label><color>#c0392b</color></value>
          <value><id>2</id><label>Standard</label><color>#e67e22</color></value>
        </values>
      </axis>
    </axes>
    <sections>
      <section>
        <title>Scenarios</title>
        <includes><include>scenarios/**/TRACE.md</include></includes>
      </section>
    </sections>
  </book>
</configuration>
```

That book needs no `paperband.yaml`. This suits generated content: the cards are written
by tooling, and the configuration stays in the POM rather than in the generated output.

| Element | Notes |
|---|---|
| `book/title` | Book title, for the cover and the PDF metadata. |
| `book/cover`, `book/back` | A full-page `<image>`, a `<template>`, and/or the cover's own text: `<text>true</text>` overlays the standard title/subtitle/series/author block on the image, and `<title>`, `<subtitle>`, `<series>`, `<author>` elements override individual lines (each inherits the book's value when unset). `<fullPage>true</fullPage>` (cover only) fills the sheet edge to edge: the first page has no margins, the image scales to cover it, and any running header/footer is suppressed on that page. Templates live in the book's `layouts/` (see below). The `site` goal renders the same declaration as the index hero, copying a declared image into the site's `assets/`. |
| `book/header`, `book/footer` | Running fixtures, same `<image>`/`<template>` shape. |
| `book/sectionLandingTemplate` | Default landing/divider template for sections that name none — a preset (`minimal`) or a path. |
| `book/author` | The book's author, for the cover. |
| `book/sidebar` | The static site's navigation sidebar. The element's presence enables it (`<sidebar/>` is enough); `<enabled>false</enabled>` turns it off from a profile. `<collapsed>` starts the sidebar collapsed, `<sectionsCollapsed>` (default true) starts each section's card list collapsed. Ignored by `build`. |
| `book/authors` | Several authors: `<authors><author>A</author><author>B</author></authors>`. Templates get `book.authors` as a list and `book.author` rendered as "A and B", so a theme written for one author still shows both. Declaring both `<author>` and `<authors>` is an error. |
| `book/sections/toc` | An empty `<toc/>` between `<section>` elements renders the printed table of contents at that point: first for contents at the front, last for contents at the back. It lists the whole book, with page numbers from a second render pass. At most one is allowed; two markers fail the build. See TOC and Index. |
| `book/sections/page` | `<page><template>matrix</template></page>` between `<section>` elements renders a generated page at that point: a Pebble template with the whole book model in scope. Any number of markers is allowed. See Generated pages below. |
| `book/index` | `true` renders a back-of-book index from each card's `index:` frontmatter terms; `auto` additionally extracts each card's distinctive terms from its text. Anything else is an error. See TOC and Index. |
| `book/vars` | Book-level template vars (`subtitle`, `series`, …). Flat strings only — see Watch Out. |
| `book/axes` | Declared axes: `name` (the frontmatter key cards use), `title`, and `values` of `id`/`label`/`color`. Declared axes replace a yaml `axes:` wholesale. |

An axis value's `<id>` is a string, where a yaml one keeps its native type. Comparisons
between an axis value and a card's frontmatter convert both sides with `String.valueOf`, so
`<id>1</id>` matches a card declaring `tier: 1`.

`<book>` doesn't have to select cards. With no `<sections>` or `<includes>`, the cards come
from walking `<root>`, as `<input>` would, so a book can take its title and cover from the
POM while keeping its structure in the directory tree.

### Layouts without a theme

A theme supplies stylesheets and template overrides. `<stylesheets>` replaces the first.
For the second, the book's own `layouts/` directory is in the template loader chain, ahead
of the bundled defaults and behind a theme's overrides:

```
theme overrides  →  <bookRoot>/layouts/  →  bundled templates
```

With no theme, put a file in `layouts/` named after the template to replace:

| File in `layouts/` | Replaces |
|---|---|
| `book.html` | the whole book shell — `<html>`, scaffold CSS, the card loop |
| `_card-body.html` | how one card renders |
| `_block-section.html` | how one block renders, at every nesting depth |
| `_section-divider.html` | the section divider page |
| `_book-cover.html`, `_book-back.html` | cover and back matter |
| `_tier-divider.html` | axis-value divider pages |
| anything else | any bundled template, by its own name |

A book can override any template a theme can. Recursive includes resolve through the same
chain, so an overridden `_block-section.html` is used at every depth.

Templates named from config (`<cover><template>`, `<sectionLandingTemplate>`, an axis's
`<landingTemplate>`, and `<layout>`) are paths relative to `layouts/`, without the
extension. A leading `layouts/` is accepted and dropped:

| Declared | Loads |
|---|---|
| `layouts/footer.html` | `<bookRoot>/layouts/footer.html` |
| `footer.html` | the same file |
| `layouts/covers/front.html` | `<bookRoot>/layouts/covers/front.html` — subdirectories work |
| `_book-cover` | the bundled template of that name |

A path that resolves nowhere fails the build and names every place it looked.

### theme=none

`<theme>none</theme>` turns theming off, whatever the book's yaml declares. An unset
`<theme>` falls back to the book's own, so without `none` the build could replace one theme
with another but not remove it. With no theme, the built-in scaffold still supplies the
structural CSS (divider pages, page geometry, code blocks) and colours inherit, giving a
plain base for your own `<stylesheets>`. `none` is a reserved name: a theme bundle called
`none` in a theme directory can't be selected.

### Where declared CSS sits in the cascade

`<stylesheets>` are inlined after the theme, so they take precedence over it:

```
book's own css: chain  →  theme  →  <stylesheets>
```

Theme CSS is inlined after a book's own, so a book can't override a bundled theme's rule
without `!important`; a build-declared stylesheet can. `<theme>blueprint</theme>` plus one
stylesheet applies that theme with local overrides.

### Precedence

- Anything inside `<book>` overrides the yaml. Declared `<sections>` also replace a yaml
  `sections:`, with a warning.
- Build geometry outside `<book>` sets the base. `<margins>` and `<pageSize>` set the
  starting point, and a `vars.page` block in the yaml can still adjust it.

### Card ids and what a pattern claims

A card's id is the PDF's `#card-<id>` destination and the site's `cards/<id>.html` page.
Undeclared, it is derived from the card's path within the book, slugified:
`scenarios/S01-spring-node/TRACE.md` → `scenarios-s01-spring-node-trace`. This is unique per
file, so files that share a name such as `TRACE.md` need no hand-written ids, and it depends
only on that card's own path, so changes to other cards don't change it. Declare `id:` in
frontmatter for a shorter id.

An `<include>` pattern that matches no card files fails the build, naming the section and
the pattern. A pattern whose matches were all claimed by an earlier section, or removed by
the section's own `<excludes>`, is not an error; it produces an empty-section warning. The
patterns of a `<where>`-skipped section are not evaluated.

Two rules limit what a pattern claims, and when either leaves a pattern empty, the failure
message names the rule:

- `README.md` is claimed only by a pattern that names it. `scenarios/*/README.md` matches
  those files; a wildcard such as `**` or `scenarios/**/*.md` excludes readmes, including
  any under `node_modules`.
- A `.yaml` card requires the book root to declare a `cardSchema:`. Without one, a pattern
  naming a yaml file matches nothing.

## Section pages

By default every named section gets a generated page: a full-page divider before its first
card in the PDF, and an `<id>.html` landing page on the static site, titled from `<title>`
and rendered by `<landingTemplate>`, as for a discovered section folder.

The divider occupies its own sheet, with page breaks on both sides and the section title and
table of contents centred. Its height is read from `--pw-content-height` rather than a fixed
page size, so it stays one sheet at every page size and margin setting. Themes restyle it
through the `.section-divider` class; the built-in style is plain, because sections have no
colour as axis values do. For a divider with the title only, use
`<landingTemplate>minimal</landingTemplate>`.

Set `<landingPage>false</landingPage>` on a section to suppress it:

```xml
<section>
  <title>Appendix</title>
  <includes>
    <include>appendix/*.md</include>
  </includes>
  <landingPage>false</landingPage>
</section>
```

The section still claims its cards, orders them, and labels the group in the site's nav,
sidebar and index. Only the page and the links to it are removed: there is no divider in the
PDF (the section's first card follows the previous section's last), no `<id>.html` on the
site, and the group's label renders as plain text. The cards keep their own pages.

Use it for cards grouped for ordering and labelling that don't need a page break, such as a
short appendix, a single-card section, or a section whose first card is its own title page.
For a plainer page instead of none, use `<landingTemplate>minimal</landingTemplate>`: title
only, no card count or table of contents.

Only a declared section can decline a page. Discovered section folders always get one.

## Generated pages

A card is loaded before the book is assembled, so a card template sees only `vars`: it
can't list other cards, count a section, or summarise the book. A `<page>` marker names a
Pebble template that renders after the book is assembled, with the same model `book.html`
sees (`cards`, `sections`, `axisGroupings`, `book`, `vars`), placed at the marker's position.
Like `<toc/>`, the marker sits directly under `<sections>`, between `<section>` elements,
never inside one; a nested `<page>` fails the build:

```xml
<sections>
  <section>…</section>
  <page><template>matrix</template></page>   <!-- layouts/matrix.html -->
  <section>…</section>
</sections>
```

```html
<!-- layouts/matrix.html: a planning matrix rebuilt on every build -->
<h1>All {{ cards | length }} cards of {{ book.title }}</h1>
<table>
  {% for c in cards %}<tr><td>{{ c.title }}</td><td>{{ c.axes.tier.label }}</td></tr>{% endfor %}
</table>
```

The template name resolves against `layouts/` like every other declared template, theme
overrides first. The page occupies its own sheet, with page breaks on both sides and the
full printable height, and gets a named PDF destination (`book-page-0`, `book-page-1`, …)
so it appears in `paperband:pages`. Positioning matches `<toc/>`: skipped and empty sections
are not counted, and a `-Dpaperband.cards` selection keeps the page before the first kept
card that followed it.

Use a `<page>` for pages derived from the book; written content belongs in a card. Themes
have a related hook, `_book-front`, which renders between the cover and the first card
without a POM declaration.

To select files without sections, put the patterns directly on `<book>` and omit
`<sections>`. The cards are emitted in pattern order and grouped by their own folders, as
walked cards are:

```xml
<book>
  <root>${project.basedir}/services</root>
  <includes>
    <include>*/TRACE.md</include>
  </includes>
</book>
```

Patterns are `glob:` patterns (`*` stops at a `/`, `**` crosses it, `{a,b}` alternates),
matched against each card's path relative to `<root>`. As elsewhere in Maven, a
whole-segment `**/` matches zero or more directories, so `docs/**/*.md` finds
`docs/overview.md` as well as `docs/api/v2/types.md`. Only card files match (`.md` except
`README.md`, plus `.yaml` when the book declares a `cardSchema:`), so a broad pattern does
not include other text files.

## Run it

When bound in the `pom.xml`, the goal runs with the rest of the build:

```bash
mvn install
```

Or invoke the goal directly without binding it to a lifecycle phase, for a one-off render or
for CI steps that shouldn't produce a PDF on every build:

```bash
mvn paperband:build -Dpaperband.input=guide -Dpaperband.output=guide.pdf
```

`paperband` is the plugin's goal prefix, derived from the `paperband-maven-plugin`
artifactId. The fully qualified `dev.noregressions.paperband:paperband-maven-plugin:build`
also works.

## Watch Out

**A repeated singular element is an error.** Maven maps configuration onto fields, so two
`<author>` elements would set one field twice and the second would win. Any `<book>` element
that can be declared only once (`<title>`, `<author>`, `<root>`, …) fails the build when it
appears twice, naming it. Repeating elements (`<section>`, `<axis>`, `<author>` inside
`<authors>`) are unaffected.

**Axes and declared sections both produce dividers.** A card is never in both an axis group
and a section, so declaring an axis over cards that belong to declared sections replaces the
section dividers with axis dividers, and the cards regroup by axis value. Declare axes only
when the axis is the intended structure. Check the result with `mvn paperband:structure`
before rendering.

**`<book><vars>` takes flat string values only.** Maven's configurator maps
`<vars><author>Name</author></vars>` onto a string map but handles nested structures
poorly, so nested settings have typed parameters instead: `<margins>`, `<pageSize>`,
`<maxPagesPerCard>`. Put other nested config in a `paperband.yaml`.

**`<book><root>` must be the book root**: the directory whose `paperband.yaml` carries the
title, css, theme and vars. That config is resolved from each card's own parent chain, not
from the `<book>` element, so a pattern reaching outside the root would match cards that
belong to a different book.

The `playwright` renderer downloads headless Chromium on first use (see Before You Start).
A build with no internet access, such as an offline CI runner, needs Chromium pre-cached.

## The site goal

The `site` goal renders the same book as a static site: an index, a landing page per section
or axis value, and a page per card with prev/next navigation:

```xml
<execution>
  <id>site</id>
  <goals><goal>site</goal></goals>
  <configuration>
    <outputDirectory>${project.build.directory}/site</outputDirectory>
    <clean>true</clean>
  </configuration>
</execution>
```

`<clean>` clears the `cards/` subtree first, so pages for cards removed from the book are
deleted. This goal's build target is `<siteTarget>`, which defaults to `web` rather than
`pdf-a4`. `<book>` works here too; put it in the plugin's own `<configuration>` and both
goals read it:

```xml
<plugin>
  <groupId>dev.noregressions.paperband</groupId>
  <artifactId>paperband-maven-plugin</artifactId>
  <version>0.1.3</version>

  <!-- Shared by every goal: what the book is, and how it renders. -->
  <configuration>
    <theme>workshop</theme>
    <margins>0</margins>
    <book>
      <root>${project.basedir}</root>
      <title>…</title>
      <sections>…</sections>
    </book>
  </configuration>

  <executions>
    <execution>
      <id>pdf</id>
      <goals><goal>build</goal></goals>
      <configuration>
        <output>${project.build.directory}/book.pdf</output>
      </configuration>
    </execution>
    <execution>
      <id>site</id>
      <goals><goal>site</goal></goals>
      <configuration>
        <outputDirectory>${project.build.directory}/site</outputDirectory>
        <clean>true</clean>
      </configuration>
    </execution>
  </executions>
</plugin>
```

Each execution then sets only its output location. Put goal-specific parameters in their
execution rather than the shared block, because every goal reads the shared block; this is
why `structure`'s `<outputFile>` is separate from `build`'s `<output>`.

`site` takes the same watermark parameters as `build`, so
`-Dpaperband.watermark="REVIEW COPY"` marks the site and the PDF in one run. It ignores
`pages:` and `font:`.

## The publish goal

`publish` builds every edition declared in the book's `publication:` block, without an
execution per edition:

```xml
<execution>
  <id>editions</id>
  <goals><goal>publish</goal></goals>
  <configuration>
    <bookDirectory>${project.basedir}/guide</bookDirectory>
  </configuration>
</execution>
```

Everything describing an edition (theme, size, output path, card selection, vars, page
limits) is in the yaml. The POM supplies only session settings: `<renderer>`,
`<emitHtmlDirectory>`, `<editions>` to build a subset, and `<set>` for one-off overrides
(`defaults.theme=carded`, `editions.mini.vars.audience=manager`). Editions build in
declaration order; a failure doesn't stop the rest, and the goal fails at the end naming
the editions that failed.

## Inspection goals

None of these render a book, and they are usually run directly rather than bound:

```bash
# What does this declaration actually produce?
mvn paperband:structure -Dpaperband.input=book
mvn paperband:structure -Dpaperband.outputFile=structure.txt   # with a <book> in the POM

# Why did this card render like that?
mvn paperband:scan -Dpaperband.input=book/setup/install.md

# How long is each card in the finished PDF?
mvn paperband:pages -Dpaperband.pdf=target/book.pdf -Dpaperband.byPages=true

# What's available in this build?
mvn paperband:renderers
mvn paperband:themes -Dpaperband.themeDir=mythemes
mvn paperband:blocks
```

`structure` takes the same `<book>` element as `build`, and lists which cards each pattern
claimed, in which section and in what order, without rendering.

`render` takes an HTML file, a renderer and the watermark parameters, and turns an
`<emitHtml>` file back into a PDF, for example after editing it. The emitted file carries its
watermark as a screen-only overlay and the PDF's mark is stamped after rendering, so
re-rendering without `-Dpaperband.watermark` produces an unmarked PDF.
