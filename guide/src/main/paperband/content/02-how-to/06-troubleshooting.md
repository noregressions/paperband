---
id: troubleshooting
oneliner: "Which goal answers which question, and the common failures with their cause and fix."
index: [troubleshooting, exit codes]
---

# Troubleshooting

## Which goal to run

Each inspection goal answers one question without a full render, or with the render you
already have.

| Question | Run |
|---|---|
| Which sections and cards are in the book, in what order? | `mvn paperband:structure` |
| What did one card parse to: id, frontmatter, blocks, resolved vars? | `mvn paperband:scan -Dpaperband.input=path/to/card.md` |
| Why does it look like that? | `-Dpaperband.emitHtml=target/book.html` on `build`, then view the file's source |
| How many pages is each card? | `mvn paperband:pages -Dpaperband.pdf=target/book.pdf -Dpaperband.byPages=true` |
| Does the PDF renderer work on this machine? | `mvn paperband:renderers` |
| Which themes and block types can this build use? | `mvn paperband:themes`, `mvn paperband:blocks` |

In the emitted HTML, every inlined stylesheet starts with a `/* === theme:name path === */`
comment, so the source shows which file each rule came from. The order is the book's
`css:` chain, then the theme, then `<stylesheets>`; see [Themes](card:themes#how-css-composes).

## Exit codes

| Code | Meaning |
|---|---|
| 2 | Bad input or an unknown renderer |
| 3 | A card exceeded its page budget ([Page Enforcement](card:page-enforcement)) |
| 4 | A structural template could not place every block ([Themes](card:themes)) |

## The build finds no book

```output
Configure <content>, <input> or <book> — nothing to build. (Or lay the book out at src/main/paperband, which needs none of them.)
```

The module has no `src/main/paperband/` and the POM names no book. Create the directory,
or point the plugin at the Markdown with `<content>`; see
[Use Existing Markdown](card:use-existing-markdown).

`No cards found under …` means a declared `<content>` path exists in the POM but holds no
cards, or does not exist. A declared path is used as written, with no fallback to the
convention. Check the `book geography:` line in the log.

## A link or icon fails the build

```output
A card link points at nothing:
  card:icnos in 01-card-structure.md — no card has that id. Did you mean 'icons'?
```

The id after `card:` matches no card in the book. Run `mvn paperband:structure` for the
real ids. A card whose id was never declared takes it from its path within the book, so
moving the file changes it. See [Card Structure](card:card-structure#linking-to-another-card).

```output
Unknown icon:
  :userz:  in "…the :userz: column…" — did you mean 'user' or 'users'?
```

The name matches neither the book's `icons/` folder nor the bundled set. To show the
literal text, write it with a doubled leading colon. See [Icons](card:icons).

## The first PDF build fails or stalls

The PDF renderer downloads headless Chromium (about 300 MB) to `~/.cache/ms-playwright/` on
first use. A build without network access fails at that point; there is no fallback
renderer. Pre-cache Chromium on the CI image, or set `PLAYWRIGHT_BROWSERS_PATH` to an
existing copy. The `site` goal needs no browser. See
[Before You Start](card:before-you-start).

` ```mermaid ` diagrams and syntax highlighting load their libraries from a CDN at render
time, so books that use them need network on every build.

## Text or glyphs are missing from the PDF

Colour emoji inside bold text, and emoji on a machine with no emoji font, render as blanks.
Use icon references instead; see [Icons](card:icons).

A watermark in a script the default Helvetica cannot set (CJK, Cyrillic, Greek) needs a
`font:`; without one the build warns and leaves the pages unmarked. See
[Watermarks](card:watermarks).

## The margins won't go away

Four things inset text from the paper edge: the page margin, a small built-in safety
padding, the theme's text measure (`--card-max-width`) and the theme's card padding. A
full-bleed book that still looks margined is usually the measure. Set it in the book's
`paperband.yaml`:

```yaml
page:
  measure: none
```

See [Themes](card:themes#full-bleed-themes).

## Deck content sits high, with empty space below

`page.size: 16x9` was set without `margins:`. The size replaces the sheet only, so A4's
20mm margins remain, and the deck lays out against 150.5mm of height instead of 190.5mm. No
error is reported. Declare zero margins; see [Slides](card:slides#the-recipe).

## A `-D` value has no effect

A `-D` property fills a parameter only when the POM's `<configuration>` doesn't set it. With
`<theme>editorial</theme>` in the POM, `-Dpaperband.theme=dark` is ignored. Declare the value
as a POM property instead, which `-D` can override; see
[Maven Plugin](card:maven-plugin#overriding-from-the-command-line).

## A heading appears as plain text

In a card that emits HTML with Pebble, the heading after the HTML was absorbed into the
HTML block. Markdown ends an HTML block only at a blank line, and Pebble removes the
newline straight after a closing tag such as `{% endif %}`. Leave two blank lines after
the tag. A trimming tag (`{%-`, `{#-`) on the line after a heading joins the following
content onto the heading line. See [Tables from Data](card:tables-from-data#whitespace).

## Every page of the PDF is smaller than expected

Chromium scales the whole document down to fit its widest element, so one table or code
line wider than the page shrinks every page. Build one section at a time to find it, for
example `mvn paperband:build -Dpaperband.select=section=configuration
-Dpaperband.output=target/part.pdf` for a book with a `section` axis. Then constrain the
element in CSS: `table-layout: fixed` and `width: 100%` for a table, and
`overflow-wrap: anywhere` for long code in its cells.

## A folder yaml is rejected

```output
…/paperband.yaml: 'page.size' can only be set at the book root — the book is printed on one sheet, and a folder can't resize it. Move it to the book's own paperband.yaml (or the POM's <pageSize>/<margins>). Only 'page.orientation' is available per folder.
```

A folder yaml may not set `theme`, `axes`, `cover`, `back`, `header`, `footer`, `sidebar`,
`cardSchema`, `publication`, or `page.size`, `page.margins` and `page.fontScale`. The
message names the file and the key. Move the key to the book's own `paperband.yaml`, or to
the POM. See [Configuration Cascade](card:configuration-cascade).

## A declared list names something missing

An `include:` entry that resolves to no file or folder fails the build; an `order:` entry
only warns. In a POM `<book>`, an `<include>` pattern that matches no card files fails the
build, naming the section and the pattern. See
[Organising Content](card:organising-content) and [Maven Plugin](card:maven-plugin).

## Check

When the cause isn't clear, run `structure` first: most failures are a card that isn't where
the build looked, or isn't the card you expected.
