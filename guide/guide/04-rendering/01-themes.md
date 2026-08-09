---
id: themes
oneliner: "Themes are directories of CSS (and optional templates) that cascade over your book."
---

# Themes

A theme is a directory with a `manifest.txt` listing its CSS files, plus an optional
`templates/` directory of Pebble overrides. Built-in themes ship on the classpath; custom
themes are discovered via `--theme-dir`. Theme CSS loads after user CSS, so the theme wins
on conflicts. List available themes with `paperband themes`.

## Built-in themes

Seven themes ship with paperband: `editorial` (serif, drop caps, magazine feel — this
guide uses it), `classical`, `fieldguide`, `dark`, `blueprint`, `carded`, and `herodevs`.
Pick one with the `theme:` key in the root `paperband.yaml`, or override at build time
with `--theme`; the CLI flag wins when both are given.

## How CSS composes

Stylesheets are inlined into the rendered HTML in a fixed order, weakest first: a
theme-neutral structural scaffold (for site pages, `site-base.css`; for PDF pages, a
minimal `<style>` block in the page template), then the book's own `css:` chain, then
every stylesheet the theme's manifest lists. Because the theme comes last, its rules win
cascade ties against your CSS without needing heavier selectors — and your CSS can still
win back any rule by being more specific. Each inlined sheet is prefixed with a
`/* === theme:name path === */` marker comment, so a view-source on the rendered page
shows exactly where each rule came from.

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
paperband build mybook out.pdf --theme-dir mythemes --theme inkwell
```

A `--theme-dir` theme with the same name as a built-in **overrides** the built-in — handy
for forking `editorial` into a house variant: copy its directory out of
`layout`'s resources, keep the name, and iterate.

## Check

```bash
paperband themes --theme-dir mythemes
```

Lists every discovered theme with its source (`built-in`, your directory, or
"overrides built-in") and how many stylesheets its manifest resolved. A `?` in the styles
column means the manifest failed to load — usually a typo'd filename inside it.
