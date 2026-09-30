---
id: blocks
oneliner: "Blocks, editorial fences, a custom block type and icons."
---

# Blocks and Fences

Each `##` heading starts a block whose class is the heading's slug, so themes can style
them ([Card Structure](https://noregressions.github.io/paperband/cards/card-structure.html)).

## What Changed

Three fence types carry an editorial role: `command` for input, `output` for what a tool
printed, and `console` for a mixed session.

```command
mvn paperband:structure
```

```output
[INFO] BOOK "Kitchen Sink"  [12 cards]
```

```console
$ mvn -q paperband:renderers
playwright        yes
```

A custom block type is a template at `layouts/blocks/<type>.html`. This fence renders
through `layouts/blocks/note.html`:

```note
A note block, drawn by the book's own template.
```

Icons are `:name:` references: :book-open: from the bundled Lucide set, and :ks: from
this book's `icons/ks.svg`
([Icons](https://noregressions.github.io/paperband/cards/icons.html)).

## Watch Out

A `## Watch Out` block is styled as a callout by the theme. Write `::name:` for a literal
`:name:` in running text.

## Check

The site's copy of this page has a **Copy** button on the `command` and `console` blocks.

## Try It

Which fence would you use for what the reader types, and which for what the terminal
prints back?

The one the reader types is a `command`; what comes back is `output`. {.solution}

## Answer {.answer .solution lines=3}

A `command` block for what the reader types, and `output` for what comes back. The
`student` build prints a box to write in here instead
([Make a Student Edition](https://noregressions.github.io/paperband/cards/make-a-student-edition.html)).
