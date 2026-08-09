/**
 * Paperband's HTML&rarr;PDF rendering SPI.
 *
 * <p>The central abstraction is {@link dev.noregressions.paperband.render.HtmlToPdfRenderer}.
 * Implementations live in their own modules and are discovered at runtime via
 * {@link java.util.ServiceLoader}.
 *
 * <h2>Adding a new renderer</h2>
 * <ol>
 *   <li>Create a new Maven module that depends on {@code core}.</li>
 *   <li>Implement {@link dev.noregressions.paperband.render.HtmlToPdfRenderer} with a unique
 *       {@link dev.noregressions.paperband.render.HtmlToPdfRenderer#name() name}.</li>
 *   <li>Register the implementation in
 *       {@code src/main/resources/META-INF/services/dev.noregressions.paperband.render.HtmlToPdfRenderer}
 *       (one fully-qualified class name per line).</li>
 *   <li>Add the new module as a dependency of {@code cli} (or any other consumer).</li>
 * </ol>
 *
 * <p>The CLI selects a renderer via {@code --renderer <name>}; lookup uses
 * {@link dev.noregressions.paperband.render.RendererRegistry}.
 *
 * <h2>Existing renderers</h2>
 * <ul>
 *   <li>{@code playwright} &mdash; headless Chromium via Playwright Java, the
 *       only renderer paperband ships with. Best CSS; first run downloads
 *       ~300MB Chromium; honours {@code PageSpec.size} and
 *       {@code PageSpec.margins} as the sole source of page geometry (a
 *       theme's own {@code @page} CSS rule is not consulted).</li>
 * </ul>
 *
 * <p>Two earlier renderers (openhtmltopdf, weasyprint) were removed:
 * both read page geometry from {@code @page} CSS rather than
 * {@link dev.noregressions.paperband.render.PageSpec}, which put two disagreeing
 * authorities for size/margins in play depending on which renderer built a
 * given PDF. Keeping a single renderer with a single geometry authority
 * removed a whole class of "looks right in one renderer, wrong in the
 * other" bugs.
 */
package dev.noregressions.paperband.render;
