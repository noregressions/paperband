---
id: steps
oneliner: "Numbered steps, including steps inside a step."
---

# Setting Up a Book

`{!step}` is replaced with "Step N", numbered by position: the first step under a parent
is 1, the next is 2, and each parent starts again. None of the headings below contains a
number; reorder them and the numbers follow
([Card Structure](https://noregressions.github.io/paperband/cards/card-structure.html#numbered-steps)).

One paragraph in each step ends with `{.instructions}`. The class changes nothing here,
because no stylesheet targets it, but the book's `cheatsheet` builds find it: with
`vars.cheatsheet` set, this card becomes one entry per step (the title, the instructions
and the command), and every card without steps is left out
([Make a Cheat Sheet](https://noregressions.github.io/paperband/cards/make-a-cheat-sheet.html)).

## Before you start

This heading has no `{!step}`, so it isn't counted, and the first step below is still
Step 1.

## {!step}: Install the tools

Paperband needs Java and Maven. The two checks below are steps inside this step, so they
number 1 and 2 again.

Install a JDK, version 21 or later, and Maven. {.instructions}

### {!step} Check Java

Confirm the JDK is version 21 or later. {.instructions}

```command
java -version
```

### {!step} Check Maven

Confirm Maven runs and uses that JDK. {.instructions}

```command
mvn -version
```

## {!step}: Create the book

Generate a new book from the archetype. {.instructions}

```command
mvn archetype:generate \
  -DarchetypeGroupId=dev.noregressions.paperband \
  -DarchetypeArtifactId=paperband-archetype \
  -DgroupId=com.example -DartifactId=my-guide
```

## {!step}: Write the first card

Three steps inside this one, restarting at 1:

### {!step} Add a file

Create `src/main/paperband/01-hello.md`. {.instructions}

### {!step} Give it a title

The first `#` heading names the card. {.instructions}

### {!step} Add a block

Each `##` heading starts a block the theme can style. {.instructions}

## {!step}: Build it

Build the PDF and the site. {.instructions}

```command
mvn package
```

## Check

Reorder or delete a step and rebuild: the numbers follow. The PDF and the site number the
same way, because the number comes from the card, not from the output.
