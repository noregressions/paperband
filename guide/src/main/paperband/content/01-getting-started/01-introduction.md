---
id: introduction
oneliner: "What Paperband is and the problem it solves."
---

# Introduction

Paperband turns structured Markdown files into PDFs and static sites from the same source.
It is designed for teams that produce guides, runbooks, or migration playbooks — content
that must exist as both a printable reference and a browsable website without the author
duplicating content.

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

This guide documents Paperband **by using Paperband to build it**. Every card is both
the documentation and a live demonstration: the rendered PDF and static site you are
reading were produced by running the plugin's `build` and `site` goals on the source
in `guide/guide/`.

Excerpts pulled from the actual project source via `{% fragment %}` tags are current
at build time — if the source drifts, the next build catches it.

## Check

Run `mvn paperband:scan -Dpaperband.input=path/to/card.md` to inspect any card's parsed structure: frontmatter
fields, resolved id, block list, and a snippet of each block's rendered HTML. A good first
step before committing to a full book build.
