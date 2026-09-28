package dev.noregressions.paperband.render.pptx;

import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Everything downstream of the browser: harvested cards to a real .pptx.
 *
 * <p>Fed hand-written card maps of the shape {@code SlideHarvest.harvest}
 * returns, so the layout mapping, placeholder filling and text styling are all
 * covered without Chromium. Several assertions read the written XML rather than
 * the POI model on purpose — see
 * {@link PlaceholderGeometry#a_title_placeholder_keeps_the_geometry_it_was_given}.
 */
@DisplayName("DeckWriter")
class DeckWriterTest {

    // ---- card-map builders ----

    private static final int W = 1280;   // 16:9 in CSS px
    private static final int H = 720;

    private static Map<String, Object> rect(double x, double y, double w, double h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x);
        m.put("y", y);
        m.put("w", w);
        m.put("h", h);
        return m;
    }

    /** A computed-style map; pairs are key, value. */
    private static Map<String, Object> style(String... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fontFamily", "\"Helvetica Neue\", sans-serif");
        m.put("fontSize", "29.3px");
        m.put("fontWeight", "400");
        m.put("fontStyle", "normal");
        m.put("color", "rgb(244, 246, 248)");
        m.put("textAlign", "start");
        m.put("lineHeight", "40px");
        m.put("letterSpacing", "normal");
        m.put("textTransform", "none");
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }

    private static Map<String, Object> line(String text, Map<String, Object> st) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", text);
        m.put("rect", rect(80, 60, 1100, 70));
        m.put("style", st);
        return m;
    }

    private static Map<String, Object> run(String text, Map<String, Object> st) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", text);
        m.put("style", st);
        return m;
    }

    private static Map<String, Object> para(boolean bullet, Map<String, Object> st,
                                            Map<String, Object>... runs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bullet", bullet);
        m.put("style", st);
        m.put("runs", Arrays.asList(runs));
        return m;
    }

    private static Map<String, Object> region(List<Map<String, Object>> paras) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rect", rect(80, 160, 700, 400));
        m.put("paras", paras);
        return m;
    }

    /** A card with a title and a one-paragraph body — the common shape. */
    private static Map<String, Object> card(String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("background", "rgb(15, 23, 32)");
        m.put("title", line("Title of " + id, style("fontSize", "47px", "fontWeight", "700")));
        m.put("subtitle", null);
        m.put("body", region(List.of(para(false, style(), run("Body of " + id, style())))));
        m.put("aside", null);
        m.put("notes", null);
        m.put("unplaced", null);
        return m;
    }

    private static DeckWriter.Result write(Path out, List<Map<String, Object>> cards,
                                           List<String> warnings) throws IOException {
        return write(out, cards, Map.of(), warnings);
    }

    private static DeckWriter.Result write(Path out, List<Map<String, Object>> cards,
                                           Map<String, byte[]> figures,
                                           List<String> warnings) throws IOException {
        return DeckWriter.write(cards, figures, W, H, out, warnings::add);
    }

    /** A figure entry of the shape the harvest emits. */
    private static Map<String, Object> figure(String id, String kind,
                                              double x, double y, double w, double h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("kind", kind);
        m.put("rect", rect(x, y, w, h));
        return m;
    }

    /** A region carrying figures as well as (possibly no) prose. */
    private static Map<String, Object> regionWith(List<Map<String, Object>> paras,
                                                  List<Map<String, Object>> figures) {
        Map<String, Object> m = region(paras);
        m.put("figures", figures);
        return m;
    }

    /**
     * A tiny real PNG. The bytes have to decode: POI reads the image header to
     * record its native size, so a placeholder like "not a png".getBytes()
     * would be stored without ever exercising the path a diagram takes.
     */
    private static byte[] png(int w, int h) throws IOException {
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.MAGENTA);
        g.fillRect(0, 0, w, h);
        g.dispose();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    // ---- XML access, for the assertions POI's model would mask ----

    private static String slideXml(Path pptx, int n) throws IOException {
        return entry(pptx, "ppt/slides/slide" + n + ".xml");
    }

    private static String entry(Path pptx, String name) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(pptx))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().equals(name)) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    /** The {@code <p:sp>} block whose placeholder tag matches {@code phMarker}. */
    private static String shapeWithPlaceholder(String slideXml, String phMarker) {
        Matcher m = Pattern.compile("<p:sp\\b.*?</p:sp>", Pattern.DOTALL).matcher(slideXml);
        while (m.find()) {
            if (m.group().contains(phMarker)) return m.group();
        }
        return null;
    }

    // ---- tests ----

    @Nested
    @DisplayName("Deck shape")
    class DeckShape {

        @Test
        void writes_one_slide_per_card(@TempDir Path dir) throws IOException {
            Path out = dir.resolve("deck.pptx");
            DeckWriter.Result r = write(out, List.of(card("a"), card("b"), card("c")),
                    new ArrayList<>());

            assertEquals(3, r.slides());
            try (XMLSlideShow ppt = new XMLSlideShow(Files.newInputStream(out))) {
                assertEquals(3, ppt.getSlides().size());
            }
        }

        @Test
        void the_page_is_powerpoints_own_16_by_9(@TempDir Path dir) throws IOException {
            Path out = dir.resolve("deck.pptx");
            write(out, List.of(card("a")), new ArrayList<>());

            // 960x540pt is what 12192000x6858000 EMU means, and it is what
            // PowerPoint calls widescreen. Getting this wrong letterboxes
            // every slide.
            try (XMLSlideShow ppt = new XMLSlideShow(Files.newInputStream(out))) {
                assertAll(
                        () -> assertEquals(960, ppt.getPageSize().width),
                        () -> assertEquals(540, ppt.getPageSize().height));
            }
            assertTrue(entry(out, "ppt/presentation.xml")
                            .contains("cx=\"12192000\" cy=\"6858000\""),
                    "sldSz should be PowerPoint's widescreen EMU");
        }

        @Test
        void the_written_file_reopens(@TempDir Path dir) throws IOException {
            // Cheap but load-bearing: a malformed relationship graph still
            // writes a zip that POI can't reopen and PowerPoint rejects.
            Path out = dir.resolve("deck.pptx");
            write(out, List.of(card("a")), new ArrayList<>());
            try (InputStream in = Files.newInputStream(out);
                 XMLSlideShow ppt = new XMLSlideShow(in)) {
                assertNotNull(ppt.getSlides().get(0));
            }
        }
    }

    @Nested
    @DisplayName("Layout selection and reuse")
    class Layouts {

        @Test
        void records_which_layout_each_signature_used(@TempDir Path dir) throws IOException {
            Path out = dir.resolve("deck.pptx");
            Map<String, Object> two = card("two");
            two.put("aside", region(List.of(para(false, style(), run("Aside", style())))));

            DeckWriter.Result r = write(out, List.of(card("one"), two), new ArrayList<>());

            assertAll(
                    () -> assertTrue(r.layoutUse().containsKey("Title and Content  <- title+body"),
                            r.layoutUse().toString()),
                    () -> assertTrue(r.layoutUse().containsKey("Two Content  <- title+body+aside"),
                            r.layoutUse().toString()));
        }

        @Test
        void two_cards_of_one_shape_share_a_slide_layout(@TempDir Path dir) throws IOException {
            // The payoff of a slot-based theme. Without shared layouts each
            // slide carries its own geometry and PowerPoint's "change layout"
            // has nothing to switch between.
            Path out = dir.resolve("deck.pptx");
            write(out, List.of(card("a"), card("b")), new ArrayList<>());

            String one = entry(out, "ppt/slides/_rels/slide1.xml.rels");
            String two = entry(out, "ppt/slides/_rels/slide2.xml.rels");
            assertEquals(layoutRef(one), layoutRef(two),
                    "same signature should reference the same slideLayout");
        }

        @Test
        void a_different_shape_uses_a_different_layout(@TempDir Path dir) throws IOException {
            Path out = dir.resolve("deck.pptx");
            Map<String, Object> two = card("two");
            two.put("aside", region(List.of(para(false, style(), run("Aside", style())))));
            write(out, List.of(card("one"), two), new ArrayList<>());

            assertFalse(layoutRef(entry(out, "ppt/slides/_rels/slide1.xml.rels"))
                            .equals(layoutRef(entry(out, "ppt/slides/_rels/slide2.xml.rels"))),
                    "title+body and title+body+aside must not share a layout");
        }

        @Test
        void a_signature_with_no_stock_layout_fails_the_write(@TempDir Path dir) {
            Path out = dir.resolve("deck.pptx");
            Map<String, Object> bad = card("bad");
            bad.put("subtitle", line("A oneliner", style()));   // title+subtitle+body

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> write(out, List.of(bad), new ArrayList<>()));
            assertTrue(e.getMessage().contains("bad"), e.getMessage());
        }

        private String layoutRef(String rels) {
            Matcher m = Pattern.compile("slideLayout(\\d+)\\.xml").matcher(rels);
            assertTrue(m.find(), "rels should reference a slideLayout: " + rels);
            return m.group();
        }
    }

    @Nested
    @DisplayName("Placeholders")
    class Placeholders {

        @Test
        void regions_fill_typed_placeholders_not_loose_text_boxes(@TempDir Path dir)
                throws IOException {
            Path out = dir.resolve("deck.pptx");
            Map<String, Object> c = card("a");
            c.put("aside", region(List.of(para(false, style(), run("Aside", style())))));
            write(out, List.of(c), new ArrayList<>());

            String xml = slideXml(out, 1);
            assertAll(
                    () -> assertTrue(xml.contains("<p:ph type=\"title\"/>"),
                            "title should be a typed placeholder"),
                    () -> assertEquals(2, countContentPlaceholders(xml),
                            "body and aside should each occupy a content placeholder: " + xml));
        }

        @Test
        void an_unused_content_placeholder_is_removed(@TempDir Path dir) throws IOException {
            // Left in place it renders as PowerPoint's "Click to add text"
            // prompt, which looks like an authoring mistake on every slide.
            Path out = dir.resolve("deck.pptx");
            Map<String, Object> titleOnly = card("t");
            titleOnly.put("body", null);
            write(out, List.of(titleOnly), new ArrayList<>());

            try (XMLSlideShow ppt = new XMLSlideShow(Files.newInputStream(out))) {
                long empty = ppt.getSlides().get(0).getShapes().stream()
                        .filter(s -> s instanceof XSLFTextShape t
                                && t.getText() != null && t.getText().isBlank())
                        .count();
                assertEquals(0, empty, "no empty placeholder should survive");
            }
        }

        @Test
        void the_layouts_date_and_page_furniture_is_dropped(@TempDir Path dir) throws IOException {
            Path out = dir.resolve("deck.pptx");
            write(out, List.of(card("a")), new ArrayList<>());

            try (XMLSlideShow ppt = new XMLSlideShow(Files.newInputStream(out))) {
                List<Placeholder> kinds = new ArrayList<>();
                for (XSLFShape s : ppt.getSlides().get(0).getShapes()) {
                    if (s instanceof XSLFTextShape t && t.getTextType() != null) {
                        kinds.add(t.getTextType());
                    }
                }
                assertAll(
                        () -> assertFalse(kinds.contains(Placeholder.DATETIME), kinds.toString()),
                        () -> assertFalse(kinds.contains(Placeholder.FOOTER), kinds.toString()),
                        () -> assertFalse(kinds.contains(Placeholder.SLIDE_NUMBER), kinds.toString()));
            }
        }

        private int countContentPlaceholders(String xml) {
            // Content placeholders carry an idx but no type attribute.
            Matcher m = Pattern.compile("<p:ph(?![^>]*type=)[^>]*idx=\"\\d+\"[^>]*/>").matcher(xml);
            int n = 0;
            while (m.find()) n++;
            return n;
        }
    }

    @Nested
    @DisplayName("Placeholder geometry")
    class PlaceholderGeometry {

        @Test
        void a_title_placeholder_keeps_the_geometry_it_was_given(@TempDir Path dir)
                throws IOException {
            // Regression test for a POI 5.2.5 quirk that cost real time:
            // setAnchor() is silently discarded on write for a TITLE
            // placeholder — it persists for BODY/CONTENT, and getAnchor()
            // keeps reporting the value that was set, so the POI model looks
            // correct while the written <p:spPr/> is empty and PowerPoint
            // falls back to the layout's own title position (drawing the
            // title on top of the body). Only the written XML shows it, which
            // is why this reads the zip rather than reopening with POI.
            Path out = dir.resolve("deck.pptx");
            write(out, List.of(card("a")), new ArrayList<>());

            String title = shapeWithPlaceholder(slideXml(out, 1), "<p:ph type=\"title\"/>");
            assertNotNull(title, "slide should have a title placeholder");
            assertTrue(title.contains("<a:off"),
                    "title placeholder must carry its own transform, not inherit "
                    + "the layout's: " + title);
        }

        @Test
        void geometry_is_the_measured_rect_converted_to_points(@TempDir Path dir)
                throws IOException {
            Path out = dir.resolve("deck.pptx");
            write(out, List.of(card("a")), new ArrayList<>());

            // The title rect is (80, 60) px; at 96 px/in that is (60, 45) pt,
            // and EMU is 12700 per point.
            String title = shapeWithPlaceholder(slideXml(out, 1), "<p:ph type=\"title\"/>");
            Matcher off = Pattern.compile("<a:off x=\"(-?\\d+)\" y=\"(-?\\d+)\"/>").matcher(title);
            assertTrue(off.find(), title);
            assertAll(
                    () -> assertEquals(60 * DeckWriter.EMU, Long.parseLong(off.group(1))),
                    () -> assertEquals(45 * DeckWriter.EMU, Long.parseLong(off.group(2))));
        }

        @Test
        void a_region_with_no_rect_is_still_written(@TempDir Path dir) throws IOException {
            // The harvest always supplies a rect, but a caller (or a future
            // layout-owned-geometry mode) may not — the text must not vanish.
            Path out = dir.resolve("deck.pptx");
            Map<String, Object> c = card("a");
            Map<String, Object> body = region(List.of(
                    para(false, style(), run("Still here", style()))));
            body.remove("rect");
            c.put("body", body);
            write(out, List.of(c), new ArrayList<>());

            assertTrue(slideXml(out, 1).contains("Still here"));
        }
    }

    @Nested
    @DisplayName("Text and styling")
    class Styling {

        @Test
        void each_list_item_becomes_its_own_bulleted_paragraph(@TempDir Path dir)
                throws IOException {
            Path out = dir.resolve("deck.pptx");
            Map<String, Object> c = card("a");
            c.put("body", region(List.of(
                    para(true, style(), run("one", style())),
                    para(true, style(), run("two", style())),
                    para(true, style(), run("three", style())))));
            write(out, List.of(c), new ArrayList<>());

            try (XMLSlideShow ppt = new XMLSlideShow(Files.newInputStream(out))) {
                XSLFTextShape body = contentShape(ppt.getSlides().get(0));
                List<XSLFTextParagraph> paras = body.getTextParagraphs();
                assertAll(
                        () -> assertEquals(3, paras.size()),
                        () -> assertTrue(paras.get(0).isBullet(), "bullets should be bulleted"),
                        () -> assertEquals("one", paras.get(0).getText()),
                        () -> assertEquals("three", paras.get(2).getText()));
            }
        }

        @Test
        void a_paragraph_keeps_one_run_per_inline_style(@TempDir Path dir) throws IOException {
            // The bug this covers: reading computed style at block level lost
            // every inline distinction, so an inline <code> silently rendered
            // in the surrounding body font.
            Path out = dir.resolve("deck.pptx");
            Map<String, Object> c = card("a");
            c.put("body", region(List.of(para(true, style(),
                    run("enforced by ", style()),
                    run("max_pages: 1", style("fontFamily", "ui-monospace, Menlo, monospace"))))));
            write(out, List.of(c), new ArrayList<>());

            try (XMLSlideShow ppt = new XMLSlideShow(Files.newInputStream(out))) {
                List<XSLFTextRun> runs =
                        contentShape(ppt.getSlides().get(0)).getTextParagraphs().get(0).getTextRuns();
                assertAll(
                        () -> assertEquals(2, runs.size()),
                        () -> assertEquals("Helvetica Neue", runs.get(0).getFontFamily()),
                        () -> assertEquals("Menlo", runs.get(1).getFontFamily(),
                                "ui-monospace must resolve to a face PowerPoint knows"));
            }
        }

        @Test
        void text_transform_is_baked_into_the_characters(@TempDir Path dir) throws IOException {
            // PowerPoint has no text-transform, so a theme's uppercase kicker
            // is lost unless the exporter applies it.
            Path out = dir.resolve("deck.pptx");
            Map<String, Object> c = card("a");
            c.put("body", region(List.of(para(false,
                    style("textTransform", "uppercase"),
                    run("Pipeline", style("textTransform", "uppercase"))))));
            write(out, List.of(c), new ArrayList<>());

            assertTrue(slideXml(out, 1).contains("PIPELINE"),
                    "uppercase should be applied to the text itself");
        }

        @Test
        void weight_size_and_colour_reach_the_run(@TempDir Path dir) throws IOException {
            Path out = dir.resolve("deck.pptx");
            Map<String, Object> c = card("a");
            c.put("body", region(List.of(para(false, style(), run("Bold green",
                    style("fontWeight", "700", "fontSize", "40px",
                          "color", "rgb(110, 231, 183)"))))));
            write(out, List.of(c), new ArrayList<>());

            try (XMLSlideShow ppt = new XMLSlideShow(Files.newInputStream(out))) {
                XSLFTextRun r = contentShape(ppt.getSlides().get(0))
                        .getTextParagraphs().get(0).getTextRuns().get(0);
                assertAll(
                        () -> assertTrue(r.isBold()),
                        () -> assertEquals(30.0, r.getFontSize(), 0.01,
                                "40px at 96dpi is 30pt"),
                        () -> assertEquals(new java.awt.Color(110, 231, 183),
                                solid(r.getFontColor())));
            }
        }

        @Test
        void a_run_with_no_style_still_writes_its_text(@TempDir Path dir) throws IOException {
            Path out = dir.resolve("deck.pptx");
            Map<String, Object> c = card("a");
            Map<String, Object> bare = new LinkedHashMap<>();
            bare.put("text", "No style at all");
            bare.put("style", null);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("bullet", false);
            p.put("style", null);
            p.put("runs", List.of(bare));
            c.put("body", region(List.of(p)));
            write(out, List.of(c), new ArrayList<>());

            assertTrue(slideXml(out, 1).contains("No style at all"));
        }

        private java.awt.Color solid(org.apache.poi.sl.usermodel.PaintStyle paint) {
            return paint instanceof org.apache.poi.sl.usermodel.PaintStyle.SolidPaint sp
                    ? org.apache.poi.sl.draw.DrawPaint.applyColorTransform(sp.getSolidColor())
                    : null;
        }

        private XSLFTextShape contentShape(XSLFSlide slide) {
            for (XSLFShape s : slide.getShapes()) {
                if (s instanceof XSLFTextShape t && t.getTextType() != null) {
                    switch (t.getTextType()) {
                        case BODY, CONTENT -> {
                            return t;
                        }
                        default -> { }
                    }
                }
            }
            throw new AssertionError("no content placeholder on the slide");
        }
    }

    @Nested
    @DisplayName("Figures")
    class Figures {

        @Test
        void a_diagram_becomes_a_picture_at_the_rect_it_occupied(@TempDir Path dir)
                throws IOException {
            Path out = dir.resolve("deck.pptx");
            Map<String, Object> c = card("d");
            c.put("body", regionWith(
                    List.of(para(false, style(), run("Before the diagram", style()))),
                    List.of(figure("c0b-0", "svg", 100, 200, 600, 300))));

            DeckWriter.Result r = write(out, List.of(c),
                    Map.of("c0b-0", png(1200, 600)), new ArrayList<>());

            assertEquals(1, r.pictures());
            try (XMLSlideShow ppt = new XMLSlideShow(Files.newInputStream(out))) {
                List<XSLFPictureShape> pics = pictures(ppt.getSlides().get(0));
                assertEquals(1, pics.size());
                // 100,200 px at 96 px/in is 75,150 pt; 600x300 px is 450x225 pt.
                assertAll(
                        () -> assertEquals(75, pics.get(0).getAnchor().getX(), 0.5),
                        () -> assertEquals(150, pics.get(0).getAnchor().getY(), 0.5),
                        () -> assertEquals(450, pics.get(0).getAnchor().getWidth(), 0.5),
                        () -> assertEquals(225, pics.get(0).getAnchor().getHeight(), 0.5));
            }
        }

        @Test
        void the_capture_is_higher_resolution_than_the_frame_it_fills(@TempDir Path dir)
                throws IOException {
            // The 2x device scale factor is what makes a diagram crisp on a
            // projector: the extra pixels become resolution, not size.
            Path out = dir.resolve("deck.pptx");
            Map<String, Object> c = card("d");
            c.put("body", regionWith(List.of(), List.of(figure("f", "svg", 0, 0, 600, 300))));
            write(out, List.of(c), Map.of("f", png(1200, 600)), new ArrayList<>());

            try (XMLSlideShow ppt = new XMLSlideShow(Files.newInputStream(out))) {
                XSLFPictureShape pic = pictures(ppt.getSlides().get(0)).get(0);
                assertAll(
                        // getImageDimension() reports points; the pixel count
                        // is what carries the resolution.
                        () -> assertEquals(1200,
                                pic.getPictureData().getImageDimensionInPixels().width,
                                "image should carry the 2x pixels"),
                        () -> assertEquals(450, pic.getAnchor().getWidth(), 0.5,
                                "but be framed at its CSS size in points"),
                        () -> assertTrue(pic.getPictureData().getImageDimensionInPixels().width
                                        > pic.getAnchor().getWidth(),
                                "more pixels than points is the whole point"));
            }
        }

        @Test
        void a_figure_only_region_leaves_no_empty_placeholder(@TempDir Path dir)
                throws IOException {
            // A card whose body is nothing but a diagram must not also carry
            // PowerPoint's "Click to add text" prompt behind the picture.
            Path out = dir.resolve("deck.pptx");
            Map<String, Object> c = card("d");
            c.put("body", regionWith(List.of(), List.of(figure("f", "svg", 80, 160, 600, 300))));
            write(out, List.of(c), Map.of("f", png(600, 300)), new ArrayList<>());

            try (XMLSlideShow ppt = new XMLSlideShow(Files.newInputStream(out))) {
                XSLFSlide slide = ppt.getSlides().get(0);
                long emptyText = slide.getShapes().stream()
                        .filter(sh -> sh instanceof XSLFTextShape t
                                && t.getTextType() != null
                                && (t.getText() == null || t.getText().isBlank()))
                        .count();
                assertAll(
                        () -> assertEquals(0, emptyText, "no empty placeholder should survive"),
                        () -> assertEquals(1, pictures(slide).size()),
                        () -> assertTrue(slide.getShapes().stream()
                                        .anyMatch(sh -> sh instanceof XSLFTextShape t
                                                && t.getTextType() == Placeholder.TITLE),
                                "the title must still be there"));
            }
        }

        @Test
        void prose_and_a_figure_can_share_a_region(@TempDir Path dir) throws IOException {
            Path out = dir.resolve("deck.pptx");
            Map<String, Object> c = card("d");
            c.put("body", regionWith(
                    List.of(para(true, style(), run("A bullet", style()))),
                    List.of(figure("f", "table", 80, 400, 600, 200))));
            write(out, List.of(c), Map.of("f", png(600, 200)), new ArrayList<>());

            try (XMLSlideShow ppt = new XMLSlideShow(Files.newInputStream(out))) {
                XSLFSlide slide = ppt.getSlides().get(0);
                assertAll(
                        () -> assertEquals(1, pictures(slide).size()),
                        () -> assertTrue(slideXmlOf(out).contains("A bullet"),
                                "the prose should still fill its placeholder"));
            }
        }

        @Test
        void figures_in_both_regions_are_all_placed(@TempDir Path dir) throws IOException {
            Path out = dir.resolve("deck.pptx");
            Map<String, Object> c = card("d");
            c.put("body", regionWith(List.of(), List.of(figure("b0", "svg", 0, 0, 400, 200))));
            c.put("aside", regionWith(List.of(), List.of(figure("a0", "pre", 500, 0, 300, 200))));

            DeckWriter.Result r = write(out, List.of(c),
                    Map.of("b0", png(800, 400), "a0", png(600, 400)), new ArrayList<>());

            assertEquals(2, r.pictures());
        }

        @Test
        void one_image_used_twice_is_stored_once(@TempDir Path dir) throws IOException {
            // POI keys addPicture on the image checksum, so a diagram repeated
            // across slides shouldn't inflate the file.
            Path out = dir.resolve("deck.pptx");
            byte[] shared = png(400, 200);
            List<Map<String, Object>> cards = new ArrayList<>();
            Map<String, byte[]> images = new LinkedHashMap<>();
            for (int i = 0; i < 3; i++) {
                Map<String, Object> c = card("c" + i);
                c.put("body", regionWith(List.of(),
                        List.of(figure("f" + i, "svg", 0, 0, 400, 200))));
                cards.add(c);
                images.put("f" + i, shared);
            }
            DeckWriter.Result r = write(out, cards, images, new ArrayList<>());

            assertEquals(3, r.pictures());
            try (XMLSlideShow ppt = new XMLSlideShow(Files.newInputStream(out))) {
                assertEquals(1, ppt.getPictureData().size(),
                        "identical images should be deduplicated");
            }
        }

        @Test
        void a_figure_with_no_captured_image_is_warned_about_not_dropped_silently(
                @TempDir Path dir) throws IOException {
            // A diagram that failed to screenshot costs its own slide a
            // picture. The build should say so — the alternative is a slide
            // that looks finished and isn't.
            Path out = dir.resolve("deck.pptx");
            List<String> warnings = new ArrayList<>();
            Map<String, Object> c = card("d");
            c.put("body", regionWith(
                    List.of(para(false, style(), run("Text survives", style()))),
                    List.of(figure("missing", "svg", 0, 0, 400, 200))));

            DeckWriter.Result r = write(out, List.of(c), Map.of(), warnings);

            assertAll(
                    () -> assertEquals(0, r.pictures()),
                    () -> assertEquals(1, warnings.size(), warnings.toString()),
                    () -> assertTrue(warnings.get(0).contains("svg"), warnings.get(0)),
                    () -> assertTrue(warnings.get(0).contains("'d'"), warnings.get(0)),
                    () -> assertTrue(slideXmlOf(out).contains("Text survives"),
                            "the rest of the slide should still be written"));
        }

        @Test
        void a_figure_with_no_geometry_is_skipped_with_a_warning(@TempDir Path dir)
                throws IOException {
            Path out = dir.resolve("deck.pptx");
            List<String> warnings = new ArrayList<>();
            Map<String, Object> fig = figure("f", "svg", 0, 0, 1, 1);
            fig.remove("rect");
            Map<String, Object> c = card("d");
            c.put("body", regionWith(List.of(), List.of(fig)));

            DeckWriter.Result r = write(out, List.of(c), Map.of("f", png(10, 10)), warnings);

            assertAll(
                    () -> assertEquals(0, r.pictures()),
                    () -> assertEquals(1, warnings.size(), warnings.toString()),
                    () -> assertTrue(warnings.get(0).contains("geometry"), warnings.get(0)));
        }

        @Test
        void a_card_with_no_figures_key_at_all_still_writes(@TempDir Path dir)
                throws IOException {
            // Every existing test exercises this, but state it: the figures
            // list is absent from a text-only region, not empty.
            Path out = dir.resolve("deck.pptx");
            DeckWriter.Result r = write(out, List.of(card("a")), new ArrayList<>());
            assertEquals(0, r.pictures());
        }

        private List<XSLFPictureShape> pictures(XSLFSlide slide) {
            List<XSLFPictureShape> out = new ArrayList<>();
            for (XSLFShape s : slide.getShapes()) {
                if (s instanceof XSLFPictureShape p) out.add(p);
            }
            return out;
        }

        private String slideXmlOf(Path pptx) throws IOException {
            return slideXml(pptx, 1);
        }
    }

    @Nested
    @DisplayName("Background")
    class Background {

        @Test
        void the_cards_own_ground_becomes_the_slide_background(@TempDir Path dir)
                throws IOException {
            Path out = dir.resolve("deck.pptx");
            write(out, List.of(card("a")), new ArrayList<>());

            try (XMLSlideShow ppt = new XMLSlideShow(Files.newInputStream(out))) {
                assertEquals(new java.awt.Color(15, 23, 32),
                        ppt.getSlides().get(0).getBackground().getFillColor());
            }
            assertTrue(slideXml(out, 1).contains("<p:bg>"),
                    "a card with a ground should write a slide background override");
        }

        @Test
        void a_transparent_ground_writes_no_background_override(@TempDir Path dir)
                throws IOException {
            // rgba(...,0) means "whatever is behind"; painting it black would
            // invent a ground the theme deliberately left open.
            //
            // Asserted on the written XML, not getBackground().getFillColor():
            // with no override POI reports the master's inherited white rather
            // than null, so the model can't distinguish "left alone" from
            // "deliberately painted white".
            Path out = dir.resolve("deck.pptx");
            Map<String, Object> c = card("a");
            c.put("background", "rgba(0, 0, 0, 0)");
            write(out, List.of(c), new ArrayList<>());

            assertFalse(slideXml(out, 1).contains("<p:bg>"),
                    "no ground means no <p:bg> element: " + slideXml(out, 1));
        }

        @Test
        void each_slide_keeps_its_own_ground(@TempDir Path dir) throws IOException {
            // Regression test. The obvious call — slide.getBackground()
            // .setFillColor() — hands back the MASTER's background object for a
            // slide that has none of its own, so it repaints the whole deck.
            // That reads as correct for as long as every card shares a ground,
            // then goes wrong silently: here the second card would win on both
            // slides, and the master would carry a colour no card asked for.
            Path out = dir.resolve("deck.pptx");
            Map<String, Object> dark = card("dark");
            Map<String, Object> light = card("light");
            light.put("background", "rgb(255, 250, 240)");
            write(out, List.of(dark, light), new ArrayList<>());

            try (XMLSlideShow ppt = new XMLSlideShow(Files.newInputStream(out))) {
                assertAll(
                        () -> assertEquals(new java.awt.Color(15, 23, 32),
                                ppt.getSlides().get(0).getBackground().getFillColor()),
                        () -> assertEquals(new java.awt.Color(255, 250, 240),
                                ppt.getSlides().get(1).getBackground().getFillColor()));
            }
            assertFalse(entry(out, "ppt/slideMasters/slideMaster1.xml").contains("0F1720"),
                    "a card's ground must not leak onto the shared master");
        }
    }

    @Nested
    @DisplayName("Speaker notes")
    class Notes {

        @Test
        void authored_notes_reach_the_notes_pane(@TempDir Path dir) throws IOException {
            Path out = dir.resolve("deck.pptx");
            Map<String, Object> c = card("a");
            c.put("notes", "Pause here.");
            write(out, List.of(c), new ArrayList<>());

            try (XMLSlideShow ppt = new XMLSlideShow(Files.newInputStream(out))) {
                XSLFSlide slide = ppt.getSlides().get(0);
                boolean found = false;
                for (XSLFTextShape p : slide.getNotes().getPlaceholders()) {
                    if (p.getText() != null && p.getText().contains("Pause here.")) found = true;
                }
                assertTrue(found, "notes body should carry the text");
            }
        }

        @Test
        void a_card_with_no_notes_gets_no_notes_slide(@TempDir Path dir) throws IOException {
            Path out = dir.resolve("deck.pptx");
            write(out, List.of(card("a")), new ArrayList<>());
            assertNull(entry(out, "ppt/notesSlides/notesSlide1.xml"),
                    "an empty notes pane should not be created");
        }

        @Test
        void an_unplaced_block_is_routed_to_the_notes_and_flagged() {
            Map<String, Object> c = card("a");
            c.put("unplaced", "Notez This heading is a typo.");
            String notes = DeckWriter.notesFor(c);
            assertAll(
                    () -> assertTrue(notes.contains("Notez"), notes),
                    () -> assertTrue(notes.contains("[unplaced"),
                            "a presenter must be able to tell it wasn't on the slide: " + notes));
        }

        @Test
        void authored_notes_come_first_when_a_card_has_both() {
            Map<String, Object> c = card("a");
            c.put("notes", "Real notes.");
            c.put("unplaced", "Stray block.");
            String notes = DeckWriter.notesFor(c);
            assertTrue(notes.indexOf("Real notes.") < notes.indexOf("Stray block."), notes);
        }

        @Test
        void nothing_to_say_produces_no_notes() {
            assertNull(DeckWriter.notesFor(card("a")));
        }
    }

    @Nested
    @DisplayName("Unplaced blocks")
    class Unplaced {

        @Test
        void are_reported_by_card_and_warned_about(@TempDir Path dir) throws IOException {
            // The safety net has to be loud. A heading typo that quietly
            // relocated a visible region to the notes is worse than a build
            // error, because the slide still looks finished.
            Path out = dir.resolve("deck.pptx");
            List<String> warnings = new ArrayList<>();
            Map<String, Object> typo = card("typo");
            typo.put("unplaced", "Notez This heading is a typo for Notes.");

            DeckWriter.Result r = write(out, List.of(card("fine"), typo), warnings);

            assertAll(
                    () -> assertEquals(List.of("typo"), r.unplaced()),
                    () -> assertEquals(1, warnings.size(), warnings.toString()),
                    () -> assertTrue(warnings.get(0).contains("typo"), warnings.get(0)),
                    () -> assertTrue(warnings.get(0).contains("Notez"), warnings.get(0)),
                    () -> assertTrue(warnings.get(0).contains("slot skeleton"),
                            "the warning should say how to fix it: " + warnings.get(0)));
        }

        @Test
        void a_clean_deck_warns_about_nothing(@TempDir Path dir) throws IOException {
            List<String> warnings = new ArrayList<>();
            DeckWriter.Result r = write(dir.resolve("deck.pptx"),
                    List.of(card("a"), card("b")), warnings);
            assertAll(
                    () -> assertTrue(r.unplaced().isEmpty()),
                    () -> assertTrue(warnings.isEmpty(), warnings.toString()));
        }

        @Test
        void a_long_unplaced_block_is_truncated_in_the_warning() {
            String long_ = "x".repeat(200);
            assertAll(
                    () -> assertTrue(DeckWriter.ellipsis(long_).length() < 70),
                    () -> assertTrue(DeckWriter.ellipsis(long_).endsWith("…")),
                    () -> assertEquals("short", DeckWriter.ellipsis("short")));
        }
    }

    @Nested
    @DisplayName("CSS value mapping")
    class CssValues {

        @Test
        void colours_are_read_from_both_rgb_spellings() {
            assertAll(
                    () -> assertEquals(new java.awt.Color(15, 23, 32),
                            DeckWriter.cssColor("rgb(15, 23, 32)")),
                    () -> assertEquals(new java.awt.Color(1, 2, 3),
                            DeckWriter.cssColor("rgba(1, 2, 3, 0.5)")),
                    () -> assertEquals(new java.awt.Color(4, 5, 6),
                            DeckWriter.cssColor("rgb(4 5 6 / 80%)")));
        }

        @Test
        void a_fully_transparent_colour_means_do_not_set_one() {
            assertAll(
                    () -> assertNull(DeckWriter.cssColor("rgba(0, 0, 0, 0)")),
                    () -> assertNull(DeckWriter.cssColor("rgba(255, 255, 255, 0)")));
        }

        @Test
        void an_unreadable_colour_is_skipped_rather_than_guessed() {
            assertAll(
                    () -> assertNull(DeckWriter.cssColor("transparent")),
                    () -> assertNull(DeckWriter.cssColor("currentColor")),
                    () -> assertNull(DeckWriter.cssColor(null)));
        }

        @Test
        void text_align_maps_the_values_computed_style_actually_returns() {
            // Chromium reports 'start'/'end' for the logical keywords, and a
            // left-to-right deck should treat 'start' as left.
            assertAll(
                    () -> assertEquals(org.apache.poi.sl.usermodel.TextParagraph.TextAlign.CENTER,
                            DeckWriter.align("center")),
                    () -> assertEquals(org.apache.poi.sl.usermodel.TextParagraph.TextAlign.RIGHT,
                            DeckWriter.align("right")),
                    () -> assertEquals(org.apache.poi.sl.usermodel.TextParagraph.TextAlign.RIGHT,
                            DeckWriter.align("end")),
                    () -> assertEquals(org.apache.poi.sl.usermodel.TextParagraph.TextAlign.JUSTIFY,
                            DeckWriter.align("justify")),
                    () -> assertEquals(org.apache.poi.sl.usermodel.TextParagraph.TextAlign.LEFT,
                            DeckWriter.align("start")),
                    () -> assertEquals(org.apache.poi.sl.usermodel.TextParagraph.TextAlign.LEFT,
                            DeckWriter.align(null)));
        }
    }

    @Nested
    @DisplayName("Signature derivation")
    class Signatures {

        @Test
        void reads_which_regions_the_card_actually_has() {
            Map<String, Object> c = card("a");
            assertEquals("title+body", DeckWriter.signatureOf(c).key());

            c.put("aside", region(List.of()));
            assertEquals("title+body+aside", DeckWriter.signatureOf(c).key());

            c.put("title", null);
            assertEquals("body+aside", DeckWriter.signatureOf(c).key());
        }

        @Test
        void a_missing_key_and_an_explicit_null_are_the_same_thing() {
            Map<String, Object> present = card("a");
            Map<String, Object> absent = new LinkedHashMap<>(present);
            absent.remove("aside");
            absent.remove("subtitle");
            assertEquals(DeckWriter.signatureOf(present).key(),
                    DeckWriter.signatureOf(absent).key());
        }
    }
}
