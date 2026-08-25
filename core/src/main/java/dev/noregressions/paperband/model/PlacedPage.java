package dev.noregressions.paperband.model;

/**
 * A generated page placed into a book's card flow: a Pebble template rendered
 * <em>with the whole book model in scope</em> (cards, sections, axis
 * groupings, book config — the same context {@code book.html} itself sees) at
 * a declared position among the cards.
 *
 * <p>This is what a card can't be: cards are loaded before the book model
 * exists, so a card template only ever sees {@code vars}. A placed page
 * renders after everything is known — a planning matrix over every card's
 * tier, a per-section summary, a generated appendix — and lands between the
 * cards around its marker, the way the printed table of contents does.
 *
 * <p>Declared as a {@code <page>} marker inside the Maven plugin's
 * {@code <book><sections>} element (see {@code maven.Page}); the marker's
 * position among the {@code <section>} elements becomes a card index the same
 * way the {@code <toc/>} marker's does.
 *
 * @param cardIndex index into the book's card list before which the page
 *                  renders; the card count puts it after the last card
 * @param template  bare Pebble template name, resolved against the book's
 *                  {@code layouts/} directory through the same loader chain
 *                  as every other declared template (theme first, bundled
 *                  last)
 */
public record PlacedPage(int cardIndex, String template) {
}
