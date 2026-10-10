---
title: A page per file
---
# A page per file

A `web` output is a site: one HTML file per page. The first page is the output's own file, and
every other page goes beside it at its source's path, `.md` replaced by `.html`. All the pages share
one stylesheet, `site.css`, and one script, `site.js`.

Every page has a header linking the first page, and links to the previous and next pages in
sequence order. A link to another page, such as [the navigation bar](#docs-web-site-03-navigation-bar-md),
points at that page's file.

```yaml
outputs:
  - name: site
    format: web
    file: target/site/index.html
```

A web output of one page stays one file that stands alone, and `single: true` puts every page in one
file. The examples showcase is built that way.
