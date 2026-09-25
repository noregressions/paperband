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

Emoji look like the obvious way to put a picture in a line of text, and they don't survive
the trip to a PDF. The headless Chromium that renders the book drops a colour-emoji glyph
whenever the surrounding text is bold. A CI image usually has no emoji font at all, so
every one of them prints as a blank box. Where they do render, they look different on every
platform and ignore the theme's colours. An SVG icon has none of those problems.

## Names

The bundled set is [Lucide](https://lucide.dev/icons), about 2,100 icons, under the ISC
licence. Search that page and use the name it shows: `:message-circle:`, `:book-open:`,
`:git-branch:`. A name is lowercase letters and digits, with single hyphens between words.

A name that doesn't exist **fails the build**, naming it, quoting the text around it, and
suggesting the nearest real names:

```
Unknown icon:
  :userz:  in "…the :userz: column…" — did you mean 'user' or 'users'?
```

A typo would otherwise ship as literal `:userz:` text, which is the kind of mistake nobody
notices until it's printed.

## Your own icons

Put an SVG file in `icons/` beside the book's `layouts/`, which for a conventional book is
`src/main/paperband/icons/`. The file's name is the icon's name: `icons/logo.svg` answers
to `:logo:`. A file with the same name as a bundled icon replaces it, so a book can redraw
one icon without giving up the rest.

```filetree
src/main/paperband/
  layouts/
  icons/
    logo.svg        ← :logo:
    users.svg       ← :users:, replacing Lucide's
```

The build strips the file's XML prolog and comments, and gives the root `<svg>` the `icon`
classes and a 1em box, replacing any `width` and `height` of its own. Keep the `viewBox`,
since that's what lets it scale. Draw in `currentColor` if the icon should follow the text's
colour. A file containing a `<script>`, an `on…=` handler or a `foreignObject` fails the
build: icons are inlined into every page that uses them, so they must be plain drawings.

## Where references work

Everywhere text reaches the page: paragraphs, table cells, headings, card titles and
oneliners, section bodies, running headers and footers, and hand-written HTML. That last one
matters. The pass runs on each finished page rather than on the markdown, and markdown
never parses inside raw HTML, so this is what lets a Pebble loop that emits a
`<table>` use icons in its cells:

```html
{% for m in vars.metrics %}<th>:{{ m.icon }}: {{ m.label }}</th>{% endfor %}
```

It never touches code: inline code, fenced blocks, `<pre>`, scripts and stylesheets all keep
a literal `:name:`, which is how this page can show the syntax. Attribute values and the
page `<title>` are left alone too.

## Literal colons

The colons must stand on their own. Neither may touch a letter, digit, underscore or another
colon, so none of these is a reference:

| Written | Why it's left alone |
|---|---|
| `group:artifact:goal` | The colons touch letters |
| `Foo::bar:` | The first colon touches another colon |
| `10:30:45` | Names start with a letter |
| `:users:globe:` | Adjacent references need a space between them |

For the rare case where you mean a literal `:name:` in running text, write `::name:`. It
renders as `:name:` with no icon. To switch references off for a whole book, set
`vars: { icons: false }` in the root `paperband.yaml`.

## Styling

Every icon carries two classes: `icon`, and `icon-<name>`. The base stylesheet sizes `.icon`
to 1em, so the usual way to make an icon bigger is to set `font-size` on the element around
it, and `color` recolours it:

```css
.metric-head .icon { font-size: 1.4em; color: var(--accent); }
svg.icon-shield-check { color: #16a34a; }
```

## Watch Out

**On slides, an icon in running text is left out.** The pptx renderer turns a slide's prose
into editable PowerPoint text, which can't hold a picture, so an icon in a paragraph or
bullet doesn't appear on the slide; the text around it does. A `<table>` or a figure is
placed as a picture of itself, so icons inside one come through exactly as they print.

## Check

Build once. An unknown name fails that build, and a name that resolved shows up in the page
source as `<svg class="icon icon-<name>" …>`.
