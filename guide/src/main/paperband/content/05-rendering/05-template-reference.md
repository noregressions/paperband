---
id: template-reference
oneliner: "Every template filter, every bundled template, the views, card numbers, and what a generated page and the site's nav see."
index: [templates, filters, query, views, generated pages]
---

# Template Reference

The filters, templates and models the layout templates work with, in one place.
[Themes](card:themes) explains how they fit together; this card lists them.

## Filters

Every filter that takes a selector takes a CSS selector, read by jsoup. An empty selector,
or one jsoup can't read, fails the build and names the filter and the selector. So does
`find`, `query`, `html` or a transform given the wrong kind of input, a class name that isn't
letters, digits, `-` and `_`, and a block a transform can't add.

| Filter | Takes | Returns |
|---|---|---|
| `select('css')` | HTML, such as `block.html` | The outer HTML of each match in document order, joined by newlines. A match inside another isn't repeated. No match is an empty string. Print it with `\| raw` |
| `find('css')` | A block (its own nodes), a list of nodes, or a node | The matching nodes, in document order, including a match inside another. No match is an empty list |
| `query('css')` | `cards` or any list of cards, a card, or a block | One entry per match, in document order (see [Query entries](card:template-reference#query-entries)). No match is an empty list |
| `drop('css')` | A card, cards, a block, a list of nodes, or a node | The same, with each match and everything in it left out |
| `keep('css')` | A card, cards, a block, a list of nodes, or a node | The same, with everything that isn't a match, inside one or around one left out |
| `addClass('css', 'name')` | A card, cards, a block, a list of nodes, or a node | The same, with the class added to each match |
| `removeClass('css', 'name')` | A card, cards, a block, a list of nodes, or a node | The same, with the class taken off each match |
| `set('css', '{key=value}')` | A card, cards, a block, a list of nodes, or a node | The same, with the attributes set on each match |
| `replace('css', '{.name}')` | A card, cards, a block, a list of nodes, or a node | The same, with an empty block in place of each match, keeping its id and attributes over the spec's |
| `insertBefore('css', '{.name}')` | A card, cards, a block, a list of nodes, or a node | The same, with an empty block before each match |
| `insertAfter('css', '{.name}')` | A card, cards, a block, a list of nodes, or a node | The same, with an empty block after each match |
| `prepend('css', '{.name}')` | A card, cards, a block, a list of nodes, or a node | The same, with an empty block inside each match, first |
| `append('css', '{.name}')` | A card, cards, a block, a list of nodes, or a node | The same, with an empty block inside each match, last |
| `wrap('css', '{.name}')` | A card, cards, a block, a list of nodes, or a node | The same, with each match inside a new empty block |
| `blank('css', 'name')` | A card, cards, a block, a list of nodes, or a node | `replace` with `'{.name}'`, except that a node it blanks keeps nothing |
| `html` | A block, a list of nodes, or a node | The nodes as HTML, as `block.html` is written: an unchanged node as it was, a templated fence through its block template. Print it with `\| raw` |

The transforms are `drop` through `blank`. Given a block, a list of nodes or a node, a
transform returns the changed nodes as a list; a block's nodes leave out its nested blocks,
as `block.html` does. Given a card it returns the changed card, with each changed block's
`html`, `card.steps` and `card.slots` made again, and given a list of cards it returns the
list. Only `drop` and `keep` can leave a card out: it's missing from the list, or null.
Nothing changes in place, so transforms chain, and `find` and `html` take what they return.
Each finds every match before it changes anything, so it never matches what it added. A
changed node's `text` and `html` describe it after the change. The keys of a node are in
[Themes](card:themes#reading-a-block-as-data).

A block a transform adds is written like a card's attributes, `'{#id .class key=value}'`,
braces optional, and is an empty `<div>`, or an empty block when the transform was given a
card. It can't carry `id` or `class` as attributes, anything the content policy strips from
a card, or an attribute that holds a URL (`href`, `src` and the like).

### What a selector sees

`find`, the transforms and the node level of `query` see each node as an element:

| Part of the node | As |
|---|---|
| `tag`, `id`, `classes`, `attributes` | The element's tag, id, classes and attributes |
| `directives` | `data-paperband-<name>` attributes: `[data-paperband-step]` |
| A fence's `lang` | A class on the `pre`: `pre.command` is a ` ```command ` block |

`query`, and a transform given a card or cards, also see two levels above the nodes, with
each block's nested blocks inside it:

| Element | Id | Classes | Attributes |
|---|---|---|---|
| `card` | The card's id: `card#setup` | `<axis>-<value>` for each axis value: `card.level-beginner` | Each frontmatter key with a scalar value; a list's items joined by spaces: `card[index~=maven]` |
| `block` | The block's id | The block's classes: `block.watch-out` | The heading's attributes, and its directives as `data-paperband-<name>` |

### Query entries

| Key | What's in it |
|---|---|
| `kind` | `card`, `block` or `node`: the level that matched |
| `card` | The card the match is in, or the match itself; null when `query` was given a block |
| `block` | The innermost block the match is in, or the match itself; null for a card match |
| `step` | The innermost block with a `{!step}` around the match, or the match itself; null when there's none |
| `node` | The node, for a node match; null otherwise |

`card`, `block` and `node` are the same models a template gets as `card`, a block of
`card.blocks` and a node of `block.nodes`.

## Bundled templates

A template is looked up in the theme's `templates/`, then the book's `layouts/`, then the
bundled set, and the first found is used. Under a [view](card:template-reference#views),
each name is looked up as `<view>/<name>` through all three first.

| Template | What it renders |
|---|---|
| `book.html` | The whole book as one HTML document, which the PDF is printed from |
| `card.html` | One card as a document of its own |
| `dividers.html` | Which divider pages come before a card, printed as tokens |
| `_book-cover.html`, `_book-back.html` | The cover and the back page |
| `_book-front.html` | Between the cover and the first card: the book's own `_section.md` |
| `_book-toc.html`, `_book-index.html` | The printed contents and the back-of-book index |
| `_card-body.html`, `_card-body-base.html` | A card's article; the `-base` file holds the markup and its named blocks |
| `_block-section.html` | A block: its section, heading and content, then its nested blocks through `_block-section` again |
| `_block-content.html` | A block's own content, `{{ block.html \| raw }}`, which `_block-section` includes |
| `_section-divider.html`, `_section-divider-base.html` | A section's or a part's divider page |
| `_tier-divider.html`, `_tier-divider-base.html` | An axis value's divider page |
| `_copy-buttons.html`, `_mermaid.html` | Scripts: copy buttons on fences, and Mermaid diagrams |
| `_site-page.html` | The shell every site page extends: head, CSS, nav, sidebar, main column |
| `_site-nav.html`, `_site-sidebar.html` | The site's top nav and sidebar |
| `_page-rail.html` | A site card page's on-this-page list of its headings |
| `site-index.html` | The site's home page |
| `site-section.html`, `site-section-minimal.html` | A section's landing page, with or without its card grid |
| `site-tier.html` | An axis value's landing page |
| `site-card.html` | A card's page |
| `site-page.html` | A generated page on the site: its template's output in the shell |
| `blocks/command.html`, `blocks/console.html`, `blocks/output.html`, `blocks/mermaid.html` | Fences of those types. A book adds a type with `layouts/blocks/<type>.html` |
| `cheatsheet/keep.html`, `cheatsheet/transform.html`, `cheatsheet/_card-body.html`, `cheatsheet/_page-rail.html` | The `cheatsheet` view; its rail lists a site page's steps |
| `student/keep.html`, `student/transform.html`, `student/_block-section.html`, `student/_answer-space.html` | The `student` view |

## Views

A view is a folder of templates named by `<view>`, with a `keep.html`, a `transform.html`
or both. `keep.html` is rendered once per card and prints `true` or `false`, and the build
leaves out the cards it prints `false` for. `transform.html` is rendered once per card as
the card's model is made: it changes the card, with statements or `result(...)`, or leaves
it out, and every template the build uses, `keep.html` included, sees that card. With both,
a card has to pass each. Any other template in the folder replaces the default of the same
name for that build.

| In `transform.html` | Means |
|---|---|
| `card`, `vars`, `output`, `target` | The card's model before its number and sheet are filled in, the card's vars, `print` or `site`, and the build's target |
| Statements, one to a line | What it prints, when it doesn't call `result`: `drop SEL`, `keep SEL`, `blank SEL as NAME`, `replace SEL with BLOCK`, `insert BLOCK before\|after SEL`, `insert BLOCK first\|last in SEL`, `wrap SEL in BLOCK`, `add .NAME to SEL`, `remove .NAME from SEL`, `set ATTRS on SEL`, each the transform of that meaning, run in order. A blank line or one starting with `#` is skipped |
| `{{ result(card \| …) }}` | Hands back the changed card instead, once, with nothing else printed; `null` leaves it out. Anything else that isn't a card fails the build, and so does `result` in any other template |
| `drop card…`, `keep card…` | A statement or filter that leaves the card out: the view doesn't hold it |

| In a view's template | Means |
|---|---|
| `{% include "_name" %}` | The view's own `_name` if some link of the chain has it, else the default |
| `{% include "default:_name" %}` | The default `_name`, as if there were no view: a theme's or the book's own, else the bundled one. What it includes goes through the view again |

| View | Keeps | Writes |
|---|---|---|
| `cheatsheet` | Cards with a `{!step}` | Its `transform.html` cuts each card to its steps, and each step to the parts `vars.cheatsheetSelect` picks; each card is then its title and one entry per step. See [Make a Cheat Sheet](card:make-a-cheat-sheet) |
| `student` | Every card | Its `transform.html`, `blank .solution as answer-space`, blanks each `{.solution}`: a block becomes a labelled `.answer-space` box, `{lines=N}` lines tall; a node an empty `.answer-space` box. See [Make a Student Edition](card:make-a-student-edition) |

The content these views read:

| Mark | Read by | Example |
|---|---|---|
| `{!step}` | `cheatsheet` | `## {!step} Install` |
| `{.instructions}` | `cheatsheet` (the default `cheatsheetSelect`) | `Install a JDK. {.instructions}` |
| `{.solution}` | `student` | `## Solution {.solution}`, `::: {.solution}`, `The answer. {.solution}` |
| `lines` | `student`, on a solution block | `## Solution {.solution lines=8}` |

| Var | Read by | Default |
|---|---|---|
| `cheatsheetSelect` | `cheatsheet/_card-body.html` | `.instructions, pre.command, pre.console` |
| `answerLabel` | `student/_answer-space.html` | `Your answer` |

## Generated pages

A `<page><template>name</template></page>` marker in a POM-declared `<sections>` renders
`layouts/name.html` once, after the cards are assembled (see
[Maven Plugin](card:maven-plugin#generated-pages)). Its template sees:

| Key | What's in it |
|---|---|
| `cards` | Every card the build holds, each the full card model: `blocks`, `steps`, `axes`, `frontmatter`, `vars`, `number` |
| `sections` | The book's sections, each `{id, label, count, cards, landingPage, …}`; each of `cards` is `{id, title, oneliner, effort, frontmatter}` |
| `axisGroupings` | Each axis as `{axis, values}`; each value `{id, label, color, count, cards}` |
| `book` | `title`, `subtitle`, `series`, `author`, `vars`, `cover`, `back` |
| `vars` | The book's vars |
| `stats` | `total`, the number of cards, and `byAxis`, each axis's count per value |
| `output` | `print` in the PDF, `site` on the site |

| | PDF | Site |
|---|---|---|
| Where | Its own sheet, in front of the card the marker precedes | `name.html` at the site root |
| Wrapped in | `<section class="book-page" id="book-page-N">`, N counting markers from 0 | `site-page.html`: `<article class="site-generated-page" id="name">` in the site shell |
| Found by | A named destination, `book-page-N` | A nav entry, labelled with the page's first `<h1>`, or `name` without one |
| Placed twice | Rendered at each marker | One page, at the first marker |
| `card:` links | `#card-<id>` | `cards/<id>.html` |

A site page whose file name matches a section's or an axis value's landing page fails the
build. The template's file name is the page's name, so it is letters, digits, `-` and `_`.

`site-page.html` sees the site shell's model, plus `pageTitle` (the nav label) and
`pageBody` (the template's output, printed with `| raw`).

## The site's nav entries

`_site-nav.html`, `_site-sidebar.html` and `site-index.html` read `navEntries`, a list in
walk order. Each entry is one of three kinds:

| `kind` | Keys | Links to |
|---|---|---|
| `axis` | `id`, `label`, `color`, `count`, `cards`, `url`, `axis`, `axisTitle` | The value's landing page |
| `section` | `id`, `label`, `count`, `cards`, `url` (null for a section with no page), `landingPage` | The section's landing page |
| `page` | `id`, `label`, `url`, `count` (null), `cards` (empty) | A generated page |

The page each template renders is `page`, `{kind, id}`, with `kind` one of `index`, `axis`,
`section`, `card` or `page`. A template that overrides one of the three nav templates and
handles only `axis` and `section` shows a generated page as a section with no count.

## Card numbers

A numbered card's number is its label as a reader sees it: `2.3` for a chapter, or its
section's format filled in, `Scenario 3` (see [Number a Series of Cards](card:number-a-series)).
It's null for an unnumbered card, and for a card whose title prints its own number with
`{!number}`, so `{% if card.number %}` is the test for printing it before the title.

| Key | Where |
|---|---|
| `card.number` | A card's model: its title in `_card-body-base.html`, the cheat sheet and `site-card.html`'s `<title>` |
| `c.number` | Each card a site list holds: the sidebar's, and `site-section.html`'s and `site-tier.html`'s grids |
| `prev.number`, `next.number` | A site card page's previous and next links |

The printed contents and the PDF bookmarks put the number in front of the title in the
entry's `label`, so a theme that knows nothing of numbers still shows them.

A card's `{!number}` is already filled in when a template sees the card: `card.title`,
`block.heading`, `block.html` and every node's `text` and `html` hold the bare number.

## CSS hooks

The classes the bundled templates and scaffold CSS give the features above:

| Class | On |
|---|---|
| `.card-number` | A card's number, in front of its title wherever the bundled templates print both |
| `.cheatsheet-card`, `.cheatsheet-card-title`, `.cheatsheet-step`, `.cheatsheet-depth-N` | The `cheatsheet` view's cards and steps |
| `.answer-space`, `.answer-space-label` | The `student` view's boxes, and a solution block's label. Height: `--answer-lines`, default 4 |
| `.book-page` | A generated page's sheet in the PDF |
| `.site-generated-page` | A generated page's article on the site |
| `.page-box`, `.page-link`, `.sidebar-page-heading` | A generated page's tile on the site index, its top-nav link and its sidebar heading |

## Check

The guide's own build keeps two of these tables honest: a test fails if the Bundled
templates table and the templates paperband ships differ by one, or if the Filters table and
the filters the layout registers do.
