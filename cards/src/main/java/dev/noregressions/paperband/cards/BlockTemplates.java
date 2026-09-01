package dev.noregressions.paperband.cards;

import dev.noregressions.paperband.block.BlockRenderException;
import dev.noregressions.paperband.block.BlockRenderer;
import dev.noregressions.paperband.block.BlockRendererRegistry;
import dev.noregressions.paperband.block.BlockRequest;
import dev.noregressions.paperband.pebble.LenientMap;
import dev.noregressions.paperband.pebble.LenientMapExtension;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.error.LoaderException;
import io.pebbletemplates.pebble.error.PebbleException;
import io.pebbletemplates.pebble.loader.ClasspathLoader;
import io.pebbletemplates.pebble.loader.DelegatingLoader;
import io.pebbletemplates.pebble.loader.FileLoader;
import io.pebbletemplates.pebble.loader.Loader;
import io.pebbletemplates.pebble.template.PebbleTemplate;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Block templates: the rendering half of a fenced code block, made pluggable.
 *
 * <p>A {@code ```} block does two things — it captures text verbatim, and it
 * implicitly selects a rendering process to turn that text into HTML. The
 * capture stays flexmark's job (the document remains standard markdown to
 * every editor and preview); this class replaces the second half: a block
 * whose type has a template at {@code blocks/<type>.html} renders through
 * that Pebble fragment instead of the default {@code <pre><code>}.
 *
 * <p>Resolution walks the same loader chain as every other template — the
 * theme's templates first, then the book's {@code layouts/blocks/}, then the
 * bundled {@code templates/blocks/} — so a book can define its own block
 * types ({@code ```trace}), override a bundled one, and a theme can restyle a
 * block type <em>structurally</em>, not just with CSS. The bundled semantic
 * types ({@code command}, {@code output}, {@code console}, {@code mermaid})
 * are themselves block templates, riding the same mechanism.
 *
 * <p>The fragment's model:
 * <ul>
 *   <li>{@code content} — the verbatim block text. Auto-escaping is ON:
 *       {@code {{ content }}} emits it safely into markup; {@code | raw} is
 *       a deliberate choice.</li>
 *   <li>{@code type} — the fence's language tag.</li>
 *   <li>{@code classes} — extra classes from info-line attributes
 *       ({@code ```trace {.wide}}), so they can be carried onto the output.</li>
 *   <li>{@code id} — an {@code {#id}} attribute, or null.</li>
 *   <li>{@code vars} — the card's cascade, lenient as everywhere else.</li>
 * </ul>
 *
 * <p>A type with no template anywhere in the chain renders as an ordinary
 * code block, exactly as before — real languages ({@code ```java}) pass
 * through untouched. The corollary: a book that ships
 * {@code layouts/blocks/java.html} deliberately captures every
 * {@code ```java} block, which is powerful and worth doing on purpose.
 *
 * <h2>Renderers, and the order of the three</h2>
 *
 * A template can only rearrange the text it was given. A block whose HTML has
 * to be <em>computed</em> — a diagram drawn at build time — needs a jar, which
 * is what {@link BlockRenderer} is: an optional module, discovered from the
 * classpath. It slots into the middle of the chain:
 *
 * <ol>
 *   <li>the theme's or the book's own {@code blocks/<type>.html},</li>
 *   <li>a registered {@link BlockRenderer} claiming the type,</li>
 *   <li>the bundled {@code blocks/<type>.html},</li>
 *   <li>nothing — an ordinary code block.</li>
 * </ol>
 *
 * <p>Author beats jar beats default. The first rung is what makes a module
 * safe to install: a book that dislikes one diagram overrides that one type
 * with markup of its own and keeps everything else. The third is what lets a
 * module claim a type paperband already ships — a server-side {@code mermaid},
 * say — without paperband having to give the type up.
 *
 * <p>Which rung a type is on is a question worth being able to ask, so
 * {@code mvn paperband:blocks} prints the answer.
 */
public final class BlockTemplates {

    /** The template directory name, under layouts/ (book) and templates/ (bundled). */
    public static final String DIR = "blocks";

    private final PebbleEngine engine;

    /** Types looked up and not found, so a book full of ```java doesn't re-probe the loader per block. */
    private final Set<String> missing = new HashSet<>();

    /** The optional modules that render a type in code. Never null; empty is the norm. */
    private final BlockRendererRegistry renderers;

    /** The two authored rungs of the chain, kept for the "does the author override this?" probe. */
    private final Loader<?> themeLoader;
    private final Path layoutsDir;

    /** Probe results, per type — a filesystem stat per block would be silly. */
    private final Map<String, Boolean> authored = new HashMap<>();

    /** The book's content root, for a renderer setting that names a file. May be null. */
    private final Path bookRoot;

    /** Bundled-only instance: the built-in block types with no book or theme in the chain. */
    private static final BlockTemplates BUNDLED = new BlockTemplates(null, null);

    /** The bundled block types alone — the default when nothing wires a book-aware chain. */
    public static BlockTemplates bundled() {
        return BUNDLED;
    }

    /**
     * Build a resolver over the standard chain with no renderer modules —
     * every type resolves to a template or to nothing.
     *
     * @param themeLoader the theme's template loader, or null
     * @param layoutsDir  the book's layouts directory, or null
     */
    public BlockTemplates(Loader<?> themeLoader, Path layoutsDir) {
        this(themeLoader, layoutsDir, null, null);
    }

    /** As {@link #BlockTemplates(Loader, Path, BlockRendererRegistry, Path)}, with no book root. */
    public BlockTemplates(Loader<?> themeLoader, Path layoutsDir, BlockRendererRegistry renderers) {
        this(themeLoader, layoutsDir, renderers, null);
    }

    /**
     * Build a resolver over the standard chain: {@code themeLoader} (when the
     * theme ships templates), then {@code layoutsDir} (the book's own), then
     * the bundled classpath templates — with {@code renderers} sitting between
     * the authored templates and the bundled ones (see the class javadoc).
     *
     * @param themeLoader the theme's template loader, or null
     * @param layoutsDir  the book's layouts directory, or null
     * @param renderers   the discovered block renderers, or null for none
     * @param bookRoot    the book's content root, for a renderer setting that
     *                    names a file; may be null
     */
    public BlockTemplates(Loader<?> themeLoader, Path layoutsDir, BlockRendererRegistry renderers,
                          Path bookRoot) {
        this.renderers = renderers == null ? BlockRendererRegistry.empty() : renderers;
        this.themeLoader = themeLoader;
        this.layoutsDir = layoutsDir;
        this.bookRoot = bookRoot;
        ClasspathLoader cp = new ClasspathLoader();
        cp.setPrefix("templates/");
        cp.setSuffix(".html");

        List<Loader<?>> chain = new ArrayList<>();
        if (themeLoader != null) chain.add(themeLoader);
        if (layoutsDir != null && Files.isDirectory(layoutsDir)) {
            FileLoader fs = new FileLoader(layoutsDir.toAbsolutePath().normalize() + "/");
            fs.setSuffix(".html");
            chain.add(fs);
        }
        chain.add(cp);

        this.engine = new PebbleEngine.Builder()
                .loader(chain.size() == 1 ? chain.get(0) : new DelegatingLoader(chain))
                .extension(new LenientMapExtension())
                .strictVariables(false)
                .autoEscaping(true)
                .build();
    }

    /**
     * Render a block with no file behind it — a section body, a test.
     *
     * @param type    the fence's language tag
     * @param content the verbatim block text
     * @param classes extra classes from info-line attributes; may be empty
     * @param id      an {@code {#id}} attribute, or null
     * @param vars    the card's resolved vars; may be null
     * @return the rendered HTML, or null when nothing claims the type
     */
    public String render(String type, String content, List<String> classes, String id,
                         Map<String, Object> vars) {
        return render(type, content, classes, id, vars, null);
    }

    /**
     * Render one fenced block, or return null when nothing claims its type —
     * the block then stays an ordinary code block.
     *
     * <p>Resolution order is the one in the class javadoc: an authored
     * template, then a {@link BlockRenderer}, then a bundled template. The
     * renderer lookup is a map hit and comes first; the "did the author
     * override this?" probe only runs for a type some module actually claims,
     * so a book with no modules installed pays nothing for the mechanism.
     *
     * @param type    the fence's language tag
     * @param content the verbatim block text
     * @param classes extra classes from info-line attributes; may be empty
     * @param id      an {@code {#id}} attribute, or null
     * @param vars    the card's resolved vars; may be null
     * @param source  the card the block came from, for error messages and for
     *                a renderer that resolves the block's own includes; may be null
     * @return the rendered HTML, or null when nothing claims the type
     * @throws BlockTemplateException when a template exists but fails, or a
     *         renderer rejects the block
     */
    public String render(String type, String content, List<String> classes, String id,
                         Map<String, Object> vars, Path source) {
        BlockRenderer renderer = renderers.forType(type).orElse(null);
        if (renderer != null && !authorOverrides(type)) {
            return renderWith(renderer, type, content, classes, id, vars, source);
        }
        return renderTemplate(type, content, classes, id, vars);
    }

    /**
     * Does the theme or the book ship its own {@code blocks/<type>.html}?
     *
     * <p>Asked of the two authored loaders directly rather than of the engine,
     * because the engine's chain ends in the bundled templates and cannot tell
     * "the book wrote one" from "paperband ships one" — and that distinction
     * is the whole precedence rule.
     */
    private boolean authorOverrides(String type) {
        return authored.computeIfAbsent(type, t -> {
            if (themeLoader != null && themeLoader.resourceExists(DIR + "/" + t)) return true;
            return layoutsDir != null
                    && Files.isRegularFile(layoutsDir.resolve(DIR).resolve(t + ".html"));
        });
    }

    /** Hand the block to a module, and turn its complaints into build failures. */
    private String renderWith(BlockRenderer renderer, String type, String content,
                              List<String> classes, String id, Map<String, Object> vars,
                              Path source) {
        Map<String, Object> all = vars == null ? Map.of() : vars;
        Object own = all.get(renderer.name());
        Map<String, Object> config = own instanceof Map<?, ?> m ? castConfig(m) : Map.of();
        String html;
        try {
            html = renderer.render(new BlockRequest(
                    type, content, classes == null ? List.of() : classes, id, all, config,
                    bookRoot, source));
        } catch (BlockRenderException e) {
            throw new BlockTemplateException("block renderer '" + renderer.name()
                    + "' rejected this ```" + type + " block: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // A module that throws something else is a bug in the module, but
            // the book author is the one holding the build: name the module.
            throw new BlockTemplateException("block renderer '" + renderer.name()
                    + "' (" + renderer.getClass().getName() + ") failed on this ```" + type
                    + " block: " + e, e);
        }
        // A renderer may decline after looking at the content -- fall through
        // to whatever a template would have done, exactly as if it were absent.
        return html != null ? html : renderTemplate(type, content, classes, id, vars);
    }

    /** Yaml gives us Map<?,?>; the SPI wants Map<String,Object>. Keys stringified, once. */
    private static Map<String, Object> castConfig(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    /** The original path: {@code blocks/<type>.html} anywhere in the loader chain. */
    private String renderTemplate(String type, String content, List<String> classes, String id,
                                  Map<String, Object> vars) {
        if (missing.contains(type)) return null;
        PebbleTemplate template;
        try {
            template = engine.getTemplate(DIR + "/" + type);
        } catch (PebbleException e) {
            if (rootedInLoader(e)) {
                missing.add(type);
                return null;                      // no template: not a block type
            }
            throw new BlockTemplateException("block template blocks/" + type
                    + " failed to parse: " + e.getMessage(), e);
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("content", content);
        model.put("type", type);
        model.put("classes", classes == null ? List.of() : classes);
        model.put("id", id);
        model.put("vars", LenientMap.of(vars == null ? Map.of() : vars));
        StringWriter out = new StringWriter();
        try {
            template.evaluate(out, model);
        } catch (PebbleException | IOException e) {
            throw new BlockTemplateException("block template blocks/" + type
                    + " failed to render: " + e.getMessage(), e);
        }
        return out.toString();
    }

    /** Is this failure "the template does not exist" rather than "it is broken"? */
    private static boolean rootedInLoader(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof LoaderException) return true;
        }
        return false;
    }

    /** A block template that exists but cannot parse or render. */
    public static final class BlockTemplateException extends RuntimeException {
        BlockTemplateException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
