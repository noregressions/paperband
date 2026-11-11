# Paperband

Paperband turns Markdown into laid-out documents: a web site, fixed-size pages in HTML, and PDF. The
same sources feed all three, so a guide, its printed handbook and its slide deck can come from one
set of files.

Two files do the steering. A *manifest* says which Markdown files go in, in what order, and what
comes out. A *layout* says where the parts of a page go, picking content with CSS selectors.

It runs as a Maven plugin and needs Java 21.

## A first build

Put a page in `docs/`:

```markdown
# Hello

This page is built by Paperband.
```

Add a `manifest.yml` beside your `pom.xml`:

```yaml
title: My guide
sources: [docs/**/*.md]
sequence:
  - files: docs/*.md
outputs:
  - { name: site, format: web, file: target/site/index.html }
```

Then add the plugin to the `pom.xml`:

```xml
<plugin>
  <groupId>dev.noregressions.paperband</groupId>
  <artifactId>paperband-maven-plugin</artifactId>
  <version>0.2.0</version>
  <executions>
    <execution>
      <goals><goal>build</goal></goals>
    </execution>
  </executions>
  <configuration>
    <manifest>manifest.yml</manifest>
  </configuration>
</plugin>
```

Run `mvn generate-resources` and open `target/site/index.html`. With more than one page, a web
output is a site: a file per page, the contents beside each one.

Paths in a manifest are relative to the manifest. Every manifest key can also be set in the
plugin's `<configuration>`, and a setting there overrides the manifest's.

## PDF and paged HTML

A build can write several outputs from the same sources:

```yaml
outputs:
  - { name: site, format: web, file: target/site/index.html }
  - { name: handbook, format: paged-html, file: target/handbook.html }
  - { name: handbook-pdf, format: pdf, file: target/handbook.pdf }
```

PDF is printed in Chromium, which lives in its own module. Add it as a dependency of the plugin:

```xml
<plugin>
  <groupId>dev.noregressions.paperband</groupId>
  <artifactId>paperband-maven-plugin</artifactId>
  ...
  <dependencies>
    <dependency>
      <groupId>dev.noregressions.paperband</groupId>
      <artifactId>chromium</artifactId>
      <version>0.2.0</version>
    </dependency>
  </dependencies>
</plugin>
```

The first build downloads Chromium. To use a browser you already have, set `browser: chrome` (or
`msedge`, or the path to an executable) in the manifest.

Paged pages have a fixed size, so content can be too big for its box. Paperband measures every box
in the browser before it writes anything. When something doesn't fit, the layout's `fit:` remedies
are tried in order: shrink the text, switch to a shorter variant, drop optional blocks, continue
on another page. If it still doesn't fit, the build fails and writes a report beside the output
(`handbook.fit.html`) that marks each box that overflowed.

Without the `chromium` module, a `pdf` output is an error and a `paged-html` output is written
unmeasured, with a warning.

## Layouts

A layout is a YAML file in `layouts/`, named after the file. Each box takes the content its
selector matches:

```yaml
# layouts/split.yml
grid:
  columns: [1fr, 2fr]
  areas:
    - "title title"
    - "intro body"
boxes:
  - { id: title, area: title, select: h1, pick: first }
  - { id: intro, area: intro, select: "section.h1 > p" }
  - { id: body,  area: body,  select: rest }
```

A page asks for a layout in its front matter (`layout: split`), or a sequence entry gives one to
every file it matches. A layout can also be written inline in a page's front matter, or start
from another with `extends` and change only some boxes.

Markers in the Markdown give selectors something to hold on to. `{! step}` after a heading or
paragraph adds the class `step`, so a layout can select `.step`:

```markdown
### Download the release {! step}

Download the latest archive. {! instruction}
```

## Themes

A theme sets how every output looks. Paperband brings two: the built-in look, and `forest`.

```yaml
theme: forest
```

`forest` is a calm editorial theme: white pages, one deep-green accent, Source Sans 3 with light
headlines. It also brings eight slide layouts on a 16:9 page (`title-photo`, `statement-photo`,
`list-photo`, `three-columns`, `four-steps`, `title-content`, `section`, `closing-photo`).
`examples/forest-deck` uses each one once.

Your own theme is a directory, named by its path:

```yaml
theme: themes/mine
```

```
themes/mine/
  theme.css      # your variables and rules, added after the built-in styles
  web.css        # each of these replaces the built-in part of the same name
  paged.css
  content.css
  site.css
  nav-side.css
  nav-top.css
  layouts/       # layouts the theme supplies
  fonts/         # files theme.css refers to
```

Everything is optional. To change only colours and fonts, write `theme.css` and set the `--pb-*`
variables:

```css
:root {
  --pb-font-body: "Georgia", serif;
  --pb-rule: #cccccc;
  --pb-link: #2f5d50;
}
```

The built-in parts in `core/src/main/resources/dev/noregressions/paperband/themes/default/` show
which variables exist and what each part holds.

Three things are worth knowing before you write one:

- A `url("fonts/text.woff2")` that names a file inside the theme is embedded in the output, so
  the theme's fonts reach the PDF. Paged output only measures the same on every machine with
  fonts that travel this way, or with the bundled Inter and JetBrains Mono.
- A layout in your project replaces a theme's layout of the same name.
- A directory wins over a bundled name. Copy `forest` into a `forest/` directory beside your
  manifest and `theme: forest` uses your copy.

The styles apply in this order, each able to override the one before: the theme, the manifest's
`stylesheets`, each layout's own CSS.

## What else it does

| Feature | What it gives you | Example |
| --- | --- | --- |
| Templating | Pebble in Markdown: variables, loops, includes, data from YAML and JSON files | `examples/docs/pebble-templating.md`, `data-and-build.md` |
| Variants | One passage in `full`, `short` and `shortest` forms; each output shows one | `examples/docs/variants.md` |
| Fitting | The remedies a box tries when its content is too big | `examples/docs/fitting.md` |
| Splitting | A box that continues on further pages, breaking only where you allow | `examples/docs/split-layout.md` |
| Index | A back-of-book index from markers, or from a list of terms | `examples/docs/index-markers.md` |
| Site navigation | Contents beside every page, in a bar of dropdowns, or none | `examples/docs/web-site/` |
| Diagrams | ` ```mermaid ` and ` ```plantuml ` blocks drawn as inline SVG at build time | add the `mermaid` or `plantuml` module as a plugin dependency |
| Copy buttons | A Copy button on code blocks in the languages you list (`copy: [bash]`) | `examples/manifest.yml` |

The full list of manifest keys is in the Javadoc of
[`Manifest`](core/src/main/java/dev/noregressions/paperband/layout/Manifest.java), and the layout
keys in [`Layout`](core/src/main/java/dev/noregressions/paperband/layout/Layout.java).

## The examples

`examples/` is the best place to see how the pieces fit. Build it from the repository root:

```bash
mvn install
```

That writes, under `examples/target/`:

| File | What it is |
| --- | --- |
| `showcase.html` | Every example's Markdown beside its rendered result |
| `handbook.html`, `handbook.pdf` | A few examples as A4 pages |
| `site/`, `site-top/` | The web-site example, with side navigation and with a navigation bar |
| `forest/deck.pdf`, `forest/web.html` | The `forest` theme as a deck |

The PDF outputs need Chromium, so the first build downloads it.

## Without Maven

The `core` module has a command-line entry point, `dev.noregressions.paperband.App`. From a
checkout:

```bash
mvn -pl core exec:java -Dexec.args="--manifest manifest.yml"
```

Run it with `--help` for the options. It also writes `ast.html`, a debug page showing the parsed
documents. PDF needs the `chromium` module on the classpath, which this command doesn't add.

## Modules

| Module | What it holds |
| --- | --- |
| `core` | The pipeline, layouts, themes and renderers |
| `maven-plugin` | The `build` goal |
| `chromium` | Measuring and printing in headless Chromium |
| `mermaid`, `plantuml` | Diagram blocks |
| `examples` | The examples above; never published |

All of them share the group `dev.noregressions.paperband`.

## Licence

Apache License 2.0. See [LICENSE](LICENSE). The bundled fonts (Inter, JetBrains Mono and Source
Sans 3) are under the SIL Open Font License; their licences sit beside the font files.
