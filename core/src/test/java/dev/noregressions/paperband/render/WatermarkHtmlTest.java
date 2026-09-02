package dev.noregressions.paperband.render;

import dev.noregressions.paperband.model.Watermark;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Watermark HTML overlay")
class WatermarkHtmlTest {

    private static final String PAGE =
            "<!DOCTYPE html><html><body><main>content</main></body></html>";

    @Test
    void the_overlay_carries_the_text_and_its_own_styles() {
        String html = WatermarkHtml.overlay(Watermark.withDefaults("DRAFT"), null, false);

        assertTrue(html.contains("<style>"), html);
        assertTrue(html.contains(">DRAFT<"), html);
        assertTrue(html.contains("class=\"pb-watermark\""), html);
    }

    @Test
    void it_is_inert_and_invisible_to_assistive_technology() {
        // An overlay that swallowed clicks or got read out would be a
        // regression in the page, not a label on it.
        String html = WatermarkHtml.overlay(Watermark.withDefaults("DRAFT"), null, false);

        assertTrue(html.contains("pointer-events:none"), html);
        assertTrue(html.contains("aria-hidden=\"true\""), html);
        assertTrue(html.contains("position:fixed"), html);
    }

    @Test
    void text_is_escaped() {
        String html = WatermarkHtml.overlay(
                Watermark.withDefaults("<script>alert('x')</script>"), null, false);

        assertFalse(html.contains("<script>"), html);
        assertTrue(html.contains("&lt;script&gt;"), html);
    }

    @Test
    void multiple_lines_break_rather_than_run_together() {
        String html = WatermarkHtml.overlay(
                Watermark.withDefaults("NOT FOR\nRESALE"), null, false);

        assertTrue(html.contains("NOT FOR<br>RESALE"), html);
    }

    @Test
    void fit_turns_the_declared_size_into_a_ceiling() {
        String fitted = WatermarkHtml.overlay(Watermark.withDefaults("DRAFT"), null, false);
        assertTrue(fitted.contains("font-size:min(96pt,"), fitted);
        assertTrue(fitted.contains("vw,"), fitted);
        assertTrue(fitted.contains("vh)"), fitted);

        String exact = WatermarkHtml.overlay(
                Watermark.fromYaml(Map.of("text", "DRAFT", "fit", false)), null, false);
        assertTrue(exact.contains("font-size:96pt"), exact);
        assertFalse(exact.contains("min("), exact);
    }

    @Test
    void a_longer_phrase_gets_a_smaller_ceiling_than_a_short_one() {
        String shortMark = WatermarkHtml.css(Watermark.withDefaults("X"), false);
        String longMark = WatermarkHtml.css(
                Watermark.withDefaults("SAMPLE - NOT FOR RESALE OR REDISTRIBUTION"), false);

        assertTrue(vw(longMark) < vw(shortMark),
                "a long line must be allowed fewer viewport units per em");
    }

    @Test
    void tiling_repeats_the_mark_in_a_grid() {
        String html = WatermarkHtml.overlay(
                Watermark.fromYaml(Map.of("text", "DRAFT", "tile", true)), null, false);

        assertTrue(html.contains("pb-watermark-grid"), html);
        assertTrue(html.contains("grid-template-columns:repeat(3,1fr)"), html);
        assertEquals(WatermarkHtml.TILE_COLS * WatermarkHtml.TILE_ROWS,
                count(html, "class=\"pb-watermark-mark\""));
    }

    @Test
    void tiling_rotates_the_grid_rather_than_each_mark() {
        // Rotating both would compound: the marks would sit at twice the angle
        // relative to the page.
        String css = WatermarkHtml.css(
                Watermark.fromYaml(Map.of("text", "DRAFT", "tile", true)), false);

        assertEquals(1, count(css, "transform:rotate("), css);
    }

    @Test
    void behind_puts_the_content_above_the_mark() {
        String css = WatermarkHtml.css(
                Watermark.fromYaml(Map.of("text", "DRAFT", "behind", true)), false);

        assertTrue(css.contains("z-index:0"), css);
        assertTrue(css.contains("body>*:not(.pb-watermark){position:relative;z-index:1;}"), css);
    }

    @Test
    void screen_only_hides_the_overlay_in_print() {
        // The emitHtml copy's print path is the PDFBox stamp; without this it
        // would carry two watermarks.
        String screen = WatermarkHtml.css(Watermark.withDefaults("DRAFT"), true);
        String both = WatermarkHtml.css(Watermark.withDefaults("DRAFT"), false);

        assertTrue(screen.contains("@media print{.pb-watermark{display:none!important;}}"), screen);
        assertFalse(both.contains("@media print"), both);
        assertTrue(both.contains("print-color-adjust:exact"),
                "a printed site page should keep the grey");
    }

    @Test
    void an_image_watermark_uses_the_url_it_is_handed() {
        String html = WatermarkHtml.overlay(
                Watermark.imageWithDefaults("brand/logo.png"), "../assets/logo.png", false);

        assertTrue(html.contains("src=\"../assets/logo.png\""), html);
        assertTrue(html.contains("width:50%"), html);
    }

    @Test
    void an_image_watermark_with_no_resolved_url_draws_nothing() {
        assertEquals("", WatermarkHtml.overlay(
                Watermark.imageWithDefaults("brand/logo.png"), null, false));
    }

    @Test
    void a_null_watermark_draws_nothing() {
        assertEquals("", WatermarkHtml.overlay(null, null, false));
    }

    @Test
    void a_malformed_colour_falls_back_to_grey_rather_than_breaking_the_css() {
        assertEquals("#888888", WatermarkHtml.cssColor("not a colour"));
        assertEquals("#aa0000", WatermarkHtml.cssColor("#AA0000"));
        assertEquals("#abc", WatermarkHtml.cssColor("abc"));
    }

    @Test
    void injection_goes_immediately_before_the_body_close() {
        String out = WatermarkHtml.inject(PAGE,
                WatermarkHtml.overlay(Watermark.withDefaults("DRAFT"), null, false));

        assertTrue(out.indexOf("pb-watermark") < out.indexOf("</body>"), out);
        assertTrue(out.indexOf("<main>") < out.indexOf("pb-watermark"),
                "the overlay paints last");
        assertTrue(out.endsWith("</body></html>"), out);
    }

    @Test
    void a_page_with_no_body_close_still_gets_the_overlay() {
        String out = WatermarkHtml.inject("<div>fragment</div>",
                WatermarkHtml.overlay(Watermark.withDefaults("DRAFT"), null, false));

        assertTrue(out.contains("pb-watermark"), out);
    }

    @Test
    void injecting_nothing_leaves_the_page_alone() {
        assertEquals(PAGE, WatermarkHtml.inject(PAGE, ""));
        assertEquals(PAGE, WatermarkHtml.inject(PAGE, null));
    }

    @Test
    void numbers_are_written_the_way_a_person_would() {
        assertEquals("0", WatermarkHtml.trim(0f));
        assertEquals("30", WatermarkHtml.trim(30f));
        assertEquals("-30", WatermarkHtml.trim(-30f));
        assertEquals("0.12", WatermarkHtml.trim(0.12f));
    }

    private static double vw(String css) {
        int at = css.indexOf("vw,");
        int from = css.lastIndexOf(',', at) + 1;
        return Double.parseDouble(css.substring(from, at));
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }
}
