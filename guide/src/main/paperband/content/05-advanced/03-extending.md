---
id: extending
oneliner: "Add renderers and block renderers via ServiceLoader; content providers, fragment processors, and themes too."
---

# Extending Paperband

Paperband has five extension points: PDF renderers, block renderers, include content
providers, include fragment processors, and themes. They differ in how they're discovered —
renderers and block renderers are true `ServiceLoader` SPIs found from the classpath; the
two include interfaces are registered explicitly in code today; themes are just
directories.

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
selects it — `name()` is the selector, matched case-sensitively. `canRender` and
`isAvailable` have sensible defaults (`true`); override `isAvailable` when the backend
needs an external binary, so the `renderers` table can say so.

One capability contract to know about: some page features render through in-page
JavaScript — ` ```mermaid ` diagrams, Prism syntax highlighting — and the bundled
`playwright` renderer waits for the promises page scripts push into
`window.paperbandPending` before snapshotting (see Renderers in the Rendering section).
A renderer whose engine doesn't execute JavaScript will print those blocks as their
unprocessed source; one that does should honour the same wait, or diagrams can race the
snapshot.

## A new block renderer

A block template (`layouts/blocks/<type>.html`) can rearrange the text a fence captured. It
cannot *compute* anything — and a diagram has to be drawn. That is what
`dev.noregressions.paperband.block.BlockRenderer` is for: a fence type whose HTML is
produced by a jar, at build time.

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
      <version>0.1.1</version>
    </dependency>
  </dependencies>
</plugin>
```

`mvn paperband:blocks` then lists every type the build can render and what renders it, and
each build logs the renderers it found. Both exist for the same reason: a fence that came
out as a code block did so because nothing claimed the type, and that is otherwise
invisible.

### What a renderer declares

| Method | Meaning |
|---|---|
| `name()` | identity in diagnostics, and the `vars` key its settings live under |
| `types()` | the fence tags it claims; two renderers claiming one tag fails the build |
| `isAvailable()` | whether the backend works *here* — false makes it decline, not fail |
| `unavailableReason()` | what to do about it, printed by `paperband:blocks` |
| `render(BlockRequest)` | the replacement HTML; `null` declines, an exception fails the build |

`BlockRequest` carries the fence text, its info-line classes and id, the card's whole `vars`
cascade, the card's path — and `config()`, which is `vars.<name>` for this renderer. That
last one is why settings need no new plumbing: they cascade per folder and per card like
every other var.

### Precedence

Three things can claim a fence type, and they are tried in this order:

1. the theme's or the book's own `blocks/<type>.html`,
2. a registered `BlockRenderer`,
3. the bundled `blocks/<type>.html`.

Author beats jar beats default. The first rung is what makes a module safe to install — a
book can override one diagram by hand without uninstalling anything. The third is what lets
a module claim a type paperband already ships (a server-side `mermaid`, say).

### Watch Out

Whatever a renderer returns is re-parsed as HTML and spliced into the card, so it must be
self-contained. Inline SVG is exempt from the content policy (a `fill` inside a picture *is*
the picture, not smuggled presentation) — but `<script>` and `on*` handlers are stripped
from it regardless, and no renderer should be emitting either.

Wrap a picture in `<figure class="diagram">` (or `plantuml`) and the base stylesheet sizes
it for you in both outputs: centred, capped at the column, scaled rather than squashed when
it has to shrink, and never sliced across a page break. A renderer that invents its own
wrapper class gets none of that and has to ask the book for CSS.

## A new content provider

`dev.noregressions.paperband.include.ContentProvider` supplies content to `fragment` include
directives from a new source — git or HTTP, say, alongside the built-in `file` provider.
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

**Registration caveat:** unlike renderers, providers and processors are *not* discovered
from `META-INF/services/` — the include pipeline is wired with explicit lists (see
`Includes.defaultProviders()` / `defaultProcessors()`), so today a new implementation
means adding it to that wiring rather than just dropping a jar on the classpath.
ServiceLoader discovery for these is planned once more than one provider exists.

## A new theme

No code at all: a directory containing a `manifest.txt` and the CSS files it lists,
passed via `<themeDir>`. See Themes in the Rendering section for the full walkthrough,
including template overrides.
