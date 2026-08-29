package dev.noregressions.paperband.cards;

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
 */
public final class BlockTemplates {

    /** The template directory name, under layouts/ (book) and templates/ (bundled). */
    public static final String DIR = "blocks";

    private final PebbleEngine engine;

    /** Types looked up and not found, so a book full of ```java doesn't re-probe the loader per block. */
    private final Set<String> missing = new HashSet<>();

    /** Bundled-only instance: the built-in block types with no book or theme in the chain. */
    private static final BlockTemplates BUNDLED = new BlockTemplates(null, null);

    /** The bundled block types alone — the default when nothing wires a book-aware chain. */
    public static BlockTemplates bundled() {
        return BUNDLED;
    }

    /**
     * Build a resolver over the standard chain: {@code themeLoader} (when the
     * theme ships templates), then {@code layoutsDir} (the book's own), then
     * the bundled classpath templates.
     *
     * @param themeLoader the theme's template loader, or null
     * @param layoutsDir  the book's layouts directory, or null
     */
    public BlockTemplates(Loader<?> themeLoader, Path layoutsDir) {
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
     * Render {@code blocks/<type>} with the block's model, or return null when
     * no template exists for the type — the block then stays an ordinary code
     * block.
     *
     * @param type    the fence's language tag
     * @param content the verbatim block text
     * @param classes extra classes from info-line attributes; may be empty
     * @param id      an {@code {#id}} attribute, or null
     * @param vars    the card's resolved vars; may be null
     * @return the rendered HTML, or null when the type has no template
     * @throws BlockTemplateException when the template exists but fails
     */
    public String render(String type, String content, List<String> classes, String id,
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
