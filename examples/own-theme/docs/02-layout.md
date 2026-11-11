---
layout: aside
---
# A layout from the theme

This page asks for `layout: aside` in its front matter. The project has no `layouts` directory:
the layout comes from the theme, in `themes/paper/layouts/aside.yml`.

Anything marked `aside` goes in the narrow column. {! aside}

A project can still have a layout of its own with the same name, and then the project's is
used. The same goes for the theme itself: a directory beside the manifest named after a theme
Paperband brings, such as `forest`, is used instead of the bundled one.

Themes can carry their own fonts as well. See the `forest` theme for one that does. {! aside}
