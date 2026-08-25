package dev.noregressions.paperband.maven.pdf;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Keeps a running header/footer off a full-page cover.
 *
 * <p>{@code cover: { fullPage: true }} drops the first page's margins via
 * {@code @page :first} so the artwork reaches the trim edge — but Chromium
 * paints its {@code displayHeaderFooter} bands onto <em>every</em> page, on
 * top of the content, margins or not. There's no per-page switch in
 * {@code Page.pdf()}, and the bands aren't marked as artifacts in the
 * content stream, so they can't be stripped surgically after the fact.
 *
 * <p>What is reliable: rendering the same HTML twice differs only in those
 * bands. So the build renders the book once more with no header/footer at
 * all and splices <em>that</em> version's first page into the real PDF —
 * identical pixels, minus the bands. Named destinations that pointed at the
 * old first page (the cover's own anchor) are remapped onto the spliced one
 * so {@code paperband:pages} and TOC page references keep seeing the cover.
 */
public final class FullPageCover {

    private FullPageCover() {}

    /** The class the built-in cover template stamps on a full-page cover section. */
    public static final String MARKER = "book-cover-fullpage";

    /**
     * Whether {@code target}'s first page needs the bare-render splice: the
     * book declared a full-page cover AND a running band that would print
     * over it.
     */
    public static boolean needsBareFirstPage(String html, String footerHtml, String headerHtml) {
        return html.contains(MARKER)
                && (notBlank(footerHtml) || notBlank(headerHtml));
    }

    /**
     * Replace {@code target}'s first page with {@code bare}'s — the same
     * render minus header/footer — remapping named destinations that pointed
     * at the replaced page.
     */
    public static void replaceFirstPage(Path target, Path bare) throws IOException {
        try (PDDocument doc = PDDocument.load(target.toFile());
             PDDocument bareDoc = PDDocument.load(bare.toFile())) {
            PDPage oldFirst = doc.getPage(0);
            // importPage appends the copy at the end of the tree; detach it
            // before re-inserting at the front, or the tree holds the same
            // kid twice and the document is corrupt.
            PDPage spliced = doc.importPage(bareDoc.getPage(0));
            doc.getPages().remove(spliced);
            doc.getPages().insertBefore(spliced, oldFirst);
            doc.removePage(oldFirst);
            remapDestinations(doc, oldFirst, spliced);
            doc.save(target.toFile());
        }
    }

    /** Point every named destination that referenced {@code from} at {@code to}. */
    private static void remapDestinations(PDDocument doc, PDPage from, PDPage to)
            throws IOException {
        PDDocumentNameDictionary names = doc.getDocumentCatalog().getNames();
        if (names != null && names.getDests() != null) {
            remapTree(names.getDests(), from, to);
        }
        // Legacy /Dests dictionary, same treatment as PagesReport reads it.
        COSDictionary catalog = doc.getDocumentCatalog().getCOSObject();
        Object legacy = catalog.getDictionaryObject(COSName.DESTS);
        if (legacy instanceof COSDictionary cd) {
            for (COSName key : cd.keySet()) {
                PDDestination dest = PDDestination.create(cd.getDictionaryObject(key));
                if (dest instanceof PDPageDestination pd && pointsAt(pd, from)) {
                    pd.setPage(to);
                }
            }
        }
    }

    private static void remapTree(PDNameTreeNode<PDPageDestination> node, PDPage from, PDPage to)
            throws IOException {
        Map<String, PDPageDestination> map = node.getNames();
        if (map != null) {
            for (PDPageDestination dest : map.values()) {
                if (pointsAt(dest, from)) dest.setPage(to);
            }
        }
        List<PDNameTreeNode<PDPageDestination>> kids = node.getKids();
        if (kids != null) for (var k : kids) remapTree(k, from, to);
    }

    /**
     * Whether {@code dest} targets {@code page}. Compared at the COS level:
     * {@code getPage()} builds a fresh {@code PDPage} wrapper per call, so
     * wrapper identity is never equal even when the underlying page is.
     */
    private static boolean pointsAt(PDPageDestination dest, PDPage page) {
        PDPage target = dest.getPage();
        return target != null && target.getCOSObject() == page.getCOSObject();
    }

    /** A scratch sibling of {@code output} for the bare render. */
    public static Path bareRenderPath(Path output) throws IOException {
        Path dir = output.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        return dir.resolve(output.getFileName() + ".bare-cover.tmp.pdf");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
