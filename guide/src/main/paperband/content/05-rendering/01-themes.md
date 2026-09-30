---
id: themes
oneliner: "Themes are directories of CSS (and optional templates) that cascade over your book."
index: [themes, CSS cascade]
---

# Themes

A theme is a directory with a `manifest.txt` listing its CSS files, plus an optional
`templates/` directory of Pebble overrides. Built-in themes ship on the classpath; custom
themes are discovered via `<themeDir>`. Theme CSS loads after user CSS, so the theme wins
on conflicts. List available themes with `mvn paperband:themes`. `<theme>none</theme>`
turns theming off, overriding the book's yaml; see [Maven Plugin](card:maven-plugin) for why it
needs a reserved name, and for how `<stylesheets>` layers your own CSS above a theme.

## Built-in themes

Eleven themes ship with paperband: `editorial` (serif, drop caps, magazine feel — this
guide uses it), `editorial-gold`, `classical`, `fieldguide`, `dark`, `blueprint`,
`carded`, `herodevs`, `noregressions`, `workshop`, and `deck`. Pick one with the `theme:`
key in the root `paperband.yaml`, or override at build time with `<theme>`; the build
setting wins when both are given. `mvn paperband:themes` lists the themes on the
classpath.

`workshop` is for a document whose steps repeat one structure, such as a lab where each
step runs why → how → run → observe → establish. It styles the block classes those
headings produce (commands, expected output, a conclusion), renders the repeated headings
as small-caps markers, and distinguishes commands from output by the fence's language
class.

`deck` lays out one card per 16:9 slide, with blocks *placed* into a fixed skeleton
instead of looped in document order. It is the only bundled theme that uses `card.slots`,
and the only one that expects a particular page size; see [Slides](card:slides).

## How CSS composes

Stylesheets are inlined into the rendered HTML in a fixed order, weakest first: a
theme-neutral structural scaffold (for site pages, `site-base.css`; for PDF pages, a
minimal `<style>` block in the page template), then the book's own `css:` chain, then
every stylesheet the theme's manifest lists. Because the theme comes last, its rules win
cascade ties against your CSS; a more specific selector in your CSS still wins. Each
inlined sheet is prefixed with a `/* === theme:name path === */` comment, so the page
source shows where each rule came from.

Prism's syntax-highlighting stylesheet sits below all of that, imported into a CSS
cascade layer (`layer(prism)`), so its `pre[class*="language-"]` box rules never outrank
a theme's plain `pre` rule. Token colours apply where no theme rule competes, and the code
block's size, background and padding come from the theme.

## Print and site layers

One theme covers two media with different requirements: a print measure and point-based
type scale suit paper, while a site has a viewport and a sidebar. A manifest entry can name
the target it applies to:

```
theme.css                 # shared — tokens, colour, type family, code, block semantics
print: theme-print.css    # paged output only — measure, point sizes, page density
site:  theme-site.css     # the static site only — web type scale, grid, breakout
```

Unprefixed entries are shared, so older manifests behave as before. Target layers are
inlined after the shared one, so they override it without heavier selectors. An unknown
prefix fails the build rather than being read as a filename.

Every site page stamps `class="paperband-site target-<target>"` on `<html>`. Key web rules
off `paperband-site` rather than `target-web`: the target name follows `<siteTarget>`, which
a book may rename, while the site hook is stable. Site pages do not carry the `size-*`
class the PDF stamps: themes set page density on it (`html.size-a4 { font-size: 10.5pt }`),
and a website has no page size.

## Width on the web

The content column keeps one left edge. Prose is not run to the window width, and
individual `pre` and `table` elements are not widened beyond the measure, since that moves
the left edge at every wide element. Code that is too wide scrolls inside its own box, and
a wide table gets its own scroller.

A card with at least three headings renders in a two-column grid: the content, then a
sticky **on this page** rail built from the card's block headings:

```
┌──────────┬───────────────────────────┬──────────────┐
│ sidebar  │ content (one left edge)   │ on this page │
└──────────┴───────────────────────────┴──────────────┘
```

Below `68rem` the rail becomes a plain list above the content. A card with fewer than
three headings keeps the single centred column.

Every block gets a stable anchor for the rail to point at: its declared `{id=x}` when the
heading has one, otherwise a slug of the heading, which is also the block's class.

`.pw-breakout` opts a single element out of the measure, such as one large diagram or one
wide matrix. It is applied per element by the author, not to a whole element type:

```css
:root { --pb-breakout-max: 90rem; }   /* how wide an opted-in breakout may go */
```

## What theme CSS can target

The markup exposes stable, data-driven class hooks, which are the main way page data
reaches a theme:

- **Block classes.** Every card block carries `block` plus its heading's auto-slugged
  class (`## Watch Out` → `section.block.watch-out`) or explicit `{.class}` attribute.
  Text before the first heading is the `intro` block. `editorial` styles drop-cap intros
  and warning boxes this way, with no book-specific rules.
- **Axis classes.** Every card's `<article>` carries one `{axisName}-{valueId}` class per
  axis it has a value for (`tier-1`, `section-rendering`). Dividers, sidebar sections,
  and landing heroes carry parallel classes (`.tier-divider.tier-1`,
  `.sidebar-section-tier-1`).
- **Page-level classes.** PDF pages tag `<html>` with `target-{target}` and
  `size-{size}`; site pages tag `<body>` with sidebar state (`has-sidebar`,
  `sidebar-collapsed`). `{size}` is the **resolved** sheet, canonically named — `a4`,
  `6x9`, `16x9`, or bare dimensions (`200x150mm`) for a sheet no preset covers. It
  follows the book's `page.size:` rather than the `<pageSize>` the build was launched
  with, and one sheet has one name however the slug was spelled (`7.5x9.25` reports
  `packt`), so a theme's `html.size-*` rule matches the paper being printed on. The
  bundled themes define `size-a4`, `size-letter` and `size-6x9` only; any other sheet
  uses the theme's bare `html` baseline, which is where `--pw-font-scale` applies.
- **Component classes.** Fixed names for the furniture: `.card-title`, `.oneliner`,
  `.card-meta`, badge classes, `.card-grid`/`.card-item` on landing pages, `.site-nav`,
  `.site-sidebar`, and so on.
- **Page geometry, as CSS custom properties.** PDF pages stamp the build's real geometry
  on `<html>`: `--pw-content-height` (printable height), `--pw-page-margin-top` /
  `-right` / `-bottom` / `-left` (the page margins), and `--pw-font-scale`. Use these
  rather than hardcoding a page size; see [Full-bleed themes](card:themes#full-bleed-themes) below.

## Full-bleed themes

Chromium paints nothing into a PDF page margin; no `@page` rule or `html` background
reaches it. On a theme with a coloured background, any page margin therefore appears as a
white border on every page. A theme whose background covers the page (`blueprint`, `dark`,
`carded`) reaches the trim edge only in a full-bleed build, which the book requests by
setting its page margins to zero:

```yaml
# book root paperband.yaml
page:
  margins: { top: 0, right: 0, bottom: 0, left: 0 }
```

The theme then supplies every inset, which takes two rules:

- **`box-decoration-break: clone` on the card.** Without it Chromium gives a fragmented
  box its padding on the first and last page only, so on the pages in between body text
  runs to the trim edge. With it, the padding and frame repeat on each page the card spans.
  Horizontal padding is kept either way; this rule fixes the vertical padding.
- **Insets sized against the build's own margin.** `max(6mm, 13mm - var(--pw-page-margin-top, 0mm))`
  gives the full inset on a full-bleed build and reduces to the 6mm minimum when the build
  keeps its margins, so one theme works with either setting.

Every bundled theme includes both rules, so any of them can be built full-bleed;
`blueprint` is the most complete example (it also places its card frame by margin rather
than by a centred measure). A custom theme needs only the two rules above.

## Part pages

A part or section divider is a full page, centred (see [Organising Content](card:organising-content)). Themes style it
through `.section-divider`, with `.section-divider .tier-divider-inner` as the centred
title block — the divider itself is the page-sized flex container, so put backgrounds,
frames and rules on the inner element rather than on `.section-divider`. Every bundled
theme styles it; the built-in fallback inherits the book's colours, so a theme that doesn't
style it still renders a legible page.

For a divider showing the title alone, a part can ask for the `minimal` preset — see
[Organising Content](card:organising-content) and the Maven plugin's `<landingTemplate>`.

## How axis colours reach CSS

An axis value's `color:` (or its default-palette fallback) flows into pages two ways.
Dividers, landing pages, index stat boxes, and sidebar accents get it as an **inline
style** emitted by the templates. A theme that wants a different divider treatment must
out-rank the inline style (`herodevs` uses `!important` gradients per
`{axisName}-{valueId}` class).

Card bodies are the opposite: the engine emits **no colour at all**, just the
`{axisName}-{valueId}` class. Themes bridge class to colour with a custom property:

```css
article.card.tier-1 { --tier-color: #c0392b; }
article.card.tier-2 { --tier-color: #e67e22; }
```

and shared rules consume `var(--tier-color)` for badges, headings, and accents. Every
bundled theme follows the same convention with `--card-max-width` (each sets its own
screen and print widths), and the site scaffold exposes `--pw-sidebar-bg` for themes to
set. The class-to-colour mapping and the inline styles don't share a source, so a theme's
`--tier-color` values must be kept in sync with the yaml `color:` values by hand.

## How templates compose

If a theme has a `templates/` directory, its templates sit at the **top** of the Pebble
loader chain: theme templates → the book's own `layouts/` directory → the bundled
defaults. Any bundled template can be overridden by filename — full pages (`card.html`,
`book.html`, `site-index.html`, `site-card.html`, the landing pages) or partials
(`_card-body.html`, `_block-section.html`, `_tier-divider.html`, `_section-divider.html`,
`_book-cover.html`, `_site-sidebar.html`, ...).

Some partials come in pairs: `_card-body.html` is a one-line
`{% extends "_card-body-base" %}`, with the markup and named blocks in the `-base` file. A
theme overrides the wrapper, extends the base, and replaces one block (`carded` restyles
the card header this way) without copying the whole card markup.

## What templates can see

Theme templates receive the same Pebble model the bundled ones use. The core objects:

| Key | Where | What's in it |
|---|---|---|
| `card` | card pages, each entry of `cards` in `book.html` | `id`, `title`, `frontmatter.*` (raw map), `axes.{axisName}.{id,label,color}`, `blocks` (nested: `heading`, `level`, `classes`, `classAttr`, `id`, `anchor`, `attributes`, `directives`, `html`, `nodes`, `children`), `steps` (every `{!step}` block flattened, as `{block, depth}`), `vars` (the card's own cascaded vars; a page's `vars` is the book's in `book.html`); in `book.html` also `dividers` and the facts behind them (see [Dividers](card:themes#dividers)) |
| `book` | book PDF + every site page | `title`, `subtitle`, `series`, `author`, `vars.*`, `cover`, `back` |
| `vars` | `card.html`, `book.html`, `site-card.html` | the fully-cascaded vars map for that card |
| `axis` / `value` | axis dividers and landing pages | `{name, title}` / `{id, label, color, count, cards}` |
| `section` | section dividers and landing pages | `{id, label, count, minimal, cards}` |

Each entry of a divider's or a landing page's `cards` is `{id, title, oneliner, effort,
frontmatter}`: `frontmatter` is the card's whole frontmatter, so a divider can list what the
book declares (`c.frontmatter.audience`), not just the two keys named.

Three vars get promoted to first-class book fields: `vars.subtitle`, `vars.series`, and
`vars.author` become `book.subtitle`, `book.series`, `book.author`, which the cover and
site index read. Everything else in `vars` is reachable too: this guide's
`paperband.yaml` sets `version:`, which no bundled template reads, but a theme template
can with `{{ book.vars.version }}`.

`vars`, `frontmatter`, a block's `attributes` and `directives`, and every node in
`block.nodes` are lenient: reading a key that was never set yields null rather than an error, so themes work across books with
different vars, and `{% if block.directives.step %}` is simply false for a block with no
step. The structural objects (`card`, `block`, `value`) are strict: a typo like
`{{ block.headign }}` fails the build instead of rendering blanks.

Extra keys under an axis value in yaml (`icon:`, `description:`) are not exposed to
templates; only `id`, `label` and `color` are. Put free-form data a theme needs in `vars`
or card frontmatter.

## Structural templates (block slots)

The default card body loops `card.blocks` in document order, so output order is
authoring order. A template can instead **place** blocks into a fixed skeleton via
`card.slots`, which every card model carries:

```
{% for b in card.slots.take('intro') %}{% include "_block-section" with {"block": b} %}{% endfor %}
{% for b in card.slots.take('what-changed') %}{% include "_block-section" with {"block": b} %}{% endfor %}
{% for b in card.slots.rest() %}{% include "_block-section" with {"block": b} %}{% endfor %}
{% for b in card.slots.require('check') %}{% include "_block-section" with {"block": b} %}{% endfor %}
```

`take(name)` consumes every top-level block whose explicit id or class set matches
(auto-slugged headings put the slug in the class set, so `## Watch Out` matches
`watch-out`); a list gives aliases: `take(['watch-out','gotchas'])`. `require(name)` is
`take` for sections every card must have. `rest()` is the optional catch-all for
unexpected blocks, and `has(name)` checks without consuming, for structure-dependent
branching (`{% if card.slots.has('diffs') %}`).

Once a template uses `card.slots`, every top-level block must be placed (in a named slot
or `rest()`) and every `require` satisfied; otherwise the build fails (exit code 4), listing
each offending card and block. Without the `rest()` line, the template becomes a strict
shape check: cards with unexpected sections fail to build. Templates that don't use
`card.slots` are not checked. Nested blocks always travel with their parent — slots operate on
top-level blocks only.

## Picking parts out of a block

Slots move whole blocks. To use *part* of one, such as the paragraph a card marked
`{.instructions}` or the console session under it, filter the block's HTML with
`select`, which takes a CSS selector:

```
{{ block.html | select('p.instructions, pre.console') | raw }}
```

It returns the outer HTML of every match, in document order, and doesn't repeat an
element that sits inside another match. No match gives an empty string, so
`{% if block.html | select('pre.console') %}` works as a test. Like `block.html` itself
the result is HTML, so print it with `| raw`. A selector jsoup can't read fails the build
and names the selector.

Together with `block.directives` it builds a second document out of pieces of the first.
This card body gives one entry per `{!step}`: its heading, its instructions and its
console session, with nested steps inside their parent's entry:

```
{% macro entries(blocks) %}
  {% for b in blocks %}
    {% if b.directives.step %}
      <section class="step-entry">
        <h3>{{ b.heading }}</h3>
        {{ b.html | select('p.instructions, pre.console') | raw }}
        {{ entries(b.children) }}
      </section>
    {% else %}
      {{ entries(b.children) }}
    {% endif %}
  {% endfor %}
{% endmacro %}
{{ entries(card.blocks) }}
```

Put it in a separate `layouts/_card-body.html` and point a second plugin execution at it
with `<layouts>` and its own `<output>`; the source cards don't change. The kitchen-sink
example's `cheatsheet` execution does exactly this.

## Reading a block as data

`select` hands back HTML, and all a template can do with HTML is print it. When a template
needs the words of a paragraph or the command in a fence, read `block.nodes` instead. It's
the same content as `block.html`, as a list of nodes in document order, and like
`block.html` it leaves out the block's children.

| Key | What's in it |
|---|---|
| `type` | `paragraph`, `fence`, `list`, `item`, `table`, `row`, `cell`, `quote`, `figure` or `rule`; inside those, `text`, `code`, `emphasis`, `strong`, `link`, `image`, `span` or `break`. Anything else, such as raw HTML or a drawn diagram, is `element` |
| `tag`, `id`, `classes`, `attributes` | The element's markup. `attributes` leaves out class, id and directives |
| `directives` | `{!name}` directives on the element, such as `{!step}` on a fence |
| `text` | Its text content |
| `html` | Its outer HTML, printed with `\| raw` |
| `children` | Its child nodes |
| `lang`, `code` | A fence's type and its text as the author wrote it |
| `ordered` | A list: true for a numbered one |
| `header` | A table cell: true for a header cell |
| `drawn` | A fence: true when a renderer module drew it, such as a PlantUML diagram |

A fence keeps what the author wrote, whatever its block template makes of it. A
` ```command ` block is `pre.command` to `find`, as it is to `select`, and it's `lang`
`command` with the command as its `code`.

`find` searches nodes with a CSS selector and returns the nodes that match. It takes a
block, `block.nodes` or a single node, and it searches everything under them. That's
enough to fill a fragment of your own from a card's content:

```
{# layouts/fragments/step.html #}
<div class="step">
  <h3>{{ heading }}</h3>
  <p>{{ instructions.text }}</p>
  {% if command is not null %}<kbd>{{ command.code | trim }}</kbd>{% endif %}
</div>
```

```
{% for s in card.steps %}
  {% include "fragments/step" with {
       "heading": s.block.heading,
       "instructions": s.block | find('.instructions') | first,
       "command": s.block | find('pre.command, pre.console') | first } %}
{% endfor %}
```

The fragment prints `text` and `code`, which are escaped like any other value, so it never
needs `| raw` on card content. Unlike `select`, `find` keeps a match that sits inside
another, so `find('li')` returns the items of a nested list too. No match is an empty
list, and a selector jsoup can't read fails the build and names the selector.

The kitchen-sink example does this in `layouts/_card-body.html`: every card with steps
opens with an "At a glance" box, one fragment per step, showing its heading and the command
it runs.

## Dividers

Which divider pages come before each card of a book is decided by a template,
`dividers.html`, not by paperband. It's rendered once per card and prints the dividers in
order, separated by spaces: `axis:<name>` for an axis value's divider, `part` for a part's,
`section` for the card's section. paperband works out the facts it decides from:

| Key | What it says |
|---|---|
| `card.startsValue[axis]` | The card's value for that axis differs from the last card that had one |
| `card.startsSection` | The card is the first of its section |
| `card.part` | Its section's part (`label`, `part`, `sections`), or null |
| `card.startsPart` | The card is the first of that part |
| `card.sectionMeta` | Its section (`label`, `landingPage`, ...), or null |

The bundled rules: one divider per axis that asks for dividers, where its value starts;
a section's divider only when no axis divider fell on the same card; a part's divider in
front of the section that opens it, only where the part spans more than one section. What the
template prints becomes `card.dividers`, a list of `{kind, axis, value, section}`, and the
book's pages, its screen nav, its printed contents, its PDF bookmarks and
`mvn paperband:structure` all follow it. A section with `landingPage: false` still starts
there, and gets no page.

To change the rules, put your own `dividers.html` in the book's `layouts/`, a theme or a
view. This one gives every section its own divider, whatever the axes do:

```
{# layouts/dividers.html #}
{% if card.startsSection %} section{% endif %}
```

## Views

A view is a second set of templates for one build: the same cards, written another way. A
build names it with `<view>`, in the POM or as `-Dpaperband.view=...`, and the site goal
takes it too:

```xml
<view>cheatsheet</view>
```

The view is a folder in the template chain. For every template the build uses, paperband
looks for the view's own first, as `<view>/<name>.html` in the theme, the book's
`layouts/` and the bundled set, then for the default of the same name. So a view only ships
what it changes, and a book overrides a view's template the way it overrides any other:
`layouts/cheatsheet/_card-body.html` beats the bundled `cheatsheet/_card-body.html`.

Every view has a `keep.html`, which says which cards the build holds. It's rendered once per
card, sees the card's model (`card`, `card.steps`, `card.vars`) and `output`, and prints
`true` or `false`:

```
{# layouts/handout/keep.html #}
{{ card.frontmatter.handout == true }}
```

The build leaves out the cards it prints `false` for before it works out anything else, so
they get no page, no contents entry and no divider, and a `card:` link to one prints as its
text. A view with no `keep.html` anywhere in the chain fails the build, which is what
catches a misspelt `<view>`.

Paperband ships one view, `cheatsheet`: it keeps the cards with a `{!step}` and writes each
as its steps. See [Make a Cheat Sheet](card:make-a-cheat-sheet). Block templates
(`blocks/<type>.html`) don't go through the view yet: a fence is written the same way in
every view.

## Authoring a custom theme

A minimal theme is two files:

```
mythemes/
  inkwell/
    manifest.txt
    theme.css
```

`manifest.txt` lists one stylesheet path per line, relative to the theme directory. Blank
lines and lines starting with `#` are ignored:

```
# Inkwell — high-contrast print theme
theme.css
```

Larger themes can be split into several files (tokens first, components after); they
inline in listed order. Build with it:

```bash
mvn paperband:build -Dpaperband.input=mybook -Dpaperband.output=out.pdf -Dpaperband.themeDir=mythemes -Dpaperband.theme=inkwell
```

A `<themeDir>` theme with the same name as a built-in overrides the built-in. To make a
variant of `editorial`, copy its directory from `layout`'s resources and keep the name.

## Check

```bash
mvn paperband:themes -Dpaperband.themeDir=mythemes
```

Lists every discovered theme with its source (`built-in`, your directory, or
"overrides built-in") and how many stylesheets its manifest resolved. A `?` in the styles
column means the manifest failed to load, usually because of a misspelt filename in it.
