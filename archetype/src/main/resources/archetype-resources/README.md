# Paperband Guide

A [Paperband](https://github.com/noregressions/paperband) guide, scaffolded from the
`paperband-archetype` Maven archetype.

## Layout

    guide/
      paperband.yaml     - book root config: title, theme, shared vars
      01-introduction.md - a card - add more .md files here
    pom.xml               - wires paperband-maven-plugin to `mvn package`

See [Paperband's guide](https://github.com/noregressions/paperband) for the full
authoring reference (frontmatter, includes, conditionals, themes, targets, page sizes).

## Build

    mvn package

Renders `guide/` to a PDF under `target/`, named after this project's artifactId.
First run downloads headless Chromium for the `playwright` renderer — make sure
the machine has internet access at least once before building offline (e.g. in CI).

To re-render without a full `mvn package`:

    mvn paperband:build -Dpaperband.input=guide -Dpaperband.output=target/guide.pdf
