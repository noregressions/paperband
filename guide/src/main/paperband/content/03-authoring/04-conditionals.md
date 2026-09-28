---
id: vars-and-conditionals
oneliner: "Substitute vars and gate sections with real Pebble {{ vars.x }} / {% if vars.x %}."
---

# Vars and Conditionals

Every card body is evaluated as a Pebble template before Markdown parsing, alongside
`{% fragment %}` resolution (see [Includes](card:includes)) — both run in the same pass.
Pebble syntax therefore works directly in card prose: variable interpolation and
`{% if %}` conditionals, scoped under a `vars` map.

## Where vars come from

`vars:` in any `paperband.yaml` along the book's config cascade, plus a handful of
built-ins: `build_date`, `build_date_long`, `build_year`, `build_month_year` and
`build_iso`. Inner-most
config wins, same as the rest of the cascade.

```yaml
vars:
  product_name: "Paperband"
  show_advanced: true
```

## Interpolation

```
{{ vars.product_name }}
```

Renders the value as text before Markdown parsing, so it works in headings, list items
and anywhere else in the body.

## Conditional sections

```
{% if vars.show_advanced %}
## Advanced section

This only appears when `show_advanced` is true.
{% endif %}
```

When the condition is false, the guarded region, including any headings inside it, is
removed before block-splitting, so it produces no card block.

## Leniency on undeclared vars

Referencing a `vars` key that was never set (`{% if vars.never_declared %}`) resolves
to null/false rather than throwing, so a card can guard a section on a var that some
books don't set. Only a Pebble syntax error, not a missing key, fails the build.

## How this composes with includes

Fragment resolution and vars/conditionals run in a single Pebble evaluation, with both
extensions and the `vars` context registered together. A Pebble parse evaluates every
construct in the document, so a separate fragment-only pass would resolve `{{ vars.x }}`
and `{% if vars.x %}` with no `vars` context, as null and false. With one pass, a card can
combine a `{% fragment %}` with sections gated by `{% if vars.x %}`.

## Watch Out

As with `{% fragment %}`, frontmatter, fenced code blocks and inline code spans are not
evaluated. Anywhere else, a `{{ }}` or `{% %}`-looking span must be valid Pebble syntax, or
be wrapped in a fenced code block, an inline code span, or Pebble's `{% verbatim %}` tag.
