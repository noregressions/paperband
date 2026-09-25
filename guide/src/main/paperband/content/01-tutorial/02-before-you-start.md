---
id: before-you-start
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

## Where to go next

| If… | Read |
|---|---|
| You are new to Paperband | [Your First Book](card:your-first-book): a step-by-step tutorial, from the first PDF to a site and a slide deck |
| The docs are new, or can live inside the project | [Start a New Book](card:start-a-new-book): the archetype, and a book at `src/main/paperband/` found by convention |
| The Markdown already exists and stays where it is | [Use Existing Markdown](card:use-existing-markdown): one `<content>` line, or glob patterns for files spread across the project |

Both setups produce the same kind of book and use the same goals. The How-to Guides cover
specific tasks; the Reference sections describe every setting.

## Check the environment

Before a project exists, run the plugin by its full coordinates. `renderers` reports
whether the PDF renderer works on this machine:

```bash
mvn dev.noregressions.paperband:paperband-maven-plugin:0.1.3:renderers
```

Inside a project that declares the plugin, the short form (`mvn paperband:renderers`)
works. The goals that describe a book, `structure` and `scan`, need a book to exist; each
path above runs them after its first build.

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
