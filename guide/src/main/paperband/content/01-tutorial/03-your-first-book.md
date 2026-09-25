---
id: your-first-book
oneliner: "From an empty directory to a two-section book with cross-links, a theme, a site sidebar and a slide deck."
index: [tutorial, archetype, first build]
---

# Your First Book

This tutorial builds a small book in seven steps. Each step ends with a result you can
check. It needs a JDK 21+ and Maven 3.8+; see [Before You Start](card:before-you-start).

## 1. First build

Generate a project from the archetype and build it:

```bash
mvn archetype:generate \
  -DarchetypeGroupId=dev.noregressions.paperband \
  -DarchetypeArtifactId=paperband-archetype \
  -DarchetypeVersion=0.1.3 \
  -DgroupId=com.example -DartifactId=my-guide
cd my-guide
mvn package
```

The first build downloads headless Chromium, so it needs internet access once. The log
ends with both outputs:

```
[INFO] Built book …/my-guide/src/main/paperband/content -> …/my-guide/target/my-guide.pdf (renderer=playwright, target=pdf-a4, size=a4, cards=1, blocks=4)
[INFO] Built site …/my-guide/src/main/paperband/content -> …/my-guide/target/site (2 pages, 1 cards)
```

**Check:** open `target/my-guide.pdf` (a cover and one card, "Getting Started") and
`target/site/index.html`.

## 2. Add a card and a section

The starter card, `01-introduction.md`, sits directly in `src/main/paperband/content/`.
A subfolder of `content/` is a section. Create one with a card in it:

```filetree
src/main/paperband/content/
  01-introduction.md
  02-reference/
    paperband.yaml
    01-options.md
```

`02-reference/paperband.yaml` names the section:

```yaml
title: "Reference"
```

`02-reference/01-options.md` is the card:

```markdown
# Options

The options this guide covers.

## Output

The PDF and the site are written to `target/`.
```

Without the folder's `title:`, the section is labelled from the folder name ("02 Reference").

Run `mvn package`. **Check:** the PDF now has four pages, including a divider page reading
"Reference" with a one-entry contents list, and the site has a section page,
`target/site/02-reference.html`. `mvn paperband:structure` lists the result:

```
[INFO] BOOK "my-guide"  [2 cards]
[INFO]   CARD introduction  "Getting Started"  (01-introduction.md)
[INFO]   SECTION 02-reference  "Reference"  [1 cards]
[INFO]     CARD 02-reference-01-options  "Options"  (02-reference/01-options.md)
```

The new card's id, `02-reference-01-options`, is derived from its path.

## 3. Link the two cards

Cross-references use the card's id with a `card:` scheme. Add a line to
`01-introduction.md`, with a deliberate typo:

```markdown
See [Options](card:02-reference-01-option) for what the build produces.
```

Run `mvn package`. **Check:** the build fails and names the link:

```
A card link points at nothing:
  card:02-reference-01-option in 01-introduction.md — no card has that id. Did you mean '02-reference-01-options'?
```

Correct the id to `02-reference-01-options` and rebuild. In the PDF the link jumps to the
card; on the site it points to `cards/02-reference-01-options.html`. See
[Card Structure](card:card-structure) for the full rules.

## 4. Add frontmatter

Give the options card a shorter id and a summary line, in YAML at the top of the file:

```markdown
---
id: options
oneliner: "Every setting the build reads, and its default."
---

# Options
…
```

Run `mvn package`. The build fails, because the link from step 3 still names the old id:

```
card:02-reference-01-options in 01-introduction.md — no card has that id.
```

Change the link to `card:options` and rebuild. **Check:** the oneliner appears under the
card's title in the PDF and on the site, and the card's page is now
`target/site/cards/options.html`. The [Frontmatter Reference](card:frontmatter-reference)
lists every field.

## 5. Switch the theme

The book's appearance comes from its theme, set in `src/main/paperband/paperband.yaml`.
Change `editorial` to `workshop`:

```yaml
title: "my-guide"
theme: workshop

vars:
  subtitle: "Built with Paperband"
```

Run `mvn package`. **Check:** the text changes from a serif to a sans-serif face in both
outputs, and the site's top bar turns dark. `mvn paperband:themes` lists the 11 built-in
themes; see [Themes](card:themes).

## 6. Add the site sidebar

Open `target/site/index.html`: the home page lists the sections, and each card has its
own page with previous and next links. To add a navigation sidebar to every page, add a
top-level key to `paperband.yaml`:

```yaml
sidebar: true
```

Run `mvn package`. **Check:** every site page has a sidebar listing the sections and their
cards. The PDF is unchanged.

## 7. Optional: a slide deck

A deck is a second book with its own home, a 16:9 sheet and the `deck` theme. Create
`src/main/paperband-deck/paperband.yaml`:

```yaml
title: "my-guide deck"
theme: deck
page:
  size: 16x9
  margins: { top: 0, right: 0, bottom: 0, left: 0 }
vars:
  maxPagesPerCard: 1
```

and `src/main/paperband-deck/content/01-overview.md`:

```markdown
---
title: "What the guide covers"
---

- Getting started with the build
- Every option, and its default

## Notes

Mention that the PDF and the site come from the same cards.
```

Add an execution to the plugin in `pom.xml` that builds from that home:

```xml
<execution>
  <id>build-deck</id>
  <phase>package</phase>
  <goals><goal>build</goal></goals>
  <configuration>
    <home>${project.basedir}/src/main/paperband-deck</home>
    <output>${project.build.directory}/my-guide-deck.pdf</output>
  </configuration>
</execution>
```

Run `mvn package`. The log gains a third line:

```
[INFO] Built book …/my-guide/src/main/paperband-deck/content -> …/my-guide/target/my-guide-deck.pdf (renderer=playwright, target=pdf-a4, size=16x9, cards=1, blocks=2)
```

**Check:** `target/my-guide-deck.pdf` has 960×540pt pages: a title slide, then the card as
one slide. The `## Notes` block does not appear on the slide. A slide that runs past one
page fails the build:

```
Page-count check failed: 1 card(s) exceeded their page limit.
  01-overview: pages 2-5 (4 pages, limit 1, from paperband.yaml) — pages 3-5 overflow
```

To write the deck as a PowerPoint file, add the `render-pptx` module to the plugin's
dependencies and set `<renderer>pptx</renderer>`; see [Slides](card:slides).

## Where next

- [Start a New Book](card:start-a-new-book) and [Place a Book in a Project](card:place-a-book):
  the setup options for a book inside a Maven project.
- [Use Existing Markdown](card:use-existing-markdown): point the plugin at docs that already
  exist.
- [I Want To…](card:i-want-to): task-by-task instructions.
- [Troubleshooting](card:troubleshooting): what to run when a build does something
  unexpected.
- [Organising Content](card:organising-content) and [Key Index](card:key-index): ordering,
  sections, and every configuration key.
- [`examples/kitchen-sink`](https://github.com/noregressions/paperband/tree/main/examples/kitchen-sink)
  in the repository: a small book that uses each feature once, with each file noting what
  it shows and which card explains it.
