---
sections: true
---

# Paperband

Paperband is a Maven plugin that renders one set of Markdown files as a print-ready PDF, a
static site and a slide deck. It is intended for content published in more than one form,
such as a guide that is both printed and browsed, or a runbook kept on a wiki and in an
onboarding pack. All outputs are rendered from the same files.

New to Paperband? [Your First Book](card:your-first-book) builds a book step by step, from the
first PDF to a site and a slide deck. To set up a real project, choose one of the two
setups below, based on where the Markdown lives. Both need JDK 21+ and Maven 3.8+.

## :folder-plus: Start a new book {.path}

The book is in the Maven project at `src/main/paperband/`, where the plugin finds it by
convention, as Maven finds `src/main/java`. The archetype creates the project:

```bash
mvn archetype:generate \
  -DarchetypeGroupId=dev.noregressions.paperband \
  -DarchetypeArtifactId=paperband-archetype \
  -DarchetypeVersion=0.1.3 \
  -DgroupId=com.example -DartifactId=my-guide
cd my-guide
mvn package
```

`mvn package` writes `target/my-guide.pdf` and a static site in `target/site/`. Cards
are `.md` files in `src/main/paperband/content/`.

[Start a new book →](card:start-a-new-book)

## :folder-open: Use the Markdown you already have {.path}

The Markdown already exists, for example in a `docs/` folder, one readme per service, or
files another tool also reads, and stays where it is. Add the plugin to the project's POM
and set the content directory:

```xml
<plugin>
  <groupId>dev.noregressions.paperband</groupId>
  <artifactId>paperband-maven-plugin</artifactId>
  <version>0.1.3</version>
  <configuration>
    <content>docs</content>
  </configuration>
</plugin>
```

Then build. Markdown without frontmatter builds as is:

```bash
mvn paperband:build \
  -Dpaperband.output=target/docs.pdf
```

[Use the Markdown you already have →](card:use-existing-markdown)

## What else it does {.features}

- **Three outputs from one source.** A PDF with a table of contents, page-numbered
  cross-references and an index; a static site with a sidebar; a 16:9 deck as a PDF or a
  real `.pptx`. See [Slides](card:slides).
- **Source excerpts.** `{% fragment %}` includes a named region of a source file at build
  time. See [Includes](card:includes).
- **Diagrams.** Mermaid in the browser, PlantUML server-side. See
  [Card Structure](card:card-structure).
- **Icons.** `:name:` draws one of 2,000 bundled icons, or your own. See [Icons](card:icons).
- **Tables from data.** Rows as YAML, markup from a loop, a print layout of its own. See
  [Tables from Data](card:tables-from-data).
- **Variants of one source.** Branch content on the output ([Targets](card:targets)),
  group cards along axes you declare ([Book Configuration](card:book-configuration#axes)), and
  publish several editions from one `publication:` block
  ([Maven Plugin](card:maven-plugin#the-publish-goal)).
- **Build-time checks.** A broken cross-reference, an unknown icon, or a card over its page
  budget fails the build. See
  [Page Enforcement](card:page-enforcement).
- **Themes.** Eleven bundled themes, and your own CSS on top of any of them. See
  [Themes](card:themes).

{% if output == 'site' %}
## Downloads {.downloads}

This guide is built with Paperband. The site, the PDF and the deck are rendered from the
same cards on every push.

- [guide.pdf](guide.pdf): the whole guide as a printed book, with contents and index
- [paperband-intro.pptx](paperband-intro.pptx): two slides, built by the pptx renderer
- [llms.txt](llms.txt): the whole reference condensed into one file for an LLM
- [Source on GitHub](https://github.com/noregressions/paperband): issues, and this guide's Markdown
- [Maven Central](https://central.sonatype.com/artifact/dev.noregressions.paperband/paperband-maven-plugin): latest release 0.1.3, Apache 2.0
{% endif %}
