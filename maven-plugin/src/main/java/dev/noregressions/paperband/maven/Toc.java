package dev.noregressions.paperband.maven;

/**
 * The {@code <toc/>} marker inside {@code <sections>}: render the printed
 * table of contents at this point in the book, between the sections around it.
 *
 * <pre>
 * &lt;sections&gt;
 *   &lt;section&gt;...&lt;/section&gt;
 *   &lt;toc/&gt;              &lt;!-- contents page goes here --&gt;
 *   &lt;section&gt;...&lt;/section&gt;
 * &lt;/sections&gt;
 * </pre>
 *
 * <p>The element carries no configuration — being present is the whole
 * declaration. It still lists every section and card in the book, wherever it
 * sits; only the page's position moves. At most one is allowed: two tables
 * of contents can only disagree about where the one page goes.
 *
 * <p>Extends {@link SectionConfig} because that is how Maven's configurator
 * lets it live inside {@code <sections>}: for each child element the converter
 * first tries a class named after the element in this package (this one),
 * and falls back to the list's declared item type ({@code SectionConfig}) —
 * so {@code <toc/>} maps here while {@code <section>} keeps mapping there,
 * and the shared supertype keeps the {@code List<SectionConfig>} honest.
 * {@link BookLayout#toSpecs} filters these markers out of the section specs
 * and reports the position separately.
 */
public class Toc extends SectionConfig {

    @Override
    public String toString() {
        return "<toc/>";
    }
}
