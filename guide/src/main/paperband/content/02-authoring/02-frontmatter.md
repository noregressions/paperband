---
id: frontmatter
title: "Frontmatter Reference"
oneliner: "Every supported frontmatter field, demonstrated in the card that documents them."
effort: S
tags: [authoring, yaml, reference]
max_pages: 2
verify: true
---

YAML frontmatter is placed between `---` delimiters at the start of the file. It is parsed
by SnakeYAML, so all YAML types are supported: strings, booleans, integers, lists and nested
maps. This card sets every field it documents, including `title:`, so it has no `#` heading.
When `title:` is set, headings in the body are rendered instead of being used as the
title.

## Field reference

| Field | Type | Default | Description |
|---|---|---|---|
| `id` | string | path within the book, slugified | Stable identifier, used as the PDF named-destination anchor and the site URL slug. The default is unique per card: `scenarios/S01-spring-node/TRACE.md` → `scenarios-s01-spring-node-trace`. Declare one for a shorter URL. Changing it after publishing breaks existing links. |
| `title` | string | first H1 | Card title. With `title:` set, the first `#` stays a heading in the body. Without it, the first `#` becomes the title and is not repeated. |
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
validation; unknown keys are kept as they are.

## Watch Out

PDF named destinations and site URLs are derived from `id`. Changing the id of a published
card breaks inbound links and PDF bookmarks unless the old URL is redirected.

If `id` is not set, it is derived from the card's path within the book, slugified
(`api/endpoints.md` → `api-endpoints`), so moving or renaming the file changes it. Set an
explicit id for any card you expect to link to externally.

## Check

```bash
mvn paperband:scan -Dpaperband.input=path/to/card.md
```

The first line of scan output shows the resolved `id`. Use it to confirm the id before
publishing links to the card.
