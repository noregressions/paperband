package dev.noregressions.paperband.model;

import dev.noregressions.paperband.render.PageSpec;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Per-card render context, produced by walking the {@code paperband.yaml}
 * cascade from book root down to the card's directory.
 *
 * @param book          the book this card belongs to
 * @param cssChain      ordered list of CSS files to apply (book global &rarr; folder &rarr; ... &rarr; card)
 * @param vars          merged variables from all levels (later overrides earlier)
 * @param layout        Pebble template path; null if not specified anywhere
 * @param target        current build target, e.g. {@code "pdf-a4"}, {@code "pdf-6x9"}, {@code "web"}
 * @param size          current page size, e.g. {@code "A4"}, {@code "6x9"}
 * @param pageSpec      resolved page geometry: the plugin's {@code <pageSize>} preset, with any
 *                      {@code vars.page} yaml override layered on top (see
 *                      {@code dev.noregressions.paperband.render.PageConfigResolver}). This is what
 *                      the build should pass to the renderer, in place of switching on {@code size}
 *                      itself, so a {@code page:} override always takes effect.
 * @param fontScale     multiplier for the theme's CSS type-scale dial, or null when nothing
 *                      should override it (a named preset with no explicit {@code page.fontScale}
 *                      keeps its own curated theme rule; see {@code PageConfigResolver}).
 */
public record RenderContext(
        BookConfig book,
        List<Path> cssChain,
        Map<String, Object> vars,
        Path layout,
        String target,
        String size,
        PageSpec pageSpec,
        Double fontScale
) {

    public RenderContext {
        cssChain = cssChain == null ? List.of() : List.copyOf(cssChain);
        vars     = vars     == null ? Map.of()  : Map.copyOf(vars);
    }

    /**
     * A copy of this context carrying a different {@link BookConfig}, leaving
     * every per-card field alone. Used where the book-level config is
     * amended after the cascade has already run — e.g. injecting parts
     * declared outside the root {@code paperband.yaml} (see
     * {@link BookConfig#withParts}).
     *
     * @param newBook the replacement book config
     * @return a copy carrying {@code newBook}
     */
    public RenderContext withBook(BookConfig newBook) {
        return new RenderContext(newBook, cssChain, vars, layout, target, size, pageSpec, fontScale);
    }

    /** Convenience constructor for call sites that don't resolve page config (defaults to plain A4, no font-scale override). */
    public RenderContext(BookConfig book, List<Path> cssChain, Map<String, Object> vars,
                          Path layout, String target, String size) {
        this(book, cssChain, vars, layout, target, size, PageSpec.a4(), null);
    }
}
