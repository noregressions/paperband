---
title: Extending a layout
layout:
  extends: split              # start from layouts/split.yml...
  grid:                       # ...swap its columns and add a row
    columns: [2fr, 1fr]
    areas:
      - "title title"
      - "body  intro"
      - "note  note"
    gap: 24px
  boxes:
    intro: { class: lede aside }    # change one box; the others stay as split defines them
  add:
    - id: note
      area: note
      text: "This page extends the split layout: the same boxes, the columns swapped, one box added."
  css: |
    & > .aside { padding-left: 16px; border-left: 3px solid #d6d3d1; }
    & > .box-note { font-size: 13px; color: #78716c; }
---
# Extending a layout

The introduction now sits in the narrow right-hand column, set off by a rule.

It is still the split layout's intro box: only its class changed.

## What changed

- `grid` replaced the split layout's grid, with a row added for the note
- `boxes` changed one box by its id; `null` would have removed it
- `add` appended a new box, and `css` added styles for this page only

## What stayed

The title, intro and body boxes, their selectors, and the split layout's own stylesheet.
