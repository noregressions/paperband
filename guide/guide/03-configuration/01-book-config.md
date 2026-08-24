---
id: book-config
oneliner: "The root paperband.yaml declares the book: title, axes, CSS, vars, and targets."
---

# Book Configuration

The `paperband.yaml` at the book root holds global configuration that applies to every
card: the book `title`, categorical `axes`, the base `css` chain, free-form `vars`, the
default `theme`, the declared build `targets`, and the book's `parts`.

## `axes`

`axes` declares any number of categorical axes, each independent of the others. A book
with no axes at all is fine — cards just fall back to folder-based "sections".

```yaml
axes:
  - name: tier
    title: Tier
    values:
      - id: 1
        label: "Tier 1 - Critical"
        color: "#c0392b"
      - id: 2
        label: "Tier 2 - Moderate"
        color: "#e67e22"
  - name: subsystem
    title: Subsystem
    values:
      - id: core
        label: "Core Subsystem"
      - id: edge
        label: "Edge Subsystem"
```

Each value's `id`/`label` are required-ish (an id with no label falls back to
`"{axis title} {id}"`); everything else under a value (e.g. `color`) is free-form metadata.
`color` is the one key paperband's own templates read; anything else is available to
custom theme templates but otherwise ignored.

A card joins an axis's value either through its own frontmatter field of the same name:

```yaml
---
title: Some card
tier: 1
subsystem: core
---
```

or through a folder-level binding that cascades to every card under that folder:

```yaml
# some-folder/paperband.yaml
axis:
  tier: 1
```

Frontmatter wins when both are present. A card can belong to zero, one, or several axes'
values at once — each declared axis is tracked completely independently. Every declared
axis (with at least one declared value) automatically gets: a site landing page per value
(`{axisName}-{valueId}.html`), a PDF divider page before the first card of each contiguous
run of that value (stacked with other axes' dividers, in `axes:` declaration order, when a
card starts a new run on more than one axis at once), nav/sidebar entries, and a
`{axisName}-{valueId}` CSS class on every card that has a value for it.

A value a card uses but that isn't listed under the axis's `values:` still gets its own
landing page (with a generated label and a colour from the default palette) rather than
being silently dropped — useful while iterating on a book's structure before every value
is finalized in the yaml.

Cards with no value on any declared axis are grouped by their top-level folder name
instead ("sections") and get their own landing pages alongside the axis-value pages.

## `parts`

Folder-derived sections are discovered — one group per top-level folder. `parts:` declares
those groups instead, giving one title to a run of folders:

```yaml
parts:
  - title: "Foundations"
    folders:
      - 01-getting-started
      - 02-authoring
```

A part is one group wherever a section would be: one divider, one landing page, one nav
entry. Folders no part claims stay discovered sections. See Organising Content for the full
treatment, alongside the folder-level `order:`, `include:`, and `sort:` keys.

## `sections.landing.template`

A section's landing page normally renders with the built-in `site-section` template, but
this is overridable in two places, checked in this order:

1. The section folder's own `paperband.yaml`, with the same `landing.template` shape an
   axis value uses:

   ```yaml
   # content/scanners/paperband.yaml
   title: "Scanners & Blindspots"
   landing:
     template: "layouts/scanners-section.html"
   ```

2. A book-wide default for every section that doesn't declare its own, set at the book
   root:

   ```yaml
   sections:
     landing:
       template: "layouts/default-section.html"
   ```

Template paths are relative to the book's `layouts/` directory, extension stripped — a
leading `layouts/` is accepted and dropped, so write the path as the file sits on disk.
Subdirectories work: `layouts/sections/scanners.html` loads exactly that. A theme's
template overrides are searched first, the bundled templates last. If neither `landing` is
set, the built-in template is used, same as before this override existed.

Instead of a path, `template:` also accepts a **named preset** — no file needed:

| Name      | Renders                                              |
|-----------|-------------------------------------------------------|
| `default` | Title, card count, and a grid of card tiles (the built-in behaviour) |
| `minimal` | Just the section title — no count, no card list        |

```yaml
# content/quickref/paperband.yaml
title: "Quick Reference"
landing:
  template: minimal
```

A `template:` value that isn't one of these names is treated as a file path, same as
above. Named presets and file-path overrides can be mixed freely across a book's
sections — some folders can use `minimal`, others their own custom template, others
nothing at all (falling through to the book default, then the built-in template).

The same folder `paperband.yaml` `title:` key (independent of `landing.template`) sets
the section's display label; without it, the label is auto-formatted from the folder name
(hyphens/underscores become spaces, each word capitalized).
