---
id: includes
oneliner: "Embed content with {% fragment %}, and live Pebble snippets with {% include %}."
index: [includes, fragments, snippets]
---

# Includes

The `{% fragment %}` tag embeds content from an external file into a card. It's a real
Pebble tag, evaluated in a pre-pass before Markdown parsing, so the inserted content is
processed as Markdown like any other card text — headings, lists, and code fences all
work. (Earlier drafts of this guide used a `{{#include ...}}` regex-based directive;
that's been replaced by this tag — same underlying file/anchor resolution, a real parser
on top of it.)

## Anchor syntax

Reference a named region between `ANCHOR: name` / `ANCHOR_END: name` comments in the
source file. The comment style (`//`, `#`, `<!-- -->`) is ignored by the pattern matcher.

```
{% fragment "path/to/file.java:AnchorName" %}
```

Here is the tag's own grammar, pulled live from the source:

{% fragment "../../../../../../include/src/main/java/dev/noregressions/paperband/include/FragmentTokenParser.java:fragment-tag-grammar" %}

The first argument is the reference — any Pebble expression, not just a quoted literal.
Everything after a comma is a `name=value` pair: `as="<type>"` sets the return type,
every other name is forwarded as a provider/processor attribute.

## Whole-file include

Omit the selector to include the entire file. Useful for small config files where an
anchor would be noise:

```
{% fragment "styles/guide.css" %}
```

## Line-range syntax

Reference a specific line span with `path:start:end` (1-indexed, inclusive):

```
{% fragment "pom.xml:1:5" %}
```

## Override the return type

By default the return type is inferred from the file extension (`.java` → code fence,
`.md` → markdown splice, `.html` → raw HTML). Override with `as`:

```
{% fragment "notes.txt", as="code" %}
{% fragment "fragment.md", as="code" %}
```

## How the preprocessor runs

Includes expand in a single pre-pass before flexmark sees the source:

1. Frontmatter, fenced code blocks, and inline code spans are masked out first, so a
   card that *shows* `{% fragment %}` syntax as a literal example (like this page) isn't
   itself evaluated.
2. The rest of the body is evaluated as a real Pebble template; every `{% fragment %}`
   tag is replaced with its fetched, processed content.
3. The masked regions are restored, and the result is parsed by flexmark as ordinary
   Markdown.

A fragment's content is spliced **verbatim** — Pebble syntax inside it, including another
`{% fragment %}`, survives as literal text. When the included file should *evaluate*, use
`{% include %}` (below). The single-pass evaluation also means any *other* stray
`{{ }}`/`{% %}`-looking text outside a code fence has to be valid Pebble syntax — wrap a
literal example in a fenced code block, an inline code span, or Pebble's own
`{% verbatim %}` tag if it isn't already one.

## `{% include %}` — live Pebble snippets

Where `{% fragment %}` embeds *content*, `{% include %}` embeds a *template*: a reusable
Pebble file that evaluates with the card's `vars` in scope and can take parameters.
Names resolve against the book's `layouts/` directory — the same place every other
declared template lives — with `.html` appended when the name has no extension, or the
exact file when it has one (`snippets/note.md`):

```
{% include "snippets/warning" %}
{% include "snippets/badge" with {"level": "danger", "text": "mind the gap"} %}
```

```html
<!-- layouts/snippets/badge.html -->
<span class="badge badge-{{ level }}">{{ text }}</span>
```

`{% import %}` brings in a macro library the same way:

```
{% import "macros/badges" %}
{{ badge("info") }}
```

An included snippet is parsed by the same engine as the card, so it can use
`{% fragment %}`, `vars`, and conditionals itself. Two things to keep straight:

- **Masking does not extend into snippets.** A card's own fenced code blocks are
  protected from evaluation; a snippet is a real template, so a fenced *example* of
  Pebble syntax inside one needs `{% verbatim %}`. Rule of thumb: `{% include %}` for
  live templates, `{% fragment %}` for verbatim content.
- **No cycle detection.** A snippet that includes itself (directly or around a loop)
  fails the build with a recursion error naming the card.

## Watch Out

The two tags resolve paths differently. A `{% fragment %}` reference is relative to the
**card file's directory** first, then the book root; an `{% include %}` name is always
relative to **`layouts/`**. Either way, a missing file or a missing anchor hard-fails the
build with the source location of the offending directive — there is no silent fallback.

Absolute paths are used verbatim and bypass all resolution.
