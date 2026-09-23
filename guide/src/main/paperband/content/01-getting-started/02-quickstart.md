---
id: quickstart
oneliner: "Go from an empty directory to a rendered PDF and site."
---

# Quickstart

## Requirements

| Requirement | Notes |
|---|---|
| JDK 21+ | Must be a JDK (not JRE) — Maven needs `javac` |
| Maven 3.8+ | Standard Maven install |
| Playwright | The only PDF renderer; downloads ~300 MB Chromium on first use |

## Start from the archetype

The archetype scaffolds a working book — a POM wired to the plugin, a `paperband.yaml`,
and one card — so you can see a PDF before you learn any of the configuration:

```bash
mvn archetype:generate \
  -DarchetypeGroupId=dev.noregressions.paperband \
  -DarchetypeArtifactId=paperband-archetype \
  -DarchetypeVersion=0.1.2 \
  -DgroupId=com.example -DartifactId=my-guide
cd my-guide
mvn package
```

The PDF lands in `target/`. Add more `.md` files under `src/main/paperband/` and run
`mvn package` again.

## Add the plugin

To put a book in a project you already have, declare the plugin there:

```xml
<plugin>
  <groupId>dev.noregressions.paperband</groupId>
  <artifactId>paperband-maven-plugin</artifactId>
  <version>0.1.2</version>
  <executions>
    <execution>
      <goals><goal>build</goal></goals>
      <configuration>
        <output>${project.build.directory}/book.pdf</output>
      </configuration>
    </execution>
  </executions>
</plugin>
```

There's no `<input>` in that block on purpose. Put the book at `src/main/paperband/`
— `paperband.yaml` at its root, cards under `content/` — and every goal finds it without
being told. [Java-first Layout](card:java-first) walks through that layout, and
[Docs Where They Already Are](card:docs-anywhere) covers `<content>`, `<book>` and the
legacy `<input>` for a book whose Markdown lives somewhere else.

`mvn package` now builds the book along with the rest of the project. Every goal also runs
on its own, without an execution, which is how the examples throughout this guide are
written.

## Build your first book

From a module whose book sits at the conventional location, the goals take no arguments:

```bash
# PDF
mvn paperband:build -Dpaperband.output=out.pdf

# Static site
mvn paperband:site -Dpaperband.outputDirectory=out-site
```

Point them somewhere else with `-Dpaperband.input=`, which walks any directory that has a
`paperband.yaml` at its root. Cards are the `.md` files found recursively beneath it.

## Explore what's available

Three goals answer questions about a book without rendering it, which makes them the
cheapest way to check your config did what you meant:

```bash
# Cards, sections, axes, page budgets and the index terms auto picked
mvn paperband:structure

# One card's parsed frontmatter, resolved id and block list
mvn paperband:scan -Dpaperband.input=path/to/card.md

# Discovered renderers, and whether each one works in this environment
mvn paperband:renderers

# Built-in themes, plus any found under <themeDir>
mvn paperband:themes
```

## Building from source

The plugin is on Maven Central, so most readers never need this. To work on Paperband
itself, or to try an unreleased change:

```bash
git clone https://github.com/noregressions/paperband.git
cd paperband
mvn -DskipTests install
```

That installs the plugin into your local repository, where the POM above resolves it.
Add `-Pguide` to build this guide too — the PDF, the static site and the deck all land
under `guide/target/`.

## Watch Out

The first Playwright render downloads headless Chromium to `~/.cache/ms-playwright/`.
In CI without internet access, pre-cache it (or point `PLAYWRIGHT_BROWSERS_PATH` at an
existing download) before the first build. Syntax highlighting and ` ```mermaid `
diagrams load their libraries from a CDN at render time, so those need network on every
build that uses them.
