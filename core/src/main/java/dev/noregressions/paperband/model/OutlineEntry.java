package dev.noregressions.paperband.model;

/**
 * One line of a book's PDF bookmark tree — what a reader sees in a viewer's
 * outline pane, and what the maven plugin's {@code PdfOutline} writes into the
 * finished document.
 *
 * <p>Deliberately the three facts a printed contents line carries minus the
 * page number: what to say, where to jump, how deep it sits. No page number
 * because a bookmark points at a <em>named destination</em> the PDF already
 * holds (Chromium emits one per linked anchor), so the page it opens is
 * whatever that destination says. That's what keeps bookmarks out of the
 * two-pass page-number dance the printed TOC needs — and what makes it
 * impossible for a bookmark to disagree with the page it lands on.
 *
 * <p>Lives in {@code core} so the layout engine (which knows the book's
 * structure) and the maven plugin (which owns the PDF file) agree on one type
 * without either depending on the other — same reasoning as
 * {@link PlacedPage}.
 *
 * @param label  what the bookmark says — plain text, card numbering included
 * @param anchor the named destination to jump to, e.g. {@code card-my-id}
 * @param depth  0 for a top-level bookmark, 1 for a child of the nearest
 *               top-level entry above it
 */
public record OutlineEntry(String label, String anchor, int depth) {

    public OutlineEntry {
        if (anchor == null || anchor.isBlank()) {
            throw new IllegalArgumentException("outline entry missing an anchor");
        }
        // A card with no title would otherwise show as a blank row in the
        // bookmark pane, which reads as a broken PDF rather than an untitled
        // card. Its anchor is at least identifying.
        label = (label == null || label.isBlank()) ? anchor.trim() : label.trim();
        anchor = anchor.trim();
        if (depth < 0) depth = 0;
    }
}
