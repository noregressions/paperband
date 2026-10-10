---
title: Inline layouts
layout:
  description: This file's own layout, written right here in its front matter.
  css: |
    & > .box-title h1 { margin-bottom: 0; }
    & > .box-lede { font-size: 1.15rem; color: #57534e; }
    & > .box-notes { padding-left: 1rem; border-left: 3px solid #d6d3d1; }
  boxes:
    - { id: title, select: h1, pick: first }
    - { id: lede, select: "section.h1 > p", pick: first }
    - id: summary
      select: "#summary"
      layout: card            # an inline layout can still use named layouts
    - { id: notes, select: rest }
---
# Inline layouts

An inline layout lives in the markdown file it lays out: `layout:` in the front matter holds the layout itself instead of naming a layout file.

Everything a layout file can say works here, including `grid`, `css` and boxes that use other layouts.

## Summary {! id=summary}

This section goes through the named `card` layout, from the inline layout's `summary` box.

- Write `layout: name` to use a layout file
- Write `layout:` followed by keys to define the layout inline
- The `css` key holds styles confined to this layout

## When to use one

Use an inline layout for a one-off page, such as a landing page or a special announcement. When several files share a layout, a layout file keeps them consistent.
