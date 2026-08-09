package dev.noregressions.paperband.cli;

import dev.noregressions.paperband.render.Orientation;
import dev.noregressions.paperband.render.PageSize;
import dev.noregressions.paperband.render.PageSpec;
import dev.noregressions.paperband.render.Unit;
import dev.noregressions.paperband.render.playwright.PlaywrightPageMeasurer;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Compute pages-per-anchor from a rendered book, one of two ways depending on
 * what's available:
 *
 * <ul>
 *   <li>{@link #analyseHtml} — a Playwright DOM measurement pass ({@code
 *       page.emulateMedia(PRINT)} + {@code getBoundingClientRect()}) over the
 *       composed HTML, <em>before</em> any PDF exists. This is what {@code
 *       pagewright build}'s {@code --report-pages}/{@code --max-pages-per-card}
 *       use, since a build already has the rendered HTML and resolved
 *       {@code PageSpec} in memory — no dependency on the renderer emitting
 *       PDF named destinations (a Chromium-specific behaviour openhtmltopdf's
 *       fast-mode renderer never provided, back when it existed), and no
 *       need to wait for the PDF to finish writing first. The page-number
 *       math is an approximation of Chromium's real print pagination (px
 *       offset divided by the page's resolved content-box height), which
 *       holds as long as nothing relies on CSS Paged Media features
 *       Chromium's print-to-PDF doesn't implement (running elements,
 *       cross-page balancing) — true of every bundled theme and template
 *       today.</li>
 *   <li>{@link #analyse(Path)} — the original PDFBox/named-destinations
 *       approach, kept for the standalone {@code pagewright pages <pdf>}
 *       command, which only ever has a PDF file on disk to work from (no
 *       source HTML, no PageSpec) — there's no DOM to measure after the
 *       fact, so this path remains the only option there.</li>
 * </ul>
 *
 * <p>Anchors are recognised by id prefix:
 * <ul>
 *   <li>{@code book-cover}                    → cover row</li>
 *   <li>{@code book-back}                     → back-page row</li>
 *   <li>{@code axis-divider-{axisName}-{id}}   → axis-divider row (one per declared
 *       book axis, independently — e.g. {@code axis-divider-tier-1},
 *       {@code axis-divider-subsystem-core})</li>
 *   <li>{@code section-divider-{sectionId}}    → section-divider row (one per
 *       axis-less "section" — a top-level folder whose cards have no value on
 *       any declared axis, e.g. {@code section-divider-front})</li>
 *   <li>{@code card-XYZ}                      → card row</li>
 * </ul>
 *
 * <p>This anchor convention is deliberately independent of the CSS class
 * convention used elsewhere ({@code {axisName}-{valueId}}, purely cosmetic) —
 * the unambiguous {@code axis-divider-}/{@code section-divider-} prefixes here
 * let this report recognise a divider generically without needing to know
 * which axis names or section ids a given book declares.
 *
 * <p>{@link #analyse(Path)} computes spans as next-anchor-start minus this
 * anchor's start (valid there — PDF named destinations already reflect real
 * pagination). {@link #analyseHtml} cannot use that trick: its positions come
 * from continuous, unpaginated DOM flow, so it instead derives each anchor's
 * span from that anchor's own measured height and walks anchors in document
 * order accumulating a running start page — see that method's own comments.
 */
public final class PagesReport {

    private PagesReport() {}

    /** One row per recognised anchor, sorted by start page. */
    public record Row(String kind, String label, String anchor, int startPage, int span) {}

    /**
     * Everything one Playwright measurement pass produces: the per-anchor
     * {@link Row}s plus the raw {@link PlaywrightPageMeasurer.Measurement}
     * and resolved content-box height, kept around so a caller can look up
     * finer-grained detail (see {@link #firstOverflowUnit}) for a card it
     * already knows — from the rows — is over its page budget, without
     * paying for a second browser launch.
     */
    public record Analysis(List<Row> rows, PlaywrightPageMeasurer.Measurement measurement, double contentHeightPx) {}

    /**
     * @param html     the fully composed book HTML (same string handed to the renderer)
     * @param baseUri  base URI for resolving relative assets, same as {@code HtmlInput.baseUri}
     * @param pageSpec resolved page geometry — used to convert measured pixel offsets into
     *                 page numbers via the page's content-box height
     * @return rows for all recognised anchors; empty if none were found
     */
    public static List<Row> analyseHtml(String html, URI baseUri, PageSpec pageSpec) {
        return analyseHtmlFull(html, baseUri, pageSpec).rows();
    }

    /**
     * Same measurement pass as {@link #analyseHtml}, but returns the full
     * {@link Analysis} instead of discarding everything except the rows —
     * use this when a caller might also need {@link #firstOverflowUnit}.
     */
    public static Analysis analyseHtmlFull(String html, URI baseUri, PageSpec pageSpec) {
        // Pin the measurement viewport to the page's own resolved width —
        // without this, content lays out against Playwright's default ~1280px
        // viewport instead of the real (usually much narrower) printed page
        // width, understating how tall the content actually gets and letting
        // real overflow pass every check silently. See PlaywrightPageMeasurer's
        // javadoc for the full explanation; this was a real, shipped bug.
        int viewportWidthPx = (int) Math.round(widthPx(pageSpec));
        PlaywrightPageMeasurer.Measurement measured =
                PlaywrightPageMeasurer.measure(html, baseUri, viewportWidthPx);
        double contentHeightPx = contentHeightPx(pageSpec);
        if (contentHeightPx <= 0) return new Analysis(List.of(), measured, contentHeightPx);

        // Document order first — this is the one thing continuous (unpaginated)
        // topPx is still trustworthy for, since anchors never overlap each
        // other in the flow. What topPx can NOT be trusted for any more is
        // deriving a page number by dividing it against contentHeightPx: that
        // silently assumes continuous flow matches real pagination, which it
        // never does once a forced break sits between two anchors (every
        // card/divider/cover/back in this book forces one). So topPx's only
        // remaining job is sorting.
        List<PlaywrightPageMeasurer.ElementPosition> ordered = new ArrayList<>(measured.positions());
        ordered.removeIf(p -> classify(p.id()) == null);
        ordered.sort(Comparator.comparingDouble(PlaywrightPageMeasurer.ElementPosition::topPx));

        // Each anchor's span comes from its OWN measured height — never from
        // the gap to the next anchor. startPage is then cumulative: anchor N
        // starts right after anchor N-1's own span ends, exactly mirroring
        // the forced-page-break-before-every-anchor layout every bundled
        // template uses. The very first anchor is the top of the document,
        // so it starts on page 1 unconditionally.
        List<Row> rows = new ArrayList<>(ordered.size());
        int nextStartPage = 1;
        for (PlaywrightPageMeasurer.ElementPosition pos : ordered) {
            String kind = classify(pos.id());
            int span = Math.max(1, (int) Math.ceil(pos.heightPx() / contentHeightPx));
            rows.add(new Row(kind, label(pos.id(), kind), pos.id(), nextStartPage, span));
            nextStartPage += span;
        }
        return new Analysis(rows, measured, contentHeightPx);
    }

    /** Where, inside an overflowing card, the overflow first crosses its page limit. */
    public record OverflowLocation(String label, int page) {}

    /**
     * Walk one card's top-level layout units (see {@link
     * PlaywrightPageMeasurer.LayoutUnit}) in document order, re-deriving page
     * numbers the same cumulative, own-height-based way {@link
     * #analyseHtmlFull} does for whole anchors — except this walk also
     * honours the two explicit break hints a unit can carry:
     * {@code pw-page-start} forces an unconditional jump to a fresh page
     * (exactly like every anchor boundary), and {@code pw-avoid-split} forces
     * one <em>conditionally</em> — only when the unit doesn't fit in
     * whatever's left of the current page, mirroring {@code break-inside:
     * avoid}'s real behaviour (move the whole thing rather than split it).
     * Units with neither hint just flow continuously, same as ordinary prose.
     *
     * @param analysis  result of a prior {@link #analyseHtmlFull} call
     * @param cardAnchor the overflowing card's raw anchor id (e.g. {@code
     *                   "card-jackson-group-id"}, i.e. {@link Row#anchor()}
     *                   for its {@code "card"}-kind row)
     * @param limitPages the card's effective page limit
     * @return the first unit whose start crosses the limit, or empty if the
     *         card has no captured layout units at all (a heading-less card
     *         with no {@code .block} sections — nothing finer to report than
     *         the page range already known from {@code analyseHtmlFull})
     */
    public static java.util.Optional<OverflowLocation> firstOverflowUnit(
            Analysis analysis, String cardAnchor, int limitPages) {
        Row cardRow = analysis.rows().stream()
                .filter(r -> "card".equals(r.kind()) && cardAnchor.equals(r.anchor()))
                .findFirst().orElse(null);
        PlaywrightPageMeasurer.ElementPosition cardPos = analysis.measurement().positions().stream()
                .filter(p -> cardAnchor.equals(p.id()))
                .findFirst().orElse(null);
        if (cardRow == null || cardPos == null) return java.util.Optional.empty();

        List<PlaywrightPageMeasurer.LayoutUnit> units = analysis.measurement().units().stream()
                .filter(u -> cardAnchor.equals(u.cardId()))
                .sorted(Comparator.comparingDouble(PlaywrightPageMeasurer.LayoutUnit::topPx))
                .toList();
        if (units.isEmpty()) return java.util.Optional.empty();

        double contentHeightPx = analysis.contentHeightPx();
        double cursorPx = cardPos.topPx();   // start of "current page" reference, in continuous px
        int page = cardRow.startPage();

        for (PlaywrightPageMeasurer.LayoutUnit u : units) {
            if (u.pageStart()) {
                page += 1;
                cursorPx = u.topPx();
            } else {
                double gap = u.topPx() - cursorPx;
                if (gap > 0) {
                    int pagesElapsed = (int) Math.floor(gap / contentHeightPx);
                    page += pagesElapsed;
                    cursorPx += pagesElapsed * contentHeightPx;
                }
            }
            if (u.avoidSplit()) {
                double usedOnPage = u.topPx() - cursorPx;
                double remaining = contentHeightPx - usedOnPage;
                if (u.heightPx() > remaining) {
                    page += 1;
                    cursorPx = u.topPx();
                }
            }

            int relativePage = page - cardRow.startPage() + 1;
            if (relativePage > limitPages) {
                return java.util.Optional.of(new OverflowLocation(u.label(), page));
            }

            // Consumed content ends at this unit's own top + height — never
            // inferred from the next unit's position, same "trust own height"
            // rule as the anchor-level fix.
            cursorPx = u.topPx() + u.heightPx();
        }
        return java.util.Optional.empty();
    }

    /**
     * The original PDFBox/named-destinations approach — see the class
     * javadoc for when to use this vs. {@link #analyseHtml}.
     *
     * @return rows for all recognised anchors; empty if the PDF has no
     *         named destinations or none match the recognised prefixes
     */
    public static List<Row> analyse(Path pdf) throws IOException {
        try (PDDocument doc = PDDocument.load(pdf.toFile())) {
            int totalPages = doc.getNumberOfPages();

            Map<String, Integer> dests = new TreeMap<>();
            PDDocumentNameDictionary names = doc.getDocumentCatalog().getNames();
            if (names != null && names.getDests() != null) {
                walkDests(names.getDests(), doc, dests);
            }
            // Also pick up dests declared in the legacy /Dests dictionary.
            COSDictionary catalogDict = doc.getDocumentCatalog().getCOSObject();
            Object legacy = catalogDict.getDictionaryObject(COSName.DESTS);
            if (legacy instanceof COSDictionary cd) {
                for (COSName key : cd.keySet()) {
                    PDDestination dest = PDDestination.create(cd.getDictionaryObject(key));
                    Integer pg = pageOf(dest, doc);
                    if (pg != null) dests.put(key.getName(), pg);
                }
            }

            List<Row> rows = new ArrayList<>(dests.size());
            record Tmp(String kind, String label, String anchor, int startPage) {}
            List<Tmp> tmp = new ArrayList<>();
            for (var e : dests.entrySet()) {
                String name = e.getKey();
                String kind = classify(name);
                if (kind == null) continue;
                tmp.add(new Tmp(kind, label(name, kind), name, e.getValue() + 1));
            }
            tmp.sort(Comparator.comparingInt(Tmp::startPage));

            for (int i = 0; i < tmp.size(); i++) {
                int next = (i + 1 < tmp.size()) ? tmp.get(i + 1).startPage : totalPages + 1;
                int span = next - tmp.get(i).startPage;
                rows.add(new Row(tmp.get(i).kind, tmp.get(i).label, tmp.get(i).anchor,
                        tmp.get(i).startPage, span));
            }
            return rows;
        }
    }

    private static void walkDests(PDNameTreeNode<PDPageDestination> node,
                                  PDDocument doc,
                                  Map<String, Integer> out) throws IOException {
        Map<String, PDPageDestination> map = node.getNames();
        if (map != null) {
            for (var e : map.entrySet()) {
                Integer page = pageOf(e.getValue(), doc);
                if (page != null) out.put(e.getKey(), page);
            }
        }
        List<PDNameTreeNode<PDPageDestination>> kids = node.getKids();
        if (kids != null) for (var k : kids) walkDests(k, doc, out);
    }

    private static Integer pageOf(PDDestination dest, PDDocument doc) throws IOException {
        if (dest instanceof PDNamedDestination named) {
            PDDocumentNameDictionary names = doc.getDocumentCatalog().getNames();
            if (names != null && names.getDests() != null) {
                PDPageDestination resolved = names.getDests().getValue(named.getNamedDestination());
                if (resolved != null) return pageOf(resolved, doc);
            }
            return null;
        }
        if (dest instanceof PDPageDestination pageDest) {
            int idx = pageDest.retrievePageNumber();
            if (idx >= 0) return idx;
            PDPage pg = pageDest.getPage();
            if (pg != null) return doc.getPages().indexOf(pg);
        }
        return null;
    }

    /**
     * Content-box height in CSS px: the resolved page's vertical dimension
     * (width and height swap under {@link Orientation#LANDSCAPE}, matching
     * how {@code Page.pdf()}'s {@code setLandscape} rotates the page) minus
     * top and bottom margins, all converted to px at the CSS reference
     * pixel density (96px/inch).
     */
    private static double contentHeightPx(PageSpec spec) {
        PageSize size = spec.size();
        double pageHeightUnitless = spec.orientation() == Orientation.LANDSCAPE ? size.width() : size.height();
        double pageHeightPx = toPx(pageHeightUnitless, size.unit());
        double marginsPx = toPx(spec.margins().top(), spec.margins().unit())
                + toPx(spec.margins().bottom(), spec.margins().unit());
        return pageHeightPx - marginsPx;
    }

    /**
     * Content-box width in CSS px, same reasoning as {@link #contentHeightPx}
     * but for the horizontal dimension: the printable area excludes left/right
     * margins, and that's the width HTML content actually lays out against
     * (browsers treat a page's margins as shrinking the layout viewport, not
     * just padding the final output) — this is the number the measurement
     * pass's viewport should be pinned to.
     */
    private static double widthPx(PageSpec spec) {
        PageSize size = spec.size();
        double pageWidthUnitless = spec.orientation() == Orientation.LANDSCAPE ? size.height() : size.width();
        double pageWidthPx = toPx(pageWidthUnitless, size.unit());
        double marginsPx = toPx(spec.margins().left(), spec.margins().unit())
                + toPx(spec.margins().right(), spec.margins().unit());
        return pageWidthPx - marginsPx;
    }

    private static double toPx(double value, Unit unit) {
        return unit.toMillimetres(value) / 25.4 * 96.0;
    }

    private static final String AXIS_DIVIDER_PREFIX = "axis-divider-";
    private static final String SECTION_DIVIDER_PREFIX = "section-divider-";

    private static String classify(String name) {
        if (name == null) return null;
        if (name.equals("book-cover")) return "cover";
        if (name.equals("book-back")) return "back";
        if (name.startsWith("card-")) return "card";
        if (name.startsWith(AXIS_DIVIDER_PREFIX)) return "axis-divider";
        if (name.startsWith(SECTION_DIVIDER_PREFIX)) return "section-divider";
        return null;
    }

    private static String label(String name, String kind) {
        return switch (kind) {
            case "card" -> name.substring("card-".length());
            case "axis-divider" -> formatHyphenatedLabel(name.substring(AXIS_DIVIDER_PREFIX.length()));
            case "section-divider" -> formatHyphenatedLabel(name.substring(SECTION_DIVIDER_PREFIX.length()));
            case "cover" -> "(cover)";
            case "back" -> "(back)";
            default -> name;
        };
    }

    /**
     * The anchor remainder after {@code axis-divider-} is {@code {axisName}-{valueId}}
     * (axis names and value ids can each contain hyphens of their own, so the two
     * can't be unambiguously split back apart here), and the remainder after
     * {@code section-divider-} is just a section id which may itself contain
     * hyphens. Both are formatted the same way — hyphens become spaces, each
     * word is title-cased — e.g. {@code "tier-1"} → {@code "Tier 1"},
     * {@code "subsystem-core"} → {@code "Subsystem Core"}, {@code "cve-process"}
     * → {@code "Cve Process"}. This reproduces the exact previous "Tier N" label
     * for the tier axis unchanged.
     */
    private static String formatHyphenatedLabel(String remainder) {
        String[] parts = remainder.split("-");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) sb.append(p.substring(1));
        }
        return sb.length() == 0 ? remainder : sb.toString();
    }
}
