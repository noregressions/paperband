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

## Two scopes

Every key has a **scope**, and the scope decides both who may set it and who wins.

**Card scope** is everything above: it cascades, and depth wins. `vars`, `axis`, `css`,
`layout`, `targets`.

**Book scope** describes the book as one artifact, so it has no depth to cascade through.
It is read from the book's own `paperband.yaml` and nowhere else: `title`, `axes`,
`theme`, `sections`, `cardSchema`, `cover`, `back`, `header`, `footer`, `page`, and the
book-wide `sections.landing.template` default.

A book-scope key set in a folder yaml is an **error**, not a silent win or a silent loss.
That matters most for `page` — see below.

`css`, `vars` and `targets` sit in both: the book root is where they start, and folders
extend or override them from there. They're card scope with a book-level entry point, not
a third kind of thing. Config Reference lists every key with its scope and precedence.

Folder-level `order:`, `include:`, `sort:`, and `where:` are in neither cascade: they
control which cards a folder emits and in what sequence, and each folder declares its own
independently (see Organising Content).

## Where the POM fits

A build tool can declare book config too — the Maven plugin's `<book>` element. The rule
has two halves, because the two scopes want opposite things:

> **The POM outranks the root yaml. Depth outranks the POM.**

For **book scope** the POM simply wins, field by field: a declaration beats a default, and
the POM is the file you just edited. Fields stay independent — declaring `<title>` doesn't
clear a yaml-declared `cover`.

For **card scope** the POM's `<book><vars>` enters the cascade *at the book's own level* —
above the built-ins, below any folder. So a folder yaml still overrides a POM-declared var,
exactly as it overrides a root-yaml one. A build-declared var has to reach every card, and
it has to stay overridable per folder; a rule that just said "the POM always wins" would
break the second half.

Book scope has no depth, so there the two halves coincide and the POM wins outright.

Geography — `<home>`, `<content>`, `<layouts>` — is POM-only in both scopes. `paperband.yaml`
declares what the book *is*; the POM declares *where* it is.

## Page geometry is book scope

A book is printed on one sheet, so `page:` is read from the book's own yaml only:

```yaml
# book root paperband.yaml
page:
  size: a5
  margins: { top: 18, right: 15, bottom: 18, left: 15 }
  orientation: portrait
```

`size`, `margins` and `fontScale` in a folder yaml raise a build error naming the file.
They used to half-apply — the card's CSS content box changed while the physical sheet did
not, so the card was laid out for a page it was never printed on — and the outcome depended
on which card the build happened to walk first.

`orientation` is the exception, and only because it isn't really one: it describes a
**block's** sheets rather than the book's, so it cascades like any other card-scope key.

```yaml
# content/appendices/paperband.yaml — these cards print sideways
page:
  orientation: landscape
```

Every card in that folder gets its whole run of pages rotated — the run, not one page:
Paperband addresses cards, never pages, and a card is always a whole number of sheets. The
book's paper stays the same; only its rotation changes.

`page:` was previously spelled `vars.page`, which still works as a deprecated alias.
Geometry riding inside `vars` is exactly what made it uncheckable — a member of a cascading
map can't be scoped without special-casing it.

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
mvn paperband:scan -Dpaperband.input=path/to/card.md
```

The scan output includes the fully resolved context for that card — book root, CSS chain
in load order, merged vars, and axis values — so you can see exactly what the cascade
produced without running a build.
