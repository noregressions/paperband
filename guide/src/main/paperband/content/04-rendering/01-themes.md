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
turns theming off entirely, overriding whatever the book's yaml asked for — see the Maven
Plugin section for why that needs a name of its own, and how `<stylesheets>` layers your
own CSS above a theme.

## Built-in themes

Nine themes ship with paperband: `editorial` (serif, drop caps, magazine feel — this
guide uses it), `editorial-gold`, `classical`, `fieldguide`, `dark`, `blueprint`,
`carded`, `herodevs`, and `workshop`. Pick one with the `theme:` key in the root
`paperband.yaml`, or override at build time with `<theme>`; the build setting wins when
both are given.

`workshop` is the one built for a document with a *repeating rhythm* — a lab or workshop
whose every step runs why → how → run → observe → establish. It keys off the block classes
those headings produce, so the phases get treatments matching what they hold (commands to
follow, output to compare against, a conclusion to keep) and the repeated headings become
small-caps markers rather than headings competing with the numbered steps. It also tells
commands from output by the fence's own language class, since a lab is mostly those two
things.

## How CSS composes

Stylesheets are inlined into the rendered HTML in a fixed order, weakest first: a
theme-neutral structural scaffold (for site pages, `site-base.css`; for PDF pages, a
minimal `<style>` block in the page template), then the book's own `css:` chain, then
every stylesheet the theme's manifest lists. Because the theme comes last, its rules win
cascade ties against your CSS without needing heavier selectors — and your CSS can still
win back any rule by being more specific. Each inlined sheet is prefixed with a
`/* === theme:name path === */` marker comment, so a view-source on the rendered page
shows exactly where each rule came from.

Prism's syntax-highlighting stylesheet sits below all of that, imported into a CSS
cascade layer (`layer(prism)`), so its `pre[class*="language-"]` box rules can never
outrank a theme's plain `pre` rule on specificity — the token colours apply where nothing
competes, and the code block's size, background and padding stay the theme's to decide.

## Print and site layers

One theme describes two media, and they don't want the same things. A measure chosen for a
210mm trim and a type scale in points are decisions about paper; a site has a viewport, a
sidebar and a reader who scrolls. A manifest entry can name the target it applies to:

```
theme.css                 # shared — tokens, colour, type family, code, block semantics
print: theme-print.css    # paged output only — measure, point sizes, page density
site:  theme-site.css     # the static site only — web type scale, grid, breakout
```

Unprefixed is shared, so a manifest written before target layers existed behaves exactly as
it did. Target layers are inlined **after** the shared one, so they correct it rather than
having to pre-empt it with heavier selectors. An unknown prefix fails the build rather than
being read as a filename.

Every site page stamps `class="paperband-site target-<target>"` on `<html>`. Key web rules
off `paperband-site` rather than `target-web`: the target name follows `<siteTarget>`, which
a book may rename, while the site hook is stable. Site pages deliberately do **not** carry
the `size-*` class the PDF stamps — themes hang page density off it
(`html.size-a4 { font-size: 10.5pt }`), and a website has no page size to be.

## Width on the web

A book column centred in a browser window leaves a lot of empty page, and there are two
tempting wrong answers. Running prose to the window edge is one: text at 1300px is less
readable, not more. Widening individual elements — pulling every `pre` and `table` out of
the measure — is the other, and it is worse than it sounds. Tried on a card with eleven
code blocks, it threw the left edge 305px sideways eleven times; the eye has nothing to
follow down the page, and a stable spine is worth more than the reclaimed width.

So the content column keeps **one left edge**. Code that is too wide scrolls inside its own
box, and a wide table gets its own scroller, rather than the page scrolling sideways.

The space is answered by the layout instead. A card with at least three headings renders in
a two-column grid — content, then a sticky **on this page** rail built from the card's own
block headings:

```
┌──────────┬───────────────────────────┬──────────────┐
│ sidebar  │ content (one left edge)   │ on this page │
└──────────┴───────────────────────────┴──────────────┘
```

Below `68rem` there is no room for a third column, so the rail becomes a plain list above
the content. A card with fewer than three headings keeps the plain centred column — an
empty gutter beside a short page reads worse than no rail.

Every block gets a stable anchor for the rail to point at: its declared `{#id}` when the
heading has one, otherwise a slug of the heading — the same slug the block already carries
as a class, so the anchor and the styling hook agree.

`.pw-breakout` remains as a deliberate, rare opt-out of the measure — one hero diagram or
one very wide matrix, chosen per element by the author, not applied to a whole element type:

```css
:root { --pb-breakout-max: 90rem; }   /* how wide an opted-in breakout may go */
```

## What theme CSS can target

The markup exposes stable, data-driven class hooks — this is the main way page data
reaches a theme:

- **Block classes.** Every card block carries `block` plus its heading's auto-slugged
  class (`## Watch Out` → `section.block.watch-out`) or explicit `{.class}` attribute.
  Text before the first heading is the `intro` block. This is how `editorial` styles
  drop-cap intros and warning boxes without knowing anything about a specific book.
- **Axis classes.** Every card's `<article>` carries one `{axisName}-{valueId}` class per
  axis it has a value for (`tier-1`, `section-rendering`). Dividers, sidebar sections,
  and landing heroes carry parallel classes (`.tier-divider.tier-1`,
  `.sidebar-section-tier-1`).
- **Page-level classes.** PDF pages tag `<html>` with `target-{target}` and
  `size-{size}`; site pages tag `<body>` with sidebar state (`has-sidebar`,
  `sidebar-collapsed`).
- **Component classes.** Fixed names for the furniture: `.card-title`, `.oneliner`,
  `.card-meta`, badge classes, `.card-grid`/`.card-item` on landing pages, `.site-nav`,
  `.site-sidebar`, and so on.
- **Page geometry, as CSS custom properties.** PDF pages stamp the build's real geometry
  on `<html>`: `--pw-content-height` (printable height), `--pw-page-margin-top` /
  `-right` / `-bottom` / `-left` (the page margins), and `--pw-font-scale`. Read these
  rather than hardcoding a page size — see Full-bleed themes below.

## Full-bleed themes

Chromium paints nothing into a PDF page margin — no `@page` rule or `html` background
reaches it — so on a theme with a coloured ground, any page margin shows up as a white
border around every page. A theme whose ground *is* the paper (`blueprint`, `dark`,
`carded`) therefore only reaches the trim edge in a full-bleed build, which the book asks
for by zeroing its page margins:

```yaml
# book root paperband.yaml
page:
  margins: { top: 0, right: 0, bottom: 0, left: 0 }
```

That hands every inset to the theme, and a full-bleed theme needs two things to supply
them:

- **`box-decoration-break: clone` on the card.** Without it Chromium gives a fragmented
  box its padding on the first and last page only, so a card running to 150 pages puts
  body text hard against the trim edge on every page in between. With it, the padding and
  frame repeat on each page the card spans. Horizontal padding survives fragmentation
  either way; this is what fixes the vertical.
- **Insets sized against the build's own margin.** `max(6mm, 13mm - var(--pw-page-margin-top, 0mm))`
  gives the full inset on a full-bleed build and collapses to the floor when the build
  keeps its margins — one theme that reads correctly either way, instead of one tuned for
  a single margin setting.

Every bundled theme carries this treatment, so any of them can be built full-bleed;
`blueprint` is the fullest worked example (it also places its card frame by margin rather
than by a centred measure). A custom theme needs the two rules above and nothing else.

## Part pages

A part or section divider is a full page, centred (see Organising Content). Themes style it
through `.section-divider`, with `.section-divider .tier-divider-inner` as the centred
title block — the divider itself is the page-sized flex container, so put backgrounds,
frames and rules on the inner element rather than on `.section-divider`. Every bundled
theme styles it in its own idiom; the built-in fallback inherits the book's own colours, so
an unstyled theme still renders a legible page rather than light-theme greys on a dark
ground.

For a divider showing the title alone, a part can ask for the `minimal` preset — see
Organising Content and the Maven plugin's `<landingTemplate>`.

## How axis colours reach CSS

An axis value's `color:` (or its default-palette fallback) flows into pages two ways.
Dividers, landing pages, index stat boxes, and sidebar accents get it as an **inline
style** emitted by the templates — data winning by specificity, so a theme that wants a
different divider treatment must out-rank it (`herodevs` does this with `!important`
gradients per `{axisName}-{valueId}` class).

Card bodies are the opposite: the engine emits **no colour at all**, just the
`{axisName}-{valueId}` class. Themes bridge class to colour with a custom property:

```css
article.card.tier-1 { --tier-color: #c0392b; }
article.card.tier-2 { --tier-color: #e67e22; }
```

and shared rules consume `var(--tier-color)` for badges, headings, and accents. Every
bundled theme follows the same convention with `--card-max-width` (each sets its own
screen and print widths), and the site scaffold exposes `--pw-sidebar-bg` for themes to
set. One catch: the class-to-colour mechanism and the inline styles don't share a source,
so a theme's `--tier-color` values can drift from the yaml `color:` values used on
dividers — keep them in sync by hand.

## How templates compose

If a theme has a `templates/` directory, its templates sit at the **top** of the Pebble
loader chain: theme templates → the book's own `layouts/` directory → the bundled
defaults. Any bundled template can be overridden by filename — full pages (`card.html`,
`book.html`, `site-index.html`, `site-card.html`, the landing pages) or partials
(`_card-body.html`, `_block-section.html`, `_tier-divider.html`, `_section-divider.html`,
`_book-cover.html`, `_site-sidebar.html`, ...).

The interesting partials come in pairs: `_card-body.html` is a one-line
`{% extends "_card-body-base" %}`, with the real markup and named blocks in the `-base`
file. A theme overrides the thin wrapper, extends the base, and replaces just one block
(`carded` restyles the card header this way) — no need to fork the whole card markup.

## What templates can see

Theme templates receive the same Pebble model the bundled ones use. The core objects:

| Key | Where | What's in it |
|---|---|---|
| `card` | card pages, each entry of `cards` in `book.html` | `id`, `title`, `frontmatter.*` (raw map), `axes.{axisName}.{id,label,color}`, `blocks` (nested: `heading`, `level`, `classAttr`, `html`, `children`) |
| `book` | book PDF + every site page | `title`, `subtitle`, `series`, `author`, `vars.*`, `cover`, `back` |
| `vars` | `card.html`, `book.html`, `site-card.html` | the fully-cascaded vars map for that card |
| `axis` / `value` | axis dividers and landing pages | `{name, title}` / `{id, label, color, count, cards}` |
| `section` | section dividers and landing pages | `{id, label, count, minimal, cards}` |

Three vars get promoted to first-class book fields: `vars.subtitle`, `vars.series`, and
`vars.author` become `book.subtitle`, `book.series`, `book.author` — that's how the cover
and site index show them. Everything else in `vars` is still reachable: this guide's
`paperband.yaml` sets `version:`, which no bundled template reads, but a theme template
can with `{{ book.vars.version }}`.

`vars` and `frontmatter` are deliberately lenient — reading a key that was never set
yields null rather than an error, so themes work across books with different vars.
The structural objects (`card`, `block`, `value`) are strict: a typo like
`{{ block.headign }}` fails the build instead of rendering blanks.

One gap to know about: extra keys under an axis value in yaml (`icon:`, `description:`)
are **not** currently exposed to templates — only `id`, `label`, and `color` survive.
Free-form data that a theme should see belongs in `vars` or card frontmatter instead.

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
unexpected blocks, and `has(name)` peeks without consuming — the hook for
structure-dependent branching (`{% if card.slots.has('diffs') %}`).

The contract: once a template touches `card.slots`, every top-level block must end up
placed (named slot or `rest()`) and every `require` satisfied — otherwise the build
fails (exit code 4) listing each offending card and block. Delete the `rest()` line and
the same template becomes a strict shape check: cards with unexpected sections can't
build. Templates that never touch `card.slots` are never checked, so existing books and
themes are unaffected. Nested blocks always travel with their parent — slots operate on
top-level blocks only.

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

Split larger themes into several files (tokens first, components after) — they inline in
listed order. Then build with it:

```bash
mvn paperband:build -Dpaperband.input=mybook -Dpaperband.output=out.pdf -Dpaperband.themeDir=mythemes -Dpaperband.theme=inkwell
```

A `<themeDir>` theme with the same name as a built-in **overrides** the built-in — handy
for forking `editorial` into a house variant: copy its directory out of
`layout`'s resources, keep the name, and iterate.

## Check

```bash
mvn paperband:themes -Dpaperband.themeDir=mythemes
```

Lists every discovered theme with its source (`built-in`, your directory, or
"overrides built-in") and how many stylesheets its manifest resolved. A `?` in the styles
column means the manifest failed to load — usually a typo'd filename inside it.
