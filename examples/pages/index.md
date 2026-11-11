Markdown to web, print and slides {! label}

# Paperband

Paperband turns Markdown into laid-out documents: a web site, fixed-size pages in HTML, and PDF.
The same sources feed all three. A manifest says what goes in and what comes out; a layout says
where the parts of a page go.

Everything linked from this page, and the page itself, was built by Paperband from the
[examples](https://github.com/noregressions/paperband/tree/main/examples) in its repository.

## See it

| Output | What it shows |
|--------|---------------|
| [Showcase](showcase.html) | Every example's Markdown beside its rendered result |
| [Handbook, PDF](handbook.pdf) | A few examples as A4 pages, measured and fitted |
| [Handbook, paged HTML](handbook.html) | The same pages in the browser |
| [Site, side navigation](site/index.html) | A page per file, the contents beside each one |
| [Site, navigation bar](site-top/index.html) | The same site with a bar of dropdowns |
| [Forest deck, PDF](forest/deck.pdf) | The `forest` theme as slides, one per layout it brings |

## Use it

Paperband runs as a Maven plugin and needs Java 21. The
[README](https://github.com/noregressions/paperband#readme) has a first build, PDF output,
layouts and themes.

Working with an AI assistant? Point it at [llms.txt](llms.txt), or give it the whole
[guide for language models](llms-full.txt): the syntax for manifests, layouts, markers and
themes in one file.

[Paperband on GitHub](https://github.com/noregressions/paperband) · Apache License 2.0 {! footer}
