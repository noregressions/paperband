---
title: A split page
layout: split
---
# A split page

The `split` layout puts this introduction in a narrow column on the left.

Everything else goes in the wider column on the right. On a narrow screen the columns stack.

## How it's defined

The layout names grid areas and puts each box in one:

```yaml
grid:
  columns: [1fr, 2fr]
  areas: ["title title", "intro body"]
```

## What goes where

- `title` takes the first H1
- `intro` takes the paragraphs directly under it
- `body` takes the rest
