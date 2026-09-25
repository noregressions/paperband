---
id: introduction
oneliner: "Getting started with this guide."
---

# Getting Started

Welcome to your new Paperband guide. This card is here to prove the build works:
replace it with your own content.

## How this project is put together

`src/main/paperband/paperband.yaml` is the book's config: its title, theme and shared
variables. The cards live in `src/main/paperband/content/`, and every `.md` file there
is one: a Markdown file with an H1 title and, optionally, YAML frontmatter. Add as many
as you like. Cards build in filename order, so a numeric prefix like this file's `01-`
is the easiest way to control it, and each subfolder of `content/` becomes a section of
the book.

The plugin finds all of this by convention, the same way Maven finds `src/main/java`,
which is why the `pom.xml` never says where the book is.

## Building it

Run `mvn package`. It writes a PDF to `target/`, named after your project's artifactId,
and a static site to `target/site/`. The PDF renderer needs headless Chromium, which it
downloads the first time you build, so make sure the machine running the build has
internet access at least once before relying on it offline (e.g. in CI).

## Check

Run `mvn paperband:structure` to see the book's sections and cards without rendering
anything, and see the [Paperband guide](https://noregressions.github.io/paperband/) for
the rest: themes, page sizes, icons, includes and the plugin's configuration.
