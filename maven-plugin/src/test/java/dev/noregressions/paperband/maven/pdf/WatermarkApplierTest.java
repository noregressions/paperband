package dev.noregressions.paperband.maven.pdf;

import dev.noregressions.paperband.model.Watermark;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Watermark stamping")
class WatermarkApplierTest {

    @TempDir
    Path dir;

    private final List<String> warnings = new ArrayList<>();

    // ---- text ----

    @Test
    void the_text_lands_on_every_page() throws IOException {
        Path pdf = pdf(3);

        assertTrue(apply(pdf, Watermark.withDefaults("DRAFT")));

        for (int page = 1; page <= 3; page++) {
            assertTrue(textOf(pdf, page).contains("DRAFT"), "page " + page);
        }
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void the_page_content_underneath_survives() throws IOException {
        // The whole point of a post-pass: it must not disturb what was rendered.
        Path pdf = pdf(2);

        apply(pdf, Watermark.withDefaults("DRAFT"));

        assertTrue(textOf(pdf, 1).contains("body 1"));
        assertTrue(textOf(pdf, 2).contains("body 2"));
    }

    @Test
    void pages_first_stamps_only_the_first_page() throws IOException {
        Path pdf = pdf(3);

        apply(pdf, Watermark.fromYaml(Map.of("text", "DRAFT", "pages", "first")));

        assertTrue(textOf(pdf, 1).contains("DRAFT"));
        assertFalse(textOf(pdf, 2).contains("DRAFT"));
        assertFalse(textOf(pdf, 3).contains("DRAFT"));
    }

    @Test
    void pages_except_cover_leaves_the_cover_clean() throws IOException {
        Path pdf = pdf(3);

        apply(pdf, Watermark.fromYaml(Map.of("text", "DRAFT", "pages", "except-cover")));

        assertFalse(textOf(pdf, 1).contains("DRAFT"), "the cover is a designed page");
        assertTrue(textOf(pdf, 2).contains("DRAFT"));
        assertTrue(textOf(pdf, 3).contains("DRAFT"));
    }

    @Test
    void a_selection_that_matches_no_page_warns_instead_of_writing_nothing() throws IOException {
        Path pdf = pdf(1);

        assertFalse(apply(pdf, Watermark.fromYaml(
                Map.of("text", "DRAFT", "pages", "except-cover"))));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("no pages"), warnings.toString());
    }

    @Test
    void every_line_of_a_multi_line_stamp_is_drawn() throws IOException {
        Path pdf = pdf(1);

        apply(pdf, Watermark.withDefaults("NOT FOR\nRESALE"));

        String text = textOf(pdf, 1);
        assertTrue(text.contains("NOT FOR"), text);
        assertTrue(text.contains("RESALE"), text);
    }

    @Test
    void tiling_repeats_the_stamp_across_the_page() throws IOException {
        Path pdf = pdf(1);

        apply(pdf, Watermark.fromYaml(Map.of("text", "DRAFT", "tile", true)));

        assertEquals(12, count(textOf(pdf, 1), "DRAFT"));
    }

    @Test
    void behind_draws_the_stamp_before_the_page_content() throws IOException {
        // Content order is paint order: prepended content is painted over.
        Path pdf = pdf(1);

        apply(pdf, Watermark.fromYaml(Map.of("text", "DRAFT", "behind", true)));

        String text = textOf(pdf, 1);
        assertTrue(text.indexOf("DRAFT") < text.indexOf("body 1"), text);
    }

    @Test
    void the_default_stamp_is_drawn_over_the_page_content() throws IOException {
        Path pdf = pdf(1);

        apply(pdf, Watermark.withDefaults("DRAFT"));

        String text = textOf(pdf, 1);
        assertTrue(text.indexOf("body 1") < text.indexOf("DRAFT"), text);
    }

    // ---- fitting ----

    /** Six times the width of A4 at the declared 96pt. */
    private static final String LONG = "SAMPLE COPY - NOT FOR RESALE OR REDISTRIBUTION";

    @Test
    void a_long_phrase_is_shrunk_to_stay_on_the_paper() throws IOException {
        Path pdf = pdf(1);

        apply(pdf, Watermark.withDefaults(LONG));

        for (float[] p : stampPositions(pdf)) {
            assertTrue(p[0] >= -1 && p[0] <= A4_WIDTH + 1, "x " + p[0] + " is off the page");
            assertTrue(p[1] >= -1 && p[1] <= A4_HEIGHT + 1, "y " + p[1] + " is off the page");
        }
    }

    @Test
    void fit_off_lets_the_stamp_run_off_the_page() throws IOException {
        // The other half of the previous test: without fit the same phrase
        // overflows, which is what makes the fitted case worth asserting.
        Path pdf = pdf(1);

        apply(pdf, Watermark.fromYaml(Map.of("text", LONG, "fit", false)));

        boolean overflows = false;
        for (float[] p : stampPositions(pdf)) {
            if (p[0] < -1 || p[0] > A4_WIDTH + 1) overflows = true;
        }
        assertTrue(overflows, "the declared 96pt should still be honoured exactly");
    }

    @Test
    void fitScale_shrinks_only_when_it_has_to() {
        assertEquals(1f, WatermarkApplier.fitScale(10, 10, 595, 842, 0f));
        assertTrue(WatermarkApplier.fitScale(2000, 100, 595, 842, 0f) < 0.3f);
        // Rotated, the same block is limited by the page's height instead.
        assertTrue(WatermarkApplier.fitScale(800, 100, 595, 842, -90f) < 1f);
    }

    // ---- fonts ----

    @Test
    void text_the_font_cannot_encode_warns_and_leaves_the_pdf_untouched() throws IOException {
        // Helvetica is WinAnsi; a CJK stamp used to throw straight out of the
        // build after a successful render.
        Path pdf = pdf(1);
        byte[] before = Files.readAllBytes(pdf);

        assertFalse(apply(pdf, Watermark.withDefaults("机密")));

        assertArrayEquals(before, Files.readAllBytes(pdf));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("机"), warnings.get(0));
        assertTrue(warnings.get(0).contains("font:"), "the warning should say how to fix it");
    }

    @Test
    void a_named_font_file_that_is_missing_warns_rather_than_failing() throws IOException {
        Path pdf = pdf(1);

        assertFalse(apply(pdf, Watermark.fromYaml(
                Map.of("text", "DRAFT", "font", "fonts/nope.ttf"))));

        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("font not found"), warnings.get(0));
    }

    @Test
    void unencodable_names_the_characters_rather_than_the_first_one() {
        String bad = WatermarkApplier.unencodable(
                PDType1Font.HELVETICA, List.of("A机", "B密"));

        assertNotNull(bad);
        assertTrue(bad.contains("机") && bad.contains("密"), bad);
    }

    @Test
    void unencodable_is_null_for_text_the_font_can_set() {
        assertNull(WatermarkApplier.unencodable(
                PDType1Font.HELVETICA, List.of("DRAFT", "NOT FOR RESALE")));
    }

    // ---- images ----

    @Test
    void an_image_watermark_is_drawn_as_an_xobject() throws IOException {
        Path pdf = pdf(2);
        writePng(dir.resolve("logo.png"));

        assertTrue(apply(pdf, Watermark.imageWithDefaults("logo.png")));

        try (PDDocument doc = PDDocument.load(pdf.toFile())) {
            for (PDPage page : doc.getPages()) {
                assertTrue(page.getResources().getXObjectNames().iterator().hasNext(),
                        "every page should carry the image");
            }
        }
    }

    @Test
    void a_missing_image_warns_rather_than_failing_the_build() throws IOException {
        Path pdf = pdf(1);

        assertFalse(apply(pdf, Watermark.imageWithDefaults("logo.png")));

        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("image not found"), warnings.get(0));
    }

    @Test
    void an_image_watermark_resolves_against_the_book_root() throws IOException {
        Path pdf = pdf(1);
        Files.createDirectories(dir.resolve("brand"));
        writePng(dir.resolve("brand/logo.png"));

        assertTrue(apply(pdf, Watermark.imageWithDefaults("brand/logo.png")));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    // ---- odds and ends ----

    @Test
    void a_null_watermark_is_a_no_op() throws IOException {
        Path pdf = pdf(1);
        byte[] before = Files.readAllBytes(pdf);

        assertFalse(apply(pdf, null));

        assertArrayEquals(before, Files.readAllBytes(pdf));
    }

    @Test
    void a_malformed_colour_falls_back_to_grey() {
        assertArrayEquals(new float[]{0.53f, 0.53f, 0.53f}, WatermarkApplier.parseColor("zzz"));
        assertArrayEquals(new float[]{1f, 1f, 1f}, WatermarkApplier.parseColor("#fff"));
        assertArrayEquals(new float[]{0f, 0f, 0f}, WatermarkApplier.parseColor("000000"));
    }

    // ---- helpers ----

    private boolean apply(Path pdf, Watermark w) throws IOException {
        return WatermarkApplier.apply(pdf, w, dir, warnings::add);
    }

    /** An A4 PDF of {@code pages} pages, each carrying "body N". */
    private Path pdf(int pages) throws IOException {
        Path file = dir.resolve("book.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA, 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText("body " + i);
                    cs.endText();
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    private static String textOf(Path pdf, int page) throws IOException {
        try (PDDocument doc = PDDocument.load(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            return stripper.getText(doc);
        }
    }

    private static final float A4_WIDTH = PDRectangle.A4.getWidth();
    private static final float A4_HEIGHT = PDRectangle.A4.getHeight();

    /**
     * Where every glyph of the watermark landed on page one, as (x, y) pairs.
     * The page's own "body 1" is filtered out.
     */
    private static List<float[]> stampPositions(Path pdf) throws IOException {
        List<float[]> positions = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> tps) {
                    for (TextPosition tp : tps) {
                        if (tp.getUnicode().isBlank()) continue;
                        // The body text is the only 12pt run on the page.
                        if (tp.getFontSizeInPt() <= 12f) continue;
                        positions.add(new float[]{tp.getX(), tp.getY()});
                    }
                }
            };
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            stripper.getText(doc);
        }
        assertFalse(positions.isEmpty(), "the stamp should have been drawn");
        return positions;
    }

    private static void writePng(Path file) throws IOException {
        BufferedImage img = new BufferedImage(60, 20, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "png", file.toFile());
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }
}
