---
id: i-want-to
oneliner: "Common tasks, each with the smallest snippet that does it and a link to the full explanation."
index: [tasks, how-to]
---

# I Want To…

Each entry gives the snippet that does the task and the card that explains it. Book-level
yaml goes in the book's `paperband.yaml` (`src/main/paperband/paperband.yaml` in a
conventional book); POM snippets go in the `paperband-maven-plugin` block.

## Setup

### Start a book from nothing

```bash
mvn archetype:generate \
  -DarchetypeGroupId=dev.noregressions.paperband \
  -DarchetypeArtifactId=paperband-archetype \
  -DarchetypeVersion=0.1.3 \
  -DgroupId=com.example -DartifactId=my-guide
```

See [Start a New Book](card:start-a-new-book).

### Build Markdown that already exists

```xml
<configuration>
  <content>docs</content>
</configuration>
```

See [Use Existing Markdown](card:use-existing-markdown).

### Put the book somewhere other than `src/main/paperband`

```xml
<configuration>
  <home>${project.basedir}/src/main/handbook</home>
</configuration>
```

See [Place a Book in a Project](card:place-a-book).

## Content

### Link to another card

```markdown
See [the install steps](card:install).
```

The link resolves for the PDF and the site, and an unknown id fails the build. See
[Card Structure](card:card-structure#linking-to-another-card).

### Include an excerpt from a source file

Mark the region in the source with `ANCHOR: main` and `ANCHOR_END: main` comments, then:

```markdown
{% fragment "src/main/java/App.java:main" %}
```

Reads outside the book root need `<externalIncludeDirs>`. See [Includes](card:includes).

### Add an icon

```markdown
:rocket: Deploy
```

Names come from the bundled Lucide set or the book's `icons/` folder. See [Icons](card:icons).

### Branch content between the PDF and the site

```markdown
{% if output == 'print' %}
See the fold-out map on the next page.
{% endif %}
```

`output` is `print` for the PDF and `site` for the static site. See
[Vars and Conditionals](card:vars-and-conditionals).

### Give a card a summary line

```yaml
---
oneliner: "Install the agent and confirm it reports."
---
```

See [Frontmatter Reference](card:frontmatter-reference).

## Structure

### Put cards in a set order

```yaml
# the folder's paperband.yaml
order:
  - 01-overview
  - 02-install
```

Listed entries come first; the rest follow alphabetically. `include:` lists the cards
exclusively. See [Organising Content](card:organising-content).

### Keep drafts out of the book

```yaml
ignore:
  - drafts/**
  - "*.tmp.md"
```

See [Organising Content](card:organising-content).

### Leave a card out of the web build

```yaml
order:
  - intro
  - { id: print-appendix, where: "target != 'web'" }
```

The site goal builds with target `web`. See [Targets](card:targets).

### Group cards by a category

```yaml
axes:
  - name: tier
    title: Tier
    values:
      - { id: 1, label: "Tier 1 - Critical", color: "#c0392b" }
```

A card joins a value with `tier: 1` in its frontmatter, or a folder with
`axis: { tier: 1 }`. See [Book Configuration](card:book-configuration#axes).

### Add a contents page and an index

```yaml
vars:
  toc: true
  index: auto
```

Page numbers are filled in by a second render pass. See
[TOC and Index](card:toc-and-index#turning-them-on).

## Output

### Build the static site

```bash
mvn paperband:site -Dpaperband.outputDirectory=target/site
```

See [Maven Plugin](card:maven-plugin#the-site-goal).

### Turn on the site sidebar

```yaml
sidebar: true
```

See [Configuration Reference](card:configuration-reference#the-site-sidebar).

### Print a folder's cards in landscape

```yaml
# the folder's paperband.yaml
page:
  orientation: landscape
```

Set it in the book's `paperband.yaml` to rotate every card. See
[Configuration Cascade](card:configuration-cascade#page-geometry-is-book-scope).

### Make a slide deck

```yaml
theme: deck
page:
  size: 16x9
  margins: { top: 0, right: 0, bottom: 0, left: 0 }
vars:
  maxPagesPerCard: 1
```

For a `.pptx`, add `render-pptx` to the plugin's `<dependencies>` and build with
`-Dpaperband.renderer=pptx`. See [Slides](card:slides).

### Publish several editions of one book

```yaml
publication:
  defaults:
    output: "target/{id}.pdf"
  editions:
    - id: full
    - id: mini
      select:
        cards: [introduction, install]
```

Build them all with `mvn paperband:publish -Dpaperband.bookDirectory=src/main/paperband`
(the directory holding that `paperband.yaml`). See
[Maven Plugin](card:maven-plugin#the-publish-goal).

## Checks

### Fail the build when a card runs long

```yaml
---
max_pages: 2
---
```

For every card, set `<maxPagesPerCard>` in the POM or `vars.maxPagesPerCard` in the yaml. A
violation exits with code 3. See [Page Enforcement](card:page-enforcement#setting-limits).

### Check the configuration without rendering

```bash
mvn paperband:structure
mvn paperband:scan -Dpaperband.input=src/main/paperband/content/01-overview.md
```

`structure` lists every section and card in build order; `scan` shows one card's
frontmatter, id and blocks. See [Troubleshooting](card:troubleshooting).

## Appearance

### Stamp a watermark

```yaml
vars:
  watermark: "DRAFT"
```

The same declaration marks the PDF and the site. For a one-off build use
`-Dpaperband.watermark="DRAFT"`. See [Watermarks](card:watermarks#declaring-in-yaml).

### Change the theme

```yaml
theme: workshop
```

`mvn paperband:themes` lists the themes available. See [Themes](card:themes#built-in-themes).

### Add your own CSS on top of a theme

```xml
<stylesheets>
  <stylesheet>${project.basedir}/src/main/paperband/styles/overrides.css</stylesheet>
</stylesheets>
```

`<stylesheets>` apply after the theme, so their rules win without `!important`. The yaml
`css:` chain applies before the theme. See [Themes](card:themes#how-css-composes).

### Add a cover image

```yaml
cover: images/cover.png
```

The path is relative to the book's `paperband.yaml` (in a conventional book,
`src/main/paperband/images/cover.png`). A bare string is shorthand for `cover.image`; the
map form adds `template`, `text` and `fullPage`. See [Configuration Reference](card:configuration-reference#book-scope-keys).
