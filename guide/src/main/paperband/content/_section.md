---
sections: true
---

# Paperband

Paperband is a Maven plugin that turns one set of Markdown files into a print-ready PDF, a
browsable static site and a slide deck. It's for writing that has to exist more than once:
a guide that is printed and browsed, a runbook that lives on a wiki and in an onboarding
pack, a migration playbook that becomes a conference talk. Written twice, those copies
drift. Written once, they can't.

Both ways in need a JDK 21+ and Maven 3.8+. Pick the one that matches where your Markdown
is going to live.

## :folder-plus: Start a new book {.path}

The book is part of a Maven project, at `src/main/paperband/`, where the plugin finds it
by convention, the way Maven finds `src/main/java`. The archetype sets it all up:

```bash
mvn archetype:generate \
  -DarchetypeGroupId=dev.noregressions.paperband \
  -DarchetypeArtifactId=paperband-archetype \
  -DarchetypeVersion=0.1.2 \
  -DgroupId=com.example -DartifactId=my-guide
cd my-guide
mvn package
```

The PDF lands in `target/`. Add cards as `.md` files under `src/main/paperband/`.

[Start a new book →](card:java-first)

## :folder-open: Use the Markdown you already have {.path}

The words already exist, in a `docs/` folder, a readme per service, or notes another tool
also reads, and they're staying put. Add the plugin to the project's POM and tell it where
to look:

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

Then build it. Plain Markdown builds as it is, with no frontmatter required:

```bash
mvn paperband:build \
  -Dpaperband.output=target/docs.pdf
```

[Use the Markdown you already have →](card:docs-anywhere)

## What else it does {.features}

- **Three outputs from one source.** A PDF with a table of contents, page-numbered
  cross-references and an index; a static site with a sidebar; a 16:9 deck as a PDF or a
  real `.pptx`. See [Slides](card:slides).
- **Examples that can't drift.** `{% fragment %}` quotes a named region of a real source
  file at build time. See [Includes](card:includes).
- **Diagrams.** Mermaid in the browser, PlantUML server-side. See
  [Card Structure](card:card-structure).
- **Icons.** `:name:` draws one of 2,000 bundled icons, or your own. See [Icons](card:icons).
- **Tables from data.** Rows as YAML, markup from a loop, a print layout of its own. See
  [Tables from Data](card:data-tables).
- **One source, several cuts.** Branch content on the output ([Targets](card:targets)),
  group cards along axes you declare ([Book Configuration](card:book-config#axes)), and
  publish several editions from one `publication:` block
  ([Maven Plugin](card:maven-plugin#the-publish-goal)).
- **Checks that fail the build.** A broken cross-reference, an unknown icon, or a card that
  outgrows its page budget stops the build instead of shipping. See
  [Page Enforcement](card:page-enforcement).
- **Themes.** Eleven bundled, or your own CSS on top of any of them. See
  [Themes](card:themes).

{% if output == 'site' %}
## Take it with you {.downloads}

This guide is written in Paperband and built by it: this site, the printed guide and the
deck are all rendered from the same cards on every push.

- [guide.pdf](guide.pdf): the whole guide as a printed book, with contents and index
- [paperband-intro.pptx](paperband-intro.pptx): two slides, built by the pptx renderer
- [llms.txt](llms.txt): the whole reference condensed into one file for an LLM
- [Source on GitHub](https://github.com/noregressions/paperband): issues, and this guide's Markdown
- [Maven Central](https://central.sonatype.com/artifact/dev.noregressions.paperband/paperband-maven-plugin): latest release 0.1.2, Apache 2.0
{% endif %}
