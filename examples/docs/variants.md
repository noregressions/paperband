---
title: Variants and optional content
---
# Variants and optional content

Some content comes in more than one length. Write the versions side by side and each output
shows one: the showcase shows the full text, the handbook prefers the short one.

:::: {! variants}
::: {! variant=full}
Download the release archive for your platform from the project's releases page, check its
SHA-256 checksum against the published value, then unpack it somewhere on your path.
:::
::: {! variant=short}
Download the archive, check its checksum and unpack it.
:::
::: {! variant=shortest}
Download and unpack.
:::
::::

The names are fixed: `full`, `short` and `shortest`. Without a preference the longest is shown;
with one, that variant, else the next shorter, else the next longer. Selectors and `rest` only
ever see the variant chosen.

## Optional content {! break=prefer}

A `{! break=prefer}` marker on this heading says that if the handbook's body box has to continue
on another page, this is a good place to break. The print layout's `split` only breaks between
second-level sections, so the marker has to be on one.

This paragraph is optional: a later build step may leave it out when the page is too full. {! optional priority=2}
