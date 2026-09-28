---
id: key-index
oneliner: "Every configuration key, A–Z: where it goes, its scope, its POM equivalent, its default and an example."
index: [keys, configuration keys, POM parameters]
---

# Key Index

Every key Paperband reads, in one alphabetical list. **Where** is the file or place the
key is written: the book's root `paperband.yaml`, a folder's `paperband.yaml`, the `vars`
map in either, a card's frontmatter, a section body (`_section.md`), or the POM (a plugin
parameter; each also has a `-Dpaperband.<name>` property). A key that can appear in more
than one kind of place has one row per place. **Scope** is who the value applies to; see
[Configuration Cascade](card:configuration-cascade). The last column links to the card
that explains the key.

Other frontmatter keys are free-form and available to templates as `card.frontmatter.<key>`.
Any `vars` key can be read in a card body as `vars.<key>`; the `vars` rows below are the
ones the engine itself reads.

| Key | Where | Scope | POM equivalent | Default | Example | See |
|---|---|---|---|---|---|---|
| `author` | vars | Book | `<book><author>`, `<book><authors>` | — | `vars: { author: "Platform Team" }` | [Book Configuration](card:book-configuration) |
| `axes` | root yaml | Book | `<book><axes>` | — | `axes: [{ name: tier, values: [{ id: 1, label: Critical }] }]` | [Book Configuration](card:book-configuration#axes) |
| `axes.dividers` | root yaml | Book | — | `true` | `axes: [{ name: tier, dividers: false }]` | [Book Configuration](card:book-configuration#axes) |
| `axis` | folder yaml | Card | — | — | `axis: { tier: 2 }` | [Configuration Cascade](card:configuration-cascade) |
| `back` | root yaml | Book | `<book><back>` | — | `back: { template: back }` | [Book Configuration](card:book-configuration) |
| `<book>` | POM | Build | — | — | `<book><root>…</root><sections>…</sections></book>` | [Maven Plugin](card:maven-plugin) |
| `<bookDirectory>` | POM | Build | — | — | `<bookDirectory>${project.basedir}/guide</bookDirectory>` (publish) | [Maven Plugin](card:maven-plugin#the-publish-goal) |
| `<byPages>` | POM | Build | — | `false` | `-Dpaperband.byPages=true` (pages) | [Page Enforcement](card:page-enforcement) |
| `cards` | `_section.md` | Section | — | `false` | `cards: true` | [Book Configuration](card:book-configuration) |
| `cardSchema` | root yaml | Book | — | — | `cardSchema: { frontmatter: [id, title], sections: [...] }` | [Configuration Reference](card:configuration-reference) |
| `<cardsOnly>` | POM | Build | — | `false` | `-Dpaperband.cardsOnly=true` (pages) | [Page Enforcement](card:page-enforcement) |
| `<clean>` | POM | Build | — | `false` | `<clean>true</clean>` (site) | [Maven Plugin](card:maven-plugin) |
| `<content>` | POM | Build | — | `${home}/content` | `<content>docs</content>` | [Use Existing Markdown](card:use-existing-markdown) |
| `contentPolicy` | vars | Card | — | `clean` | `vars: { contentPolicy: strict }` | [Card Structure](card:card-structure) |
| `copyButtons` | vars | Card | — | on | `vars: { copyButtons: false }` | [Card Structure](card:card-structure) |
| `cover` | root yaml | Book | `<book><cover>` | — | `cover: { image: images/cover.png }` | [Book Configuration](card:book-configuration) |
| `cover.fullPage` | root yaml | Book | `<book><cover><fullPage>` | `false` | `cover: { image: c.png, fullPage: true }` | [Book Configuration](card:book-configuration) |
| `css` | root yaml, folder yaml | Card | `<stylesheets>` (a separate, later layer) | — | `css: [styles/book.css]` | [Themes](card:themes) |
| `<editions>` | POM | Build | — | every edition | `-Dpaperband.editions=mini` (publish) | [Maven Plugin](card:maven-plugin#the-publish-goal) |
| `effort` | frontmatter | Card | — | — | `effort: S` | [Frontmatter Reference](card:frontmatter-reference) |
| `<emitHtml>` | POM | Build | — | — | `-Dpaperband.emitHtml=target/book.html` | [Maven Plugin](card:maven-plugin) |
| `<emitHtmlDirectory>` | POM | Build | — | — | `<emitHtmlDirectory>target/html</emitHtmlDirectory>` (publish) | [Maven Plugin](card:maven-plugin#the-publish-goal) |
| `<externalIncludeDirs>` | POM | Build | — | — | `<externalIncludeDirs><externalIncludeDir>${project.basedir}/..</externalIncludeDir></externalIncludeDirs>` | [Includes](card:includes) |
| `<externalIncludeFiles>` | POM | Build | — | — | `<externalIncludeFiles><externalIncludeFile>../pom.xml</externalIncludeFile></externalIncludeFiles>` | [Includes](card:includes) |
| `footer` | root yaml | Book | `<book><footer>` | — | `footer: { template: footer }` | [Book Configuration](card:book-configuration) |
| `header` | root yaml | Book | `<book><header>` | — | `header: { template: header }` | [Book Configuration](card:book-configuration) |
| `<home>` | POM | Build | — | `src/main/paperband` | `<home>${project.basedir}/src/main/handbook</home>` | [Place a Book in a Project](card:place-a-book) |
| `icons` | vars | Book | — | on | `vars: { icons: false }` | [Icons](card:icons) |
| `id` | frontmatter | Card | — | the card's path, slugified | `id: install` | [Frontmatter Reference](card:frontmatter-reference) |
| `ignore` | folder yaml | Folder | `<excludes>` | — | `ignore: [drafts/**]` | [Organising Content](card:organising-content) |
| `include` | folder yaml | Folder | `<includes>` | — | `include: [02-install, 01-intro]` | [Organising Content](card:organising-content) |
| `index` | frontmatter | Card | — | — | `index: [install, agent]` | [Frontmatter Reference](card:frontmatter-reference) |
| `index` | vars | Book | `<book><index>` | off | `vars: { index: auto }` | [TOC and Index](card:toc-and-index) |
| `indexStop` | vars | Book | — | — | `vars: { indexStop: [guide, file] }` | [TOC and Index](card:toc-and-index) |
| `indexTitle` | vars | Book | — | `Index` | `vars: { indexTitle: "Index of terms" }` | [TOC and Index](card:toc-and-index) |
| `<input>` | POM | Build | — | — | `-Dpaperband.input=path/to/card.md` | [Maven Plugin](card:maven-plugin) |
| `landing` | `_section.md` | Section | `<landingPage>` | `true` | `landing: false` | [Book Configuration](card:book-configuration) |
| `landing` | folder yaml | Folder | `<landingTemplate>` | `default` | `landing: { template: minimal }` | [Book Configuration](card:book-configuration) |
| `<layout>` | POM | Build | `layout` (folder yaml) | — | `<layout>wide-card</layout>` | [Maven Plugin](card:maven-plugin) |
| `layout` | folder yaml | Card | `<layout>` | — | `layout: wide-card.html` | [Configuration Reference](card:configuration-reference) |
| `<layouts>` | POM | Build | — | `${home}/layouts` | `<layouts>book/templates</layouts>` | [Maven Plugin](card:maven-plugin) |
| `<margins>` | POM | Build | `page.margins` (wins) | the page size's own | `<margins>0</margins>` | [Maven Plugin](card:maven-plugin) |
| `max_pages` | frontmatter | Card | — | — | `max_pages: 1` | [Page Enforcement](card:page-enforcement) |
| `<maxPagesPerCard>` | POM | Build | `vars.maxPagesPerCard` | — | `<maxPagesPerCard>2</maxPagesPerCard>` | [Page Enforcement](card:page-enforcement) |
| `maxPagesPerCard` | vars | Book | `<maxPagesPerCard>` (wins) | no limit | `vars: { maxPagesPerCard: 2 }` | [Page Enforcement](card:page-enforcement) |
| `mermaidTheme` | vars | Card | — | `default` | `vars: { mermaidTheme: dark }` | [Card Structure](card:card-structure) |
| `numbered` | `_section.md` | Section | — | `true` | `numbered: false` | — |
| `numbering` | vars | Book | — | off | `vars: { numbering: sequential }` | — |
| `oneliner` | frontmatter | Card | — | — | `oneliner: "Install the agent."` | [Frontmatter Reference](card:frontmatter-reference) |
| `order` | folder yaml | Folder | — | filename order | `order: [introduction, install]` | [Organising Content](card:organising-content) |
| `<output>` | POM | Build | — | required for `build` | `<output>${project.build.directory}/book.pdf</output>` | [Maven Plugin](card:maven-plugin) |
| `<outputDirectory>` | POM | Build | — | — | `<outputDirectory>${project.build.directory}/site</outputDirectory>` (site) | [Maven Plugin](card:maven-plugin) |
| `<outputFile>` | POM | Build | — | log only | `-Dpaperband.outputFile=structure.txt` (structure) | [Maven Plugin](card:maven-plugin) |
| `page.fontScale` | root yaml | Book | — | derived for sheets with no preset | `page: { fontScale: 1.1 }` | [Targets](card:targets) |
| `page.margins` | root yaml | Book | `<margins>` (base only) | the size preset's | `page: { margins: { top: 18, right: 15, bottom: 18, left: 15 } }` | [Book Configuration](card:book-configuration) |
| `page.measure` | root yaml | Book | — | the theme's `--card-max-width` | `page: { measure: none }` | [Targets](card:targets) |
| `page.orientation` | root yaml, folder yaml | Card | — | `portrait` | `page: { orientation: landscape }` | [Configuration Cascade](card:configuration-cascade) |
| `<pageSize>` | POM | Build | `page.size` (wins) | `a4` | `<pageSize>letter</pageSize>` | [Targets](card:targets) |
| `page.size` | root yaml | Book | `<pageSize>` (base only) | `a4` | `page: { size: a5 }` | [Targets](card:targets) |
| `part` | `_section.md` | Section | — | — | `part: 1` | — |
| `part_title` | `_section.md` | Section | — | — | `part_title: "Part One"` | — |
| `<pdf>` | POM | Build | — | — | `-Dpaperband.pdf=target/book.pdf` (pages) | [Page Enforcement](card:page-enforcement) |
| `pdfBookmarks` | vars | Book | — | on | `vars: { pdfBookmarks: false }` | [TOC and Index](card:toc-and-index) |
| `plantuml` | vars | Card | — | — | `vars: { plantuml: { styleFile: styles/diagrams.puml } }` | [Card Structure](card:card-structure) |
| `publication` | root yaml | Book | `<editions>`, `<set>` (per run) | — | `publication: { editions: [{ id: full }] }` | [Maven Plugin](card:maven-plugin#the-publish-goal) |
| `<renderer>` | POM | Build | — | `playwright` | `<renderer>pptx</renderer>` | [Renderers](card:renderers) |
| `<reportPages>` | POM | Build | — | `false` | `<reportPages>true</reportPages>` | [Page Enforcement](card:page-enforcement) |
| `sections` | `_section.md` | Book | — | `false` | `sections: true` (in the content root's `_section.md`) | [Book Configuration](card:book-configuration) |
| `sections` | root yaml, folder yaml | Book | `<book><sections>` | one section per top-level folder | `sections: [{ title: Reference, folders: [04-configuration] }]` | [Organising Content](card:organising-content) |
| `<select>` | POM | Build | — | every card | `-Dpaperband.select=tier=1` | [Maven Plugin](card:maven-plugin) |
| `series` | vars | Book | `<book><vars><series>` | — | `vars: { series: "Runbooks" }` | [Book Configuration](card:book-configuration) |
| `<set>` | POM | Build | — | — | `-Dpaperband.set=defaults.theme=carded` (publish) | [Maven Plugin](card:maven-plugin#the-publish-goal) |
| `sidebar` | root yaml | Book | `<book><sidebar>` | off on the site; on in an `emitHtml` file | `sidebar: true` | [Configuration Reference](card:configuration-reference) |
| `<siteTarget>` | POM | Build | — | `web` | `<siteTarget>web</siteTarget>` (site) | [Targets](card:targets) |
| `<skip>` | POM | Build | — | `false` | `-Dpaperband.skip=true` | [Maven Plugin](card:maven-plugin) |
| `sort` | folder yaml | Folder | `<sort>` | filename order | `sort: tier,-id` | [Organising Content](card:organising-content) |
| `strapline` | vars | Edition | — | — | `vars: { strapline: "Client edition" }` | [Maven Plugin](card:maven-plugin#the-publish-goal) |
| `<stylesheets>` | POM | Build | `css` (applied earlier) | — | `<stylesheets><stylesheet>css/fix.css</stylesheet></stylesheets>` | [Themes](card:themes) |
| `subtitle` | vars | Book | `<book><vars><subtitle>` | — | `vars: { subtitle: "…" }` | [Book Configuration](card:book-configuration) |
| `<target>` | POM | Build | — | `pdf-a4` | `-Dpaperband.target=pdf-6x9` | [Targets](card:targets) |
| `targets` | root yaml, folder yaml | Card | — | — | `targets: [pdf-a4, web]` | [Targets](card:targets) |
| `<theme>` | POM | Build | `theme` (loses) | the book's `theme:` | `<theme>none</theme>` | [Themes](card:themes) |
| `theme` | root yaml | Book | `<theme>` (wins) | no theme | `theme: workshop` | [Themes](card:themes) |
| `<themeDir>` | POM | Build | — | — | `<themeDir>${project.basedir}/themes</themeDir>` | [Themes](card:themes) |
| `title` | folder yaml | Folder | `<section><title>` | the folder name | `title: "Getting Started"` | [Organising Content](card:organising-content) |
| `title` | frontmatter | Card | — | the first H1 | `title: "Installing"` | [Frontmatter Reference](card:frontmatter-reference) |
| `title` | root yaml | Book | `<book><title>` | — | `title: "My Book"` | [Book Configuration](card:book-configuration) |
| `toc` | vars | Book | `<toc/>` in `<book><sections>` | off | `vars: { toc: true }` | [TOC and Index](card:toc-and-index) |
| `tocTitle` | vars | Book | — | `Contents` | `vars: { tocTitle: "In this book" }` | [TOC and Index](card:toc-and-index) |
| `vars` | root yaml, folder yaml | Card | `<book><vars>` (flat strings) | — | `vars: { audience: internal }` | [Vars and Conditionals](card:vars-and-conditionals) |
| `verify` | frontmatter | Card | — | `true` | `verify: false` | [Frontmatter Reference](card:frontmatter-reference) |
| `<watermark>` | POM | Build | `vars.watermark` | — | `<watermark>DRAFT</watermark>` | [Watermarks](card:watermarks) |
| `watermark` | vars | Book | `<watermark>` (wins) | — | `vars: { watermark: DRAFT }` | [Watermarks](card:watermarks) |
| `<watermarkImage>` | POM | Build | `vars.watermark.image` | — | `<watermarkImage>images/logo.png</watermarkImage>` | [Watermarks](card:watermarks) |
| `where` | folder yaml | Folder | `<where>` | — | `order: [{ id: demos, where: "target == 'web'" }]` | [Targets](card:targets) |

## Check

The guide's own build keeps this list honest: a test reads this page and fails if a yaml
key in it is not read by the configuration loader, if a `vars`, frontmatter or
`_section.md` key is not read by the engine or its templates, or if a POM row names a
parameter the plugin doesn't have.
