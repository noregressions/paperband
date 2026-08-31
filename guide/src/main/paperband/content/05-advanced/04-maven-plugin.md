---
id: maven-plugin
oneliner: "Nine goals: build, site, publish, and the inspection goals around them."
---

# Maven Plugin

`paperband-maven-plugin` is how Paperband runs: a book builds as part of `mvn install`
(or any phase you bind it to), in the same reactor and CI as the rest of the project.

## Goals

| Goal | What it does | Default phase |
|---|---|---|
| `build` | PDF from a card, a book directory, or a POM-declared book | `process-resources` |
| `site` | Multi-file static HTML site from the same book | `process-resources` |
| `publish` | Every edition declared in the book's `publication:` block | `process-resources` |
| `structure` | Dump the resolved structure — sections, cards, blocks — without rendering | `process-resources` |
| `pages` | Page-span report read from a rendered PDF | `verify` |
| `scan` | One card's parsed frontmatter, blocks and resolved config | *(invoke directly)* |
| `render` | One HTML file straight to PDF, no card pipeline in the way | *(invoke directly)* |
| `renderers` | List the renderers this build can reach | *(invoke directly)* |
| `themes` | List the themes `<theme>` can name | *(invoke directly)* |

Every goal runs standalone as well as from an execution — `mvn paperband:structure
-Dpaperband.input=book` needs no POM edit — and every parameter has a `-D` property, so a
bound execution can be overridden from the command line.

## Add the plugin

The plugin shares the parent's version. The parent POM version:

{% fragment "../../../../../../pom.xml:version-declaration" %}

```xml
<plugin>
  <groupId>dev.noregressions.paperband</groupId>
  <artifactId>paperband-maven-plugin</artifactId>
  <version>0.1.0</version>
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
| `renderer` | `paperband.renderer` | `playwright` | See Renderers in the Rendering section. |
| `target` | `paperband.target` | `pdf-a4` | Build target, e.g. `pdf-a4`, `pdf-6x9`. |
| `pageSize` | `paperband.pageSize` | `a4` | Page size slug, e.g. `a4`, `letter`, `6x9`. |
| `margins` | `paperband.margins` | *(the page size's own)* | Page margins, CSS-style shorthand: `0`, `18mm`, `20mm 15mm`, `20 15 25 15`. Units `mm` (default), `cm`, `in`, `pt`. See Full-bleed builds below. |
| `layout` | `paperband.layout` | *(context default)* | Layout template override. |
| `theme` | `paperband.theme` | *(book's `theme:`)* | Named theme; overrides `paperband.yaml`. |
| `themeDir` | `paperband.themeDir` | — | User theme directory, checked before built-ins. |
| `stylesheets` | `paperband.stylesheets` | — | Stylesheets this build contributes, inlined *after* the theme. See Declaring the whole book below. |
| `skip` | `paperband.skip` | `false` | Skip the goal without failing the build. |
| `emitHtml` | `paperband.emitHtml` | — | Also write the rendered HTML here, before the renderer sees it. A book's copy is standalone — local images are inlined as `data:` URIs, and on screen it shows a navigation sidebar — so it can be copied or shared without the project that built it. |
| `reportPages` | `paperband.reportPages` | `false` | Print a per-anchor page-span table after rendering. |
| `maxPagesPerCard` | `paperband.maxPagesPerCard` | *(`vars.maxPagesPerCard`)* | Fail the build if a card runs longer. See Page Enforcement. |
| `select` | `paperband.select` | — | Keep only cards whose `field=value` matches. Book builds only. |
| `watermark` | `paperband.watermark` | *(`vars.watermark`)* | Stamp this text on every page. See Watermarks for the four tuning parameters. |
| `externalIncludeDirs` | `paperband.externalIncludeDirs` | — | Permit `{{#include}}` to read below these directories, outside the book root. |
| `externalIncludeFiles` | `paperband.externalIncludeFiles` | — | Permit `{{#include}}` to read these specific files. |

### Full-bleed builds

`<margins>0</margins>` renders with no page margin at all, which is what a theme whose
ground is the paper (`blueprint`, `dark`, `carded`, `fieldguide`, …) needs: Chromium paints
nothing into a PDF page margin, so any margin shows up as a white border around every page,
and zero is the only way a coloured ground reaches the trim edge. The bundled themes supply
their own insets in that case — including on the continuation pages of a card that spans
many pages — so the text still has room to breathe. See Themes / Full-bleed themes.

Like `pageSize`, this parameter seeds the *base* geometry: a `vars.page.margins` block in
the book's own yaml still wins over it.

Exactly one of `input` and `book` must be configured — the first walks a directory tree,
the second declares the structure outright. Every other parameter also has a `-D` property,
so a bound execution can be overridden from the command line without editing the
`pom.xml`.

## Declare the book in the POM

`<input>` hands a directory to the book walker and lets the folder layout decide the rest —
see Organising Content for the `paperband.yaml` keys that steer it. `<book>` is the other
end of that dial: the sections, their titles and their card lists are stated in the POM, and
the cards are selected by glob rather than by where they sit.

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

That builds a three-section book: one card pulled out of each service directory under a single
"Execution Traces" divider, then everything under `docs/` (minus drafts) under "Reference",
then the appendix cards as a group with no divider of their own. No directory layout
expresses that first section — the trace cards' own folders say nothing about the grouping.

| Element | Notes |
|---|---|
| `root` | Book root. Patterns resolve against it. **Defaults to the conventional geography** — the `content/` wrapper if there is one, else `src/main/paperband`, else the module basedir — so a `<book>` that carries only config doesn't move the book. Declare it only for a book that lives somewhere else. |
| `sections` | Ordered list of `section` elements. |
| `section/id` | Section id — becomes `<id>.html` on the static site. Defaults to a slug of `title`. |
| `section/title` | Shown on the divider and landing page. |
| `section/landingTemplate` | Preset name or template path, exactly as a section folder's own `landing.template`. |
| `section/where` | Pebble predicate over `target`; false skips the whole section. |
| `section/includes` | Glob patterns selecting the section's cards, in emission order. An `.html` file becomes a card only for a pattern that itself ends in `.html` (`pages/*.html`) — a bare sweep never claims one, and warns when it would have. |
| `section/excludes` | Glob patterns removing what an include matched. |
| `section/sort` | Comma-separated frontmatter fields, `-` for descending — the `sort:` key's semantics. |
| `section/landingPage` | Whether the section gets a page of its own — `true` by default. See below. |

Order is entirely declared: sections in order, then each section's `include` patterns in order,
then matches within one pattern by `sort` or, with no `sort`, by path. A file is emitted
once — the first section to match it claims it — so overlapping patterns narrow rather than
duplicate.

Two sections may draw *different* files out of the *same* folder, which is the thing
`sections:` in a `paperband.yaml` can't express: a yaml declaration claims whole folders, while a
POM-declared section claims the individual cards its patterns matched.

## Declaring the whole book

A book normally describes itself: a `paperband.yaml` at its root carries the title, cover,
theme and vars, and the directory layout supplies the structure. The POM can take over
both, which leaves a clean three-way split — **structure in XML, content in markdown,
appearance in CSS**:

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

That book needs no `paperband.yaml` at all. It's the natural shape for **generated**
content — cards written by tooling, structure selected by pattern — where there's no
sensible owner for a config file sitting among the output.

| Element | Notes |
|---|---|
| `book/title` | Book title, for the cover and the PDF metadata. |
| `book/cover`, `book/back` | A full-page `<image>`, a `<template>`, and/or the cover's own text: `<text>true</text>` overlays the standard title/subtitle/series/author block on the image, and `<title>`, `<subtitle>`, `<series>`, `<author>` elements override individual lines (each inherits the book's value when unset). `<fullPage>true</fullPage>` (cover only) fills the sheet trim edge to trim edge — the first page loses its margins, the image scales to cover it, and any running header/footer is suppressed on that page. Templates live in the book's `layouts/` — see below. The `site` goal renders the same declaration as the index hero, copying a declared image into the site's `assets/`, so one `cover:` serves both targets. |
| `book/header`, `book/footer` | Running fixtures, same `<image>`/`<template>` shape. |
| `book/sectionLandingTemplate` | Default landing/divider template for sections that name none — a preset (`minimal`) or a path. |
| `book/author` | The book's author, for the cover. |
| `book/sidebar` | The static site's navigation sidebar. The element's presence is the opt-in — `<sidebar/>` is enough; `<enabled>false</enabled>` turns one off from a profile. `<collapsed>` starts the sidebar shut, `<sectionsCollapsed>` (default true) starts each section's card list shut. Ignored by `build`. |
| `book/authors` | Several authors: `<authors><author>A</author><author>B</author></authors>`. Templates get `book.authors` as a list and `book.author` rendered as "A and B", so a theme written for one author still shows both. Declaring both `<author>` and `<authors>` is an error. |
| `book/sections/toc` | An empty `<toc/>` between `<section>` elements renders the printed table of contents at that point in the book — first for the traditional spot up front, last for contents-at-the-back. It always lists the whole book, with real page numbers from a second render pass, and at most one is allowed (two markers fail the build). See TOC and Index. |
| `book/sections/page` | `<page><template>matrix</template></page>` between `<section>` elements renders a generated page at that point — a Pebble template with the **whole book model** in scope. Any number of markers is fine. See Generated pages below. |
| `book/index` | `true` renders a back-of-book index from each card's `index:` frontmatter terms; `auto` additionally extracts each card's distinctive terms from its text. Anything else is an error. See TOC and Index. |
| `book/vars` | Book-level template vars (`subtitle`, `series`, …). Flat strings only — see Watch Out. |
| `book/axes` | Declared axes: `name` (the frontmatter key cards use), `title`, and `values` of `id`/`label`/`color`. Declared axes replace a yaml `axes:` wholesale. |

An axis value's `<id>` is a string, where a yaml one keeps its native type. That costs
nothing: every comparison between an axis value and a card's frontmatter runs both sides
through `String.valueOf` first, so `<id>1</id>` matches a card declaring `tier: 1`.

`<book>` doesn't have to select cards. Declare config with no `<sections>` or `<includes>` and
the cards come from walking `<root>`, exactly as `<input>` would — so a book can take its
title and cover from the POM while keeping its structure in the directory tree.

### Layouts without a theme

A theme supplies two things: stylesheets, and template overrides. `<stylesheets>` replaces
the first. The second was never theme-only — the book's own **`layouts/` directory** sits
in the template loader chain, ahead of the bundled defaults and behind a theme's overrides:

```
theme overrides  →  <bookRoot>/layouts/  →  bundled templates
```

So with no theme, drop a file in `layouts/` named after whatever you want to replace:

| File in `layouts/` | Replaces |
|---|---|
| `book.html` | the whole book shell — `<html>`, scaffold CSS, the card loop |
| `_card-body.html` | how one card renders |
| `_block-section.html` | how one block renders, at every nesting depth |
| `_section-divider.html` | the section divider page |
| `_book-cover.html`, `_book-back.html` | cover and back matter |
| `_tier-divider.html` | axis-value divider pages |
| anything else | any bundled template, by its own name |

Everything a theme could override, a book can — the recursion resolves through the same
chain, so an overridden `_block-section.html` is used at every depth.

Templates named from config — `<cover><template>`, `<sectionLandingTemplate>`, an axis's
`<landingTemplate>`, and `<layout>` — are paths **relative to `layouts/`**, extension
stripped. A leading `layouts/` is accepted and dropped, since that's the file as it sits on
disk:

| Declared | Loads |
|---|---|
| `layouts/footer.html` | `<bookRoot>/layouts/footer.html` |
| `footer.html` | the same file |
| `layouts/covers/front.html` | `<bookRoot>/layouts/covers/front.html` — subdirectories work |
| `_book-cover` | the bundled template of that name |

A path that resolves nowhere fails the build and names every place it looked.

### theme=none

`<theme>none</theme>` turns theming off, whatever the book's yaml asked for. It's needed
because an unset `<theme>` *falls back* to the book's own — without `none` the build can
replace one theme with another but never with nothing. With no theme, the built-in scaffold
still supplies the structural CSS (divider pages, page geometry, code blocks) and colours
inherit, so what you get is plain rather than broken — the right base for a `<stylesheets>`
layer of your own. `none` is therefore a reserved name: a theme directory containing a
bundle called `none` can't be selected.

### Where declared CSS sits in the cascade

`<stylesheets>` are inlined **after** the theme, making them the strongest layer:

```
book's own css: chain  →  theme  →  <stylesheets>
```

That ordering earns its keep beyond the themeless case: theme CSS is inlined after a book's
own, so a book can't override a bundled theme's rule without `!important`. A build-declared
stylesheet can. `<theme>blueprint</theme>` plus one stylesheet means "that theme, with my
corrections".

### Precedence

Two rules, and they deliberately differ:

- **Anything inside `<book>` wins over the yaml.** It's a declaration, not a default, and
  the POM is the file you just edited. Declared `<sections>` also replace a yaml `sections:`,
  with a warning.
- **Build geometry outside `<book>` seeds the base.** `<margins>` and `<pageSize>` set the
  starting point and a `vars.page` block in the yaml can still tune it, matching how
  `--page-size` always behaved.

### Card ids and what a pattern claims

A card's id is the PDF's `#card-<id>` destination and the site's `cards/<id>.html` page.
Undeclared, it's derived from the card's path within the book, slugified —
`scenarios/S01-spring-node/TRACE.md` → `scenarios-s01-spring-node-trace`. That's unique per
file, so a book whose every scenario file is called `TRACE.md` needs no hand-written ids;
and it depends on that card's own path alone, so adding or renaming a *different* card can
never change this one's URL. Declare `id:` in frontmatter for something shorter.

An `<include>` pattern that matches **no card files at all fails the build**, naming the
section and the pattern — a dead pattern is a broken reference (a typo, a moved folder),
and continuing would ship a silently thinner book. Legitimately-empty stays legal: a
pattern whose matches were all claimed by an earlier section (the documented narrowing)
or removed by the section's own `<excludes>` did find its files, and only draws the
empty-section warning. A `<where>`-skipped section's patterns are never evaluated at all.

Two rules about what a pattern actually claims are worth knowing — and when they leave a
pattern with nothing, the failure message says which rule bit:

- **`README.md` is claimed only by a pattern that names it.** `scenarios/*/README.md` means
  those files and gets them. A wildcard sweep — `**`, `scenarios/**/*.md` — leaves readmes
  out, which is what stops a book swallowing every readme under `node_modules`.
- **A `.yaml` card needs the book root to declare a `cardSchema:`.** Without one, a pattern
  naming a yaml file matches nothing.

## Section pages

Every named section gets a page of its own, generated for you: a full-page divider before its
first card in the PDF, and an `<id>.html` landing page on the static site — the same
treatment a discovered section folder gets, titled from `<title>` and rendered by
`<landingTemplate>`. That's the default; nothing needs declaring to get it.

The divider takes a sheet to itself — forced page break on both sides, the section title and
its table of contents centred on the page — and stands to the full printable height, which
it reads from `--pw-content-height` rather than any fixed page size, so it stays one sheet
at every page size and margin setting. Themes restyle it through the `.section-divider`
class (the built-in look is deliberately plain: sections have no colour concept the way
axis values do). For a divider showing the title alone, dead centre with no card count or
contents list, use `<landingTemplate>minimal</landingTemplate>`.

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

The section still exists — it claims its cards, orders them, and labels the group in the
site's nav, sidebar and index. What goes away is the page itself and every link into it:
no divider in the PDF (the section's first card follows straight on from the previous section's
last), no `<id>.html` on the site, and the group's label renders as plain text rather than
a dead link. The cards keep their own pages either way.

Use it for a run of cards that belongs together for ordering and labelling but doesn't
warrant a page break — a short appendix, a single-card section, or a section whose first card
is already its own title page. For a page that's present but plainer, reach for
`<landingTemplate>minimal</landingTemplate>` instead: title only, no card count or
table of contents.

Only a declared section can decline a page. Discovered section folders always get one.

## Generated pages

A card is loaded before the book is assembled, so a card template only ever sees `vars` —
it can't list the other cards, count a section, or summarise the book it sits in. A
`<page>` marker can: it names a Pebble template that renders **after** everything is
known, with the same model `book.html` itself sees — `cards`, `sections`,
`axisGroupings`, `book`, `vars` — placed at the marker's position in the flow.
Like `<toc/>`, the marker is positional: it sits directly under `<sections>`, *between*
`<section>` elements, never inside one (a nested `<page>` fails the build with a message
saying so):

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

The template name resolves against `layouts/` like every other declared template (theme
overrides first). The page takes a sheet of its own — forced break on both sides, the
full printable height to lay out — and gets a named PDF destination (`book-page-0`,
`book-page-1`, …) so it shows up in `paperband:pages`. Position arithmetic matches
`<toc/>`: skipped and empty sections cost nothing, and a `-Dpaperband.cards` selection
keeps the page before the first kept card that followed it.

Use a `<page>` for pages *derived from* the book; content someone writes belongs in a
card. (Themes have a related hook, `_book-front`, which renders between the cover and
the first card without any POM declaration — a `<page>` is the positioned, book-declared
version of the same idea.)

For the plain "just glob me these files" case, put the patterns straight on `<book>` and
skip `<sections>`. The cards are emitted in pattern order and grouped by their own folders,
exactly as walked cards are:

```xml
<book>
  <root>${project.basedir}/services</root>
  <includes>
    <include>*/TRACE.md</include>
  </includes>
</book>
```

Patterns are `glob:` patterns (`*` stops at a `/`, `**` crosses it, `{a,b}` alternates),
matched against each card's path relative to `<root>`. As everywhere else in Maven, a
whole-segment `**/` matches *zero* or more directories, so `docs/**/*.md` finds
`docs/overview.md` as well as `docs/api/v2/types.md`. Only card files can ever match —
`.md` except `README.md`, plus `.yaml` when the book declares a `cardSchema:` — so a
deliberately broad pattern won't drag in stray text files.

## Run it

Bound in your `pom.xml`, it runs with the rest of the build:

```bash
mvn install
```

Or invoke the goal directly without binding it to a lifecycle phase at all — useful for a
one-off render, or for CI steps that shouldn't produce a PDF on every build:

```bash
mvn paperband:build -Dpaperband.input=guide -Dpaperband.output=guide.pdf
```

`paperband` is the plugin's goal prefix, derived from the `paperband-maven-plugin`
artifactId, so that short form works; the fully-qualified
`dev.noregressions.paperband:paperband-maven-plugin:build` works too.

## Where the pipeline lives

The plugin depends only on the library modules (`core`, `cards`, `config`, `layout`,
`include`, `render-playwright`), and the goals are thin: `build` and `publish` both drive
one `BookBuild`, so an edition build and a plain build can't drift apart, and `build`,
`site` and `structure` share one card-selection step, which is what lets `structure`
describe exactly the book `build` would render.

PDF post-processing — watermark stamping, and the page-span analysis behind `pages` and
`<reportPages>` — reads and rewrites a finished PDF, so it needs PDFBox directly and lives
in the plugin alongside the goals that use it.

## Watch Out

**A repeated singular element is an error, not a merge.** Maven maps configuration onto
fields, so two `<author>` elements set one field twice and the second wins — the build
succeeds with one of two authors on the cover and nothing said. Any `<book>` element that
can only be declared once (`<title>`, `<author>`, `<root>`, …) now fails the build when it
appears twice, naming it. Elements meant to repeat — `<section>`, `<axis>`, `<author>` inside
`<authors>` — are unaffected.

**Axes and declared sections compete for the divider slot.** A card is never in both an axis group and
a section, so declaring an axis over cards that also belong to declared sections replaces the section
divider pages with axis dividers — the cards regroup by axis value. Declare axes when the
axis *is* the structure you want; leave them out when the sections are. Check which you got
with `mvn paperband:structure` before rendering.

`<book><vars>` takes **flat string values only**. Maven's configurator maps
`<vars><author>Name</author></vars>` onto a string map cleanly and nested structures
badly, so the nested config that matters has typed parameters instead — `<margins>`,
`<pageSize>`, `<maxPagesPerCard>`. Anything genuinely nested belongs in a `paperband.yaml`,
which is better at it.

`<book><root>` has to *be* the book root — the directory whose `paperband.yaml` carries the
title, css, theme and vars. That side of the config is still resolved from each card's own
parent chain, not from the `<book>` element, so a pattern reaching outside the root would
match cards that belong to a different book.

The `playwright` renderer needs headless Chromium on first use — see the Watch Out in
Quickstart. A Maven build with no internet access (an offline CI runner, say) needs
Chromium pre-cached before the goal runs.

## The site goal

The same book, as a browsable static site — an index, a landing page per section or axis
value, and a page per card with prev/next navigation:

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

`<clean>` clears the `cards/` subtree first, so a card removed from the book stops being
served from a stale page. This goal's build target is `<siteTarget>` and defaults to `web`
rather than the `pdf-a4` the others use, since target-scoped content is usually written to
distinguish exactly that. `<book>` works here too, so one declaration feeds both the PDF
and the site — put it in the plugin's own `<configuration>` and both goals read it:

```xml
<plugin>
  <groupId>dev.noregressions.paperband</groupId>
  <artifactId>paperband-maven-plugin</artifactId>
  <version>0.1.0</version>

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

Each execution then contributes only what's its own — where the output goes. A goal-specific
parameter belongs in its execution rather than the shared block, since goals read the shared
one indiscriminately: `structure`'s `<outputFile>` is separate from `build`'s `<output>` for
exactly that reason.

## The publish goal

Builds every edition declared in the book's `publication:` block — the same content cut
several ways without an execution per cut:

```xml
<execution>
  <id>editions</id>
  <goals><goal>publish</goal></goals>
  <configuration>
    <bookDirectory>${project.basedir}/guide</bookDirectory>
  </configuration>
</execution>
```

Everything describing an artefact — theme, size, output path, card selection, vars, page
contract — lives in the yaml. The POM contributes only session settings: `<renderer>`,
`<emitHtmlDirectory>`, `<editions>` to build a subset, and `<set>` for one-off overrides
(`defaults.theme=carded`, `editions.mini.vars.audience=manager`). Editions build in
declaration order; one failing doesn't stop the rest, and the goal fails at the end naming
those that did.

## Inspection goals

None of these render a book, and all four are usually run directly rather than bound:

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
```

`structure` takes the same `<book>` element `build` does, which is the point of it: the
outline lists exactly which cards each pattern claimed, in which section, in what order — the
cheapest way to check a declaration without waiting for a render.

`render` is the odd one out: it takes an HTML file and a renderer and nothing else, which
makes it the way to turn an `<emitHtml>` file back into a PDF after hand-editing it.
