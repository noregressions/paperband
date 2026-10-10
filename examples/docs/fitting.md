---
title: Making content fit
---
# Making content fit

A paged output has fixed pages, so Paperband measures every box in Chromium before anything is
written. When a box's content is bigger than the box, the box's `fit:` remedies are tried in the
order the layout lists them, and the page is measured again after each one. The code example below
is a little too wide for the body box of the handbook's A4 print layout, so the handbook uses the
box's remedies: shrinking the text to about 96% is enough, and nothing else is needed. The showcase
is a flowing web page, so there it appears as written.

## What a box can try

The handbook's print layout gives its body box three remedies, one after another:

```yaml
fit:
  - shrink: { min: 90% }    # text as large as fits, never below 90%
  - variant: next           # then a shorter variant, where the content offers one
  - drop: optional          # then leave out optional blocks, least important first
```

`shrink` searches for the largest text size that fits, so the text is only as small as it needs
to be. `tighten` reduces line height and the space between blocks. `variant` switches variants
groups to shorter versions, one step at a time or straight to the shortest. `drop: optional` leaves
out blocks marked `{! optional}`, those without a priority first, then the highest number first.
`truncate` cuts the text after a number of lines, or after as many as fit. What one remedy did
stays done while the next is tried, so the remedies add up.

:::: {! variants}
::: {! variant=full}
When none of a box's remedies is enough, the page has remedies of its own, listed under the
layout's `fit:`. It can drop boxes that allow it (`drop: { below-min: true }`), optional ones before
normal ones; it can switch to another of the layout's named grids, so the same boxes are arranged
differently; and it can lay the page out again with a fallback layout, whose own remedies then
apply. Lower-priority boxes already give way to higher ones in the page's grid rows and stack
slots, down to their minimum size, so that needs no remedy of its own.
:::
::: {! variant=short}
When a box's remedies aren't enough, the page's own `fit:` can drop boxes, switch grids or use a
fallback layout.
:::
::::

## When nothing works

If the remedies run out and something still doesn't fit, the build fails and nothing is written.
Instead, a fit report is written beside the output: the pages with each box that doesn't fit
outlined and labelled, and a list of the problems, each with the remedies that were tried. The
build's error message lists the same problems, by page, source file, box and how many millimetres
didn't fit, so the fix can start from the message alone.

Every remedy that was used is reported too, so a page that only fits because its text was shrunk
is never a surprise. Style remedies end up as CSS in the paged HTML, which is exactly what was
measured and printed: opening the HTML shows the PDF's pages.

This paragraph is optional, the first thing the body box would give up after a shorter variant. {! optional}
