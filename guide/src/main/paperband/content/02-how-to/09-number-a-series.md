---
id: number-a-series
oneliner: "Number the cards of a section as Scenario 1, 2, 3, or the whole book as chapters, from book order."
index: [numbering, scenarios, chapter numbers, card numbers]
---

# Number a Series of Cards

A set of scenarios, labs or sessions reads better numbered: Scenario 1, Scenario 2,
Scenario 3. Write the numbers into the files and they go stale the first time one moves.
Paperband numbers them from book order instead, so reordering the files renumbers them, and
a link that names one by number is checked.

`{!step}` doesn't do this. It numbers the steps inside one card, and starts again in the
next. To print a card's own number in its text, use `{!number}` (see
[Print a card's own number](card:number-a-series#print-a-cards-own-number)).

## Number one section

Put the cards in a folder of their own, and give the folder a `_section.md` that says how
its numbers read. `{n}` is the card's place in the section:

```markdown
---
numbering: "Scenario {n}"
---

# Scenarios

Each scenario is one thing that goes wrong, and how to put it right.
```

The section's cards are Scenario 1, 2 and 3, in the order the book walks them: filename
order, or the folder's `order:`. Nothing else in the book is numbered.

The number goes in front of the card's title on its page, in the printed contents and the
PDF bookmarks, and in the site's sidebar, card grids and next and previous links. Inside each
scenario, `{!step}` still counts from Step 1.

The format can say anything around the number: `"Lab {n}"`, `"Session {n}"`, `"Exercise
{n}"`. It must have `{n}`; a format without one fails the build.

## Number a series that crosses sections

When the scenarios sit among other cards, spread over several sections, a section's format
can't number them: it would number its other cards too. Mark the cards themselves instead,
with `numberAs` in their vars. A folder's `paperband.yaml` does it for every card under the
folder:

```yaml
# scenarios/paperband.yaml
vars:
  numberAs: "S{n}"
```

Every card whose vars say `numberAs: "S{n}"` takes the next number in book order, whichever
section it's in: S1 to S6 in one part and S7 in another. Cards with a different format are a
different series, so `investigations/paperband.yaml` can say `numberAs: "T{n}"` and count
T1, T2, T3 alongside.

A card in a series takes its number from the series. Its section's format and the book's
chapter numbers skip it, so the chapters around it still count 1.1, 1.2. `{n}` is
required, and `{part}` isn't allowed, because a series runs across parts.

## Link to a scenario by number

A `card:` link with no text gets the number as its text:

```markdown
This is the same fault as [](card:login-fails), one layer down.
```

That prints "Scenario 3" for the third scenario. `[#](card:login-fails)` prints the number
alone, "3", for prose that names the series itself: `Scenarios [#](card:a) and
[#](card:b)`.

A link whose text names a scenario is checked against it. `[Scenario 2](card:login-fails)`
fails the build when that card is Scenario 3, and names the file, so a reorder can't leave a
wrong number behind. Text that names no number, such as "the login scenario", is left
alone.

## Print a card's own number

`{!number}` in a card's text prints that card's number, and nothing else: no words, no
link.

```markdown
# Login fails

This is scenario {!number} of five. Each scenario starts from a clean install.

## Scenario {!number} recap
```

In the third scenario that reads "This is scenario 3 of five" and "Scenario 3 recap". The
number is the bare one, as `[#](card:id)` prints it: `3` for `"Scenario {n}"`, `2.4` for a
chapter, `4.2` for `"Lab {part}.{n}"`. Write the words around it yourself.

It works anywhere text does: a paragraph, a list, a table, a heading, the card's title. A
title that prints its own number, such as `# S{!number} — Extended SBOM`, gets no number in
front of it as well: the card page, the contents and the site show "S3 — Extended SBOM" once.
A heading's anchor
doesn't change with the number, so `## Scenario {!number} recap` is `#scenario-number-recap`
in every build. In code, `` `{!number}` `` is an example and prints as written.

A card can only print a number it has. `{!number}` in a card that no series, section or
chapter numbering covers fails the build, and names the card. So does `{!number}` in a `_section.md`, which isn't a card. To
print another card's number, link to it.

## Number the whole book

To number every card as a chapter, set `numbering` in the book's vars:

```yaml
vars:
  numbering: sequential
```

Each section is a group, numbered in the order it first appears, and its cards are 1.1,
1.2, then 2.1, and so on. An empty `card:` link prints "Chapter 2.3". A section's
`_section.md` changes its own numbers:

| In `_section.md` | Effect |
|---|---|
| `numbered: false` | The section's cards have no number: front matter, appendices |
| `part: 3` | The section shares group 3 with every section that says the same, and their cards number on across them: 3.1 to 3.12 |
| `part_title: "Getting Started"` | With `part:`, the title of the part's divider page, printed before the part's first section when the part has two or more |
| `numbering: "Scenario {n}"` | The section's cards read as the format. `{part}` is the group's number, for `"Lab {part}.{n}"` |

A section with a format keeps it in a numbered book, so a book of chapters can still have a
section of scenarios. Either `part:` is set on every numbered section or on none; a book
that sets it on some fails the build and lists them.

## Check

```command
mvn package
```

Open the PDF's bookmarks, or its contents page if the book prints one: each scenario should
be listed as "Scenario N" and its title. Move a file to a different place in the folder's
`order:`, build again, and the numbers follow.

## Watch Out

The number is the card's place in the whole book's section. A `<view>` or a `select:` that
leaves cards out doesn't renumber the rest: Scenario 3 stays Scenario 3 in a build without
Scenario 2.

`numbering:` and `numbered: false` together fail the build, because one says to number the
section and the other says not to.

A section declared in the POM finds its `_section.md` by id. Give it the folder's name as its
`<id>`, or it won't see the format (see
[Maven Plugin](card:maven-plugin#generated-pages)).
