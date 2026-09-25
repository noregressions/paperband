---
id: docs-anywhere
oneliner: "Point the plugin at Markdown that lives anywhere: a docs folder, a readme per service, another tool's source."
index: [existing docs, content root]
---

# Use the Markdown You Already Have

Use this setup when the Markdown already exists, for example in `docs/`, in one readme
per service, or in a folder a static site generator also reads. The files stay where they
are, and the POM tells the plugin where to find them. It requires JDK 21+ and Maven 3.8+;
see [Before You Start](card:quickstart).

## From your docs to a PDF

**1. Add the plugin** to the POM of the module that owns the docs, and set the folder:

```xml
<plugin>
  <groupId>dev.noregressions.paperband</groupId>
  <artifactId>paperband-maven-plugin</artifactId>
  <version>0.1.2</version>
  <configuration>
    <content>docs</content>
  </configuration>
</plugin>
```

**2. Build it.**

```bash
mvn paperband:build -Dpaperband.output=target/docs.pdf
mvn paperband:site  -Dpaperband.outputDirectory=target/site
```

The first PDF build downloads headless Chromium, so it needs internet access once. The
site goal does not need a browser.

**3. Check the result.** `mvn paperband:structure` lists every section and card found,
without rendering. Each `.md` file is a card and each subfolder a section;
`README.md` files are left out.

Existing Markdown builds without edits: frontmatter is optional, and a card's id comes from
its path. To build the book on every `mvn package`, bind the goals in
`<executions>` as shown under One folder, below.

## One folder, or files all over

The setting depends on how the files are arranged:

| Your docs are… | Declare | Structure comes from |
|---|---|---|
| One folder, organised in reading order | `<content>docs</content>` | The folder tree |
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
Put `<content>` in the plugin's shared `<configuration>`, not inside one execution, so the
`build`, `site` and `structure` goals all read the same book.

Every file in that folder is a card: `.md` files, `.html` files, and `.yaml` files when the
book declares a `cardSchema:`. Two files are excluded: `README.md`, which describes its
directory, and `_section.md`, which is a section's landing text. Each subfolder becomes a section, and filename order
is reading order until a `paperband.yaml` says otherwise (see
[Organising Content](card:organising-content)).

### Where the config goes

The docs folder needs no Paperband files. With `<content>` declared, the book's
configuration, templates and stylesheets can stay in the conventional home:

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

This keeps Paperband's files out of a folder that other tools, or GitHub's Markdown view,
also read. For a self-contained folder, put `paperband.yaml` at the root of `docs/` instead;
a content root's own yaml is used when no home supplies one. Neither is required: a book
with no `paperband.yaml` builds without a title.

### Existing Markdown mostly works as-is

A card is a Markdown file with an H1 title. Frontmatter is optional, and an undeclared id
comes from the card's path (`api/endpoints.md` → `api-endpoints`), so plain docs build
without edits. Add frontmatter when you want a shorter id, a `oneliner:` on the section
page, or axis values. H2 headings become blocks that themes can style.

## Docs outside the module

In a multi-module build the docs are often at the repository root, above the module that
builds them. Set `<content>` to an absolute path:

```xml
<configuration>
  <content>${project.basedir}/../docs</content>
</configuration>
```

Cards can be read from any location. `{% include %}` and `{% fragment %}` can only read
files beneath the book root, so a fragment that quotes source code elsewhere in the
repository needs that directory allowed:

```xml
<externalIncludeDirs>
  <externalIncludeDir>${project.basedir}/..</externalIncludeDir>
</externalIncludeDirs>
```

This guide uses the same setting so [Maven Plugin](card:maven-plugin) can quote the parent
POM. [Includes](card:includes) covers both tags.

## Scattered files: `<book>`

When the cards are not in one folder, such as a `TRACE.md` beside each service, declare
the structure in the POM and select the cards by glob:

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

Patterns resolve against `<root>`, and `target/` and `node_modules/` are never matched. A
pattern that matches nothing fails the build. The home at `src/main/paperband` still supplies
`paperband.yaml`, `layouts/` and `styles/` if it exists. [Maven Plugin](card:maven-plugin)
covers every element `<book>` takes, including the title, cover and vars, for a book with
no yaml at all.

## The older spelling: `<input>`

`<input>` predates `<content>` and still works. `<input>` expects a book directory that
may contain its own `layouts/` and `styles/` beside the cards, which it skips by name;
`<content>` treats every file in the folder as content. `-Dpaperband.input=` remains the
usual form for one-off runs against a directory or a single card:

```bash
mvn paperband:scan -Dpaperband.input=docs/api/endpoints.md
```

## Watch Out

**Declare the book's files once.** `<content>` with `<input>`, `<input>` with `<book>`, or
`<content>` with a `<book>` that has its own `<includes>` fail the build. A `<book>` that
carries only configuration, such as a title, a cover and vars, can be combined with
`<content>`.

**A declared path is not replaced by the default.** If `<content>` names a folder that does
not exist, the build fails; it does not fall back to `src/main/paperband`.

## Check

```bash
mvn paperband:structure
```

The log shows the resolved geography (`book geography: home=…, content=…`), which
confirms the folder that was read. The outline that follows lists every section and card
found, without rendering the book.
