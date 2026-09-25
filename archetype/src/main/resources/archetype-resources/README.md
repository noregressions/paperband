# Paperband Guide

A [Paperband](https://github.com/noregressions/paperband) guide, scaffolded from the
`paperband-archetype` Maven archetype.

## Layout

    src/main/paperband/
      paperband.yaml       - the book: title, theme, shared vars
      content/
        01-introduction.md - a card - add more .md files here
    pom.xml                 - wires paperband-maven-plugin to `mvn package`

Everything under `content/` is a card, and each subfolder of it becomes a section.
Templates (`layouts/`) and stylesheets (`styles/`) go beside `content/`, not in it.

## Build

    mvn package

Renders the book to `target/<artifactId>.pdf` and to a static site in `target/site/`.
The first PDF build downloads headless Chromium for the `playwright` renderer, so make
sure the machine has internet access at least once before building offline (e.g. in
CI). The site needs no browser.

The goals also run on their own, with no arguments beyond where the output goes:

    mvn paperband:build -Dpaperband.output=target/book.pdf
    mvn paperband:site  -Dpaperband.outputDirectory=target/site
    mvn paperband:structure

See the [Paperband guide](https://noregressions.github.io/paperband/) for the full
authoring reference: frontmatter, includes, conditionals, icons, themes, targets and
page sizes.
