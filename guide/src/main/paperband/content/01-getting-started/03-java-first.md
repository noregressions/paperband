---
id: java-first
oneliner: "The book lives in src/main/paperband and the POM never says where."
index: [src/main/paperband, convention]
---

# Java-first Layout

A Java project doesn't tell Maven where its code is. `src/main/java` is the convention, and
the convention is the configuration. A Paperband book works the same way: put it at
`src/main/paperband/` and every goal finds it with no `<input>`, `<home>` or `<content>`
declared.

This guide is laid out this way. If you started from the archetype in the
[Quickstart](card:quickstart), your book already is too.

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
the book declares a `cardSchema:`. `README.md` and `_section.md` are the exceptions, and
each subfolder of `content/` becomes a section of the book. Nothing else under `content/`
needs to know it's in a book.

The split is deliberate. The home holds the book's own machinery (config, templates,
stylesheets), and `content/` holds what a reader reads, so a template never turns up as a
card and a card never has to share a folder with CSS.

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

Each execution says where its output goes, and that's all it says. `mvn package` builds
both. From the command line, the goals need no arguments beyond the output:

```bash
mvn paperband:build -Dpaperband.output=target/book.pdf
mvn paperband:site  -Dpaperband.outputDirectory=target/site
mvn paperband:structure
```

## A module that is only a book

When a module exists just to build documentation, give it `pom` packaging. There are no
Java sources to compile and no jar worth producing, so `pom` packaging skips the compile,
test and jar steps (and Maven's "JAR will be empty" warning) while still running the plugin
executions bound to `package`. This guide's own module does exactly that:

```xml
<artifactId>guide</artifactId>
<packaging>pom</packaging>
```

## Moving the whole home

`<home>` relocates the convention rather than abandoning it. Set it and `content/`,
`layouts/` and `paperband.yaml` are looked for beneath the new location instead:

```xml
<configuration>
  <home>${project.basedir}/src/main/handbook</home>
</configuration>
```

That's how one module carries two books. This guide's companion slide deck is a second home
at `src/main/paperband-deck`, built by an execution that declares only `<home>`, `<output>`
and `<renderer>`. See [Slides](card:slides).

## Watch Out

**The convention applies only when the directory exists.** A module without
`src/main/paperband/` resolves to no book at all, and a goal run there fails, saying it has
nothing to build. That's what keeps the plugin from claiming a folder you never meant as a
book. An explicit `<home>` or `<content>` is taken as declared, whether or not it exists,
so a typo in a path fails the build instead of quietly falling back to the convention.

**`src/main/paperband` is not a resources directory.** Maven copies `src/main/resources`
into the jar, not this. The book's sources stay out of your artifact, and the built PDF and
site land in `target/`, where any other build output goes.

## Check

Every goal logs the geography it resolved, in one line:

```
book geography: home=…/src/main/paperband, content=…/src/main/paperband/content, layouts=…/src/main/paperband/layouts
```

`content=(none)` means the home has no `content/` folder. The plugin then walks the home
itself, the older layout, where `layouts/` and `styles/` sit beside the cards and are
skipped by name. Then run `mvn paperband:structure` to see the sections and cards the walk produced.
