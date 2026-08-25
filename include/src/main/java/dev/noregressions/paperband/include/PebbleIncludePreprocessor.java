package dev.noregressions.paperband.include;

import dev.noregressions.paperband.cards.MarkdownPreprocessor;
import dev.noregressions.paperband.pebble.LenientMap;
import dev.noregressions.paperband.pebble.LenientMapExtension;
import dev.noregressions.paperband.pebble.MaskedRegions;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.error.LoaderException;
import io.pebbletemplates.pebble.error.PebbleException;
import io.pebbletemplates.pebble.loader.DelegatingLoader;
import io.pebbletemplates.pebble.loader.FileLoader;
import io.pebbletemplates.pebble.loader.Loader;
import io.pebbletemplates.pebble.template.PebbleTemplate;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Pre-flexmark hook that runs a single real Pebble parse/render pass over a
 * card's raw markdown, handling three things at once:
 * <ul>
 *   <li>{@code {% fragment %}} tags — resolved via {@link ContentProvider}/
 *       {@link FragmentProcessor}, exactly as before (only the outer syntax
 *       and parsing layer moved from a hand-rolled regex scanner to Pebble's
 *       own lexer/parser via {@link FragmentExtension});</li>
 *   <li>{@code vars} — the book/folder-level {@code paperband.yaml} cascade
 *       (including {@code BuiltInVars} built-in
 *       vars) is exposed as a template variable, so {@code {{ vars.x }}}
 *       and real {@code {% if vars.x %}}/{@code {% for %}} conditionals work
 *       directly in card markdown; and</li>
 *   <li>{@code {% include %}}/{@code {% import %}} — the engine's loader is
 *       rooted at the book's {@code layouts/} directory (see
 *       {@link #snippetLoader}), so a card can pull in a reusable Pebble
 *       snippet or macro library, parameterised via {@code with {...}} and
 *       with {@code vars} in scope. An included file is a live template — its
 *       Pebble constructs evaluate — where a {@code {% fragment %}} splices
 *       content verbatim; the two are complementary, not interchangeable.
 *       Note the masking below covers only the card itself: a fenced example
 *       of Pebble syntax <em>inside an included snippet</em> needs
 *       {@code {% verbatim %}}.</li>
 * </ul>
 *
 * <h2>Why these can't be two separate passes</h2>
 * <p>They used to be: a fragment-only pass followed by a separate vars-only
 * pass (in {@code cards}). That's broken, and not in a subtle way
 * — a real Pebble parse evaluates <em>every</em> construct it finds, not just
 * the ones the caller cares about. The fragment-only pass, running with no
 * {@code vars} in its context, would parse {@code {{ vars.product_name }}}
 * as a real print expression, resolve the undefined root variable {@code vars}
 * to null (non-strict mode), and print empty — permanently, before the vars
 * pass ever got a chance to see the original text. Likewise {@code {% if
 * vars.show_advanced %}} would evaluate {@code null} as falsy and silently
 * drop the whole conditional body. Splitting "evaluate the fragment tag" from
 * "evaluate vars/conditionals" into two passes over the same Pebble-syntax
 * document doesn't compose — whichever pass runs first destroys the other's
 * input. So both live in one engine, one parse, one {@code evaluate} call.
 *
 * <h2>Why mask first</h2>
 * <p>Pebble's lexer has no notion of markdown structure: unlike the old
 * regex scanner (which skipped frontmatter, fenced code, and inline code
 * spans on purpose), handing a card's raw body to Pebble unmodified would try
 * to evaluate any {@code {{ }}}/{@code {% %}}-looking text anywhere in it,
 * including inside a fenced example of the very syntax this class implements
 * (see {@code guide/guide/02-authoring/03-includes.md}, which
 * shows {@code {% fragment %}} as a literal example nine times). {@link
 * MaskedRegions} protects frontmatter, fenced code, and inline code spans by
 * swapping them for inert placeholder tokens before Pebble ever sees the
 * text, restored verbatim afterward.
 *
 * <h2>Leniency</h2>
 * <p>{@code vars} is wrapped in {@link LenientMap} — exactly like the
 * final-render {@code LayoutEngine} model wraps the same map — so
 * {@code {% if vars.optional %}} guards work even when {@code optional} was
 * never declared anywhere in the book, consistent with how {@code vars}
 * behaves everywhere else in Paperband.
 *
 * <p>One consequence worth knowing: any other stray {@code {{ }}}/
 * {@code {% %}} outside a masked region is no longer silently ignored the
 * way the old regex scanner (which only recognised the exact
 * {@code {{#include}}} shape) left it — it now has to parse as valid Pebble
 * syntax. Legitimate content that happens to look like Pebble syntax outside
 * a code fence needs Pebble's own {@code {% verbatim %}} tag.
 */
public final class PebbleIncludePreprocessor implements MarkdownPreprocessor {

    private final Map<String, ContentProvider> providers;
    private final Map<String, FragmentProcessor> processors;
    private final Path bookRoot;
    private final Map<String, Map<String, Object>> providerConfigs;
    private final Map<String, Object> vars;

    /**
     * @param providers       map of provider-name → provider; {@code file} is expected
     * @param processors      map of return-type → processor; {@code code, markdown, html, text} are expected
     * @param bookRoot        absolute path to the book root, or null for single-card builds
     * @param providerConfigs per-provider config blocks from {@code paperband.yaml};
     *                        empty map if none configured
     * @param vars            resolved {@code vars} map for the card being processed (varies per
     *                        card via the folder-yaml axis cascade — construct a fresh instance
     *                        per card rather than reusing one across a whole book); {@code null}
     *                        is treated as empty
     */
    public PebbleIncludePreprocessor(
            Map<String, ContentProvider> providers,
            Map<String, FragmentProcessor> processors,
            Path bookRoot,
            Map<String, Map<String, Object>> providerConfigs,
            Map<String, Object> vars) {
        this.providers = Map.copyOf(providers);
        this.processors = Map.copyOf(processors);
        this.bookRoot = bookRoot;
        this.providerConfigs = (providerConfigs == null) ? Map.of() : Map.copyOf(providerConfigs);
        this.vars = (vars == null) ? Map.of() : vars;
    }

    @Override
    public String process(String markdown, Path sourceFile) {
        if (markdown == null || markdown.isEmpty()) return markdown;

        MaskedRegions.Masked masked = MaskedRegions.substitute(markdown);

        Path layouts = layoutsDir(bookRoot, sourceFile);
        FragmentExtension fragmentExtension =
                new FragmentExtension(providers, processors, sourceFile, bookRoot, providerConfigs);
        PebbleEngine engine = new PebbleEngine.Builder()
                .extension(fragmentExtension)
                .extension(new LenientMapExtension())
                .loader(snippetLoader(layouts))
                .strictVariables(false)
                .autoEscaping(false)
                .build();

        String rendered;
        try {
            PebbleTemplate tmpl = engine.getLiteralTemplate(masked.text());
            StringWriter out = new StringWriter();
            tmpl.evaluate(out, Map.of("vars", LenientMap.of(vars)));
            rendered = out.toString();
        } catch (IncludeException e) {
            throw e; // already carries sourceFile + a clear message
        } catch (LoaderException e) {
            // An {% include %}/{% import %} named a template the loader can't
            // find. Pebble's own message names the missing template; add where
            // we looked and the resolution rule, since neither is guessable.
            throw new IncludeException(
                    sourceFile + ": " + e.getPebbleMessage()
                            + " — {% include %} and {% import %} names resolve against the book's "
                            + "layouts/ directory (" + layouts + "), with .html appended when the "
                            + "name has no extension.",
                    sourceFile, e);
        } catch (PebbleException e) {
            throw new IncludeException(
                    sourceFile + ": template syntax error while evaluating fragments/vars/conditionals"
                            + (e.getLineNumber() != null ? " at line " + e.getLineNumber() : "")
                            + ": " + e.getPebbleMessage()
                            + ". If this is a literal example of Pebble syntax rather than a real "
                            + "directive, wrap it in a fenced code block, an inline code span, or {% verbatim %}.",
                    sourceFile, e);
        } catch (IOException e) {
            // StringWriter never throws; keep the compiler happy without hiding a real bug.
            throw new IncludeException(sourceFile + ": unexpected I/O error rendering fragments", sourceFile, e);
        } catch (StackOverflowError e) {
            // Pebble has no cycle detection: a snippet that {% include %}s
            // itself (directly or around a loop of snippets) recurses until
            // the stack runs out. Name the card rather than letting a bare
            // StackOverflowError with a thousand-frame trace speak for it.
            throw new IncludeException(
                    sourceFile + ": include recursion overflowed the stack — a layouts/ snippet "
                            + "{% include %}s itself, directly or via a cycle.",
                    sourceFile, null);
        }

        return masked.restore(rendered);
    }

    /**
     * The directory {@code {% include %}}/{@code {% import %}} names resolve
     * against: the book's {@code layouts/} directory — the same place every
     * other declared template lives (see {@code NamedTemplates.LAYOUTS_DIR}).
     * A single-card build with no book root falls back to a {@code layouts/}
     * beside the card, so the mechanism still works outside a full book.
     */
    private static Path layoutsDir(Path bookRoot, Path sourceFile) {
        Path base = bookRoot != null ? bookRoot
                : sourceFile != null ? sourceFile.toAbsolutePath().getParent()
                : Path.of("");
        return base.resolve("layouts").toAbsolutePath().normalize();
    }

    /**
     * Loader for {@code {% include %}}/{@code {% import %}} in card markdown,
     * rooted at {@code layouts/}. Two spellings resolve: a bare name gets
     * {@code .html} appended ({@code "snippets/warning"} →
     * {@code snippets/warning.html}, matching how {@code LayoutEngine} loads
     * declared templates), and a name with an extension is taken as the file
     * on disk ({@code "snippets/note.md"}). Always set explicitly: the
     * builder's default loader would resolve names against the classpath and
     * the JVM's working directory, neither of which is the book.
     *
     * <p>Pebble 4.1+ requires the (absolute) prefix at construction time and
     * rejects paths climbing out of it — part of the CVE-2025-1686 traversal
     * fix, and exactly the containment wanted here.
     */
    private static Loader<?> snippetLoader(Path layouts) {
        String prefix = layouts.toString() + "/";
        FileLoader named = new FileLoader(prefix);
        named.setSuffix(".html");
        FileLoader exact = new FileLoader(prefix);
        return new DelegatingLoader(List.of(named, exact));
    }

    /** Map a media type to the default processor name. */
    static String defaultProcessorFor(String mediaType) {
        if (mediaType == null) return "text";
        if (mediaType.equals("text/markdown")) return "markdown";
        if (mediaType.equals("text/html")) return "html";
        if (mediaType.equals("text/plain")) return "text";
        // text/x-java, text/x-python, ... → code
        if (mediaType.startsWith("text/x-")) return "code";
        return "text";
    }

    /** Convenience for building a registered map from a list of providers. */
    public static Map<String, ContentProvider> indexProviders(List<ContentProvider> providers) {
        Map<String, ContentProvider> out = new java.util.LinkedHashMap<>();
        for (ContentProvider p : providers) {
            if (out.put(p.name(), p) != null) {
                throw new IllegalArgumentException("duplicate content provider name: " + p.name());
            }
        }
        return out;
    }

    /** Convenience for building a registered map from a list of processors. */
    public static Map<String, FragmentProcessor> indexProcessors(List<FragmentProcessor> processors) {
        Map<String, FragmentProcessor> out = new java.util.LinkedHashMap<>();
        for (FragmentProcessor p : processors) {
            if (out.put(p.name(), p) != null) {
                throw new IllegalArgumentException("duplicate fragment processor name: " + p.name());
            }
        }
        return out;
    }
}
