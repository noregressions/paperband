---
id: introduction
oneliner: "Getting started with this guide."
---

# Getting Started

Welcome to your new Paperband guide. This card is here to prove the build works —
replace it with your own content.

## How this project is put together

`src/main/paperband/paperband.yaml` is the book root config (title, theme, shared
variables) — the same `src/main/<language>` convention plugins like Asciidoctor
or ANTLR use for their own sources. Every other `.md` file under
`src/main/paperband/` is a **card**: a Markdown file with YAML frontmatter and an
H1 title. Add as many as you like — `paperband-maven-plugin` walks the whole
directory tree and assembles them into one PDF in declared (filename) order, so a
`NN-` numeric prefix like this file's `01-` is the easiest way to control ordering.

## Building it

Run `mvn package`. It produces a PDF under `target/` named after your project's
artifactId. The `playwright` renderer needs headless Chromium on first use,
downloaded automatically the first time you build — make sure the machine
running the build has internet access at least once before relying on it
offline (e.g. in CI).

## Check

Run `mvn paperband:build -Dpaperband.input=src/main/paperband -Dpaperband.output=target/guide.pdf`
to re-render without a full `mvn package`, and see the
[Maven Plugin section of the Paperband guide](https://github.com/noregressions/paperband)
for the rest of the plugin's configuration options (renderer, target, page size,
theme, and more).
