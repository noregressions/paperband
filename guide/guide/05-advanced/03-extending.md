---
id: extending
oneliner: "Add renderers via ServiceLoader; content providers, fragment processors, and themes too."
---

# Extending Paperband

Paperband has four extension points: PDF renderers, include content providers, include
fragment processors, and themes. They differ in how they're discovered — renderers are
true `ServiceLoader` SPIs found from the classpath; the two include interfaces are
registered explicitly in code today; themes are just directories.

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

With the jar on the classpath, `pagewright renderers` lists it and `--renderer prince`
selects it — `name()` is the selector, matched case-sensitively. `canRender` and
`isAvailable` have sensible defaults (`true`); override `isAvailable` when the backend
needs an external binary, so the `renderers` table can say so.

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
passed via `--theme-dir`. See Themes in the Rendering section for the full walkthrough,
including template overrides.
