---
id: icons
oneliner: "Write ::name: and the build draws an inline SVG icon, in the PDF and on the site alike."
index: [icons, Lucide, SVG]
---

# Icons

Write an icon's name between colons and the build draws it in:

```markdown
| :users: Reach | :trophy: Authority | :shield-check: Trust |
```

| :users: Reach | :trophy: Authority | :shield-check: Trust |
|---|---|---|
| 4 | 3 | 4 |

Each reference becomes an inline SVG drawn in `currentColor`. It takes the colour of the
text around it, is sized to that text's font, and sits on its baseline like a glyph. The
PDF and the site get the same drawing.

## Why not emoji

Emoji are unreliable in the PDF. The headless Chromium that renders it drops colour-emoji
glyphs in bold text, and CI images often have no emoji font, so emoji print as blank boxes.
Where they render, they vary by platform and ignore the theme's colours. SVG icons have
none of these limitations.

## Names

The bundled set is [Lucide](https://lucide.dev/icons), about 2,100 icons, under the ISC
licence. Use the names shown on that page: `:message-circle:`, `:book-open:`,
`:git-branch:`. A name is lowercase letters and digits, with single hyphens between words.

An unknown name fails the build. The error names it, quotes the surrounding text, and
suggests the nearest names:

```
Unknown icon:
  :userz:  in "…the :userz: column…" — did you mean 'user' or 'users'?
```

## Your own icons

Put an SVG file in `icons/` in the book's home, beside `paperband.yaml`: for a
conventional book, `src/main/paperband/icons/`. A book with no home keeps `icons/` at its
root. The file's name is the icon's name: `icons/logo.svg` answers
to `:logo:`. A file with the same name as a bundled icon replaces that icon.

```filetree
src/main/paperband/
  paperband.yaml
  icons/
    logo.svg        ← :logo:
    users.svg       ← :users:, replacing Lucide's
```

The build strips the file's XML prolog and comments, and gives the root `<svg>` the `icon`
classes and a 1em box, replacing any `width` and `height` of its own. Keep the `viewBox`;
it is needed for scaling. Draw in `currentColor` if the icon should follow the text's
colour. A file containing a `<script>`, an `on…=` handler or a `foreignObject` fails the
build.

## Where references work

In any text on the page: paragraphs, table cells, headings, card titles and oneliners,
section bodies, running headers and footers, and hand-written HTML. The pass runs on each
finished page rather than on the markdown, which does not parse inside raw HTML, so a
Pebble loop that emits a `<table>` can use icons in its cells:

```html
{% for m in vars.metrics %}<th>:{{ m.icon }}: {{ m.label }}</th>{% endfor %}
```

Code is not processed: inline code, fenced blocks, `<pre>`, scripts and stylesheets keep a
literal `:name:`. Attribute values and the page `<title>` are also left unchanged.

## Literal colons

The colons must stand on their own. Neither may touch a letter, digit, underscore or another
colon, so none of these is a reference:

| Written | Why it's left alone |
|---|---|
| `group:artifact:goal` | The colons touch letters |
| `Foo::bar:` | The first colon touches another colon |
| `10:30:45` | Names start with a letter |
| `:users:globe:` | Adjacent references need a space between them |

For a literal `:name:` in running text, write `::name:`. It renders as `:name:` with no
icon. To switch references off for a whole book, set
`vars: { icons: false }` in the root `paperband.yaml`.

## Styling

Every icon carries two classes: `icon`, and `icon-<name>`. The base stylesheet sizes `.icon`
to 1em, so set `font-size` on the surrounding element to resize it, and `color` to recolour
it:

```css
.metric-head .icon { font-size: 1.4em; color: var(--accent); }
svg.icon-shield-check { color: #16a34a; }
```

## Watch Out

On slides, an icon in running text is omitted. The pptx renderer converts slide prose into
editable PowerPoint text, which can't hold a picture; the surrounding text is kept. A
`<table>` or a figure is placed as a picture of itself, so icons inside one are kept.

## Check

Build the book. An unknown name fails the build; a resolved name appears in the page
source as `<svg class="icon icon-<name>" …>`.
