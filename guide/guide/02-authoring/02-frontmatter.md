---
id: frontmatter
title: "Frontmatter Reference"
oneliner: "Every supported frontmatter field, demonstrated in the card that documents them."
effort: S
tags: [authoring, yaml, reference]
max_pages: 2
verify: true
---

YAML frontmatter sits between `---` delimiters at the very start of the file, before
the body. It is parsed by SnakeYAML, so all YAML types work: strings, booleans,
integers, lists, and nested maps. This card uses every field it documents in its own
frontmatter — including `title:`, which is why it has no `#` heading of its own: a card
that declares a title doesn't need to repeat it as a heading, and any heading it does
write is rendered rather than swallowed.

## Field reference

| Field | Type | Default | Description |
|---|---|---|---|
| `id` | string | filename stem | Stable identifier. Becomes the PDF named-destination anchor and the site URL slug. Change with care after publishing. |
| `title` | string | first H1 | Card title. With `title:` set, no heading is consumed — the first `#` stays a heading in the body. Without it, the first `#` names the card and isn't repeated. |
| `oneliner` | string | — | Short summary line shown in card meta and index listings. |
| `effort` | string | — | Size estimate (`XS` / `S` / `M` / `L` / `XL`). Rendered as a badge. |
| `tags` | list | — | Free-form tag list. Reserved for future filtering and index generation. |
| `max_pages` | integer | — | Post-render page-count ceiling. Build fails (exit 3) if this card exceeds the limit. |
| `verify` | boolean | `true` | When `false`, the `check` block is suppressed from the rendered output. |
| `tier` | integer | — | Numeric tier when using the tier axis (1–3). Drives tier dividers and colour-coding in the PDF. |
| `openrewrite` | boolean | — | When `true`, renders an OpenRewrite badge in card metadata. |

## Custom fields

Any YAML key not in the table above is preserved in the card's frontmatter map and
available to Pebble templates as `card.frontmatter.get("yourKey")`. There is no schema
validation — unknown keys are silently carried through.

## Watch Out

The `id` field is load-bearing once published. PDF named destinations and site URLs are
derived from it. Renaming a published card breaks inbound links and PDF bookmarks unless
you redirect the old slug.

If `id` is not set, the card's filename stem is used (e.g. `02-frontmatter.md` → id
`02-frontmatter`). Prefer explicit ids for anything you expect to link to externally.

## Check

```bash
mvn paperband:scan -Dpaperband.input=path/to/card.md
```

The first line of scan output shows the resolved `id` — useful for confirming the id
before you publish and distribute links.
