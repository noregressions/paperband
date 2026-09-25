---
id: extending
oneliner: "Add renderers and block renderers via ServiceLoader; content providers, fragment processors, and themes too."
---

# Extending Paperband

Paperband has five extension points: PDF renderers, block renderers, include content
providers, include fragment processors, and themes. Renderers and block renderers are
`ServiceLoader` SPIs discovered from the classpath; the two include interfaces are
registered explicitly in code; themes are directories.

## A new PDF renderer

Implement `dev.noregressions.paperband.render.HtmlToPdfRenderer`:

```java
public final class PrinceRenderer implements HtmlToPdfRenderer {
    @Override public String name() { return "prince"; }
    @Override public String description() { return "Subprocess to the Prince binary."; }
    @Override public void render(HtmlInput input, Path output) throws PdfRenderException {
        // write input.html() somewhere, invoke the engine, produce output
    }
    @Override public boolean isAvailable() { /* probe the binary */ return true; }
}
```

Register it in `META-INF/services/dev.noregressions.paperband.render.HtmlToPdfRenderer`:

```
com.example.render.PrinceRenderer
```

With the jar on the plugin's classpath, `mvn paperband:renderers` lists it and `<renderer>prince</renderer>`
selects it. `name()` is the selector, matched case-sensitively. `canRender`,
`isAvailable` and `producesPdf` default to `true`; override `isAvailable` when the backend
needs an external binary, so the `renderers` table reports it.

Some page features render through in-page JavaScript: ` ```mermaid ` diagrams and Prism
syntax highlighting. The bundled `playwright` renderer waits for the promises page scripts
push into `window.paperbandPending` before snapshotting (see Renderers in the Rendering
section). A renderer whose engine doesn't execute JavaScript prints those blocks as
unprocessed source; one that does should honour the same wait, or diagrams may be captured
before they finish rendering.

### When the output isn't a PDF

The SPI's name is historical; the contract is HTML in, one file out. A renderer that writes
something other than a PDF declares it:

```java
@Override public boolean producesPdf() { return false; }
```

Four passes run after `render` and reopen the output file with PDFBox: page-number
resolution for a printed TOC and index, the full-page-cover splice, the watermark stamp, and
the bookmark outline. With the default `true`, PDFBox is given a file that isn't a PDF and
the build fails after the render has succeeded. The page-budget check (`maxPagesPerCard`)
measures the DOM rather than the finished file, so it still runs for a non-PDF renderer.

The bundled `render-pptx` is the worked example — see Slides in the Rendering section.

## A new block renderer

A block template (`layouts/blocks/<type>.html`) can rearrange the text a fence captured, but
cannot compute anything, such as drawing a diagram.
`dev.noregressions.paperband.block.BlockRenderer` handles that case: a fence type whose HTML
is produced by a jar at build time.

```java
public final class DotBlockRenderer implements BlockRenderer {
    @Override public String name() { return "graphviz"; }
    @Override public String description() { return "Graphviz diagrams, via the dot binary."; }
    @Override public Set<String> types() { return Set.of("dot", "graphviz"); }
    @Override public boolean isAvailable() { return which("dot") != null; }
    @Override public String unavailableReason() { return "dot not on PATH — install graphviz"; }

    @Override public String render(BlockRequest request) {
        // request.content() is the fence text; return the HTML that replaces it,
        // or null to decline and leave it an ordinary code block.
        return "<figure class=\"diagram\">" + runDot(request.content()) + "</figure>";
    }
}
```

Register it the way a PDF renderer is registered, in
`META-INF/services/dev.noregressions.paperband.block.BlockRenderer`, and put the jar on the
plugin's classpath — a `<dependency>` inside the `<plugin>` element, not the project's own
dependencies:

```xml
<plugin>
  <groupId>dev.noregressions.paperband</groupId>
  <artifactId>paperband-maven-plugin</artifactId>
  <dependencies>
    <dependency>
      <groupId>dev.noregressions.paperband</groupId>
      <artifactId>block-plantuml</artifactId>
      <version>0.1.2</version>
    </dependency>
  </dependencies>
</plugin>
```

`mvn paperband:blocks` lists every type the build can render and what renders it, and each
build logs the renderers it found. A fence that renders as a plain code block is one whose
type nothing claimed.

### What a renderer declares

| Method | Meaning |
|---|---|
| `name()` | identity in diagnostics, and the `vars` key its settings live under |
| `types()` | the fence tags it claims; two renderers claiming one tag fails the build |
| `isAvailable()` | whether the backend works *here* — false makes it decline, not fail |
| `unavailableReason()` | what to do about it, printed by `paperband:blocks` |
| `render(BlockRequest)` | the replacement HTML; `null` declines, an exception fails the build |

`BlockRequest` carries the fence text, its info-line classes and id, the card's whole `vars`
cascade, the card's path, and `config()`, which is `vars.<name>` for this renderer. Renderer
settings therefore cascade per folder and per card like any other var.

### Precedence

Three things can claim a fence type, and they are tried in this order:

1. the theme's or the book's own `blocks/<type>.html`,
2. a registered `BlockRenderer`,
3. the bundled `blocks/<type>.html`.

A book can therefore override one type by hand without removing a module, and a module can
claim a type paperband already ships (for example, a server-side `mermaid`).

### Watch Out

A renderer's output is re-parsed as HTML and inserted into the card, so it must be
self-contained. Inline SVG is exempt from the content policy, except that `<script>` and
`on*` handlers are stripped.

Wrap a picture in `<figure class="diagram">` (or `plantuml`) and the base stylesheet sizes
it in both outputs: centred, capped at the column width, scaled proportionally, and kept on
one page. A renderer that uses its own wrapper class gets none of this and needs book CSS.

## A new content provider

`dev.noregressions.paperband.include.ContentProvider` supplies content to `fragment` include
directives from a new source, such as git or HTTP, alongside the built-in `file` provider.
The provider is chosen by a scheme prefix on the reference (`git:some/path@ref`);
references with no scheme go to `file`:

```java
public final class GitContentProvider implements ContentProvider {
    @Override public String name() { return "git"; }
    @Override public Fragment fetch(String reference, IncludeContext ctx)
            throws ContentResolutionException {
        // resolve "repo-relative/path@ref", return its content as a Fragment
    }
}
```

## A new fragment processor

`dev.noregressions.paperband.include.FragmentProcessor` turns a fetched fragment into markdown.
`name()` matches the directive's `as="<type>"` attribute; built-ins are `code`,
`markdown`, `html`, and `text`:

```java
public final class CsvTableProcessor implements FragmentProcessor {
    @Override public String name() { return "csv-table"; }
    @Override public String process(Fragment fragment, IncludeContext ctx) {
        // return a markdown table built from the fragment's content
    }
}
```

Unlike renderers, providers and processors are not discovered from `META-INF/services/`.
The include pipeline uses explicit lists (`Includes.defaultProviders()` /
`defaultProcessors()`), so a new implementation must be added there; a jar on the classpath
is not enough. ServiceLoader discovery is planned once more than one provider exists.

## A new theme

A theme needs no code: it is a directory containing a `manifest.txt` and the CSS files it
lists, passed via `<themeDir>`. See Themes in the Rendering section for the full walkthrough,
including template overrides.
