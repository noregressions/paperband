---
title: A navigation bar
---
# A navigation bar

With `nav: top`, every page has a bar under the header instead of a sidebar. Each heading of the
contents page is a group in the bar, and opens a list of its pages. A link before the first heading
is an entry of its own.

```yaml
outputs:
  - name: site-top
    format: web
    file: target/site-top/index.html
    nav: top
    contents: docs/web-site/00-contents.md
```

A group's label is its heading up to the colon, so `Part 1: Navigation` shows as `Part 1`. The whole
heading is its tooltip. The current page, and the group it's in, are marked.

Without a contents page, the pages are grouped by folder, and a folder of one page is that page. If
every page is in one folder, the bar lists the pages themselves.

The groups open without the script. With it, one group is open at a time, Escape or a click elsewhere
closes it, and a group near the right edge opens leftwards.
