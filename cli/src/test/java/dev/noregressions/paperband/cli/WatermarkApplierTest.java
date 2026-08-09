package dev.noregressions.paperband.cli;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WatermarkApplierTest {

    @Nested
    @DisplayName("Color parsing")
    class ColorParsing {

        @Test
        void should_parse_full_hex_colors() {
            float[] red = WatermarkApplier.parseColor("#FF0000");
            assertArrayEquals(new float[]{1.0f, 0.0f, 0.0f}, red, 0.001f);

            float[] green = WatermarkApplier.parseColor("00FF00");
            assertArrayEquals(new float[]{0.0f, 1.0f, 0.0f}, green, 0.001f);

            float[] blue = WatermarkApplier.parseColor("#0000FF");
            assertArrayEquals(new float[]{0.0f, 0.0f, 1.0f}, blue, 0.001f);
        }

        @Test
        void should_parse_short_hex_colors() {
            float[] red = WatermarkApplier.parseColor("#F00");
            assertArrayEquals(new float[]{1.0f, 0.0f, 0.0f}, red, 0.001f);

            float[] white = WatermarkApplier.parseColor("FFF");
            assertArrayEquals(new float[]{1.0f, 1.0f, 1.0f}, white, 0.001f);

            float[] black = WatermarkApplier.parseColor("#000");
            assertArrayEquals(new float[]{0.0f, 0.0f, 0.0f}, black, 0.001f);
        }

        @Test
        void should_handle_mixed_case() {
            float[] purple = WatermarkApplier.parseColor("#fF00Ff");
            assertArrayEquals(new float[]{1.0f, 0.0f, 1.0f}, purple, 0.001f);

            float[] cyan = WatermarkApplier.parseColor("00ffFF");
            assertArrayEquals(new float[]{0.0f, 1.0f, 1.0f}, cyan, 0.001f);
        }

        @Test
        void should_handle_whitespace() {
            float[] color = WatermarkApplier.parseColor("  #FF0000  ");
            assertArrayEquals(new float[]{1.0f, 0.0f, 0.0f}, color, 0.001f);
        }

        @Test
        void should_fall_back_to_mid_grey_for_invalid_input() {
            float[] midGrey = new float[]{0.53f, 0.53f, 0.53f};

            assertArrayEquals(midGrey, WatermarkApplier.parseColor(null), 0.001f);
            assertArrayEquals(midGrey, WatermarkApplier.parseColor(""), 0.001f);
            assertArrayEquals(midGrey, WatermarkApplier.parseColor("   "), 0.001f);
            assertArrayEquals(midGrey, WatermarkApplier.parseColor("invalid"), 0.001f);
            assertArrayEquals(midGrey, WatermarkApplier.parseColor("#GGHHII"), 0.001f);
            assertArrayEquals(midGrey, WatermarkApplier.parseColor("12345"), 0.001f); // Wrong length
            assertArrayEquals(midGrey, WatermarkApplier.parseColor("1234567"), 0.001f); // Wrong length
        }

        @Test
        void should_handle_partial_invalid_hex() {
            float[] midGrey = new float[]{0.53f, 0.53f, 0.53f};

            assertArrayEquals(midGrey, WatermarkApplier.parseColor("FF00GG"), 0.001f);
            assertArrayEquals(midGrey, WatermarkApplier.parseColor("#12XX34"), 0.001f);
        }
    }

    @Nested
    @DisplayName("Watermark application")
    class WatermarkApplication {

        @Test
        void should_handle_null_watermark(@TempDir Path tempDir) throws IOException {
            Path pdfFile = createBasicPdf(tempDir, "test.pdf");

            // Should not throw and should not modify the file
            assertDoesNotThrow(() -> WatermarkApplier.apply(pdfFile, null));

            // File should still exist and be valid
            assertTrue(Files.exists(pdfFile));
        }

        @Test
        void should_apply_watermark_to_single_page_pdf(@TempDir Path tempDir) throws IOException {
            Path pdfFile = createBasicPdf(tempDir, "single.pdf");
            Watermark watermark = Watermark.withDefaults("TEST");

            assertDoesNotThrow(() -> WatermarkApplier.apply(pdfFile, watermark));

            // Verify PDF is still readable
            try (PDDocument doc = PDDocument.load(pdfFile.toFile())) {
                assertEquals(1, doc.getNumberOfPages());
            }
        }

        @Test
        void should_apply_watermark_to_multi_page_pdf(@TempDir Path tempDir) throws IOException {
            Path pdfFile = createMultiPagePdf(tempDir, "multi.pdf", 3);
            Watermark watermark = Watermark.withDefaults("DRAFT");

            assertDoesNotThrow(() -> WatermarkApplier.apply(pdfFile, watermark));

            // Verify all pages are still present
            try (PDDocument doc = PDDocument.load(pdfFile.toFile())) {
                assertEquals(3, doc.getNumberOfPages());
            }
        }

        @Test
        void should_handle_different_watermark_configurations(@TempDir Path tempDir) throws IOException {
            Path pdfFile = createBasicPdf(tempDir, "configured.pdf");

            // Test various configurations
            Watermark bold = new Watermark("BOLD", "#FF0000", 0.5f, 45f, 72, true);
            assertDoesNotThrow(() -> WatermarkApplier.apply(pdfFile, bold));

            // Create fresh PDF for next test
            Path pdfFile2 = createBasicPdf(tempDir, "configured2.pdf");
            Watermark light = new Watermark("LIGHT", "#0000FF", 0.1f, -45f, 24, false);
            assertDoesNotThrow(() -> WatermarkApplier.apply(pdfFile2, light));
        }

        @Test
        void should_preserve_pdf_structure(@TempDir Path tempDir) throws IOException {
            Path pdfFile = createBasicPdf(tempDir, "structure.pdf");
            Watermark watermark = Watermark.withDefaults("PRESERVE");

            // Get original page count and size
            int originalPages;
            PDRectangle originalSize;
            try (PDDocument doc = PDDocument.load(pdfFile.toFile())) {
                originalPages = doc.getNumberOfPages();
                originalSize = doc.getPage(0).getMediaBox();
            }

            WatermarkApplier.apply(pdfFile, watermark);

            // Verify structure is preserved
            try (PDDocument doc = PDDocument.load(pdfFile.toFile())) {
                assertEquals(originalPages, doc.getNumberOfPages());
                PDRectangle newSize = doc.getPage(0).getMediaBox();
                assertEquals(originalSize.getWidth(), newSize.getWidth(), 0.001f);
                assertEquals(originalSize.getHeight(), newSize.getHeight(), 0.001f);
            }
        }

        @Test
        void should_throw_io_exception_for_nonexistent_file(@TempDir Path tempDir) {
            Path nonexistentFile = tempDir.resolve("nonexistent.pdf");
            Watermark watermark = Watermark.withDefaults("TEST");

            assertThrows(IOException.class, () ->
                WatermarkApplier.apply(nonexistentFile, watermark));
        }

        @Test
        void should_throw_io_exception_for_invalid_pdf(@TempDir Path tempDir) throws IOException {
            Path invalidPdf = tempDir.resolve("invalid.pdf");
            Files.writeString(invalidPdf, "This is not a PDF file");

            Watermark watermark = Watermark.withDefaults("TEST");

            assertThrows(IOException.class, () ->
                WatermarkApplier.apply(invalidPdf, watermark));
        }

        @Test
        void should_handle_unreadable_pdf(@TempDir Path tempDir) throws IOException {
            Path pdfFile = createBasicPdf(tempDir, "readonly.pdf");
            Watermark watermark = Watermark.withDefaults("TEST");

            // Make file read-only
            try {
                Files.setPosixFilePermissions(pdfFile,
                    java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ));

                // Should throw IOException because it can't write back
                assertThrows(IOException.class, () ->
                    WatermarkApplier.apply(pdfFile, watermark));

            } catch (UnsupportedOperationException e) {
                // POSIX permissions not supported on this system, skip test
                org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "POSIX permissions not supported on this system");
            } finally {
                // Restore permissions for cleanup
                try {
                    Files.setPosixFilePermissions(pdfFile,
                        java.util.Set.of(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
                } catch (Exception ignored) {}
            }
        }
    }

    @Nested
    @DisplayName("Edge cases and robustness")
    class EdgeCasesAndRobustness {

        @Test
        void should_handle_very_large_font_sizes(@TempDir Path tempDir) throws IOException {
            Path pdfFile = createBasicPdf(tempDir, "large-font.pdf");
            Watermark watermark = new Watermark("BIG", "#000000", 0.3f, 0f, 200, true);

            assertDoesNotThrow(() -> WatermarkApplier.apply(pdfFile, watermark));
        }

        @Test
        void should_handle_very_small_font_sizes(@TempDir Path tempDir) throws IOException {
            Path pdfFile = createBasicPdf(tempDir, "small-font.pdf");
            Watermark watermark = new Watermark("tiny", "#000000", 0.8f, 0f, 8, false);

            assertDoesNotThrow(() -> WatermarkApplier.apply(pdfFile, watermark));
        }

        @Test
        void should_handle_extreme_angles(@TempDir Path tempDir) throws IOException {
            Path pdfFile = createBasicPdf(tempDir, "extreme-angle.pdf");
            Watermark watermark = new Watermark("ROTATED", "#FF0000", 0.5f, 359f, 48, true);

            assertDoesNotThrow(() -> WatermarkApplier.apply(pdfFile, watermark));
        }

        @Test
        void should_handle_very_long_text(@TempDir Path tempDir) throws IOException {
            Path pdfFile = createBasicPdf(tempDir, "long-text.pdf");
            String longText = "This is a very long watermark text that might span across multiple lines";
            Watermark watermark = new Watermark(longText, "#888888", 0.2f, -30f, 24, false);

            assertDoesNotThrow(() -> WatermarkApplier.apply(pdfFile, watermark));
        }

        @Test
        void should_handle_special_characters(@TempDir Path tempDir) throws IOException {
            Path pdfFile = createBasicPdf(tempDir, "special-chars.pdf");
            Watermark watermark = new Watermark("ÅÄÖÜ@#$%^&*()", "#666666", 0.3f, 0f, 36, true);

            assertDoesNotThrow(() -> WatermarkApplier.apply(pdfFile, watermark));
        }

        @Test
        void should_handle_minimum_and_maximum_opacity(@TempDir Path tempDir) throws IOException {
            Path pdfFile1 = createBasicPdf(tempDir, "min-opacity.pdf");
            Watermark transparent = new Watermark("BARELY", "#FF0000", 0.01f, 0f, 48, true);
            assertDoesNotThrow(() -> WatermarkApplier.apply(pdfFile1, transparent));

            Path pdfFile2 = createBasicPdf(tempDir, "max-opacity.pdf");
            Watermark opaque = new Watermark("SOLID", "#FF0000", 1.0f, 0f, 48, true);
            assertDoesNotThrow(() -> WatermarkApplier.apply(pdfFile2, opaque));
        }

        @Test
        void should_handle_different_page_sizes(@TempDir Path tempDir) throws IOException {
            // Test with A4
            Path a4File = createPdfWithSize(tempDir, "a4.pdf", PDRectangle.A4);
            Watermark watermark = Watermark.withDefaults("A4 TEST");
            assertDoesNotThrow(() -> WatermarkApplier.apply(a4File, watermark));

            // Test with Letter
            Path letterFile = createPdfWithSize(tempDir, "letter.pdf", PDRectangle.LETTER);
            assertDoesNotThrow(() -> WatermarkApplier.apply(letterFile, watermark));

            // Test with custom small size
            Path smallFile = createPdfWithSize(tempDir, "small.pdf", new PDRectangle(200, 200));
            assertDoesNotThrow(() -> WatermarkApplier.apply(smallFile, watermark));
        }
    }

    // Helper methods for creating test PDFs

    private Path createBasicPdf(Path tempDir, String filename) throws IOException {
        return createPdfWithSize(tempDir, filename, PDRectangle.A4);
    }

    private Path createPdfWithSize(Path tempDir, String filename, PDRectangle pageSize) throws IOException {
        Path pdfFile = tempDir.resolve(filename);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(pageSize);
            doc.addPage(page);

            // Add minimal content so it's a valid PDF
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(100, 700);
                content.showText("Test Page");
                content.endText();
            }

            doc.save(pdfFile.toFile());
        }
        return pdfFile;
    }

    private Path createMultiPagePdf(Path tempDir, String filename, int pageCount) throws IOException {
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
}