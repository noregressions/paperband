---
id: place-a-book
oneliner: "Add a book to an existing module, give a docs-only module pom packaging, or keep two books in one module."
index: [pom packaging, home]
---

# Place a Book in a Project

The conventional location is `src/main/paperband/` in the module that builds the book.
These are the three common variations. [Start a New Book](card:start-a-new-book) covers the
layout itself.

## In a project that already exists

No archetype is needed. Create `src/main/paperband/content/` in the module, optionally add
a `paperband.yaml` beside `content/` for the title and theme, and declare the plugin with
only an output (the block is under [The POM](card:start-a-new-book#the-pom)). `mvn package` then builds the book with the rest of
the module.

## A module that is only a book

When a module only builds documentation, give it `pom` packaging. This skips the compile,
test and jar steps, and Maven's "JAR will be empty" warning, while still running the plugin
executions bound to `package`. This guide's module uses it:

```xml
<artifactId>guide</artifactId>
<packaging>pom</packaging>
```

## Two books in one module, or a different location

`<home>` moves the conventional location. `content/`, `layouts/` and `paperband.yaml` are
then looked up under the new directory:

```xml
<configuration>
  <home>${project.basedir}/src/main/handbook</home>
</configuration>
```

This lets one module build two books. This guide's slide deck is a second home at
`src/main/paperband-deck`, built by an execution that sets only `<home>`, `<output>` and
`<renderer>`. See [Slides](card:slides).

## Check

Every goal logs the resolved location as `book geography: home=…, content=…, layouts=…`.
Confirm the `home=` path is the one you intended.
