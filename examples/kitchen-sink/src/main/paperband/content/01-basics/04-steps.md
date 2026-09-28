---
id: steps
oneliner: "Numbered steps with {!step}, including steps inside a step."
---

# Setting Up a Book

`{!step}` numbers a heading by its position: the first stepped heading under a parent is
1, the next is 2, and each parent starts again. The headings below don't contain a single
number; the book's CSS (`styles/book.css`) shows them as "Step 1", "Step 2" and so on
([Card Structure](https://noregressions.github.io/paperband/cards/card-structure.html#numbered-steps)).

## Before you start

This heading has no `{!step}`, so it isn't counted, and the first step below is still
Step 1.

## Install the tools {!step}

Paperband needs Java and Maven. The two checks below are steps inside this step, so they
number 1 and 2 again.

### Check Java {!step}

```command
java -version
```

### Check Maven {!step}

```command
mvn -version
```

## Create the book {!step}

```command
mvn archetype:generate \
  -DarchetypeGroupId=dev.noregressions.paperband \
  -DarchetypeArtifactId=paperband-archetype \
  -DgroupId=com.example -DartifactId=my-guide
```

## Write the first card {!step}

Three steps inside this one, restarting at 1:

### Add a file {!step}

Create `src/main/paperband/01-hello.md`.

### Give it a title {!step}

The first `#` heading names the card.

### Add a block {!step}

Each `##` heading starts a block the theme can style.

## Build it {!step}

```command
mvn package
```

## Check

Reorder or delete a step and rebuild: the numbers follow. The PDF and the site number the
same way, because the number comes from the card, not from the output.
