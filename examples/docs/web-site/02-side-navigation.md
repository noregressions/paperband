---
title: Contents beside the page
---
# Contents beside the page

Side navigation is the default (`nav: side`). The contents page's own content is shown beside every
page, with the current page marked, and the contents isn't a page of its own any more.

The contents page is the one whose layout is `contents`, or the one `contents:` names, by file or
layout name. Without one, the navigation lists every page.

```yaml
outputs:
  - name: site
    format: web
    file: target/site/index.html
    contents: docs/web-site/00-contents.md
```

On a narrow screen the navigation is hidden behind a Contents button in the header.
