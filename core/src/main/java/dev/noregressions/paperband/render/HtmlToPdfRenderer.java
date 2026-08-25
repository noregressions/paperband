package dev.noregressions.paperband.render;

import java.nio.file.Path;

/**
 * Service Provider Interface for rendering an HTML document to a PDF file.
 *
 * <p>Implementations are discovered at runtime via {@link java.util.ServiceLoader}.
 * To register a new renderer, place its fully-qualified class name in
 * {@code META-INF/services/dev.noregressions.paperband.render.HtmlToPdfRenderer}.
 *
 * <p>Implementations are expected to be safe to use from a single thread but
 * are not required to be thread-safe.
 */
public interface HtmlToPdfRenderer {

    /**
     * Stable, lowercase identifier for this renderer (e.g. {@code "playwright"}).
     * Used by the Maven plugin's {@code <renderer>} parameter and by configuration files.
     */
    String name();

    /**
     * Human-readable description, including any external runtime requirements
     * and known limitations (e.g. unsupported CSS features).
     */
    String description();

    /**
     * Render the given HTML document to {@code output}, overwriting any existing file.
     * Implementations should create parent directories as needed.
     *
     * @throws PdfRenderException if rendering fails for any reason
     */
    void render(HtmlInput input, Path output) throws PdfRenderException;

    /**
     * Optional capability check. Renderers that cannot satisfy a particular
     * request (e.g. unsupported page size, unsupported CSS feature) should
     * return {@code false}. Default is {@code true}.
     */
    default boolean canRender(HtmlInput input) {
        return true;
    }

    /**
     * Optional environment probe. Renderers that depend on external binaries
     * or downloaded assets (browser engines, native libraries, subprocess
     * tools) should report whether they are usable in the current environment.
     * Default is {@code true}.
     */
    default boolean isAvailable() {
        return true;
    }
}
