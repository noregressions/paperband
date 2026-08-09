---
id: conditionals
oneliner: "Substitute vars and gate sections with real Pebble {{ vars.x }} / {% if vars.x %}."
---

# Vars and Conditionals

Every card body is evaluated as a Pebble template before Markdown parsing, alongside
`{% fragment %}` resolution (see [Includes](03-includes)) — both run in the same pass.
This means real Pebble syntax works directly in card prose: variable interpolation and
`{% if %}` conditionals, scoped under a `vars` map.

## Where vars come from

`vars:` in any `paperband.yaml` along the book's config cascade, plus a handful of
built-ins (`build_date`, `build_year`, and similar — see `BuiltInVars`). Inner-most
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

Renders the value as text, substituted before Markdown parsing runs — so it can sit
inside headings, list items, or anywhere else in the body.

## Conditional sections

```
{% if vars.show_advanced %}
## Advanced section

This only appears when `show_advanced` is true.
{% endif %}
```

When the condition is false, the entire guarded region — including any headings inside
it — is removed from the source before block-splitting happens, so it never becomes a
card block at all. There's no leftover empty section or CSS class to hide.

## Leniency on undeclared vars

Referencing a `vars` key that was never set (`{% if vars.never_declared %}`) resolves
to null/false rather than throwing. This is deliberate: a card author shouldn't need to
know every book's full `vars:` set to write a guarded section defensively. Only a genuine
Pebble syntax error — not a missing key — fails the build.

## How this composes with includes

Fragment resolution and vars/conditionals run in a **single** Pebble evaluate call, not
two sequential passes. That's a hard requirement, not an implementation detail worth
knowing: a Pebble parse evaluates every construct it finds in the document, so a
fragment-only pass with no `vars` context would also encounter `{{ vars.x }}` and
`{% if vars.x %}` and silently resolve them wrong (undefined `vars` → null → conditionals
read as false) before a hypothetical second pass ever ran. A card that both pulls in a
`{% fragment %}` and gates a section behind `{% if vars.x %}` works correctly because
there's only one pass, with both extensions and the `vars` context registered together.

## Watch Out

Masking works the same way it does for `{% fragment %}`: frontmatter, fenced code blocks,
and inline code spans are protected from evaluation, so this page can show
`{{ vars.product_name }}` and `{% if vars.x %}` as literal examples without paperband
trying to evaluate them. Anywhere else, a stray `{{ }}` or `{% %}`-looking span has to be
valid Pebble syntax, or wrapped in a fenced code block, an inline code span, or Pebble's
own `{% verbatim %}` tag.
