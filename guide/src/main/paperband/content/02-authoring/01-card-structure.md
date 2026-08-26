---
id: card-structure
oneliner: "A card is a Markdown file. H2 headings create named, styled blocks."
---

# Card Structure

A card is a `.md` file. YAML frontmatter between `---` delimiters carries metadata.
The first H1 becomes the card title, unless the frontmatter declares a `title:` — then
nothing is consumed and every heading renders. Every H2 heading starts a new block, and
the heading text is slugified to produce a CSS class on that block's `<section>` element.
A *further* H1 is a block too, one rank above the H2s beneath it — which is how a long
card numbers its top-level steps with `#`.

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

Wide fenced code blocks get clipped at the page edge in print/PDF targets — there's no
scrollbar to fall back on there the way there is on the web. Rather than picking a font
size, drop a specific block a step with the same attribute-list syntax used above,
placed on its own line right after the closing fence:

````markdown
```java
some wide line of code that would otherwise run off the page...
```
{.fs--1}
````

The attribute line has to sit immediately after the closing fence with a blank line (or
end of file) after it — glue it directly to the next paragraph with no blank line and it
attaches to *that* paragraph instead of the code block. `fs--1` shaves roughly 10% off
the rendered code size, `fs--2` about 20%; going further than `fs--2` hurts legibility in
print.

Attributes can also ride the opening fence's info line, which keeps the tag with the
block it describes instead of dangling after it:

````markdown
```text {.output}
[INFO] tool output here
```
````

Both spellings do the same thing, and the language survives for syntax highlighting.
The trailing form stays useful when an attribute is an afterthought, like a size
step-down on an already-written block.

For the three recurring editorial roles there's a shorthand: the fence language
*is* the block type, no attribute syntax at all —

````markdown
```command
mvn dependency:tree
```

```output
[INFO] com.example:app:jar:1.0.0
```

```console
$ ls target
book.pdf
```
````

`command` renders highlighted as bash with a "Command" label — the reader types this.
`output` is unhighlighted with an "Output" label — a tool produced this. `console` is a
mixed session ($-prefixed commands with their output), highlighted as `shell-session`.
Every theme gets a neutral treatment for all three; themes may restyle them. Real
languages (` ```java `, ` ```xml `) pass through untouched, and the attribute spellings
(` ```bash {.command} `) keep working for cases the shorthand doesn't cover.

## Raw HTML and the content policy

Raw HTML in a card is a legitimate *structural* escape hatch — a table with rowspans,
`<kbd>Ctrl</kbd>`, a `<details>` block. What it is **not** is a styling channel: content
carries structure, and the theme owns appearance — that separation is what lets a reader
pick a theme and have it actually apply.

The build enforces this with a content policy, declared through the `vars` cascade
(book-wide in the root yaml, overridable per folder, or via the POM's `<vars>`):

```yaml
vars:
  contentPolicy: clean    # the default — allow | clean | strict
```

- **`clean`** (default) — presentation found in content is stripped and each removal is
  logged, naming the card and what went. Stripped: inline `style=`, `<style>` and
  `<script>` blocks, head-metadata elements (`<link>`, `<meta>`, `<title>`, `<base>`),
  presentational tags (`<font>`, `<center>` — unwrapped, their content kept) and
  attributes (`align`, `bgcolor`, `width`, `border`, …), and `on*` event handlers.
  Classes and ids survive — they're the sanctioned route — and so does `align` on
  table cells, because GFM's `---:` column syntax renders as exactly that: it's
  markdown-authored semantics, not smuggled styling.
- **`strict`** — the same findings fail the build instead, for teams that want the
  source fixed rather than laundered.
- **`allow`** — content HTML passes verbatim, today's escape hatch.

Fenced and inline code are never touched: a literal `<div style="…">` inside an example
is escaped text by the time the policy runs, so this page can show the syntax it strips.

To style content, name the *meaning* with a class and let CSS own the look:

```markdown
> Deletes the working directory. {.warning}
```

with a `.warning` rule in the book's `css:` chain, a theme, or the POM's
`<stylesheets>`. That's the styling that survives a theme switch — and the reason
`style="color: red"` doesn't.

## Check

```bash
mvn paperband:scan -Dpaperband.input=path/to/card.md
```

The scan output lists every block's heading, resolved CSS classes, and a snippet of its
rendered HTML. Use it to verify block boundaries before a full build.
