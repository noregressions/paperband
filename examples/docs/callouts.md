---
title: Callouts
---
# Callouts

A callout is a boxed aside in the running text: a caveat, a definition, a note on method. It is
the one marker tag that comes with a look of its own, so a document needs no stylesheet for it.

::: {! callout}
**Why the counts differ.** Two sources can report the same programme and disagree, because each
counts something slightly different. Before comparing two figures, check what each one counts.
:::

One paragraph can be a callout too. {! callout}

A theme sets its colours with `--pb-callout-bg` and `--pb-callout-rule`; without them it takes the
theme's code background and link colour. In print a callout is kept on one page.

## Names a tag can't have

A tag becomes a class on its element. Paperband's own elements have classes too: `page`, `layout`,
`box` and `block`, and names starting `box-`, `layout-` or `block-`. A tag with one of those names
would share a stylesheet rule with every page, layout box or rendered block, so the build refuses
it and says so.
