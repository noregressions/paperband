---
id: quickstart
oneliner: "What you need installed, which way in to take, and the goals that inspect a book."
---

# Before You Start

Paperband is a Maven plugin. It needs only a JDK and Maven; Maven downloads the plugin from
Maven Central on first use.

## Requirements

| Requirement | Notes |
|---|---|
| JDK 21+ | Must be a JDK (not JRE) — Maven needs `javac` |
| Maven 3.8+ | Standard Maven install |
| Playwright | The only PDF renderer; downloads ~300 MB Chromium on first use |

## Two ways in

The setup depends on where the Markdown will live:

| If… | Read |
|---|---|
| The docs are new, or can live inside the project | [Start a New Book](card:java-first): the archetype, and a book at `src/main/paperband/` found by convention |
| The Markdown already exists and stays where it is | [Use the Markdown You Already Have](card:docs-anywhere): one `<content>` line, or glob patterns for files spread across the project |

Both produce the same kind of book and use the same goals. The rest of the guide applies
to both.

## Explore what's available

These goals report on a book without rendering it, so they are the fastest way to check
configuration:

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

To work on Paperband itself or use an unreleased change, build it from source:

```bash
git clone https://github.com/noregressions/paperband.git
cd paperband
mvn -DskipTests install
```

This installs the plugin into the local Maven repository. Add `-Pguide` to also build this
guide; the PDF, site and deck are written to `guide/target/`.

## Watch Out

The first Playwright render downloads headless Chromium to `~/.cache/ms-playwright/`.
In CI without internet access, pre-cache it, or point `PLAYWRIGHT_BROWSERS_PATH` at an
existing download, before the first build. Syntax highlighting and ` ```mermaid `
diagrams load their libraries from a CDN at render time, so those need network on every
build that uses them.
