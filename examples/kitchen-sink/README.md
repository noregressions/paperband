# Kitchen sink

A small Paperband book that uses every major feature once. Each file says what it shows and
links to the guide card that explains it (https://noregressions.github.io/paperband/).

Build it from the repository root with the examples profile, which renders it with the
plugin built in the same run:

    mvn install -Pexamples      # target/kitchen-sink.pdf, target/site/, and the cheat sheet
                                # as target/cheatsheet.pdf and target/cheatsheet-site/

Or build it on its own with `mvn package` in this directory, once the root has been
installed.

The PDF has 12 cards; the site has 13, because one card is site-only.

## Feature map

Paths are relative to `src/main/paperband/`.

| Feature | Where |
|---|---|
| Conventional layout, no `<input>` or `<content>` | `../../../pom.xml`, this directory |
| `pom` packaging for a documentation-only project | `../../../pom.xml` |
| Title, subtitle, author, theme, CSS chain, sidebar | `paperband.yaml` |
| Printed table of contents and back-of-book index (`toc`, `index: auto`) | `paperband.yaml` |
| Watermark on the PDF and the site | `paperband.yaml` (`vars.watermark`) |
| An axis with coloured values | `paperband.yaml` (`axes`), `content/03-levels/` |
| YAML cards through `cardSchema` | `paperband.yaml`, `content/02-authoring/04-yaml-card.yaml` |
| The book's own body (home page, PDF front matter) | `content/_section.md` |
| `ignore:` hiding a draft | `content/paperband.yaml`, `content/01-basics/99-notes.draft.md` |
| Folder sections with a title and `order:` | `content/01-basics/paperband.yaml`, `content/02-authoring/paperband.yaml` |
| A `where:`-gated card (site only) | `content/01-basics/paperband.yaml`, `content/01-basics/03-web-only.md` |
| Section body listing its cards | `content/01-basics/_section.md` |
| Every card frontmatter field | `content/01-basics/01-first-card.md` |
| `card:` cross-links, including to a section anchor | `content/01-basics/01-first-card.md` |
| Blocks: Watch Out, Check | `content/01-basics/02-blocks.md` |
| `command`, `output` and `console` fences | `content/01-basics/02-blocks.md` |
| Numbered steps with `{!step}`, including steps inside a step | `content/01-basics/04-steps.md`, `styles/book.css` |
| A cheat sheet of a chosen set of cards (`<book><includes>`, `<view>cheatsheet</view>`, `{.instructions}`), as a PDF and a site | `../../../pom.xml` (`cheatsheet` and `cheatsheet-site` executions), `content/04-workshop/` |
| A custom block type | `layouts/blocks/note.html`, used in `content/01-basics/02-blocks.md` |
| A fragment filled from a card's content (`block.nodes`, `find`): an "At a glance" box of each step and its command | `layouts/_card-body.html`, `layouts/fragments/step-glance.html`, `styles/book.css` |
| `:name:` icons, bundled and the book's own | `content/01-basics/02-blocks.md`, `icons/ks.svg` |
| `{% fragment %}` from a source file | `content/02-authoring/01-includes.md`, `content/02-authoring/Hello.java` |
| `{% include %}` of a layouts snippet | `content/02-authoring/01-includes.md`, `layouts/snippets/support.html` |
| `{{ vars.x }}` and `{% if %}` | `content/02-authoring/02-templating.md` |
| Different content for the PDF and the site (`output`) | `content/02-authoring/02-templating.md` |
| An HTML card | `content/02-authoring/03-html-card.html` |
| Book CSS for content classes | `styles/book.css` |

The cover is the default text cover, built from the title, subtitle and author.
