---
id: maven-plugin
oneliner: "Run Paperband builds as part of a Maven build, via a `build` goal Mojo."
---

# Maven Plugin

For projects that already build with Maven, `paperband-maven-plugin` runs a Paperband
build directly as part of `mvn install` (or any phase you bind it to) — no shell alias, no
separate CLI jar. It wraps the same single-card / book pipeline as the CLI's `build`
command, just with Maven parameters instead of picocli options.

## Add the plugin

The plugin shares the parent's version. The parent POM version:

{% fragment "../../../pom.xml:version-declaration" %}

```xml
<plugin>
  <groupId>dev.noregressions.paperband</groupId>
  <artifactId>paperband-maven-plugin</artifactId>
  <version>0.0.1</version>
  <executions>
    <execution>
      <goals><goal>build</goal></goals>
      <configuration>
        <input>${project.basedir}/guide</input>
        <output>${project.build.directory}/guide.pdf</output>
      </configuration>
    </execution>
  </executions>
</plugin>
```

The `build` goal's default phase is `process-resources`; override `<phase>` in the
`<execution>` if you want the PDF built later (e.g. `package`).

## Configuration

| Parameter | Property | Default | Notes |
|---|---|---|---|
| `input` | `paperband.input` | *(required)* | Markdown file (single card) or directory (book). Relative paths resolve against the module's basedir. |
| `output` | `paperband.output` | *(required)* | Output PDF file. |
| `renderer` | `paperband.renderer` | `playwright` | See Renderers in the Rendering section. |
| `target` | `paperband.target` | `pdf-a4` | Build target, e.g. `pdf-a4`, `pdf-6x9`. |
| `pageSize` | `paperband.pageSize` | `a4` | Page size slug, e.g. `a4`, `letter`, `6x9`. |
| `layout` | `paperband.layout` | *(context default)* | Layout template override. |
| `theme` | `paperband.theme` | *(book's `theme:`)* | Named theme; overrides `paperband.yaml`. |
| `themeDir` | `paperband.themeDir` | — | User theme directory, checked before built-ins. |
| `skip` | `paperband.skip` | `false` | Skip the goal without failing the build. |

Every parameter also has a `-D` property, so a bound execution can be overridden from the
command line without editing the `pom.xml`.

## Run it

Bound in your `pom.xml`, it runs with the rest of the build:

```bash
mvn install
```

Or invoke the goal directly without binding it to a lifecycle phase at all — useful for a
one-off render, or for CI steps that shouldn't produce a PDF on every build:

```bash
mvn paperband:build -Dpaperband.input=guide -Dpaperband.output=guide.pdf
```

`paperband` is the plugin's goal prefix, derived from the `paperband-maven-plugin`
artifactId, so that short form works; the fully-qualified
`dev.noregressions.paperband:paperband-maven-plugin:build` works too.

## What's not supported yet

The plugin depends only on the library modules (`core`, `cards`, `config`, `layout`,
`include`, `render-playwright`) — deliberately not on `cli`, whose shaded jar bundles
picocli and Playwright as a console entry point. A few CLI-only features currently live in
`cli` itself rather than a shared library module, so the plugin doesn't have them yet:

- Watermarking (`--watermark` and friends)
- `--select` / multi-edition publishing
- Page-count reporting and enforcement (`--report-pages`, `--max-pages-per-card`)
- Debug HTML emission (`--emit-html`), external include escape hatches

## Watch Out

Like the CLI, the `playwright` renderer needs headless Chromium on first use — see the
Watch Out in Quickstart. A Maven build with no internet access (an offline CI runner, say)
needs Chromium pre-cached before the goal runs, exactly as with the CLI.
