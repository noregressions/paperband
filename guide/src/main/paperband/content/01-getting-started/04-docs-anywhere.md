---
id: docs-anywhere
oneliner: "Keep the Markdown where it already lives, and tell the plugin where that is."
index: [existing docs, content root]
---

# Docs Where They Already Are

Most projects that need a PDF already have the words. They sit in `docs/`, in a readme per
service, or in a folder a static site generator reads. Moving them into `src/main/paperband`
to suit a build tool would be the tail wagging the dog. Instead you tell the plugin where
they are.

How you say it depends on the shape of what's there:

| Your docs are… | Declare | Structure comes from |
|---|---|---|
| One folder, organised the way you'd read it | `<content>docs</content>` | The folder tree |
| Scattered: one file per service, picked out by pattern | `<book>` with `<includes>` globs | The POM |

## One folder: `<content>`

```xml
<plugin>
  <groupId>dev.noregressions.paperband</groupId>
  <artifactId>paperband-maven-plugin</artifactId>
  <version>0.1.2</version>
  <configuration>
    <content>docs</content>
  </configuration>
  <executions>
    <execution>
      <goals><goal>build</goal></goals>
      <configuration>
        <output>${project.build.directory}/docs.pdf</output>
      </configuration>
    </execution>
  </executions>
</plugin>
```

A relative path resolves against the module's basedir, so `docs` means `my-project/docs`.
Put `<content>` in the plugin's shared `<configuration>`, not inside one execution, and the
`build`, `site` and `structure` goals all read the same book.

Everything in that folder is a card, by declaration: `.md` files, `.html` files, and
`.yaml` files when the book declares a `cardSchema:`. Two things are left out: `README.md`,
because a readme is about its directory rather than a page of the book, and `_section.md`,
which is a section's own landing text. Each subfolder becomes a section, and filename order
is reading order until a `paperband.yaml` says otherwise (see
[Organising Content](card:organising-content)).

### Where the config goes

The docs folder doesn't have to know it's a book. With `<content>` declared, the book's own
machinery can still live at the conventional home:

```filetree
my-project/
  pom.xml
  docs/                      ← <content>: the cards, untouched
    getting-started.md
    api/
      endpoints.md
  src/main/paperband/        ← the home: config, templates, css
    paperband.yaml
    layouts/
    styles/
```

That keeps Paperband's files out of a folder that other tools, or GitHub's own Markdown
view, also read. If you'd rather have one self-contained folder, put `paperband.yaml` at
the root of `docs/` instead. A content root's own yaml is used when no home supplies one.
Neither is required: a book with no `paperband.yaml` at all still builds, just untitled.

### Existing Markdown mostly works as-is

A card is a Markdown file with an H1 title. Frontmatter is optional, and an undeclared id
comes from the card's path (`api/endpoints.md` → `api-endpoints`), so plain docs build
without edits. Add frontmatter when you want a shorter id, a `oneliner:` on the section
page, or axis values. H2 headings become blocks that themes can style.

## Docs outside the module

In a multi-module build the docs often sit at the repository root, above the module that
builds them. Point `<content>` there with an absolute path:

```xml
<configuration>
  <content>${project.basedir}/../docs</content>
</configuration>
```

Cards read fine from anywhere. What stays fenced is `{% include %}` and `{% fragment %}`:
they may only read files beneath the book root, so a fragment that quotes source code
elsewhere in the repository needs that directory permitted:

```xml
<externalIncludeDirs>
  <externalIncludeDir>${project.basedir}/..</externalIncludeDir>
</externalIncludeDirs>
```

This guide uses that same line so [Maven Plugin](card:maven-plugin) can quote the parent
POM. [Includes](card:includes) covers both tags.

## Scattered files: `<book>`

When the cards don't share one folder, such as a `TRACE.md` beside each service, no
directory walk can express the book. Declare the structure in the POM and select the cards
by glob instead:

```xml
<configuration>
  <output>${project.build.directory}/traces.pdf</output>
  <book>
    <root>${project.basedir}</root>
    <sections>
      <section>
        <title>Services</title>
        <includes><include>services/*/TRACE.md</include></includes>
      </section>
      <section>
        <title>Reference</title>
        <includes><include>docs/**/*.md</include></includes>
        <excludes><exclude>docs/draft/**</exclude></excludes>
      </section>
    </sections>
  </book>
</configuration>
```

Patterns resolve against `<root>`, and `target/` and `node_modules/` are never matched,
whatever the pattern says. A pattern that matches nothing fails the build, because it's
almost always a typo or a moved folder. The home at `src/main/paperband` still supplies
`paperband.yaml`, `layouts/` and `styles/` if it exists. [Maven Plugin](card:maven-plugin)
covers every element `<book>` takes, including the title, cover and vars, for a book with
no yaml at all.

## The older spelling: `<input>`

Before `<content>` existed, `<input>` did this job, and it still works. The difference is
what it assumes: `<input>` expects a book directory that may carry its own `layouts/` and
`styles/` beside the cards (and skips them by name), while `<content>` means everything
there is content. Examples elsewhere use `-Dpaperband.input=` for one-off runs against a
directory or a single card, and that's the one place it's still the natural choice:

```bash
mvn paperband:scan -Dpaperband.input=docs/api/endpoints.md
```

## Watch Out

**Pick one answer to "which files are in the book".** `<content>` with `<input>`, `<input>`
with `<book>`, or `<content>` with a `<book>` that has its own `<includes>` all fail the
build. Each pair names the book's files twice. A `<book>` that carries only config, such as
a title, a cover and vars, combines with `<content>` without complaint.

**A declared path is taken at its word.** If `<content>` names a folder that doesn't exist,
the build fails. It doesn't fall back to `src/main/paperband`, because a silent fallback
would build a different book from the one you asked for.

## Check

```bash
mvn paperband:structure
```

The log names the resolved geography (`book geography: home=…, content=…`), which confirms
which folder was read. The outline after it lists every section and card the walk or the
patterns found, so you can see the book before waiting for a render.
