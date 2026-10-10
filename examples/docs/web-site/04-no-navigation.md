---
title: No navigation
---
# No navigation

With `nav: none`, pages have only the header and the previous and next links. The contents page stays
a page of its own, in its place in the sequence. `contents:` can't be given with it.

```yaml
outputs:
  - name: plain
    format: web
    file: target/plain/index.html
    nav: none
```
