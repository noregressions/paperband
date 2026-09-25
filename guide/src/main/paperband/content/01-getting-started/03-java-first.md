---
id: java-first
oneliner: "A book inside a Maven project, at src/main/paperband, found by convention with no configuration."
index: [src/main/paperband, convention, archetype]
---

# Start a New Book

Use this setup for new documentation, or documentation that can live inside the project.
The book goes in `src/main/paperband/`, where every goal finds it without `<input>`,
`<home>` or `<content>`, in the same way Maven finds `src/main/java`.

This guide uses this layout. It requires JDK 21+ and Maven 3.8+; see
[Before You Start](card:quickstart).

## From nothing to a PDF

**1. Generate the project.** The archetype creates a Maven project with the plugin
configured and one starter card:

```bash
mvn archetype:generate \
  -DarchetypeGroupId=dev.noregressions.paperband \
  -DarchetypeArtifactId=paperband-archetype \
  -DarchetypeVersion=0.1.2 \
  -DgroupId=com.example -DartifactId=my-guide
cd my-guide
```

**2. Build it.**

```bash
mvn package
```

The PDF is written to `target/my-guide.pdf`. The first build downloads headless Chromium,
so it needs internet access once.

**3. Write.** Replace the starter card and add cards. Each `.md` file under
`src/main/paperband/content/` is a card, built in filename order, and each subfolder is a
section. A card is Markdown with an H1 title:

```markdown
# Installing the Agent

Two steps, and a check at the end.

## Setup

…
```

Run `mvn package` again. For the same book as a website, run
`mvn paperband:site -Dpaperband.outputDirectory=target/site` and open
`target/site/index.html`.

Archetype 0.1.2 generates an older layout: the starter card is directly in
`src/main/paperband/`, and the POM sets that folder with `<input>`. That layout still builds.
To convert it, create `src/main/paperband/content/`, move the `.md` files into it, and delete
the `<input>` line. Later archetype releases generate this layout and a site execution.

## Adding a book to a project you already have

No archetype is needed. Create `src/main/paperband/content/` in the module, optionally add
a `paperband.yaml` beside `content/` for the title and theme, and declare the plugin with
only an output (see The POM, below). `mvn package` then builds the book with the rest of
the module.

## The layout

```filetree
my-project/
  pom.xml
  src/main/java/             ← your code, if the module has any
  src/main/paperband/        ← the book's home
    paperband.yaml           ← what the book is: title, theme, axes, vars, css
    content/                 ← the cards, and only the cards
      paperband.yaml         ← optional: order:, include:, sections:
      01-getting-started/
        paperband.yaml       ← optional: this folder's title and order
        01-introduction.md
        02-install.md
      02-reference/
        01-options.md
    layouts/                 ← optional: {% include %} snippets, template overrides
    styles/                  ← optional: the files the css: chain names
```

Everything under `content/` is a card: `.md` files, `.html` files, and `.yaml` files when
the book declares a `cardSchema:`. `README.md` and `_section.md` are the exceptions. Each
subfolder of `content/` becomes a section of the book.

The home holds the book's configuration, templates and stylesheets; `content/` holds only
cards. Templates and stylesheets are therefore never picked up as cards.

## The POM

```xml
<plugin>
  <groupId>dev.noregressions.paperband</groupId>
  <artifactId>paperband-maven-plugin</artifactId>
  <version>0.1.2</version>
  <executions>
    <execution>
      <id>pdf</id>
      <phase>package</phase>
      <goals><goal>build</goal></goals>
      <configuration>
        <output>${project.build.directory}/book.pdf</output>
      </configuration>
    </execution>
    <execution>
      <id>site</id>
      <phase>package</phase>
      <goals><goal>site</goal></goals>
      <configuration>
        <outputDirectory>${project.build.directory}/site</outputDirectory>
        <clean>true</clean>
      </configuration>
    </execution>
  </executions>
</plugin>
```

Each execution sets only its output location. `mvn package` builds both. On the command
line, the goals need only the output:

```bash
mvn paperband:build -Dpaperband.output=target/book.pdf
mvn paperband:site  -Dpaperband.outputDirectory=target/site
mvn paperband:structure
```

## A module that is only a book

When a module only builds documentation, give it `pom` packaging. This skips the compile,
test and jar steps, and Maven's "JAR will be empty" warning, while still running the plugin
executions bound to `package`. This guide's module uses it:

```xml
<artifactId>guide</artifactId>
<packaging>pom</packaging>
```

## Moving the whole home

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

## Watch Out

**The convention applies only when the directory exists.** A module without
`src/main/paperband/` has no book, and a goal run there fails with a "nothing to build"
message. An explicit `<home>` or `<content>` is used as declared even if the directory does
not exist, so a mistyped path fails the build rather than falling back to the convention.

**`src/main/paperband` is not a resources directory.** Maven copies `src/main/resources`
into the jar but not this directory, so the book's sources are not packaged. The built PDF
and site are written to `target/`.

## Check

Every goal logs the geography it resolved, in one line:

```
book geography: home=…/src/main/paperband, content=…/src/main/paperband/content, layouts=…/src/main/paperband/layouts
```

`content=(none)` means the home has no `content/` folder. The plugin then walks the home
itself, the older layout, where `layouts/` and `styles/` sit beside the cards and are
skipped by name. Run `mvn paperband:structure` to list the sections and cards found.
