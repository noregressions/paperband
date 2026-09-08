---
id: and-slides
title: "…and slides"
---

- A **card is already a page** — give it a 16:9 sheet and it's a slide
- `maxPagesPerCard: 1` fails the build when a slide overflows
- Add `render-pptx` and the same book exports native PowerPoint shapes

## Aside

This deck is built by the guide's own `mvn install -Pguide`, from two markdown
files, by the plugin in the same reactor.

## Notes

Land on the budget check: nothing in PowerPoint can tell you a slide is too
full before you present it. That's the argument for authoring decks this way.
