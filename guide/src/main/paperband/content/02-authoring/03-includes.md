---
id: includes
oneliner: "Embed content with {% fragment %}, and live Pebble snippets with {% include %}."
index: [includes, fragments, snippets]
---

# Includes

The `{% fragment %}` tag embeds content from another file into a card. It is a Pebble tag
evaluated before Markdown parsing, so the inserted content is processed as Markdown like
the rest of the card: headings, lists and code fences all work.

## Anchor syntax

Reference a named region between `ANCHOR: name` / `ANCHOR_END: name` comments in the
source file. The comment style (`//`, `#`, `<!-- -->`) is ignored by the pattern matcher.

```
{% fragment "path/to/file.java:AnchorName" %}
```

The tag's grammar, included from its source file:

{% fragment "../../../../../../include/src/main/java/dev/noregressions/paperband/include/FragmentTokenParser.java:fragment-tag-grammar" %}

The first argument is the reference, which can be any Pebble expression. Each argument
after a comma is a `name=value` pair: `as="<type>"` sets the return type, and every other
name is passed to the provider or processor as an attribute.

## Whole-file include

Omit the selector to include the whole file, for example a small config file:

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

1. Frontmatter, fenced code blocks and inline code spans are masked, so literal examples
   of `{% fragment %}` syntax, like those on this page, are not evaluated.
2. The rest of the body is evaluated as a Pebble template; each `{% fragment %}` tag is
   replaced with its fetched, processed content.
3. The masked regions are restored, and the result is parsed by flexmark as ordinary
   Markdown.

A fragment's content is inserted verbatim: Pebble syntax inside it, including another
`{% fragment %}`, stays literal text. To evaluate the included file, use `{% include %}`
(below). Any other `{{ }}`/`{% %}`-like text outside code must be valid Pebble syntax;
wrap literal examples in a fenced code block, an inline code span, or Pebble's
`{% verbatim %}` tag.

## `{% include %}` — live Pebble snippets

`{% fragment %}` embeds content; `{% include %}` embeds a template, a Pebble file that is
evaluated with the card's `vars` in scope and can take parameters. Names resolve against
the book's `layouts/` directory, with `.html` appended when the name has no extension, or
the exact file when it has one (`snippets/note.md`):

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
`{% fragment %}`, `vars` and conditionals.

- **Masking does not apply inside snippets.** A card's fenced code blocks are not
  evaluated, but a snippet is a template, so a fenced example of Pebble syntax inside one
  needs `{% verbatim %}`. Use `{% include %}` for templates and `{% fragment %}` for
  verbatim content.
- **Include cycles are not detected in advance.** A snippet that includes itself,
  directly or through a loop, fails the build with a recursion error naming the card.

## Watch Out

The two tags resolve paths differently. A `{% fragment %}` reference is resolved relative
to the card file's directory first, then the book root; an `{% include %}` name is always
relative to `layouts/`. A missing file or anchor fails the build, reporting the location of
the directive.

Absolute paths are used verbatim and bypass all resolution.
