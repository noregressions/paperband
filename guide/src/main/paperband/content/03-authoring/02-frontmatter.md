---
id: frontmatter-reference
title: "Frontmatter Reference"
oneliner: "Every frontmatter field a card can set, and what each one does."
effort: S
index: [frontmatter, card id]
max_pages: 2
verify: true
---

Frontmatter is YAML between `---` delimiters at the start of a card. Every field is
optional. This card's own frontmatter sets most of them:

```yaml
---
id: frontmatter-reference
title: "Frontmatter Reference"
oneliner: "Every frontmatter field a card can set, and what each one does."
effort: S
index: [frontmatter, card id]
max_pages: 2
verify: true
---
```

Because it sets `title:`, the card has no `#` heading; with `title:` set, headings in the
body are rendered rather than used as the title. The `effort` value appears as the
"Effort: S" badge under the title, and the `oneliner` as the line beneath it.

## Field reference

| Field | Type | Default | Effect |
|---|---|---|---|
| `id` | string | path within the book, slugified | The PDF named-destination anchor (`#card-<id>`) and the site page (`cards/<id>.html`). The default is unique per card: `scenarios/S01-spring-node/TRACE.md` → `scenarios-s01-spring-node-trace`. |
| `title` | string | first H1 | The card title. With `title:` set, the first `#` stays a heading in the body. Without it, the first `#` becomes the title and is not repeated. |
| `oneliner` | string | — | Summary line shown under the title, on section landing pages and in site tiles. |
| `effort` | string | — | Rendered as an "Effort" badge under the title (conventionally `XS` to `XL`). |
| `max_pages` | integer | — | Page-count ceiling for this card. The build fails (exit 3) if the rendered card is longer. Overrides `<maxPagesPerCard>`. See [Page Enforcement](card:page-enforcement). |
| `verify` | boolean | `true` | `false` hides every `check`-classed block in the card, at any nesting depth. |
| `index` | list or string | — | Back-of-book index terms for this card. See [TOC and Index](card:toc-and-index). |
| *axis name* | any | — | The card's value for a declared axis, such as `tier: 1` for an axis named `tier`. Overrides the folder's `axis:` binding. See [Book Configuration](card:book-configuration#axes). |

A section body (`_section.md`) takes a different set of fields, such as `cards:` and
`landing:`; see [Book Configuration](card:book-configuration).

## Custom fields

Any other key is kept in the card's frontmatter map and is available to templates as
`card.frontmatter.yourKey`, which is null when the key is absent. Custom keys are not
validated.

## Watch Out

The PDF anchor and the site URL both come from `id`, so changing the id of a published card
breaks inbound links and PDF bookmarks. Without an `id`, moving or renaming the file changes
it. Set an explicit `id` on any card that other people will link to.

## Check

```bash
mvn paperband:scan -Dpaperband.input=path/to/card.md
```

The output includes the resolved `id`.
