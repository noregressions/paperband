---
# Every frontmatter field a card can set.
# https://noregressions.github.io/paperband/cards/frontmatter-reference.html
id: first-card
oneliner: "A card that sets every frontmatter field."
effort: S
max_pages: 2
verify: true
index: [frontmatter, card id]
---

# The First Card

A card is a Markdown file with an H1 title. Its frontmatter sets an explicit `id`, so this
card is at `cards/first-card.html` on the site and `#card-first-card` in the PDF. `effort`
renders as the badge under the title, and `max_pages: 2` fails the build if this card runs
past two pages
([Page Enforcement](https://noregressions.github.io/paperband/cards/page-enforcement.html)).

Cross-links use the card's id and are checked at build time: see
[Blocks and Fences](card:blocks) and the [Templating](card:templating#output-branching)
card's section on output branching
([Card Structure](https://noregressions.github.io/paperband/cards/card-structure.html)).

## Check

`mvn paperband:scan -Dpaperband.input=src/main/paperband/content/01-basics/01-first-card.md`
prints this card's resolved id and blocks. A `## Check` block is hidden when the card sets
`verify: false`.
