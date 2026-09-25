---
id: tables-from-data
oneliner: "Write a big table's rows once as YAML, build the markup with a Pebble loop, and give print its own layout."
index: [tables, rowspan, thead, macros]
---

# Tables from Data

For a table with many rows of the same shape, keep the rows in YAML, generate the markup
with a Pebble loop in the card, and put the styling in CSS.

The repository has a full worked example in `examples/effectiveness-map`: a 28-row scoring
matrix with three row groups, nine scored columns, a legend and a grid of tiles, ported from a
hand-written HTML page. This card describes the pattern it uses.

## The three files

```filetree
src/main/paperband/
  paperband.yaml         ← page: landscape, measure: none; css: [styles/table.css]
  content/
    paperband.yaml       ← vars: the rows, as data
    01-table.md          ← the card: loops that turn the data into markup
  styles/
    table.css            ← the look, screen and print
```

The content policy strips inline `style=` attributes and `<style>` blocks from cards (see
[Card Structure](card:card-structure#raw-html-and-the-content-policy)), so an HTML table
styled inline can't be pasted in unchanged. Classes are kept: give cells classes and style
them in the stylesheet.

## The data

Rows are ordinary `vars`, so they cascade like any other var and can live in the folder that
holds the card:

```yaml
# content/paperband.yaml
vars:
  metrics:
    - { icon: users,     label: Reach }
    - { icon: book-open, label: Depth }
  formats:
    - { icon: file-text, name: "Technical article", scores: [3, 4] }
    - { icon: mic,       name: "Conference talk",   scores: [4, 3] }
```

Nested lists and maps are supported here. The POM's `<vars>` takes flat strings only.

## The markup

A Markdown pipe table can't carry cell classes or spanning cells, so the loop emits HTML:

```html
<table class="scores">
<thead><tr>
<th>Format</th>
{%- for m in vars.metrics %}<th>:{{ m.icon }}: {{ m.label }}</th>{% endfor %}
</tr></thead>
<tbody>
{%- for f in vars.formats %}
<tr><td class="name"><span class="ico">:{{ f.icon }}:</span> {{ f.name }}</td>
{%- for s in f.scores %}<td class="score">{{ s }}</td>{% endfor %}</tr>
{%- endfor %}
</tbody>
</table>
```

The `:{{ f.icon }}:` references become inline SVG icons, including inside hand-written table
cells (see [Icons](card:icons)). The same loop, run over three rows declared in this folder's
`paperband.yaml`:

<table class="data-table-demo">
<thead><tr><th>Format</th><th>:users: Reach</th><th>:book-open: Depth</th></tr></thead>
<tbody>
{%- for f in vars.demoFormats %}
<tr><td><span class="ico">:{{ f.icon }}:</span> {{ f.name }}</td><td>{{ f.reach }}</td><td>{{ f.depth }}</td></tr>
{%- endfor %}
</tbody>
</table>

## Whitespace

Pebble runs first, then Markdown parses the result. Markdown ends an HTML block at the first
blank line, which gives three rules:

- **Keep the table itself free of blank lines.** Use `{%-` inside the table: the `-` trims
  the whitespace in front of the tag, so a loop emits one compact block.
- **Leave two blank lines after a closing tag that Markdown follows.** Pebble removes the
  newline straight after a `{% endif %}` or `{% endfor %}`, so a single blank line there
  becomes none, and the heading that follows is parsed as part of the HTML block and
  printed as literal text.
- **Never put a trimming tag straight after a heading.** `{#-` and `{%-` trim newlines too,
  so a trimming comment on the line after `## The map` joins the whole table onto the
  heading's line. Start the block with a plain `{#` or `{%`.

To inspect the generated markup, run `build` with `-Dpaperband.emitHtml=target/book.html`,
which writes the page as the renderer receives it.

## A separate layout for print

On screen a group of rows usually has its label in a cell spanning those rows (`rowspan`).
In print, a spanning cell's text stays on the page where the group started, so a group that
continues onto the next page has an empty label column there.

Chromium repeats a table's `<thead>` at the top of each page the table continues onto. In
print, make each group its own table, with the group's name as a band row in the `<thead>`
beneath the column headers. Each page then shows the group name and the column headers.

The `output` variable is `print` for the PDF and `site` for the static site, so one card can
emit both layouts from the same data:

```html
{% macro rowCells(r) %}
<td class="name">{{ r.name }}</td>
{%- for s in r.scores %}<td class="score">{{ s }}</td>{% endfor %}
{%- endmacro %}
{%- if output == 'print' %}
{%- for g in vars.groups %}
<table class="scores group-table">
<colgroup><col class="c-name">{% for m in vars.metrics %}<col class="c-metric">{% endfor %}</colgroup>
<thead>
<tr>…column headers…</tr>
<tr class="band"><th colspan="{{ (vars.metrics | length) + 1 }}">{{ g.label }}</th></tr>
</thead>
<tbody>
{%- for r in g.rows %}<tr>{{ rowCells(r) }}</tr>{% endfor %}
</tbody>
</table>
{%- endfor %}
{%- else %}
…one table, each group's label in a <th rowspan="{{ g.rows | length }}">…
{% endif %}
```

Put cells shared by both layouts in a macro, such as `rowCells` here.

## Lining the tables up

Separate tables size their columns independently. Give each table the same `<colgroup>` and
a fixed layout so the columns align:

```css
@media print {
  table.group-table { table-layout: fixed; width: 100%; }
  table.group-table col.c-name   { width: 22%; }
  table.group-table col.c-metric { width: 6%; }
  table.scores tbody tr { break-inside: avoid; }
}
```

Percentages keep the columns aligned at any page size. `break-inside: avoid` keeps each row
on one page. Don't combine it with `rowspan`: the rule then applies to the whole group, and a
group that doesn't fit the remaining space moves to the next page, leaving a gap.

## The page

A wide table needs landscape orientation and the full text width:

```yaml
# src/main/paperband/paperband.yaml
page:
  size: a4
  orientation: landscape
  margins: { top: 10, right: 10, bottom: 10, left: 10 }
  measure: none              # the text measure would cap the table's width too
```

On screen, give the table a minimum width inside a wrapper with `overflow: auto`, so it
scrolls horizontally in narrow windows. Put those rules under `@media screen` so they don't
apply to the PDF.

## Watch Out

An icon reference's colons must not touch adjacent text. `:{{ f.icon }}:{{ f.name }}`
produces `:mic:Conference`, which stays literal text. Put a space or a tag between them.

Use icon references rather than emoji in cells; emoji are unreliable in the PDF (see
[Icons](card:icons)).

## Check

Build the PDF and open a page where a group breaks: it should start with the column headers
and the group's band. On the site, the same card builds the single-table layout, in the
site's `cards/<id>.html`.
