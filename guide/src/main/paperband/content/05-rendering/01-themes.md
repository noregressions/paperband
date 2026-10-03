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

Every filter, bundled template and view is listed in
[Template Reference](card:template-reference).

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

## Querying the whole book

`find` sees one block, and a node it returns doesn't say where it was. A page built from the
whole book, such as every command or every Watch Out, needs both: it crosses cards, and it
has to say which card each piece came from. `query` does that. It takes `cards`, one card or
one block, and a CSS selector that sees two more levels above the nodes:

| Element | What the selector sees |
|---|---|
| `card` | Its id; a `<axis>-<value>` class per axis value, as its article carries; its frontmatter's scalar keys as attributes, with a list's items joined by spaces: `card#setup`, `card.level-beginner`, `card[index~=maven]` |
| `block` | Its id, classes, attributes and directives, as its `<section>` has them, with its nested blocks inside it: `block.watch-out`, `block[data-paperband-step]` |
| nodes | What `find` sees, inside their block |

It returns one entry per match, in document order:

| Key | What's in it |
|---|---|
| `kind` | `card`, `block` or `node`: the level that matched |
| `card` | The card it's in, or the match itself |
| `block` | The innermost block it's in, or the match itself; null for a card match |
| `step` | The innermost `{!step}` block around it, or null |
| `node` | The node, for a node match; otherwise null |

Each is the model a template already has, so `e.block.heading`, `e.block.html` and
`e.node | find(...)` work as usual. Like `find`, `query` keeps a match inside another. No
match is an empty list, and a selector jsoup can't read fails the build and names the
selector.

Only a template that sees the whole book has `cards`, so this belongs in a `<page>` template
(see [Maven Plugin](card:maven-plugin#generated-pages)) or `_book-front.html`. A card body
can still query its own `card`. This `layouts/commands.html` is a command reference: each
card that has a command in a step, with each step's command under its heading:

```
<h1>Command reference</h1>
{% for c in cards %}
  {% set commands = c | query('block[data-paperband-step] pre.command') %}
  {% if commands is not empty %}
  <h2><a href="card:{{ c.id }}">{{ c.title }}</a></h2>
  <dl class="command-reference">
  {% for e in commands %}
    <dt>{{ e.step.heading }}</dt>
    <dd><pre class="command"><code>{{ e.node.code | trim }}</code></pre></dd>
  {% endfor %}
  </dl>
  {% endif %}
{% endfor %}
```

Querying one card at a time is what groups the entries: the loop gives the card heading, and
the query gives its commands. A query of `cards` gives one flat list instead, which suits a
page that doesn't group. This one collects every Watch Out in the book:

```
{% for e in cards | query('block.watch-out') %}
  <section class="watch-out-entry">
    <h2><a href="card:{{ e.card.id }}">{{ e.card.title }}</a></h2>
    {{ e.block.html | raw }}
  </section>
{% endfor %}
```

`card:` links in a page template resolve like the ones in cards do.

## Changing what a block prints

`find`, `select` and `query` pick parts out of a card, but they hand them back as they were.
To print a changed copy, such as a step without its console output, transform it and write
it back:

```
{{ e.block | drop('pre.console') | addClass('p.instructions', 'lead') | html | raw }}
```

| Filter | What it does |
|---|---|
| `drop('css')` | Leaves out every match, and everything in it |
| `keep('css')` | Leaves out everything that isn't a match, inside one, or around one |
| `addClass('css', 'name')` | Adds a class to every match. The name is letters, digits, `-` and `_` |
| `removeClass('css', 'name')` | Takes a class off every match |
| `set('css', '{key=value}')` | Sets attributes on every match |
| `replace('css', '{.name}')` | Puts an empty block where each match was. It keeps the match's id and attributes, the match's winning over the spec's, so a link to it still lands and `{lines=8}` on a solution still counts |
| `insertBefore('css', '{.name}')`, `insertAfter(…)` | Adds an empty block before or after each match |
| `prepend('css', '{.name}')`, `append(…)` | Adds an empty block inside each match, first or last |
| `wrap('css', '{.name}')` | Puts each match inside a new empty block |
| `blank('css', 'name')` | `replace` with a class and nothing else. A node it blanks keeps nothing of the match |
| `html` | Writes nodes back as HTML, the way `block.html` is written. Print it with `\| raw` |

A block the filters add is written the way a card writes attributes: `'{.answer lines=4}'`,
`'{#q1 .answer}'`. The braces are optional. It's an empty `<div>` (or an empty block, at
card level) with that id, those classes and those attributes, and nothing else.

### What they take and return

Given a block, a list of nodes or one node, the selector sees nodes, as `find`'s does, and
each filter returns the changed nodes as a list. So they chain, `find` can search what they
return, and `html` writes it. A block's children are left out, as they are from
`block.html`.

Given a card, or a list of cards such as `cards`, the selector sees cards and blocks as well
as nodes, as `query`'s does, and the filter returns the changed card:

```
{% set card = card | replace('.solution', '{.answer-space lines=4}') %}
{% for block in card.blocks %}{% include "_block-section" with {"block": block} %}{% endfor %}
```

`.solution` there matches a `## Solution {.solution}` block, heading, nested blocks and
all, and a `{.solution}` paragraph inside another block. `block.solution` matches only the
block, and `p.solution` only the paragraph. The card that comes back is one a template
writes like any other: each changed block's `html` is written again, and `card.steps` and
`card.slots` are made from its blocks. A template that places the changed card's blocks
through `card.slots` is checked as the card's own would be.

`drop` and `keep` can leave out whole cards, which nothing else can change:

```
{% set kept = cards | keep('card:has([data-paperband-step])') %}
```

A card left out is missing from the list, or null when the filter was given one card.

### Every match first, then the change

Each filter finds all its matches before it changes anything, so it never matches a block
it added. The next filter in the chain sees what the last one did:
`insertAfter('li', '{.note}') | addClass('.note', 'wide')` adds the class to every new note.

Nothing changes in place: the card prints as it did everywhere else, and a changed node's
`text` and `html` describe the change. `html` writes an unchanged node exactly as
`block.html` has it, and a ` ```command ` block through its block template, so it keeps
its label and copy button. A class `addClass` puts on a fence reaches the template too.

No transform can add markup of the template's choosing. They remove things, change classes
and attributes, or add an empty block, and a block can't carry anything the content policy
strips from a card (`style`, `width`, `on*` handlers and the rest), nor an attribute that
holds a URL. So the content is as safe as it was when the card loaded.

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

A view's `keep.html` says which cards the build holds. It's rendered once per card, sees
the card's model (`card`, `card.steps`, `card.vars`) and `output`, and prints `true` or
`false`:

```
{# layouts/handout/keep.html #}
{{ card.frontmatter.handout == true }}
```

The build leaves out the cards it prints `false` for before it works out anything else, so
they get no page, no contents entry and no divider, and a `card:` link to one prints as its
text. A view's `transform.html` (below) can leave cards out too, so a view needs one of
the two. A view with neither anywhere in the chain fails the build, which is what catches a
misspelt `<view>`.

A view's template can still use the template it replaces. `default:` in front of a name
looks it up as if there were no view, through the theme, the book's `layouts/` and the
bundled set:

```
{# layouts/handout/_block-section.html #}
{% if not (block.classes contains "aside") %}{% include "default:_block-section" %}{% endif %}
```

That handout leaves out every `.aside` block and hands the rest to the default, the book's
own `_block-section.html` if it has one. Whatever the default includes goes through the
view again, so the nested blocks it writes come back to the handout's template, and an
aside nested anywhere is left out too. The default `_block-section.html` writes a block's
own content through `_block-content.html`, so a view can change what a block says without
writing its section.

### Changing the cards a view writes

Templates decide how a card is written. A view that changes what a card holds, rather than
how it looks, does it once, in a `transform.html`, and every template sees the result. The
simplest `transform.html` is a list of statements, one to a line:

```
{# layouts/handout/transform.html #}
drop .aside
replace .solution with {.answer-space}
```

Each statement is one of the transforms in
[Changing what a block prints](card:themes#changing-what-a-block-prints), applied to the
card:

| Statement | Runs |
|---|---|
| `drop SEL`, `keep SEL` | `drop`, `keep` |
| `blank SEL as NAME` | `blank` |
| `replace SEL with BLOCK` | `replace` |
| `insert BLOCK before SEL`, `insert BLOCK after SEL` | `insertBefore`, `insertAfter` |
| `insert BLOCK first in SEL`, `insert BLOCK last in SEL` | `prepend`, `append` |
| `wrap SEL in BLOCK` | `wrap` |
| `add .NAME to SEL`, `remove .NAME from SEL` | `addClass`, `removeClass` |
| `set ATTRS on SEL` | `set`, as in `set lines=8 on block.solution` |

`SEL` is a selector, as the transforms take, and can have spaces in it:
`insert {.answer lines=6} after .exercise:not(:has(.solution))`. `BLOCK` is written as a
card writes attributes. A keyword in brackets or quotes is part of the selector or block,
not a keyword. The statements run in order, and each sees what the ones before it did. A
blank line, or one starting with `#`, is skipped.

The file is still a template, so Pebble can choose the statements:

```
{% if output == 'site' %}drop .print-only{% endif %}
blank .solution as answer-space
```

One thing to watch: `{#` opens a Pebble comment, so write an id in a block without the
braces, `#q1 .answer`, or the rest of the file is a comment.

For anything the statements can't say, the file can hand the changed card back itself, with
`result(...)`, once, and print nothing else:

```
{{ result(card | drop('.aside') | replace('.solution', '{.answer-space}')) }}
```

Either way it's rendered for each card as the card's model is made, with `card`, `vars`,
`output` and `target`. The card body, the site's on-this-page rail, `card.steps`, `card.slots`
and the view's own `keep.html` all see the changed card, so a block the transform leaves
out doesn't turn up in the rail, and `keep.html` can ask what's left. The card's number and
sheet are filled in afterwards, so a transform can't read them.

A transform can also leave its card out, and then the view doesn't hold it, as if
`keep.html` had printed `false`:

```
{# layouts/handout/transform.html #}
drop card[draft=true]
keep card:has(.exercise)
drop .aside
```

`card[draft=true]` is a card whose frontmatter says `draft: true`, and `card:has(.exercise)`
one with an exercise in it (see [Template Reference](card:template-reference#what-a-selector-sees)).
With `result(...)`, handing back `null` leaves the card out. A view with a `transform.html`
doesn't need a `keep.html`; with both, a card has to pass each, the transform first. A
transform that hands back anything else but a card fails the build.

Paperband ships two views:

- `cheatsheet` keeps the cards with a `{!step}` and writes each as its steps. See
  [Make a Cheat Sheet](card:make-a-cheat-sheet).
- `student` keeps every card, and its `transform.html`, `blank .solution as answer-space`,
  blanks each `{.solution}`, which its `_block-section.html` writes as space to write the
  answer in.
  See [Make a Student Edition](card:make-a-student-edition).

Block templates
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

## Watch Out

A card body template has to keep `id="card-{{ card.id }}"` on what it writes. After a book
renders, paperband reads the ids its templates printed, and a card the book holds with no
`card-<id>` among them fails the build, naming it: `card:` links, the contents and the
bookmarks would otherwise point at nothing. To leave a card out on purpose, use a view whose
`keep.html` prints `false` for it (see [Views](card:themes#views)).

A divider page is different: a template may choose not to print one. Its bookmark is left
out, the build log says which, and the cards under it move up a level in the bookmark pane.
