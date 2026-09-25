---
id: data-tables
oneliner: "Write a big table's rows once as YAML, build the markup with a Pebble loop, and give print its own layout."
index: [tables, rowspan, thead, macros]
---

# Tables from Data

Some tables are really datasets: dozens of rows sharing one shape, a score per column, groups
of rows under a label. Written out by hand, every row is a copy of the one above it, and every
change is a search-and-replace. Paperband's answer is to split the table three ways. The rows
go in YAML, a Pebble loop in the card turns them into markup, and CSS owns the look.

The repository has a full worked example in `examples/effectiveness-map`: a 28-row scoring
matrix with three row groups, nine scored columns, a legend and a grid of tiles, ported from a
hand-written HTML page. This card walks through the pattern it uses.

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

The split isn't only tidiness. The content policy strips inline `style=` attributes and
`<style>` blocks from cards (see [Card Structure](card:card-structure#raw-html-and-the-content-policy)),
so an HTML table that carries its look inline can't be pasted in as it stands. Classes survive,
so the markup names what each cell *is* and the stylesheet decides how that looks.

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

Nested lists and maps are fine here. It's the POM's `<vars>` that only takes flat strings.

## The markup

A Markdown pipe table can't carry a class on a cell, a row that spans columns, or a cell that
spans rows. So the loop emits HTML, which a card may contain:

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

The `:{{ f.icon }}:` references become inline SVG icons once the page is built, including
inside hand-written table cells like these (see [Icons](card:icons)). Here is the same loop run
over the three rows declared in this folder's own `paperband.yaml`:

<table class="data-table-demo">
<thead><tr><th>Format</th><th>:users: Reach</th><th>:book-open: Depth</th></tr></thead>
<tbody>
{%- for f in vars.demoFormats %}
<tr><td><span class="ico">:{{ f.icon }}:</span> {{ f.name }}</td><td>{{ f.reach }}</td><td>{{ f.depth }}</td></tr>
{%- endfor %}
</tbody>
</table>

## Whitespace

Two passes see the card in turn. Pebble runs first, then Markdown parses what Pebble left, and
Markdown is strict about where HTML stops. Three rules keep the two out of each other's way:

- **Keep the table itself free of blank lines.** Markdown ends an HTML block at the first
  blank line. Use `{%-` inside the table: the `-` trims the whitespace in front of the tag,
  so a loop emits one compact block.
- **Leave two blank lines after a closing tag that Markdown follows.** Pebble removes the
  newline straight after a `{% endif %}` or `{% endfor %}`, so a single blank line there
  becomes none, and the heading that follows is swallowed into the HTML block as literal
  text.
- **Never put a trimming tag straight after a heading.** `{#-` and `{%-` trim newlines too,
  so a trimming comment on the line after `## The map` joins the whole table onto the
  heading's line. Start the block with a plain `{#` or `{%`.

When a table comes out wrong, the generated markup is the first thing to look at.
`-Dpaperband.emitHtml=target/book.html` on a `build` writes the page as the renderer sees it.

## Print wants a different table

On screen a group of rows usually gets its label in a cell spanning those rows
(`rowspan`). On paper that breaks: a spanning cell's text stays on the page where the group
started, so a group that runs onto the next page arrives with an empty label column and no
column headers to say what the scores mean.

What does repeat is a table's `<thead>`: Chromium prints it again at the top of every page the
table continues onto. So in print, make each group its own table and put the group's name in
the `<thead>` as a band beneath the column headers. Every page a group reaches then says which
group it is and what each column holds.

A card knows which output it's being built for. `output` is `print` for the PDF and `site` for
the static site, so one card can emit both layouts from the same data:

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

Put the cells both layouts share in a macro, as `rowCells` is here, so the two versions can't
drift apart.

## Lining the tables up

Separate tables size their columns separately, so a print layout of several group tables
comes out ragged unless every table is told the same widths. Give each one the same
`<colgroup>` and a fixed layout:

```css
@media print {
  table.group-table { table-layout: fixed; width: 100%; }
  table.group-table col.c-name   { width: 22%; }
  table.group-table col.c-metric { width: 6%; }
  table.scores tbody tr { break-inside: avoid; }
}
```

Percentages keep the columns in line at any page size. `break-inside: avoid` on the rows keeps
a row from splitting across a page, which is safe once no cell spans rows. With a `rowspan`
in the table, the same rule makes the whole group unbreakable, and a group too tall for the
space left jumps to the next page, leaving a gap behind it.

## The page

A table with a dozen columns wants the long edge of the sheet and all of its width:

```yaml
# src/main/paperband/paperband.yaml
page:
  size: a4
  orientation: landscape
  margins: { top: 10, right: 10, bottom: 10, left: 10 }
  measure: none              # the text measure would cap the table's width too
```

On screen, let the table keep a comfortable minimum width and scroll sideways inside a
wrapper (`overflow: auto`) on narrow windows, with those rules under `@media screen` so they
never reach the PDF.

## Watch Out

**An icon reference needs its colons clear of the words around it.**
`:{{ f.icon }}:{{ f.name }}` produces `:mic:Conference`, where the closing colon touches a
letter, so it stays literal text. Put a space or a tag between them, as the examples above
do.

**Don't reach for emoji in cells.** They drop out of the PDF inside bold text, and on a CI
machine usually drop out entirely. Use icon references; the Icons card explains why.

## Check

Build the PDF and look at a page where a group breaks. It should open with the column headers
and the group's band. For the site, the same card builds the single-table layout, which you
can confirm in the site's `cards/<id>.html`.
