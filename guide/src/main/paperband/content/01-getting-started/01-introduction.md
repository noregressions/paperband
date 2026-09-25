---
id: introduction
oneliner: "What Paperband is and the problem it solves."
---

# Introduction

Paperband renders structured Markdown files as PDFs and static sites from the same source.
It is intended for guides, runbooks and migration playbooks that must be available as both
a printable reference and a website, without maintaining two copies.

## The card model

Every piece of content is a **card** — a single Markdown file with YAML frontmatter and
an H1 title. H2 headings inside the file create named **blocks** (intro, what-changed,
how-to-fix, watch-out, check). The block heading is slugified into a CSS class, so themes
can style each section type distinctly.

A card is the unit of authoring, the unit of navigation in the static site, and the named
destination anchor in the PDF.

## The book model

A **book** is a directory tree with a `paperband.yaml` at the root. Subdirectories add
configuration layers: each folder can bind an axis value (grouping cards into sections),
extend the CSS chain, or override variables. The walker collects all `.md` files in
declared order.

## What this guide is

This guide is built with Paperband. The PDF and static site are produced by running the
plugin's `build` and `site` goals on `guide/src/main/paperband/`, so each card also
demonstrates the features it describes.

Excerpts included with `{% fragment %}` are read from the project source at build time, so
they match the current code.

## Check

Run `mvn paperband:scan -Dpaperband.input=path/to/card.md` to inspect any card's parsed structure: frontmatter
fields, resolved id, block list, and a snippet of each block's rendered HTML. Use it to check a card before
building the whole book.
