---
sections: true
---

# Two ways to set up a book

**Two ways to set up a book.** Paperband runs as a Maven plugin, so the first decision is where the book sits relative to
the build. There are two answers. Everything after that — cards, themes, the PDF, the site —
works the same under either.

## Java-first

The book is part of the project's source tree, at `src/main/paperband/`, next to
`src/main/java`. Maven finds it by convention, the same way it finds your code, and the
plugin block in the POM never says where the book is.

```filetree
src/main/paperband/
  paperband.yaml
  content/
    01-introduction.md
```

Choose this for a new book, a docs-only module, or anything you start from the archetype.

[Set up a Java-first book →](card:java-first)

## Docs where they already are

The Markdown already exists — a `docs/` folder, a readme per service, a wiki export — and
it isn't moving. One parameter tells the plugin where to look.

```xml
<configuration>
  <content>docs</content>
</configuration>
```

Choose this for an existing project, docs another tool also reads, or cards spread across
several modules.

[Point Paperband at existing docs →](card:docs-anywhere)
