package dev.noregressions.paperband.maven;

/**
 * The {@code <page>} marker inside {@code <sections>}: render a Pebble
 * template as a full page of the book at this point, between the sections
 * around it.
 *
 * <pre>
 * &lt;sections&gt;
 *   &lt;section&gt;...&lt;/section&gt;
 *   &lt;page&gt;&lt;template&gt;matrix&lt;/template&gt;&lt;/page&gt;
 *   &lt;section&gt;...&lt;/section&gt;
 * &lt;/sections&gt;
 * </pre>
 *
 * <p>The template renders with the <em>whole book model</em> in scope — the
 * same context {@code book.html} sees: {@code cards}, {@code sections},
 * {@code axisGroupings}, {@code book}, {@code vars} — which is exactly what a
 * card can't have: cards load before the model exists. Use it for generated
 * pages (a planning matrix over every card, a per-section summary) rather
 * than authored content, which belongs in a card. The name resolves against
 * the book's {@code layouts/} directory like every other declared template,
 * with {@code .html} appended.
 *
 * <p>Unlike {@code <toc/>} there may be any number of {@code <page>} markers,
 * each naming its own template (or the same one twice, if a book really wants
 * the same generated page in two places).
 *
 * <p>Extends {@link SectionConfig} for the same reason {@link Toc} does: the
 * configurator maps a {@code <sections>} child to a class named after the
 * element in this package first, so {@code <page>} lands here while
 * {@code <section>} keeps falling back to {@code SectionConfig}.
 * {@link BookLayout#toSpecs} filters these markers out of the section specs;
 * {@link BookLayout#pageMarkers} reports their positions and templates.
 */
public class Page extends SectionConfig {

    /** Template name or path, resolved against the book's {@code layouts/} directory. */
    private String template;

    /** @return the declared template name, or null when the marker forgot one */
    public String getTemplate() {
        return template;
    }

    @Override
    public String toString() {
        return "<page>" + (template == null ? "(no template)" : template) + "</page>";
    }
}
