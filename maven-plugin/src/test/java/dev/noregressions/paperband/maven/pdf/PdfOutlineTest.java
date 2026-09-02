package dev.noregressions.paperband.maven.pdf;

import dev.noregressions.paperband.model.OutlineEntry;

import org.apache.pdfbox.pdmodel.PDDestinationNameTreeNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PageMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Writing the book's bookmark tree into a rendered PDF: nesting by depth,
 * destinations taken from the anchors the document already names, and an
 * untouched file whenever there is nothing to write.
 */
class PdfOutlineTest {

    @Nested
    @DisplayName("Writing the tree")
    class Writing {

        @Test
        void should_write_one_bookmark_per_entry_in_order(@TempDir Path dir) throws IOException {
            Path pdf = pdfWith(dir, Map.of("card-alpha", 0, "card-beta", 1));

            PdfOutline.Result result = PdfOutline.apply(pdf, List.of(
                    new OutlineEntry("Alpha", "card-alpha", 0),
                    new OutlineEntry("Beta", "card-beta", 0)));

            assertEquals(2, result.items());
            assertTrue(result.unresolved().isEmpty());
            assertEquals(List.of("Alpha", "Beta"), titles(pdf));
        }

        @Test
        void should_nest_depth_one_entries_under_the_top_level_entry_above_them(@TempDir Path dir)
                throws IOException {
            Path pdf = pdfWith(dir, Map.of(
                    "axis-divider-tier-1", 0, "card-one", 1, "card-two", 2,
                    "axis-divider-tier-2", 3, "card-three", 4));

            PdfOutline.apply(pdf, List.of(
                    new OutlineEntry("Critical", "axis-divider-tier-1", 0),
                    new OutlineEntry("First", "card-one", 1),
                    new OutlineEntry("Second", "card-two", 1),
                    new OutlineEntry("Standard", "axis-divider-tier-2", 0),
                    new OutlineEntry("Third", "card-three", 1)));

            assertEquals(List.of("Critical", "Standard"), titles(pdf), "top level: the dividers");
            assertEquals(List.of("First", "Second"), childTitles(pdf, "Critical"));
            assertEquals(List.of("Third"), childTitles(pdf, "Standard"));
        }

        @Test
        void should_lift_a_child_with_no_parent_to_the_top_level(@TempDir Path dir)
                throws IOException {
            // A book whose sections print no divider page: its cards carry
            // depth 1 with nothing above them to nest under. Dropping them
            // would lose the whole book from the pane.
            Path pdf = pdfWith(dir, Map.of("card-alpha", 0, "card-beta", 1));

            PdfOutline.apply(pdf, List.of(
                    new OutlineEntry("Alpha", "card-alpha", 1),
                    new OutlineEntry("Beta", "card-beta", 1)));

            assertEquals(List.of("Alpha", "Beta"), titles(pdf));
        }

        @Test
        void should_open_the_viewers_outline_pane(@TempDir Path dir) throws IOException {
            Path pdf = pdfWith(dir, Map.of("card-alpha", 0));

            PdfOutline.apply(pdf, List.of(new OutlineEntry("Alpha", "card-alpha", 0)));

            try (PDDocument doc = PDDocument.load(pdf.toFile())) {
                assertEquals(PageMode.USE_OUTLINES, doc.getDocumentCatalog().getPageMode(),
                        "bookmarks nobody can see are bookmarks nobody uses");
            }
        }
    }

    @Nested
    @DisplayName("Destinations")
    class Destinations {

        @Test
        void should_point_each_bookmark_at_its_anchors_page(@TempDir Path dir) throws IOException {
            Path pdf = pdfWith(dir, Map.of("card-alpha", 0, "card-beta", 2));

            PdfOutline.apply(pdf, List.of(
                    new OutlineEntry("Alpha", "card-alpha", 0),
                    new OutlineEntry("Beta", "card-beta", 0)));

            assertEquals(0, pageOf(pdf, "Alpha"));
            assertEquals(2, pageOf(pdf, "Beta"));
        }

        @Test
        void should_keep_the_scroll_position_an_anchor_pins(@TempDir Path dir) throws IOException {
            // Chromium pins the anchor's own offset on the page, not the page
            // top: a card starting halfway down a sheet should open there.
            Path pdf = pdfWithXyz(dir, "card-alpha", 1, 432);

            PdfOutline.apply(pdf, List.of(new OutlineEntry("Alpha", "card-alpha", 0)));

            PDPageDestination dest = destinationOf(pdf, "Alpha");
            assertInstanceOf(PDPageXYZDestination.class, dest);
            assertEquals(432, ((PDPageXYZDestination) dest).getTop());
            assertEquals(1, dest.retrievePageNumber());
        }

        @Test
        void should_not_share_the_documents_own_destination_objects(@TempDir Path dir)
                throws IOException {
            // FullPageCover rewrites entries in the destination table in
            // place; a bookmark aliasing one would be rewritten with it.
            Path pdf = pdfWith(dir, Map.of("card-alpha", 0));

            PdfOutline.apply(pdf, List.of(new OutlineEntry("Alpha", "card-alpha", 0)));

            try (PDDocument doc = PDDocument.load(pdf.toFile())) {
                PDOutlineItem item = doc.getDocumentCatalog().getDocumentOutline()
                        .getFirstChild();
                PDPageDestination named = doc.getDocumentCatalog().getNames().getDests()
                        .getValue("card-alpha");
                assertNotSame(named.getCOSObject(),
                        ((PDPageDestination) item.getDestination()).getCOSObject());
            }
        }
    }

    @Nested
    @DisplayName("Nothing to write")
    class NothingToWrite {

        @Test
        void should_leave_the_file_alone_when_there_are_no_entries(@TempDir Path dir)
                throws IOException {
            Path pdf = pdfWith(dir, Map.of("card-alpha", 0));
            byte[] before = Files.readAllBytes(pdf);

            PdfOutline.Result result = PdfOutline.apply(pdf, List.of());

            assertTrue(result.isEmpty());
            assertArrayEquals(before, Files.readAllBytes(pdf), "no save, no rewrite");
        }

        @Test
        void should_report_anchors_it_could_not_place(@TempDir Path dir) throws IOException {
            Path pdf = pdfWith(dir, Map.of("card-alpha", 0));

            PdfOutline.Result result = PdfOutline.apply(pdf, List.of(
                    new OutlineEntry("Alpha", "card-alpha", 0),
                    new OutlineEntry("Ghost", "card-ghost", 0)));

            assertEquals(1, result.items(), "the resolvable entry is still written");
            assertEquals(List.of("card-ghost"), result.unresolved());
            assertEquals(List.of("Alpha"), titles(pdf));
        }

        @Test
        void should_leave_the_file_alone_when_nothing_resolves(@TempDir Path dir)
                throws IOException {
            Path pdf = pdfWith(dir, Map.of());
            byte[] before = Files.readAllBytes(pdf);

            PdfOutline.Result result = PdfOutline.apply(pdf, List.of(
                    new OutlineEntry("Alpha", "card-alpha", 0)));

            assertTrue(result.isEmpty());
            assertEquals(List.of("card-alpha"), result.unresolved());
            assertArrayEquals(before, Files.readAllBytes(pdf),
                    "an outline of nothing is worse than none — and a rewrite for it is waste");
        }
    }

    // ---- helpers ----

    /** A PDF of enough pages to hold {@code destinations}, each a page-fit named destination. */
    private static Path pdfWith(Path dir, Map<String, Integer> destinations) throws IOException {
        Path pdf = dir.resolve("book.pdf");
        int pages = destinations.values().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        try (PDDocument doc = new PDDocument()) {
            addPages(doc, pages);
            Map<String, PDPageDestination> named = new TreeMap<>();
            destinations.forEach((name, page) -> {
                PDPageFitDestination dest = new PDPageFitDestination();
                dest.setPage(doc.getPage(page));
                named.put(name, dest);
            });
            if (!named.isEmpty()) setNames(doc, named);
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    /** A PDF whose single named destination pins a vertical offset, the way Chromium's do. */
    private static Path pdfWithXyz(Path dir, String name, int page, int top) throws IOException {
        Path pdf = dir.resolve("book.pdf");
        try (PDDocument doc = new PDDocument()) {
            addPages(doc, page + 1);
            PDPageXYZDestination dest = new PDPageXYZDestination();
            dest.setPage(doc.getPage(page));
            dest.setLeft(0);
            dest.setTop(top);
            dest.setZoom(-1);
            setNames(doc, new LinkedHashMap<>(Map.of(name, dest)));
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private static void addPages(PDDocument doc, int count) throws IOException {
        for (int i = 0; i < count; i++) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(100, 700);
                content.showText("Page " + (i + 1));
                content.endText();
            }
        }
    }

    private static void setNames(PDDocument doc, Map<String, PDPageDestination> named) {
        PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
        PDDestinationNameTreeNode dests = new PDDestinationNameTreeNode();
        dests.setNames(named);
        names.setDests(dests);
        doc.getDocumentCatalog().setNames(names);
    }

    private static List<String> titles(Path pdf) throws IOException {
        try (PDDocument doc = PDDocument.load(pdf.toFile())) {
            PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
            if (outline == null) return List.of();
            List<String> out = new ArrayList<>();
            for (PDOutlineItem item : outline.children()) out.add(item.getTitle());
            return out;
        }
    }

    private static List<String> childTitles(Path pdf, String parentTitle) throws IOException {
        try (PDDocument doc = PDDocument.load(pdf.toFile())) {
            for (PDOutlineItem item : doc.getDocumentCatalog().getDocumentOutline().children()) {
                if (!parentTitle.equals(item.getTitle())) continue;
                List<String> out = new ArrayList<>();
                for (PDOutlineItem child : item.children()) out.add(child.getTitle());
                return out;
            }
            return List.of();
        }
    }

    private static int pageOf(Path pdf, String title) throws IOException {
        return destinationOf(pdf, title).retrievePageNumber();
    }

    private static PDPageDestination destinationOf(Path pdf, String title) throws IOException {
        try (PDDocument doc = PDDocument.load(pdf.toFile())) {
            for (PDOutlineItem item : doc.getDocumentCatalog().getDocumentOutline().children()) {
                if (title.equals(item.getTitle())) {
                    return (PDPageDestination) item.getDestination();
                }
            }
            throw new AssertionError("no bookmark titled " + title);
        }
    }
}
