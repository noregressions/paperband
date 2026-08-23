---
id: maven-plugin
oneliner: "Run Paperband builds as part of a Maven build, via a `build` goal Mojo."
---

# Maven Plugin

For projects that already build with Maven, `paperband-maven-plugin` runs a Paperband
build directly as part of `mvn install` (or any phase you bind it to) — no shell alias, no
separate CLI jar. It wraps the same single-card / book pipeline as the CLI's `build`
command, just with Maven parameters instead of picocli options.

## Add the plugin

The plugin shares the parent's version. The parent POM version:

{% fragment "../../../pom.xml:version-declaration" %}

```xml
<plugin>
  <groupId>dev.noregressions.paperband</groupId>
  <artifactId>paperband-maven-plugin</artifactId>
  <version>0.0.1</version>
  <executions>
    <execution>
      <goals><goal>build</goal></goals>
      <configuration>
        <input>${project.basedir}/guide</input>
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
| `input` | `paperband.input` | — | Markdown file (single card) or directory (book). Relative paths resolve against the module's basedir. |
| `book` | — | — | A book whose structure is declared in the POM and whose cards are selected by glob. Mutually exclusive with `input`; see below. |
| `output` | `paperband.output` | *(required)* | Output PDF file. |
| `renderer` | `paperband.renderer` | `playwright` | See Renderers in the Rendering section. |
| `target` | `paperband.target` | `pdf-a4` | Build target, e.g. `pdf-a4`, `pdf-6x9`. |
| `pageSize` | `paperband.pageSize` | `a4` | Page size slug, e.g. `a4`, `letter`, `6x9`. |
| `margins` | `paperband.margins` | *(the page size's own)* | Page margins, CSS-style shorthand: `0`, `18mm`, `20mm 15mm`, `20 15 25 15`. Units `mm` (default), `cm`, `in`, `pt`. See Full-bleed builds below. |
| `layout` | `paperband.layout` | *(context default)* | Layout template override. |
| `theme` | `paperband.theme` | *(book's `theme:`)* | Named theme; overrides `paperband.yaml`. |
| `themeDir` | `paperband.themeDir` | — | User theme directory, checked before built-ins. |
| `skip` | `paperband.skip` | `false` | Skip the goal without failing the build. |

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
end of that dial: the parts, their titles and their card lists are stated in the POM, and
the cards are selected by glob rather than by where they sit.

```xml
<configuration>
  <output>${project.build.directory}/traces.pdf</output>
  <book>
    <root>${project.basedir}</root>
    <parts>
      <part>
        <title>Execution Traces</title>
        <includes>
          <include>services/*/TRACE.md</include>
        </includes>
        <sort>tier,-id</sort>
      </part>
      <part>
        <id>reference</id>
        <title>Reference</title>
        <landingTemplate>minimal</landingTemplate>
        <includes>
          <include>docs/**/*.md</include>
        </includes>
        <excludes>
          <exclude>docs/draft/**</exclude>
        </excludes>
      </part>
      <part>
        <title>Appendix</title>
        <includes>
          <include>appendix/*.md</include>
        </includes>
        <landingPage>false</landingPage>
      </part>
    </parts>
  </book>
</configuration>
```

That builds a three-part book: one card pulled out of each service directory under a single
"Execution Traces" divider, then everything under `docs/` (minus drafts) under "Reference",
then the appendix cards as a group with no divider of their own. No directory layout
expresses that first part — the trace cards' own folders say nothing about the grouping.

| Element | Notes |
|---|---|
| `root` | Book root. Patterns resolve against it, and its `paperband.yaml` still supplies the title, css, theme, vars, cover and footer. Defaults to the module basedir. |
| `parts` | Ordered list of `part` elements. |
| `part/id` | Part id — becomes `<id>.html` on the static site. Defaults to a slug of `title`. |
| `part/title` | Shown on the divider and landing page. |
| `part/landingTemplate` | Preset name or template path, exactly as a section folder's own `landing.template`. |
| `part/where` | Pebble predicate over `target`; false skips the whole part. |
| `part/includes` | Glob patterns selecting the part's cards, in emission order. |
| `part/excludes` | Glob patterns removing what an include matched. |
| `part/sort` | Comma-separated frontmatter fields, `-` for descending — the `sort:` key's semantics. |
| `part/landingPage` | Whether the part gets a page of its own — `true` by default. See below. |

Order is entirely declared: parts in order, then each part's `include` patterns in order,
then matches within one pattern by `sort` or, with no `sort`, by path. A file is emitted
once — the first part to match it claims it — so overlapping patterns narrow rather than
duplicate.

Two parts may draw *different* files out of the *same* folder, which is the thing
`parts:` in a `paperband.yaml` can't express: a yaml part claims whole folders, while a
POM-declared part claims the individual cards its patterns matched.

## Part pages

Every named part gets a page of its own, generated for you: a full-page divider before its
first card in the PDF, and an `<id>.html` landing page on the static site — the same
treatment a discovered section folder gets, titled from `<title>` and rendered by
`<landingTemplate>`. That's the default; nothing needs declaring to get it.

The divider takes a sheet to itself — forced page break on both sides, the part title and
its table of contents centred on the page — and stands to the full printable height, which
it reads from `--pw-content-height` rather than any fixed page size, so it stays one sheet
at every page size and margin setting. Themes restyle it through the `.section-divider`
class (the built-in look is deliberately plain: sections have no colour concept the way
axis values do). For a divider showing the title alone, dead centre with no card count or
contents list, use `<landingTemplate>minimal</landingTemplate>`.

Set `<landingPage>false</landingPage>` on a part to suppress it:

```xml
<part>
  <title>Appendix</title>
  <includes>
    <include>appendix/*.md</include>
  </includes>
  <landingPage>false</landingPage>
</part>
```

The part still exists — it claims its cards, orders them, and labels the group in the
site's nav, sidebar and index. What goes away is the page itself and every link into it:
no divider in the PDF (the part's first card follows straight on from the previous part's
last), no `<id>.html` on the site, and the group's label renders as plain text rather than
a dead link. The cards keep their own pages either way.

Use it for a run of cards that belongs together for ordering and labelling but doesn't
warrant a page break — a short appendix, a single-card part, or a part whose first card
is already its own title page. For a page that's present but plainer, reach for
`<landingTemplate>minimal</landingTemplate>` instead: title only, no card count or
table of contents.

Only a declared part can decline a page. Discovered section folders always get one.

For the plain "just glob me these files" case, put the patterns straight on `<book>` and
skip `<parts>`. The cards are emitted in pattern order and grouped by their own folders,
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

## What's not supported yet

The plugin depends only on the library modules (`core`, `cards`, `config`, `layout`,
`include`, `render-playwright`) — deliberately not on `cli`, whose shaded jar bundles
picocli and Playwright as a console entry point. A few CLI-only features currently live in
`cli` itself rather than a shared library module, so the plugin doesn't have them yet:

- Watermarking (`--watermark` and friends)
- `--select` / multi-edition publishing
- Page-count reporting and enforcement (`--report-pages`, `--max-pages-per-card`)
- Debug HTML emission (`--emit-html`), external include escape hatches

## Watch Out

`<book><root>` has to *be* the book root — the directory whose `paperband.yaml` carries the
title, css, theme and vars. That side of the config is still resolved from each card's own
parent chain, not from the `<book>` element, so a pattern reaching outside the root would
match cards that belong to a different book.

Like the CLI, the `playwright` renderer needs headless Chromium on first use — see the
Watch Out in Quickstart. A Maven build with no internet access (an offline CI runner, say)
needs Chromium pre-cached before the goal runs, exactly as with the CLI.
