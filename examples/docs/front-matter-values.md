---
title: Front matter values
status: review
summary: "Shows `title`, `status` and this text in boxes of their own — written as *markdown*, with [markers]{! highlight} too."
layout:
  boxes:
    - { id: title, select: h1, pick: first }
    - id: panel
      unplaced: ignore                                      # it only shows values, none of the page's content
      boxes:
        - { id: status, value: status, lookup: statuses }   # the name from data/statuses.yml; class status-review
        - { id: summary, value: summary }                   # inline markdown, markers and all
        - { id: owner, value: owner, when: { not-empty: true } }   # this page has no owner, so no box
    - { id: body, select: rest }
  css: |
    & .box-panel { display: flex; gap: 12px; align-items: baseline; padding: 8px 12px; border-radius: 6px; background: #f5f5f4; }
    & .box-status { font-weight: 600; padding: 2px 8px; border-radius: 4px; }
    & .status-review { background: #fef3c7; color: #92400e; }
    & .status-final { background: #dcfce7; color: #166534; }
    & .highlight { background: #e0e7ff; }
---
# Front matter values

A `value:` box shows a value from the page's front matter. Text is read as markdown, so backticks,
emphasis and `{! }` markers work there as they do here, with no HTML in the front matter.

With `lookup:`, the value is a key into a data file: this page says `status: review`, and the box
shows the name `data/statuses.yml` gives it. A value that's an id also becomes a class on its
box, `status-review`, so a stylesheet can colour each status without the page saying how.
