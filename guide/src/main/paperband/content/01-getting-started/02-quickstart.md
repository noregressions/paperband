---
id: quickstart
oneliner: "Build and run Paperband from source in five minutes."
---

# Quickstart

## Requirements

| Requirement | Notes |
|---|---|
| JDK 21+ | Must be a JDK (not JRE) — Maven needs `javac` |
| Maven 3.8+ | Standard Maven install |
| Playwright | The only renderer; downloads ~300 MB Chromium on first use |

## Add the plugin

Paperband builds books from Maven. Declare the plugin in the project that holds your book:

```xml
<plugin>
  <groupId>dev.noregressions.paperband</groupId>
  <artifactId>paperband-maven-plugin</artifactId>
  <version>0.1.0</version>
  <executions>
    <execution>
      <goals><goal>build</goal></goals>
      <configuration>
        <input>${project.basedir}/book</input>
        <output>${project.build.directory}/book.pdf</output>
      </configuration>
    </execution>
  </executions>
</plugin>
```

The plugin shares the parent's version. The parent POM version:

{% fragment "../../../../../../pom.xml:version-declaration" %}

`mvn package` now builds the book as part of the build. Every goal can also be invoked
directly, without an execution, which is how the examples throughout this guide are
written.

## Building from source

```bash
git clone https://github.com/gruff-dev/paperband.git
cd paperband
mvn -DskipTests install
```

That installs the plugin into your local repository, ready for the POM above to resolve.

## Build your first book

```bash
# PDF
mvn paperband:build -Dpaperband.input=path/to/your-book -Dpaperband.output=out.pdf

# Static site
mvn paperband:site -Dpaperband.input=path/to/your-book -Dpaperband.outputDirectory=out-site
```

A "book" is any directory containing a `paperband.yaml` at its root. Cards are any
`.md` files found recursively under it.

## Explore what's available

```bash
# List discovered renderers and whether each is available in your environment
mvn paperband:renderers

# List all themes (built-in and any under <themeDir>)
mvn paperband:themes
```

## Watch Out

The first Playwright render downloads headless Chromium to `~/.cache/ms-playwright/`.
In a CI environment without internet access, pre-cache it (or point
`PLAYWRIGHT_BROWSERS_PATH` at an existing download) before the first build.
