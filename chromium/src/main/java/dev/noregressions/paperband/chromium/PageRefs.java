package dev.noregressions.paperband.chromium;

import com.microsoft.playwright.Page;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDestinationDictionary;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.cos.COSName;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Page numbers for links, which Chromium can't put in a printed document itself (it has no
 * {@code target-counter()}). A link tagged {@code pageref} ({@code [3.6](#id){! pageref}}) shows the
 * number of the sheet its target is on, as {@code counter(page)} counts them, and a {@code pageref}
 * link that would repeat the number of the {@code pageref} link just before it is hidden.
 * <p>
 * It takes more than one print: print, read which sheet each link target landed on from the PDF's
 * named destinations (Chromium makes one for every {@code #id} a link points to), write the
 * numbers in, and print again, until the numbers stop changing (they can move text, and so pages,
 * themselves).
 */
final class PageRefs {

    /** At most this many prints; numbers that are still moving after it are left as they are. */
    static final int MAX_PRINTS = 4;

    private static final String TARGETS = """
            () => [...document.querySelectorAll('a.pageref[href^="#"]')].map(a => decodeURIComponent(a.getAttribute('href').slice(1)))""";

    private static final String MISSING = """
            ids => ids.filter(id => !document.getElementById(id))""";

    /**
     * Writes in the numbers it's given, and hides a link that repeats the number of the last one
     * shown in a run of adjacent {@code pageref} links; true if anything changed.
     */
    private static final String APPLY = """
            pages => {
              let changed = false;
              let shown = null;
              for (const a of document.querySelectorAll('a.pageref[href^="#"]')) {
                const n = pages[decodeURIComponent(a.getAttribute('href').slice(1))];
                const text = n === undefined ? '?' : String(n);
                if (a.textContent !== text) { a.textContent = text; changed = true; }
                const prev = a.previousElementSibling;
                if (!(prev && prev.matches('a.pageref'))) shown = null;
                const hide = shown !== null && shown.textContent === text;
                if (hide !== (a.style.display === 'none')) { a.style.display = hide ? 'none' : 'inline'; changed = true; }
                if (!hide) shown = a;
              }
              return changed;
            }""";

    private PageRefs() {
    }

    /**
     * Prints {@code page}, filling in page references first if it has any.
     *
     * @param print prints the page as it is now
     * @throws IOException if a reference's target isn't in the document
     */
    @SuppressWarnings("unchecked")
    static byte[] print(Page page, Supplier<byte[]> print) throws IOException {
        byte[] pdf = print.get();
        List<String> targets = (List<String>) page.evaluate(TARGETS);
        if (targets.isEmpty()) {
            return pdf;
        }
        List<String> missing = (List<String>) page.evaluate(MISSING, targets);
        if (!missing.isEmpty()) {
            throw new IOException(missing.size() + " page reference(s) link to an id that isn't in the document, e.g. #"
                    + missing.getFirst() + (missing.size() > 1 ? ", #" + missing.get(1) : ""));
        }
        for (int n = 1; n < MAX_PRINTS; n++) {
            boolean changed = (Boolean) page.evaluate(APPLY, pages(pdf));
            if (!changed) {
                break;
            }
            pdf = print.get();
        }
        return pdf;
    }

    /** The sheet number (from 1) of every named destination in a PDF. */
    static Map<String, Integer> pages(byte[] pdf) throws IOException {
        Map<String, Integer> out = new HashMap<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDDocumentCatalog catalog = doc.getDocumentCatalog();
            PDDocumentNameDestinationDictionary dests = catalog.getDests();
            if (dests != null) {
                for (COSName name : dests.getCOSObject().keySet()) {
                    put(out, name.getName(), dests.getDestination(name.getName()));
                }
            }
            PDDocumentNameDictionary names = catalog.getNames();
            if (names != null && names.getDests() != null) {
                Map<String, PDPageDestination> tree = names.getDests().getNames();
                if (tree != null) {
                    tree.forEach((name, d) -> put(out, name, d));
                }
            }
        }
        return out;
    }

    private static void put(Map<String, Integer> out, String name, PDDestination d) {
        if (d instanceof PDPageDestination p) {
            int index = p.retrievePageNumber();
            if (index >= 0) {
                out.put(name, index + 1);
            }
        }
    }
}
