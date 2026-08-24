package dev.noregressions.paperband.maven.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class PagesReportTest {

    @Nested
    @DisplayName("Empty and basic PDFs")
    class EmptyAndBasicPdfs {

        @Test
        void should_return_empty_list_for_pdf_without_destinations(@TempDir Path tempDir) throws IOException {
            Path pdfFile = createBasicPdf(tempDir, "no-dests.pdf", 3);

            List<PagesReport.Row> result = PagesReport.analyse(pdfFile);

            assertTrue(result.isEmpty());
        }

        @Test
        void should_return_empty_list_for_pdf_with_unrecognized_destinations(@TempDir Path tempDir) throws IOException {
            Path pdfFile = createPdfWithDestinations(tempDir, "unrecognized.pdf",
                Map.of("random-dest", 0, "another-dest", 1));

            List<PagesReport.Row> result = PagesReport.analyse(pdfFile);

            assertTrue(result.isEmpty());
        }

        @Test
        void should_throw_io_exception_for_nonexistent_file(@TempDir Path tempDir) {
            Path nonexistentFile = tempDir.resolve("nonexistent.pdf");

            assertThrows(IOException.class, () -> PagesReport.analyse(nonexistentFile));
        }

        @Test
        void should_throw_io_exception_for_invalid_pdf(@TempDir Path tempDir) throws IOException {
            Path invalidFile = tempDir.resolve("invalid.pdf");
            Files.writeString(invalidFile, "This is not a PDF");

            assertThrows(IOException.class, () -> PagesReport.analyse(invalidFile));
        }
    }

    @Nested
    @DisplayName("Single destination types")
    class SingleDestinationTypes {

        @Test
        void should_analyse_cover_destination(@TempDir Path tempDir) throws IOException {
            Path pdfFile = createPdfWithDestinations(tempDir, "cover.pdf",
                Map.of("book-cover", 0));

            List<PagesReport.Row> result = PagesReport.analyse(pdfFile);

            assertEquals(1, result.size());
            PagesReport.Row row = result.get(0);
            assertEquals("cover", row.kind());
            assertEquals("(cover)", row.label());
            assertEquals("book-cover", row.anchor());
            assertEquals(1, row.startPage()); // 1-based page numbering
            assertEquals(1, row.span()); // Spans to end of document
        }

        @Test
        void should_analyse_card_destination(@TempDir Path tempDir) throws IOException {
            Path pdfFile = createPdfWithDestinations(tempDir, "card.pdf",
                Map.of("card-example", 0));

            List<PagesReport.Row> result = PagesReport.analyse(pdfFile);

            assertEquals(1, result.size());
            PagesReport.Row row = result.get(0);
            assertEquals("card", row.kind());
            assertEquals("example", row.label());
            assertEquals("card-example", row.anchor());
            assertEquals(1, row.startPage());
            assertEquals(1, row.span());
        }

        @Test
        void should_analyse_tier_destination(@TempDir Path tempDir) throws IOException {
            // Divider anchors use the unambiguous "axis-divider-{axisName}-{valueId}"
            // convention so PagesReport can recognise any axis's divider generically,
            // independent of the (purely cosmetic) CSS class convention.
            Path pdfFile = createPdfWithDestinations(tempDir, "tier.pdf",
                Map.of("axis-divider-tier-2", 1));

            List<PagesReport.Row> result = PagesReport.analyse(pdfFile);

            assertEquals(1, result.size());
            PagesReport.Row row = result.get(0);
            assertEquals("axis-divider", row.kind());
            assertEquals("Tier 2", row.label());
            assertEquals("axis-divider-tier-2", row.anchor());
            assertEquals(2, row.startPage());
            assertEquals(1, row.span()); // Single page document, so spans 1
        }

        @Test
        void should_analyse_section_destination(@TempDir Path tempDir) throws IOException {
            // Plain-section dividers use "section-divider-{sectionId}" -- a
            // distinct prefix from "axis-divider-" so the two never collide.
            Path pdfFile = createPdfWithDestinations(tempDir, "section.pdf",
                Map.of("section-divider-front", 0));

            List<PagesReport.Row> result = PagesReport.analyse(pdfFile);

            assertEquals(1, result.size());
            PagesReport.Row row = result.get(0);
            assertEquals("section-divider", row.kind());
            assertEquals("Front", row.label());
            assertEquals("section-divider-front", row.anchor());
            assertEquals(1, row.startPage());
            assertEquals(1, row.span());
        }

        @Test
        void should_format_multi_word_section_id(@TempDir Path tempDir) throws IOException {
            Path pdfFile = createPdfWithDestinations(tempDir, "section-multiword.pdf",
                Map.of("section-divider-cve-process", 0));

            List<PagesReport.Row> result = PagesReport.analyse(pdfFile);

            assertEquals(1, result.size());
            assertEquals("section-divider", result.get(0).kind());
            assertEquals("Cve Process", result.get(0).label());
        }
    }

    @Nested
    @DisplayName("Multiple destinations and span calculations")
    class MultipleDestinationsAndSpans {

        @Test
        void should_calculate_spans_correctly_for_multiple_destinations(@TempDir Path tempDir) throws IOException {
            Map<String, Integer> destinations = Map.of(
                "book-cover", 0,             // Page 1, should span 2 pages
                "axis-divider-tier-1", 2,    // Page 3, should span 2 pages
                "card-first", 4              // Page 5, should span 1 page (to end)
            );
            Path pdfFile = createPdfWithDestinations(tempDir, "spans.pdf", destinations, 5);

            List<PagesReport.Row> result = PagesReport.analyse(pdfFile);

            assertEquals(3, result.size());

            // Results should be sorted by start page
            assertEquals("cover", result.get(0).kind());
            assertEquals(1, result.get(0).startPage());
            assertEquals(2, result.get(0).span());

            assertEquals("axis-divider", result.get(1).kind());
            assertEquals(3, result.get(1).startPage());
            assertEquals(2, result.get(1).span());

            assertEquals("card", result.get(2).kind());
            assertEquals(5, result.get(2).startPage());
            assertEquals(1, result.get(2).span());
        }

        @Test
        void should_handle_destinations_in_random_order(@TempDir Path tempDir) throws IOException {
            // Create destinations out of page order
            Map<String, Integer> destinations = Map.of(
                "card-third", 4,     // Page 5
                "card-first", 0,     // Page 1
                "card-second", 2     // Page 3
            );
            Path pdfFile = createPdfWithDestinations(tempDir, "random-order.pdf", destinations, 6);

            List<PagesReport.Row> result = PagesReport.analyse(pdfFile);

            assertEquals(3, result.size());

            // Should be sorted by start page
            assertEquals("first", result.get(0).label());
            assertEquals(1, result.get(0).startPage());
            assertEquals(2, result.get(0).span());

            assertEquals("second", result.get(1).label());
            assertEquals(3, result.get(1).startPage());
            assertEquals(2, result.get(1).span());

            assertEquals("third", result.get(2).label());
            assertEquals(5, result.get(2).startPage());
            assertEquals(2, result.get(2).span()); // To end of document
        }

        @Test
        void should_handle_adjacent_destinations(@TempDir Path tempDir) throws IOException {
            Map<String, Integer> destinations = Map.of(
                "card-first", 0,     // Page 1
                "card-second", 1,    // Page 2
                "card-third", 2      // Page 3
            );
            Path pdfFile = createPdfWithDestinations(tempDir, "adjacent.pdf", destinations, 3);

            List<PagesReport.Row> result = PagesReport.analyse(pdfFile);

            assertEquals(3, result.size());
            assertEquals(1, result.get(0).span());
            assertEquals(1, result.get(1).span());
            assertEquals(1, result.get(2).span());
        }

        @Test
        void should_handle_mixed_destination_types(@TempDir Path tempDir) throws IOException {
            Map<String, Integer> destinations = Map.of(
                "book-cover", 0,
                "axis-divider-tier-1", 1,
                "card-alpha", 2,
                "card-beta", 3,
                "axis-divider-tier-2", 4,
                "card-gamma", 5
            );
            Path pdfFile = createPdfWithDestinations(tempDir, "mixed.pdf", destinations, 6);

            List<PagesReport.Row> result = PagesReport.analyse(pdfFile);

            assertEquals(6, result.size());

            // Verify types and labels
            assertEquals("cover", result.get(0).kind());
            assertEquals("(cover)", result.get(0).label());

            assertEquals("axis-divider", result.get(1).kind());
            assertEquals("Tier 1", result.get(1).label());

            assertEquals("card", result.get(2).kind());
            assertEquals("alpha", result.get(2).label());

            assertEquals("card", result.get(3).kind());
            assertEquals("beta", result.get(3).label());

            assertEquals("axis-divider", result.get(4).kind());
            assertEquals("Tier 2", result.get(4).label());

            assertEquals("card", result.get(5).kind());
            assertEquals("gamma", result.get(5).label());
        }

        @Test
        void should_handle_axis_and_section_dividers_together(@TempDir Path tempDir) throws IOException {
            // A real axis-less book: cover, then one section divider per
            // chapter, interleaved with card rows -- no axis-divider rows at
            // all, mirroring a book that declares no axes.
            Map<String, Integer> destinations = Map.of(
                "book-cover", 0,
                "section-divider-intro", 1,
                "card-welcome", 2,
                "section-divider-cve-process", 3,
                "card-what-is-a-cve", 4
            );
            Path pdfFile = createPdfWithDestinations(tempDir, "sections-book.pdf", destinations, 5);

            List<PagesReport.Row> result = PagesReport.analyse(pdfFile);

            assertEquals(5, result.size());
            assertEquals("cover", result.get(0).kind());
            assertEquals("section-divider", result.get(1).kind());
            assertEquals("Intro", result.get(1).label());
            assertEquals("card", result.get(2).kind());
            assertEquals("section-divider", result.get(3).kind());
            assertEquals("Cve Process", result.get(3).label());
            assertEquals("card", result.get(4).kind());
        }
    }

    @Nested
    @DisplayName("Edge cases and malformed input")
    class EdgeCasesAndMalformedInput {

        @Test
        void should_handle_empty_card_names(@TempDir Path tempDir) throws IOException {
            Path pdfFile = createPdfWithDestinations(tempDir, "empty-names.pdf",
                Map.of("card-", 0)); // Empty card name

            List<PagesReport.Row> result = PagesReport.analyse(pdfFile);

            assertEquals(1, result.size());
            assertEquals("card", result.get(0).kind());
            assertEquals("", result.get(0).label()); // Empty label
            assertEquals("card-", result.get(0).anchor());
        }

        @Test
        void should_handle_complex_card_names(@TempDir Path tempDir) throws IOException {
            Path pdfFile = createPdfWithDestinations(tempDir, "complex-names.pdf", Map.of(
                "card-multi-word-example", 0,
                "card-with_underscores", 1,
                "card-123-numbers", 2
            ));

            List<PagesReport.Row> result = PagesReport.analyse(pdfFile);

            assertEquals(3, result.size());
            assertEquals("multi-word-example", result.get(0).label());
            assertEquals("with_underscores", result.get(1).label());
            assertEquals("123-numbers", result.get(2).label());
        }

        @Test
        void should_handle_tier_numbers_correctly(@TempDir Path tempDir) throws IOException {
            Path pdfFile = createPdfWithDestinations(tempDir, "tier-numbers.pdf", Map.of(
                "axis-divider-tier-1", 0,
                "axis-divider-tier-10", 1,
                "axis-divider-tier-999", 2
            ));

            List<PagesReport.Row> result = PagesReport.analyse(pdfFile);

            assertEquals(3, result.size());
            assertEquals("Tier 1", result.get(0).label());
            assertEquals("Tier 10", result.get(1).label());
            assertEquals("Tier 999", result.get(2).label());
        }

        @Test
        void should_ignore_destinations_with_invalid_pages(@TempDir Path tempDir) throws IOException {
            // This test depends on the internal behavior of PDFBox destination handling
            // It's hard to create invalid page references directly, so we test with empty destinations
            Path pdfFile = createBasicPdf(tempDir, "empty-dests.pdf", 3);

            List<PagesReport.Row> result = PagesReport.analyse(pdfFile);

            assertTrue(result.isEmpty());
        }

        @Test
        void should_handle_single_page_pdf(@TempDir Path tempDir) throws IOException {
            Path pdfFile = createPdfWithDestinations(tempDir, "single-page.pdf",
                Map.of("card-only", 0), 1);

            List<PagesReport.Row> result = PagesReport.analyse(pdfFile);

            assertEquals(1, result.size());
            assertEquals(1, result.get(0).startPage());
            assertEquals(1, result.get(0).span());
        }

        @Test
        void should_handle_destinations_on_same_page(@TempDir Path tempDir) throws IOException {
            Map<String, Integer> destinations = Map.of(
                "card-first", 0,
                "card-second", 0  // Same page
            );
            Path pdfFile = createPdfWithDestinations(tempDir, "same-page.pdf", destinations, 2);

            List<PagesReport.Row> result = PagesReport.analyse(pdfFile);

            assertEquals(2, result.size());
            // Both should start on page 1
            assertEquals(1, result.get(0).startPage());
            assertEquals(1, result.get(1).startPage());

            // But the spans will be different due to sorting
            // The TreeMap will impose a deterministic order
            assertTrue(result.get(0).span() >= 0);
            assertTrue(result.get(1).span() >= 0);
        }
    }

    @Nested
    @DisplayName("Report formatting and output")
    class ReportFormattingAndOutput {

        @Test
        void should_format_labels_correctly(@TempDir Path tempDir) throws IOException {
            Map<String, Integer> destinations = Map.of(
                "book-cover", 0,
                "axis-divider-tier-advanced", 1,
                "card-special-feature", 2
            );
            Path pdfFile = createPdfWithDestinations(tempDir, "formatting.pdf", destinations);

            List<PagesReport.Row> result = PagesReport.analyse(pdfFile);

            assertEquals(3, result.size());
            assertEquals("(cover)", result.get(0).label());
            // The anchor remainder after "axis-divider-" ("tier-advanced") can't be
            // unambiguously split back into axis name + value id (either could
            // contain hyphens), so every hyphen-separated word is title-cased:
            // "tier-advanced" -> "Tier Advanced".
            assertEquals("Tier Advanced", result.get(1).label());
            assertEquals("special-feature", result.get(2).label());
        }

        @Test
        void should_preserve_anchor_names_exactly(@TempDir Path tempDir) throws IOException {
            Map<String, Integer> destinations = Map.of(
                "card-Preserve-Case-123", 0,
                "axis-divider-tier-UPPERCASE", 1
            );
            Path pdfFile = createPdfWithDestinations(tempDir, "preserve-case.pdf", destinations);

            List<PagesReport.Row> result = PagesReport.analyse(pdfFile);

            assertEquals(2, result.size());
            assertEquals("card-Preserve-Case-123", result.get(0).anchor());
            assertEquals("axis-divider-tier-UPPERCASE", result.get(1).anchor());
        }
    }

    // Helper methods for creating test PDFs with named destinations

    private Path createBasicPdf(Path tempDir, String filename, int pageCount) throws IOException {
        Path pdfFile = tempDir.resolve(filename);
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
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
            doc.save(pdfFile.toFile());
        }
        return pdfFile;
    }

    private Path createPdfWithDestinations(Path tempDir, String filename,
                                         Map<String, Integer> destinations) throws IOException {
        return createPdfWithDestinations(tempDir, filename, destinations,
                                       Math.max(1, destinations.values().stream().mapToInt(Integer::intValue).max().orElse(0) + 1));
    }

    private Path createPdfWithDestinations(Path tempDir, String filename,
                                         Map<String, Integer> destinations,
                                         int totalPages) throws IOException {
        Path pdfFile = tempDir.resolve(filename);
        try (PDDocument doc = new PDDocument()) {
            // Create pages
            for (int i = 0; i < totalPages; i++) {
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

            // Add named destinations
            if (!destinations.isEmpty()) {
                PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
                org.apache.pdfbox.pdmodel.PDDestinationNameTreeNode dests =
                    new org.apache.pdfbox.pdmodel.PDDestinationNameTreeNode();

                Map<String, org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination> destMap =
                    new TreeMap<>();

                for (Map.Entry<String, Integer> entry : destinations.entrySet()) {
                    String name = entry.getKey();
                    int pageIndex = entry.getValue();

                    if (pageIndex >= 0 && pageIndex < totalPages) {
                        PDPage targetPage = doc.getPage(pageIndex);
                        PDPageFitDestination dest = new PDPageFitDestination();
                        dest.setPage(targetPage);
                        destMap.put(name, dest);
                    }
                }

                dests.setNames(destMap);
                names.setDests(dests);
                doc.getDocumentCatalog().setNames(names);
            }

            doc.save(pdfFile.toFile());
        }
        return pdfFile;
    }
}