---
id: card-structure
oneliner: "A card is a Markdown file. H2 headings create named, styled blocks."
---

# Card Structure

A card is a `.md` file. YAML frontmatter between `---` delimiters carries metadata.
The H1 becomes the card title. Every H2 heading starts a new block, and the heading
text is slugified to produce a CSS class on that block's `<section>` element.

This card uses all five conventional block types — the rendered output IS the demo.

## What Changed

Each H2 creates a block. The heading text is lowercased and non-alphanumeric characters
are replaced with hyphens: `## What Changed` → CSS class `what-changed`.

Explicit classes and ids override the auto-slug via Pandoc attribute syntax:

```markdown
## Watch Out {.watch-out #wo-overview}
```

Multiple classes are space-separated inside the braces. The explicit `id` becomes the
HTML `id` attribute and the PDF named-destination anchor for that block.

## How to Fix

The conventional block headings and their CSS classes:

| Heading text | CSS class | Conventional use |
|---|---|---|
| (text before first H2) | `intro` | Lead paragraph / overview |
| `## What Changed` | `what-changed` | API or behaviour delta |
| `## How to Fix` | `how-to-fix` | Remediation steps |
| `## Watch Out` | `watch-out` | Pitfalls and gotchas |
| `## Check` | `check` | Verification steps |

Themes style each class distinctly. You are not limited to these names — any heading
level from H2 through H6 produces a block with an auto-slugged class you can target in
your own CSS.

## Nested blocks

H3 (and deeper, down to H6) also create blocks — nested inside whichever shallower
block was open when they appeared, not flattened into it. A heading at level *L* closes
every currently-open block at level *L* or deeper, then opens a new one nested under
whichever block (if any) is still open above it: the same rank-based rule Pandoc's
`--section-divs` and Docutils' section transform use.

```markdown
## Setup

Runs before every H3 below it.

### Prerequisites

Nested inside "Setup", not a sibling of it.

## Usage
```

`## Setup` and `## Usage` are top-level blocks; `### Prerequisites` is a child block
*inside* `## Setup`'s block, rendered as its own nested `<section>` — independently
targetable in CSS via its own auto-slugged class (`prerequisites`) or an explicit
`{.class #id}` attribute, exactly like a top-level block. A card that never goes deeper
than H2 renders identically to before nesting existed — nesting is purely additive.

Skipping a level (an H4 directly under an H2, no H3 in between) still nests correctly:
the H4 attaches to the nearest still-open shallower block, here the H2.

## Watch Out

Do not place an H2 (or any block-level heading) before the H1. The parser treats H1 as
the card title and every H2–H6 as a block boundary. A heading before the H1 produces a
block with no title above it, which renders oddly in most themes.

`verify: false` in frontmatter suppresses `check`-classed blocks at every nesting depth,
not just the top level — a `### Check {.check}` nested three levels deep is suppressed
exactly like a top-level `## Check`.

## Check

```bash
pagewright scan path/to/card.md
```

The scan output lists every block's heading, resolved CSS classes, and a snippet of its
rendered HTML. Use it to verify block boundaries before a full build.
