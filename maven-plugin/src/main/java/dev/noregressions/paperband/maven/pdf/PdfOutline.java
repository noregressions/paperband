package dev.noregressions.paperband.maven.pdf;

import dev.noregressions.paperband.model.OutlineEntry;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PageMode;
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the book's bookmark tree — a PDF <em>document outline</em>, what a
 * viewer shows in its sidebar — into a rendered book, as a post-processing
 * pass over the finished file.
 *
 * <p>Built from the structure the layout engine already knows
 * ({@code LayoutEngine.outline()}: dividers, cards, the contents and index
 * pages) rather than scraped from the document's headings. Chromium can emit
 * an outline of its own from heading levels ({@code Page.pdf()}'s
 * {@code outline} option), but that tree is whatever the headings happen to
 * be: every block heading at every depth, the contents page's own {@code h1},
 * no card numbering, and no notion that a divider owns the cards after it.
 * The book knows its own shape; this writes that.
 *
 * <h2>Where the pages come from</h2>
 * Each entry names a <em>named destination</em>, not a page number — the same
 * anchors the printed contents page points at, which Chromium emits for every
 * id the document links to (that's what the {@code pt-anchor-bait} div in
 * {@code book.html} is for). So the destination already carries the exact
 * page and scroll position of the thing it names, and a bookmark cannot
 * disagree with the page it opens. An anchor with no destination is reported,
 * not guessed at.
 *
 * <h2>Ordering in the build</h2>
 * Runs <b>last</b>, after every step that rewrites pages or destinations: the
 * two-pass page-number render, {@link FullPageCover#replaceFirstPage} (which
 * re-points destinations at a spliced cover) and
 * {@link WatermarkApplier}. Written earlier, its items would point at pages
 * those steps replaced.
 *
 * @see dev.noregressions.paperband.model.OutlineEntry
 */
public final class PdfOutline {

    private PdfOutline() {}

    /**
     * What the pass did: how many bookmarks it wrote, and which anchors it
     * could not place.
     *
     * @param items      bookmarks written
     * @param unresolved anchors with no named destination in the PDF, in
     *                   entry order, each named once
     */
    public record Result(int items, List<String> unresolved) {

        public Result {
            unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
        }

        /** Nothing to write — no entries, or none of them resolved. */
        public boolean isEmpty() {
            return items == 0;
        }
    }

    /**
     * Write {@code entries} into {@code pdf} as its document outline, saving
     * in place. A no-op (the file untouched) when there is nothing to write.
     *
     * <p>Nesting follows each entry's depth: a depth-0 entry becomes a
     * top-level bookmark and the parent of the depth-1 entries that follow it.
     * A depth-1 entry with no top-level entry before it — a book whose cards
     * belong to sections that print no divider page — sits at the top level
     * itself rather than being dropped, and adopts nothing.
     *
     * @param pdf     the rendered book, overwritten in place
     * @param entries the bookmark tree in page order
     * @return what was written
     * @throws IOException if the PDF cannot be read or written
     */
    public static Result apply(Path pdf, List<OutlineEntry> entries) throws IOException {
        if (entries == null || entries.isEmpty()) return new Result(0, List.of());

        try (PDDocument doc = PDDocument.load(pdf.toFile())) {
            Map<String, PDPageDestination> destinations = readDestinations(doc);
            PDDocumentOutline root = new PDDocumentOutline();
            List<String> unresolved = new ArrayList<>();
            PDOutlineItem topLevel = null;
            int items = 0;

            for (OutlineEntry entry : entries) {
                PDPageDestination target = destinations.get(entry.anchor());
                if (target == null) {
                    if (!unresolved.contains(entry.anchor())) unresolved.add(entry.anchor());
                    continue;
                }
                PDOutlineItem item = new PDOutlineItem();
                item.setTitle(entry.label());
                item.setDestination(destinationFor(target, doc));
                if (entry.depth() == 0) {
                    root.addLast(item);
                    topLevel = item;
                } else if (topLevel != null) {
                    topLevel.addLast(item);
                } else {
                    // Nothing to nest under yet. Lift it rather than drop it —
                    // but it does not become a parent, or the first orphan
                    // would adopt every orphan after it and invent a hierarchy
                    // the book doesn't have.
                    root.addLast(item);
                }
                items++;
            }

            if (items == 0) return new Result(0, unresolved);

            PDDocumentCatalog catalog = doc.getDocumentCatalog();
            catalog.setDocumentOutline(root);
            // Without this a viewer opens the book with the pane closed, and a
            // reader has to know the bookmarks are there to find them.
            catalog.setPageMode(PageMode.USE_OUTLINES);
            doc.save(pdf.toFile());
            return new Result(items, unresolved);
        }
    }

    /**
     * Every named destination in the document, by name.
     *
     * <p>Both places they can live, the same two {@link PagesReport} reads and
     * {@link FullPageCover} remaps: the name tree, and the legacy
     * {@code /Dests} dictionary.
     */
    private static Map<String, PDPageDestination> readDestinations(PDDocument doc)
            throws IOException {
        Map<String, PDPageDestination> out = new LinkedHashMap<>();
        PDDocumentNameDictionary names = doc.getDocumentCatalog().getNames();
        if (names != null && names.getDests() != null) {
            walk(names.getDests(), out);
        }
        COSDictionary catalog = doc.getDocumentCatalog().getCOSObject();
        if (catalog.getDictionaryObject(COSName.DESTS) instanceof COSDictionary legacy) {
            for (COSName key : legacy.keySet()) {
                PDDestination dest = PDDestination.create(legacy.getDictionaryObject(key));
                if (dest instanceof PDPageDestination pageDest) {
                    out.putIfAbsent(key.getName(), pageDest);
                }
            }
        }
        return out;
    }

    private static void walk(PDNameTreeNode<PDPageDestination> node,
                             Map<String, PDPageDestination> out) throws IOException {
        Map<String, PDPageDestination> named = node.getNames();
        if (named != null) out.putAll(named);
        List<PDNameTreeNode<PDPageDestination>> kids = node.getKids();
        if (kids != null) for (PDNameTreeNode<PDPageDestination> kid : kids) walk(kid, out);
    }

    /**
     * A fresh destination equivalent to {@code source} — same page, same
     * scroll position — rather than the name tree's own object, so the
     * outline never shares mutable state with the destination table
     * ({@link FullPageCover} rewrites entries in that table in place).
     *
     * <p>Keeps the reader's zoom where the source pins a vertical position
     * ({@code /XYZ}, {@code /FitH} — what Chromium emits for an anchor), and
     * falls back to "show this page" where it doesn't, which is honest about
     * knowing the page but not the offset.
     */
    private static PDPageDestination destinationFor(PDPageDestination source, PDDocument doc) {
        PDPage page = pageOf(source, doc);
        Integer top = topOf(source);
        if (top == null) {
            PDPageFitDestination fit = new PDPageFitDestination();
            if (page != null) fit.setPage(page);
            return fit;
        }
        PDPageXYZDestination xyz = new PDPageXYZDestination();
        if (page != null) xyz.setPage(page);
        // -1 leaves left and zoom unset, which is what tells a viewer to keep
        // the reader's own zoom and horizontal position and only scroll.
        xyz.setLeft(-1);
        xyz.setZoom(-1);
        xyz.setTop(top);
        return xyz;
    }

    /** The page {@code dest} names, by object or by index. */
    private static PDPage pageOf(PDPageDestination dest, PDDocument doc) {
        PDPage page = dest.getPage();
        if (page != null) return page;
        int index = dest.retrievePageNumber();
        return index >= 0 && index < doc.getNumberOfPages() ? doc.getPage(index) : null;
    }

    /** The vertical offset {@code dest} pins, or null when it pins none. */
    private static Integer topOf(PDPageDestination dest) {
        if (dest instanceof PDPageXYZDestination xyz) {
            return xyz.getTop() == -1 ? null : xyz.getTop();
        }
        if (dest instanceof PDPageFitWidthDestination fitWidth) {
            return fitWidth.getTop() == -1 ? null : fitWidth.getTop();
        }
        return null;
    }
}
