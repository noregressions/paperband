---
id: book-config
oneliner: "The root paperband.yaml declares the book: title, axes, CSS, vars, and targets."
index: [paperband.yaml, axes, vars]
---

# Book Configuration

The `paperband.yaml` at the book root holds global configuration that applies to every
card: the book `title`, categorical `axes`, the base `css` chain, free-form `vars`, the
default `theme`, the declared build `targets`, the book's `page` geometry, and the book's
declared `sections`.

These are **book scope**: read from this file and no other. A folder's `paperband.yaml`
that sets one is an error, not a quiet override — see Config Cascade for the full scope
model and for how the Maven plugin's `<book>` element layers on top.

## `page`

The sheet every page of the book is printed on:

```yaml
page:
  size: a5                                       # preset slug, or { width, height, unit }
  margins: { top: 18, right: 15, bottom: 18, left: 15 }
  orientation: portrait                          # portrait (default) | landscape
  measure: 58rem                                 # text line-length; see Targets
```

`size` and `margins` seed from the plugin's `<pageSize>`/`<margins>`, and this block wins
over them. Margins declared here are emitted as the book's CSS `@page` rule, so don't also
write one in your book stylesheet — two rules for one thing, and the CSS one would win on
cascade order while the content-box height still described this one.

`orientation` is the single key here that a folder *may* also set: it rotates that folder's
cards without changing the book's paper. See Config Cascade.

## `axes`

`axes` declares any number of categorical axes, each independent of the others. A book
with no axes at all is fine — cards just fall back to folder-based "sections".

```yaml
axes:
  - name: tier
    title: Tier
    values:
      - id: 1
        label: "Tier 1 - Critical"
        color: "#c0392b"
      - id: 2
        label: "Tier 2 - Moderate"
        color: "#e67e22"
  - name: subsystem
    title: Subsystem
    values:
      - id: core
        label: "Core Subsystem"
      - id: edge
        label: "Edge Subsystem"
```

Each value's `id`/`label` are required-ish (an id with no label falls back to
`"{axis title} {id}"`); everything else under a value (e.g. `color`) is free-form metadata.
`color` is the one key paperband's own templates read; anything else is available to
custom theme templates but otherwise ignored.

A card joins an axis's value either through its own frontmatter field of the same name:

```yaml
---
title: Some card
tier: 1
subsystem: core
---
```

or through a folder-level binding that cascades to every card under that folder:

```yaml
# some-folder/paperband.yaml
axis:
  tier: 1
```

Frontmatter wins when both are present. A card can belong to zero, one, or several axes'
values at once — each declared axis is tracked completely independently. Every declared
axis (with at least one declared value) automatically gets: a site landing page per value
(`{axisName}-{valueId}.html`), a PDF divider page before the first card of each contiguous
run of that value (stacked with other axes' dividers, in `axes:` declaration order, when a
card starts a new run on more than one axis at once), nav/sidebar entries, and a
`{axisName}-{valueId}` CSS class on every card that has a value for it.

A value a card uses but that isn't listed under the axis's `values:` still gets its own
landing page (with a generated label and a colour from the default palette) rather than
being silently dropped — useful while iterating on a book's structure before every value
is finalized in the yaml.

Cards with no value on any declared axis are grouped by their top-level folder name
instead ("sections") and get their own landing pages alongside the axis-value pages.

## `sections`

Folder-derived sections are discovered — one group per top-level folder. `sections:`
declares those groups instead, giving one title to a run of folders:

```yaml
sections:
  - title: "Foundations"
    folders:
      - 01-getting-started
      - 02-authoring
```

A declared section is one group wherever a discovered one would be: one divider, one
landing page, one nav entry. Folders no declaration claims stay discovered sections. See
Organising Content for the full treatment, alongside the folder-level `order:`,
`include:`, and `sort:` keys.

`sections:` also has a map form, for books that need the declared list *and* the
book-wide landing default below at once — the list moves under `declare:`:

```yaml
sections:
  landing:
    template: minimal
  declare:
    - title: "Foundations"
      folders: [01-getting-started, 02-authoring]
```

## `sections.landing.template`

A section's landing page normally renders with the built-in `site-section` template, but
this is overridable in three places, most specific first:

1. **A declared section's own template.** A section declared in the book yaml or the POM
   carries its own, because it can span several folders and no one of them speaks for the
   group — a declared section never consults a folder yaml at all:

   ```yaml
   sections:
     - title: "Scanners & Blindspots"
       folders: [scanners, blindspots]
       landing:
         template: "layouts/scanners-section.html"
   ```

   ```xml
   <section>
     <title>Scanners &amp; Blindspots</title>
     <landingTemplate>layouts/scanners-section.html</landingTemplate>
     <includes><include>scanners/*.md</include></includes>
   </section>
   ```

2. **A discovered folder's own `paperband.yaml`**, with the same `landing.template` shape
   an axis value uses:

   ```yaml
   # content/scanners/paperband.yaml
   title: "Scanners & Blindspots"
   landing:
     template: "layouts/scanners-section.html"
   ```

3. **A book-wide default** for every section that doesn't declare its own — at the book
   root, or as the POM's `<book><sectionLandingTemplate>`:

   ```yaml
   sections:
     landing:
       template: "layouts/default-section.html"
   ```

Template paths are relative to the book's `layouts/` directory, extension stripped — a
leading `layouts/` is accepted and dropped, so write the path as the file sits on disk.
Subdirectories work: `layouts/sections/scanners.html` loads exactly that. A theme's
template overrides are searched first, the bundled templates last. If neither `landing` is
set, the built-in template is used, same as before this override existed.

Instead of a path, `template:` also accepts a **named preset** — no file needed:

| Name      | Renders                                              |
|-----------|-------------------------------------------------------|
| `default` | Title, card count, and a grid of card tiles (the built-in behaviour) |
| `minimal` | Just the section title — no count, no card list        |

```yaml
# content/quickref/paperband.yaml
title: "Quick Reference"
landing:
  template: minimal
```

A `template:` value that isn't one of these names is treated as a file path, same as
above. Named presets and file-path overrides can be mixed freely across a book's
sections — some folders can use `minimal`, others their own custom template, others
nothing at all (falling through to the book default, then the built-in template).

`minimal` is worth one note: it is not only a site preset. The PDF's section divider has no
HTML template of its own to dispatch on, so it reads the resolved choice and scales itself
down to match — title only, no count or contents. Choosing `minimal` therefore changes
**both** targets. A custom file path only changes the site; the divider keeps its default.

## Section content in markdown

Most "customise the section page" needs are writing, not layout. For that, don't write a
template at all — put a markdown file in the section folder and it **becomes** that
section's landing page content:

```markdown
<!-- content/02-tools/_section.md -->
# The Tools

Ten JDK tools that answer *what will break* before you change a line of code.

Run them roughly in the order below: `jdeps` first to map what you depend on,
then the scanners, then the runtime diagnostics once something actually fails.

{% if vars.audience == "internal" %}Start with the flag audit.{% endif %}
```

It goes through **exactly the pipeline a card gets**, so `{{ vars.x }}`, `{% if %}`,
`{% for %}`, `{% include %}` and `{% fragment %}` all work, along with block templates and
the content policy.

| File | Notes |
|---|---|
| `_section.md` | The explicit spelling; wins when both are present |
| `README.md` | Works too — card discovery already skips readmes, and a readme *is* documentation about the directory |

Neither is loaded as a card, so the section's card count and card list are unaffected.

### The book has one too

The content root is the outermost section, so `content/_section.md` is the **book's** own —
no second filename and no second rule. It renders as the site index's body, and in the PDF
as front matter between the cover and the first card. It replaces the index's stat rows and
section grid; `sections: true` in its frontmatter asks for them back.

### Both targets, one file

A section body renders on the site's landing page **and** on the PDF's section divider —
the same writing, not two copies. Where the two want different words, branch on `output`:

```markdown
# The Tools

Ten JDK tools that answer *what will break* before you change a line of code.

{% if output == 'print' %}
The chapters that follow cover each in turn.
{% else %}
Browse them below — start with `jdeps`.
{% endif %}
```

| In scope | Value |
|---|---|
| `output` | `print` or `site` — what to branch on |
| `target` | the raw build target (`pdf-a4`, `web`), which a book may rename |

On the divider a body replaces the card count and printed contents, exactly as it replaces
the card grid on the site.

### It replaces, it doesn't decorate

The card list is the **default** content — what a section shows when it has nothing of its
own to say. Writing a `_section.md` replaces it. To keep the list as well, ask for it:

```markdown
---
cards: true
---

# The Tools

Prose, and then the usual card list beneath it.
```

That holds even for a book with a custom landing template: the rule is about the section,
not about who draws the list, so the test wraps the `cards` block's *invocation* rather
than its contents.

### Laying the cards out yourself

The section's own cards are in scope as `section`, so the markdown can list them however it
likes instead of choosing between the default grid and nothing:

```markdown
## All {{ section.count }} chapters

{% for c in section.cards %}1. [{{ c.title }}]({{ c.url }})

{% endfor %}
```

| In scope | What |
|---|---|
| `section.id` | the folder name |
| `section.count` | how many cards it holds |
| `section.cards` | each `{id, title, url, anchor, oneliner, frontmatter}`, in book order |
| `vars` | the config cascade, as in any card |

`url` is relative to the landing page and `anchor` is the in-PDF `#card-…` target, so a
body that serves both targets links with whichever suits: `[{{ c.title }}]({{ c.url }})` on
the site, `{{ c.anchor }}` in print. `frontmatter` gives you everything else the card declared — filter or group on
it with ordinary Pebble.

**One gotcha, and it will bite you:** Pebble eats the newline immediately after a
`{% %}` tag. A markdown line that *ends* with a tag loses its line break, and the next line
runs on — a ten-item list collapses into one. Put something after the tag, or leave a blank
line before the closing one:

```markdown
{% for c in section.cards %}1. [{{ c.title }}]({{ c.url }}){% if c.oneliner %} — {{ c.oneliner }}{% endif %}
{% endfor %}          ← broken: the line ends with {% endif %}

{% for c in section.cards %}1. [{{ c.title }}]({{ c.url }})

{% endfor %}          ← fine: a blank line survives
```

### Other details

The `# Heading` becomes the **section's label** when the folder declares no `title:` — one
file then describes the section completely. (The markdown loader hoists a leading `#` out
of the body, so without that rule it would be written and silently dropped.)

Only folder-backed sections can have one. An axis value is a label spanning the whole book
with no directory of its own, so there's nowhere to put the file. Declared sections that
span several folders don't pick one up either — bodies are keyed by folder name.

The wrapper is `.section-body`. The hero above it is page chrome, not content; override the
`hero` block to drop it. The PDF divider is unaffected — this is a site feature.

## Writing a custom section template

**A custom template does not have to replace the whole page.** The shell — document head,
stylesheet, top nav, sidebar, main column — lives in one base template, and every built-in
site page extends it. Override only the block you care about:

```html
{# layouts/sectionLanding.html — a reading list instead of the card grid #}
{% extends "site-section" %}

{% block cards %}
<ol class="section-landing-list">
  {% for c in cards %}
  <li>
    <a href="{{ urlPrefix }}cards/{{ c.id }}.html">{{ c.title }}</a>
    {% if c.oneliner %}<span class="section-landing-oneliner">{{ c.oneliner }}</span>{% endif %}
  </li>
  {% endfor %}
</ol>
{% endblock %}
```

That is the whole file. Everything else is inherited, which matters beyond brevity: a page
that hardcodes the shell stops tracking it, so a new sidebar option or theme hook silently
passes it by.

Every site page works this way, not just section landings. Extend the page you want to
change and override one block:

| Extend | Blocks it adds | Page |
|---|---|---|
| `site-index` | `hero`, `stats`, `sections` | the book's front page |
| `site-section` | `hero`, `body`, `cards` | a section landing page |
| `site-tier` | `hero`, `cards` | an axis-value landing page |
| `site-card` | `cardNav`, `body`, `rail`, `cardNavBottom` | a card page |
| `_site-page` | — | the shell itself, when you want the whole main column |

`cards` is the body below the hero — the card grid and its heading. `body` on a card page is
the card itself plus any auto-cards; `rail` is the on-this-page nav.

All of them also inherit the shell's own blocks:

| Block | Contents |
|---|---|
| `title` | the `<title>` text; defaults to the book title |
| `head` | extra `<head>` content, before the stylesheet |
| `bodyClass` | extra classes on `<body>`, appended to the sidebar state |
| `content` | everything inside `<main>` |

A block you don't mention keeps its built-in contents, and an empty block removes it —
`site-section-minimal` is nothing but `{% extends "site-section" %}` with an empty `cards`
block.

Don't name your file the same as the template it extends — a same-named override resolves
`{% extends %}` to itself and recurses. Name it for what it is (`sectionLanding.html`), not
for what it replaces.

### What the template is given

| Key | What |
|---|---|
| `section` | this section: `id`, `label`, `count`, `landingTemplate`, `minimal`, `landingPage`, `cards` |
| `cards` | its cards, each `{id, title, oneliner, axes, effort, openrewrite, subsystem}` |
| `book` | `title`, `subtitle`, `series`, `author`, `vars`, `cover`, `back`, `header`, `footer` |
| `sections` | every section's meta, for cross-links |
| `navEntries` / `sidebarEntries` | the nav model (the shell passes these to the partials) |
| `stats` | `{total, openrewrite}` |
| `css` / `cssImports` | the composed stylesheet (the shell emits it) |
| `htmlClass` / `measure` | the `<html>` hooks — see Themes / print and site layers |
| `sidebar`, `sidebar_collapsed`, `sidebar_sections_collapsed` | sidebar state |
| `page` | `{kind: "section", id}` — lets the partials mark the active row |
| `urlPrefix` | `""` on a landing page (`"../"` on card pages) |

Style your own classes from the book's CSS chain, or the POM's `<stylesheets>` — they're
yours, not the theme's.

Axis values work identically: extend `site-tier` and override its `cards` or `hero` block.
