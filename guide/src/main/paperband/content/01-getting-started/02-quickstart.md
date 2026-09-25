---
id: quickstart
oneliner: "What you need installed, which way in to take, and the goals that inspect a book."
---

# Before You Start

Paperband is a Maven plugin, so there's nothing to install beyond a JDK and Maven: the
plugin comes from Maven Central the first time a build asks for it.

## Requirements

| Requirement | Notes |
|---|---|
| JDK 21+ | Must be a JDK (not JRE) — Maven needs `javac` |
| Maven 3.8+ | Standard Maven install |
| Playwright | The only PDF renderer; downloads ~300 MB Chromium on first use |

## Two ways in

Everything else depends on one question: where is your Markdown going to live?

| If… | Read |
|---|---|
| You're starting the writing, or the docs can live inside the project | [Start a New Book](card:java-first): the archetype, and a book at `src/main/paperband/` found by convention |
| The Markdown already exists somewhere and is staying there | [Use the Markdown You Already Have](card:docs-anywhere): one `<content>` line, or glob patterns for files spread across the project |

Both lead to the same books, built by the same goals. Everything after Getting Started
applies to either.

## Explore what's available

These goals answer questions about a book without rendering it, which makes them the
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
