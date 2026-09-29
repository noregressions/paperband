---
id: workshop-build
oneliner: "Generate a book, add a card, and build the PDF."
---

# Build a Book

This session makes a book from the archetype, writes one card, and builds it. By the end
there is a PDF with your card in it.

## {!step} Generate the project

The archetype writes a working book: a POM with the plugin, a `paperband.yaml`, and one
sample card to replace.

Generate a project called `my-guide` from the archetype. {.instructions}

```console
$ mvn archetype:generate -DarchetypeGroupId=dev.noregressions.paperband \
    -DarchetypeArtifactId=paperband-archetype -DgroupId=com.example -DartifactId=my-guide
$ cd my-guide
```

## {!step} Write a card

A card is one Markdown file. Its first heading is the title, and every `##` heading
starts a block the theme can style.

### {!step} Create the file

Cards live under `src/main/paperband/content/`, and the file name sets their order.

Create a card file next to the sample. {.instructions}

```console
$ touch src/main/paperband/content/02-hello.md
```

### {!step} Give it a title and a block

Start with a `#` title and one `##` section. Anything more can come later.

Add a title and one section with a sentence in it. {.instructions}

```console
$ printf '# Hello\n\n## What it does\n\nIt says hello.\n' > src/main/paperband/content/02-hello.md
```

## {!step} Build the PDF

`package` runs the plugin, which renders the book through Chromium.

Build the book and open the PDF. {.instructions}

```console
$ mvn package
$ open target/my-guide.pdf
```

The first build is the slow one; after that a book this size builds in seconds.
