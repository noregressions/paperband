package dev.noregressions.paperband.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Watermark spec")
class WatermarkTest {

    @Nested
    @DisplayName("from yaml")
    class FromYaml {

        @Test
        void a_bare_string_takes_every_default() {
            Watermark w = Watermark.fromYaml("DRAFT");

            assertEquals("DRAFT", w.text());
            assertEquals(Watermark.DEFAULT_COLOR, w.color());
            assertEquals(Watermark.DEFAULT_OPACITY, w.opacity());
            assertEquals(Watermark.DEFAULT_ANGLE, w.angle());
            assertEquals(Watermark.DEFAULT_FONT_SIZE, w.fontSize());
            assertTrue(w.bold());
            assertTrue(w.fit(), "fit is on by default: overflow used to be silent");
            assertFalse(w.behind());
            assertFalse(w.tile());
            assertEquals(Watermark.Pages.ALL, w.pages());
        }

        @Test
        void a_map_reads_every_knob() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", "SAMPLE");
            m.put("color", "#aa0000");
            m.put("opacity", 0.2);
            m.put("angle", -45);
            m.put("font_size", 72);
            m.put("bold", false);
            m.put("fit", false);
            m.put("behind", true);
            m.put("tile", true);
            m.put("pages", "except-cover");
            m.put("font", "fonts/Noto.ttf");

            Watermark w = Watermark.fromYaml(m);

            assertEquals("SAMPLE", w.text());
            assertEquals("#aa0000", w.color());
            assertEquals(0.2f, w.opacity(), 0.0001);
            assertEquals(-45f, w.angle());
            assertEquals(72, w.fontSize());
            assertFalse(w.bold());
            assertFalse(w.fit());
            assertTrue(w.behind());
            assertTrue(w.tile());
            assertEquals(Watermark.Pages.EXCEPT_COVER, w.pages());
            assertEquals("fonts/Noto.ttf", w.font());
        }

        @Test
        void fontSize_is_accepted_in_either_spelling() {
            assertEquals(40, Watermark.fromYaml(Map.of("text", "X", "fontSize", 40)).fontSize());
            assertEquals(40, Watermark.fromYaml(Map.of("text", "X", "font_size", 40)).fontSize());
        }

        @Test
        void an_unknown_key_is_an_error_rather_than_a_shrug() {
            // A misspelled knob that silently stamped at the default would be
            // found by whoever printed the proof, which is late.
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Watermark.fromYaml(Map.of("text", "X", "opacty", 0.5)));

            assertTrue(e.getMessage().contains("opacty"), e.getMessage());
            assertTrue(e.getMessage().contains("opacity"), "should list the keys that do exist");
        }

        @Test
        void a_map_with_neither_text_nor_image_is_no_watermark() {
            assertNull(Watermark.fromYaml(Map.of("color", "#fff")));
            assertNull(Watermark.fromYaml(""));
            assertNull(Watermark.fromYaml(null));
        }

        @Test
        void an_unparseable_number_names_the_key_it_came_from() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Watermark.fromYaml(Map.of("text", "X", "opacity", "quite")));

            assertTrue(e.getMessage().contains("opacity"), e.getMessage());
        }

        @Test
        void strings_that_look_like_numbers_and_booleans_still_parse() {
            Watermark w = Watermark.fromYaml(Map.of(
                    "text", "X", "opacity", "0.4", "font_size", "40", "tile", "yes"));

            assertEquals(0.4f, w.opacity(), 0.0001);
            assertEquals(40, w.fontSize());
            assertTrue(w.tile());
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        void text_and_image_together_are_refused() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Watermark.fromYaml(Map.of("text", "X", "image", "logo.png")));

            assertTrue(e.getMessage().contains("pick one"), e.getMessage());
        }

        @Test
        void opacity_outside_zero_to_one_is_refused() {
            assertThrows(IllegalArgumentException.class,
                    () -> Watermark.fromYaml(Map.of("text", "X", "opacity", 1.5)));
        }

        @Test
        void a_tiny_font_size_is_refused() {
            assertThrows(IllegalArgumentException.class,
                    () -> Watermark.fromYaml(Map.of("text", "X", "font_size", 4)));
        }

        @Test
        void an_image_scale_outside_its_range_is_refused() {
            assertThrows(IllegalArgumentException.class,
                    () -> Watermark.fromYaml(Map.of("image", "logo.png", "scale", 2)));
        }

        @Test
        void an_image_watermark_ignores_the_font_size_floor() {
            // font_size means nothing to an image; it should not be able to
            // reject one.
            assertNotNull(Watermark.imageWithDefaults("logo.png").image());
        }
    }

    @Nested
    @DisplayName("pages")
    class PagesSelection {

        @Test
        void parses_every_spelling() {
            assertEquals(Watermark.Pages.ALL, Watermark.Pages.parse("all"));
            assertEquals(Watermark.Pages.FIRST, Watermark.Pages.parse("First"));
            assertEquals(Watermark.Pages.EXCEPT_COVER, Watermark.Pages.parse("except-cover"));
            assertEquals(Watermark.Pages.EXCEPT_COVER, Watermark.Pages.parse("EXCEPT_COVER"));
        }

        @Test
        void an_unknown_selection_names_the_ones_that_exist() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Watermark.Pages.parse("last"));

            assertTrue(e.getMessage().contains("except-cover"), e.getMessage());
        }

        @Test
        void selects_the_right_page_indices() {
            assertTrue(Watermark.Pages.ALL.includes(0));
            assertTrue(Watermark.Pages.ALL.includes(7));
            assertTrue(Watermark.Pages.FIRST.includes(0));
            assertFalse(Watermark.Pages.FIRST.includes(1));
            assertFalse(Watermark.Pages.EXCEPT_COVER.includes(0));
            assertTrue(Watermark.Pages.EXCEPT_COVER.includes(1));
        }
    }

    @Nested
    @DisplayName("lines")
    class Lines {

        @Test
        void a_single_line_is_one_line() {
            assertEquals(List.of("DRAFT"), Watermark.withDefaults("DRAFT").lines());
        }

        @Test
        void real_newlines_split() {
            assertEquals(List.of("NOT FOR", "RESALE"),
                    Watermark.withDefaults("NOT FOR\nRESALE").lines());
        }

        @Test
        void the_two_character_backslash_n_splits_too() {
            // What a single-quoted yaml scalar and a -D command line deliver.
            assertEquals(List.of("NOT FOR", "RESALE"),
                    Watermark.withDefaults("NOT FOR\\nRESALE").lines());
        }

        @Test
        void a_trailing_blank_line_does_not_become_stamped_whitespace() {
            assertEquals(List.of("DRAFT"), Watermark.withDefaults("DRAFT\n").lines());
        }

        @Test
        void an_image_watermark_has_no_lines() {
            assertEquals(List.of(), Watermark.imageWithDefaults("logo.png").lines());
        }
    }

    @Nested
    @DisplayName("overrides")
    class Overrides {

        @Test
        void named_knobs_replace_and_the_rest_survive() {
            Watermark base = Watermark.fromYaml(Map.of(
                    "text", "DRAFT", "color", "#aa0000", "angle", -45));

            Watermark w = base.withOverrides(new Watermark.Overrides(
                    null, 0.3f, null, null, null, null, null, true, null,
                    Watermark.Pages.FIRST, null));

            assertEquals("DRAFT", w.text());
            assertEquals("#aa0000", w.color(), "an unnamed knob keeps the base value");
            assertEquals(-45f, w.angle());
            assertEquals(0.3f, w.opacity(), 0.0001);
            assertTrue(w.behind());
            assertEquals(Watermark.Pages.FIRST, w.pages());
        }

        @Test
        void an_empty_bundle_changes_nothing() {
            Watermark base = Watermark.withDefaults("DRAFT");

            assertEquals(base, base.withOverrides(Watermark.Overrides.NONE));
            assertEquals(base, base.withOverrides(null));
        }
    }

    @Test
    void describe_says_what_is_unusual_and_stays_quiet_otherwise() {
        assertEquals("\"DRAFT\"", Watermark.withDefaults("DRAFT").describe());

        String d = Watermark.fromYaml(Map.of(
                "text", "DRAFT", "tile", true, "behind", true, "pages", "first")).describe();
        assertTrue(d.contains("pages=first"), d);
        assertTrue(d.contains("tiled"), d);
        assertTrue(d.contains("behind"), d);
    }
}
