---
title: Nested layouts
layout: nested-demo
---
# Nested layouts

A layout's box can hand its content to another layout instead of showing it directly. This page uses both ways of doing that; compare its markdown with its layout file.

## Summary {! id=summary}

The `summary` box selects this section and renders it through the named `card` layout, which has a stylesheet of its own.

- A named layout over a selection: `select` plus `layout: card`
- One nested layout per match: `each` plus `boxes` written inside the box
- Grids work inside nested layouts too

## Questions

### Where does a nested layout get its content? {! faq}

From whatever the outer box passes in: a match, a selection, or the whole file. Its selectors and `rest` only see that.

### Can nested layouts nest? {! faq}

Yes, as deep as you like, as long as no layout ends up inside itself; a loop, direct or through other layouts, is an error.

### What about content they don't place? {! faq}

The same as any layout: add a `rest` box, or set `unplaced` to warn, ignore or error.
