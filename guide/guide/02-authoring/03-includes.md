---
id: includes
oneliner: "Pull code fragments from any file with {% fragment \"path:anchor\" %}."
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

{% fragment "../../../pagewright-include/src/main/java/dev.noregressions.paperband/include/FragmentTokenParser.java:fragment-tag-grammar" %}

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

This means `{% fragment %}` inside an included `.md` file is also expanded — includes
compose. It also means any *other* stray `{{ }}`/`{% %}`-looking text outside a code
fence now has to be valid Pebble syntax — wrap a literal example in a fenced code block,
an inline code span, or Pebble's own `{% verbatim %}` tag if it isn't already one.

## Watch Out

Path resolution is relative to the **card file's directory** first, then the book root.
A missing file or a missing anchor hard-fails the build with the source location of the
offending directive — there is no silent fallback.

Absolute paths are used verbatim and bypass all resolution.
