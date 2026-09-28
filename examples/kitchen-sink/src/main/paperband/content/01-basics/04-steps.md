---
id: steps
oneliner: "Numbered steps, including steps inside a step."
---

# Setting Up a Book

`{!step}` is replaced with "Step N", numbered by position: the first step under a parent
is 1, the next is 2, and each parent starts again. None of the headings below contains a
number; reorder them and the numbers follow
([Card Structure](https://noregressions.github.io/paperband/cards/card-structure.html#numbered-steps)).

## Before you start

This heading has no `{!step}`, so it isn't counted, and the first step below is still
Step 1.

## {!step}: Install the tools

Paperband needs Java and Maven. The two checks below are steps inside this step, so they
number 1 and 2 again.

### {!step} Check Java

```command
java -version
```

### {!step} Check Maven

```command
mvn -version
```

## {!step}: Create the book

```command
mvn archetype:generate \
  -DarchetypeGroupId=dev.noregressions.paperband \
  -DarchetypeArtifactId=paperband-archetype \
  -DgroupId=com.example -DartifactId=my-guide
```

## {!step}: Write the first card

Three steps inside this one, restarting at 1:

### {!step} Add a file

Create `src/main/paperband/01-hello.md`.

### {!step} Give it a title

The first `#` heading names the card.

### {!step} Add a block

Each `##` heading starts a block the theme can style.

## {!step}: Build it

```command
mvn package
```

## Check

Reorder or delete a step and rebuild: the numbers follow. The PDF and the site number the
same way, because the number comes from the card, not from the output.
