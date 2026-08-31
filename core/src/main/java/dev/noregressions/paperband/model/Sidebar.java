package dev.noregressions.paperband.model;

/**
 * The static site's navigation sidebar: whether it renders, and how it opens.
 *
 * <p>Book scope. The sidebar is structure — it either frames every page of the
 * site or none of them — so it is declared once, by the book, and read from the
 * book's own {@code paperband.yaml} or the POM's {@code <book>} element.
 *
 * <p>It used to be three {@code vars} entries ({@code sidebar},
 * {@code sidebar_collapsed}, {@code sidebar_sections_collapsed}), which put a
 * whole-site decision on a per-card channel: {@code vars} cascade, but the site
 * only ever read the copy belonging to whichever card the build walked first.
 * A folder that set them was therefore either ignored entirely or turned the
 * sidebar on for the entire site, decided by walk order alone. Those spellings
 * still work as deprecated aliases; this is the declaration.
 *
 * @param enabled          render the sidebar at all. Off by default: a book with
 *                         no declared sidebar gets the plain single-column site.
 * @param collapsed        start the sidebar itself collapsed, leaving the reader
 *                         to open it. Defaults to false — declaring a sidebar
 *                         and hiding it is the unusual case.
 * @param sectionsCollapsed start each section's card list closed. Defaults to
 *                         <em>true</em>, the opposite of the other two: a
 *                         sidebar listing every card in every section at once
 *                         is a wall of links, so it behaves like a table of
 *                         contents that opens what you need.
 */
public record Sidebar(boolean enabled, boolean collapsed, boolean sectionsCollapsed) {

    /** No sidebar — what a book that never mentions one gets. */
    public static final Sidebar NONE = new Sidebar(false, false, true);

    /** A sidebar with the default open/closed behaviour — the {@code sidebar: true} shorthand. */
    public static Sidebar on() {
        return new Sidebar(true, false, true);
    }
}
