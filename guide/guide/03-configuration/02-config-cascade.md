---
id: config-cascade
oneliner: "Root, folder, and card config layers merge — innermost wins, CSS chains root-first."
---

# Configuration Cascade

Configuration is layered. The root `paperband.yaml` sets book-wide defaults; each folder's
`paperband.yaml` can bind an axis value, set card `order`, or extend the CSS chain; and a
card's frontmatter carries per-card metadata. The book root is discovered by walking up
from the card's directory: the **topmost** directory containing a `paperband.yaml` is the
book root, and every `paperband.yaml` between it and the card forms the cascade chain.

## What cascades — and how

Not every key merges the same way. The per-key rules:

| Key | Merge rule |
|---|---|
| `vars` | Map merge — innermost (closest to the card) wins per key |
| `axis` | Merged into `vars` the same way, so predicates and templates see bindings as plain variables |
| `css` | **Concatenated root-first** — book CSS loads first, each deeper folder's CSS is appended after it, so inner CSS wins on ties |
| `layout` | Last non-null wins, resolved against the declaring yaml's own directory |
| `targets` | Last non-empty list **replaces** the whole list — not concatenated |

So "innermost wins" holds for scalars and map entries, but the two list-valued keys differ
from each other: `css` accumulates outward-in, `targets` replaces wholesale.

## Book-root-only keys

Some keys are read only from the root `paperband.yaml` and deliberately do not cascade:
`title`, `axes`, `theme`, `cardSchema`, `cover`, `back`, and the book-wide
`sections.landing.template` default. They describe the book as a whole, not a subtree.

Folder-level `order:`, `sort:`, and `where:` are also not part of this value cascade —
they control which cards a folder emits and in what sequence, and each folder declares
its own independently.

## A three-level cascade, worked

```yaml
# book root paperband.yaml
vars:
  audience: "everyone"
  product: "Paperband"
css:
  - styles/base.css
```

```yaml
# internals/paperband.yaml
axis:
  tier: 2
vars:
  audience: "contributors"
css:
  - internals.css
```

```yaml
# internals/gc-tuning.md frontmatter
---
id: gc-tuning
tier: 1
---
```

Resolving for `gc-tuning.md`:

- `vars.product` → `"Paperband"` (only the root sets it)
- `vars.audience` → `"contributors"` (folder overrides root — innermost wins)
- CSS chain → `styles/base.css` then `internals.css`, in that order (root first, so the
  folder's rules win on equal specificity)
- The `tier` axis → the folder's `axis: {tier: 2}` binding cascades to every card in the
  folder, but this card's **frontmatter `tier: 1` wins for axis classification** — a
  card's own frontmatter field always beats the folder binding when both are present

## Built-in vars

Before any yaml is read, the vars map is seeded with build-time values: `build_date`,
`build_date_long`, `build_year`, `build_month_year`, and `build_iso`. Because they are
seeded first, any `paperband.yaml` can override them — pinning `build_date` in the root
yaml gives reproducible builds.

## Check

```bash
paperband scan path/to/card.md
```

The scan output includes the fully resolved context for that card — book root, CSS chain
in load order, merged vars, and axis values — so you can see exactly what the cascade
produced without running a build.
