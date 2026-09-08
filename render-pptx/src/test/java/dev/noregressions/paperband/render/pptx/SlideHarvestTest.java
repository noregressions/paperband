package dev.noregressions.paperband.render.pptx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The value readers that sit between {@code getComputedStyle} and POI.
 *
 * <p>These take whatever Chromium hands back, which is not always a number: a
 * property the theme never set resolves to a keyword ({@code normal}), and
 * {@code font-family} comes back as the <em>declared</em> list rather than the
 * face that was used. Every one of these has to degrade to something POI can
 * accept, because throwing here would fail a build over a stylistic detail.
 */
@DisplayName("SlideHarvest value readers")
class SlideHarvestTest {

    @Nested
    @DisplayName("px()")
    class Px {

        @Test
        void reads_a_px_length() {
            assertAll(
                    () -> assertEquals(29.3, SlideHarvest.px("29.3px"), 0.001),
                    () -> assertEquals(16, SlideHarvest.px("16px"), 0.001),
                    () -> assertEquals(0, SlideHarvest.px("0px"), 0.001));
        }

        @Test
        void tolerates_surrounding_whitespace() {
            assertEquals(12, SlideHarvest.px("  12px "), 0.001);
        }

        @Test
        void a_bare_number_is_still_read() {
            assertEquals(14, SlideHarvest.px("14"), 0.001);
        }

        @Test
        void keywords_and_junk_degrade_to_zero_rather_than_throwing() {
            // 'normal' is what line-height and letter-spacing resolve to when
            // the theme never set them, so this is the common case, not an
            // edge one — and callers treat 0 as "don't set this property".
            assertAll(
                    () -> assertEquals(0, SlideHarvest.px("normal"), 0.001),
                    () -> assertEquals(0, SlideHarvest.px("auto"), 0.001),
                    () -> assertEquals(0, SlideHarvest.px("2em"), 0.001),
                    () -> assertEquals(0, SlideHarvest.px(""), 0.001),
                    () -> assertEquals(0, SlideHarvest.px(null), 0.001));
        }

        @Test
        void a_negative_length_survives() {
            assertEquals(-3, SlideHarvest.px("-3px"), 0.001);
        }
    }

    @Nested
    @DisplayName("weight()")
    class Weight {

        @Test
        void reads_numeric_weights() {
            assertAll(
                    () -> assertEquals(400, SlideHarvest.weight("400")),
                    () -> assertEquals(700, SlideHarvest.weight("700")),
                    () -> assertEquals(300, SlideHarvest.weight("300")));
        }

        @Test
        void reads_the_two_keywords_computed_style_can_still_return() {
            assertAll(
                    () -> assertEquals(700, SlideHarvest.weight("bold")),
                    () -> assertEquals(400, SlideHarvest.weight("normal")));
        }

        @Test
        void unknown_input_falls_back_to_regular() {
            assertAll(
                    () -> assertEquals(400, SlideHarvest.weight("bolder")),
                    () -> assertEquals(400, SlideHarvest.weight("")),
                    () -> assertEquals(400, SlideHarvest.weight(null)));
        }

        @Test
        void the_bold_threshold_sits_at_600() {
            // DeckWriter maps >= 600 to bold; semibold should read as bold
            // rather than being rounded away.
            assertAll(
                    () -> assertTrue(SlideHarvest.weight("600") >= 600),
                    () -> assertTrue(SlideHarvest.weight("500") < 600));
        }
    }

    @Nested
    @DisplayName("family()")
    class Family {

        @Test
        void takes_the_first_declared_family() {
            assertEquals("Helvetica Neue",
                    SlideHarvest.family("\"Helvetica Neue\", Helvetica, Arial, sans-serif"));
        }

        @Test
        void strips_both_quote_styles() {
            assertAll(
                    () -> assertEquals("Iowan Old Style", SlideHarvest.family("\"Iowan Old Style\"")),
                    () -> assertEquals("Iowan Old Style", SlideHarvest.family("'Iowan Old Style'")));
        }

        @Test
        void maps_generic_families_to_real_faces() {
            // The bug this exists for: computed font-family returns the
            // declared list, so an inline <code> arrives as 'ui-monospace',
            // which PowerPoint has never heard of.
            assertAll(
                    () -> assertEquals("Menlo", SlideHarvest.family("ui-monospace")),
                    () -> assertEquals("Menlo", SlideHarvest.family("monospace")),
                    () -> assertEquals("Helvetica Neue", SlideHarvest.family("system-ui")),
                    () -> assertEquals("Helvetica Neue", SlideHarvest.family("sans-serif")),
                    () -> assertEquals("Georgia", SlideHarvest.family("serif")));
        }

        @Test
        void a_generic_first_in_the_list_is_mapped_not_passed_through() {
            // This is exactly what the deck theme's code rule produces:
            // font-family: ui-monospace, Menlo, Consolas, monospace.
            assertEquals("Menlo",
                    SlideHarvest.family("ui-monospace, Menlo, Consolas, monospace"));
        }

        @Test
        void generic_matching_ignores_case() {
            assertEquals("Menlo", SlideHarvest.family("UI-Monospace"));
        }

        @Test
        void a_real_face_is_never_rewritten() {
            assertEquals("Consolas", SlideHarvest.family("Consolas, monospace"));
        }

        @Test
        void nothing_usable_still_yields_a_font_POI_can_set() {
            assertAll(
                    () -> assertEquals("Helvetica Neue", SlideHarvest.family(null)),
                    () -> assertEquals("Helvetica Neue", SlideHarvest.family("")),
                    () -> assertEquals("Helvetica Neue", SlideHarvest.family("   ")));
        }
    }

    @Nested
    @DisplayName("Harvest script")
    class Script {

        @Test
        void the_region_selectors_are_the_classes_the_deck_template_emits() {
            // These four strings are the contract between themes/deck's
            // _card-body.html and this renderer. Renaming one on either side
            // silently produces empty slides, so pin them.
            assertAll(
                    () -> assertEquals(".slide-body", SlideHarvest.BODY_SELECTOR),
                    () -> assertEquals(".slide-aside", SlideHarvest.ASIDE_SELECTOR),
                    () -> assertEquals(".notes:not(.notes--unplaced)", SlideHarvest.NOTES_SELECTOR),
                    () -> assertEquals(".notes--unplaced", SlideHarvest.UNPLACED_SELECTOR));
        }

        @Test
        void every_harvested_property_is_one_the_writer_consumes() {
            // The allowlist is only useful if it stays aligned with what
            // DeckWriter.styleRun actually reads; an entry nobody consumes is
            // a silent fidelity loss rather than an error.
            assertAll(
                    () -> assertTrue(SlideHarvest.PROPS.contains("fontFamily")),
                    () -> assertTrue(SlideHarvest.PROPS.contains("fontSize")),
                    () -> assertTrue(SlideHarvest.PROPS.contains("fontWeight")),
                    () -> assertTrue(SlideHarvest.PROPS.contains("fontStyle")),
                    () -> assertTrue(SlideHarvest.PROPS.contains("color")),
                    () -> assertTrue(SlideHarvest.PROPS.contains("textAlign")),
                    () -> assertTrue(SlideHarvest.PROPS.contains("lineHeight")),
                    () -> assertTrue(SlideHarvest.PROPS.contains("letterSpacing")),
                    () -> assertTrue(SlideHarvest.PROPS.contains("textTransform")));
        }

        @Test
        void every_selector_is_interpolated_where_it_belongs() {
            // Six %s placeholders now feed .formatted(), and getting the order
            // wrong still compiles: swapping body and aside would put the
            // aside's text in the body's placeholder on every slide, and
            // swapping FIG and TEXT would treat prose as figures and vice
            // versa. Pin each one to its own position in the script.
            String script = scriptSource();
            assertAll(
                    () -> assertNotNull(script),
                    () -> assertTrue(script.contains("const FIG = 'svg, table, pre, img, figure'"),
                            "figure selector should land in FIG"),
                    () -> assertTrue(script.contains("const TEXT = 'p, li, h2, h3, h4, h5, h6, blockquote'"),
                            "text selector should land in TEXT"),
                    () -> assertTrue(script.contains("region(card, '.slide-body'"),
                            "body selector should reach the script"),
                    () -> assertTrue(script.contains("region(card, '.slide-aside'"),
                            "aside selector should reach the script"),
                    () -> assertTrue(script.contains("setAttribute('data-pb-figure'"),
                            "the figure attribute must match what capture() queries"),
                    () -> assertTrue(script.indexOf(".slide-body") < script.indexOf(".slide-aside"),
                            "body must be harvested before aside: fill order decides "
                            + "which content placeholder each region lands in"));
        }

        @Test
        void figure_ids_are_unique_across_cards_and_regions() {
            // capture() looks each figure up by a global attribute selector,
            // so two cards must not both call their first diagram the same
            // thing — the second lookup would return the first card's element.
            String script = scriptSource();
            assertAll(
                    () -> assertTrue(script.contains("'c' + ci + 'b'"),
                            "body figure ids should include the card index"),
                    () -> assertTrue(script.contains("'c' + ci + 'a'"),
                            "aside figure ids should include the card index"),
                    () -> assertTrue(script.contains("tag + '-' + figures.length"),
                            "and the figure's position within its region"));
        }

        @Test
        void code_fences_are_figures_not_text() {
            // A deliberate call, so worth pinning: a highlighted fence is a
            // thicket of inline spans over whitespace that must not collapse,
            // and a picture of it beats an editable approximation on a slide.
            assertAll(
                    () -> assertTrue(SlideHarvest.FIGURE_SELECTOR.contains("pre")),
                    () -> assertFalse(SlideHarvest.TEXT_SELECTOR.contains("pre")));
        }

        @Test
        void diagrams_and_tables_are_figures() {
            // Mermaid and PlantUML both land as inline svg.
            assertAll(
                    () -> assertTrue(SlideHarvest.FIGURE_SELECTOR.contains("svg")),
                    () -> assertTrue(SlideHarvest.FIGURE_SELECTOR.contains("table")),
                    () -> assertTrue(SlideHarvest.FIGURE_SELECTOR.contains("img")));
        }

        @Test
        void prose_stays_text() {
            assertAll(
                    () -> assertTrue(SlideHarvest.TEXT_SELECTOR.contains("li")),
                    () -> assertTrue(SlideHarvest.TEXT_SELECTOR.contains("blockquote")),
                    () -> assertFalse(SlideHarvest.FIGURE_SELECTOR.contains("li")),
                    () -> assertFalse(SlideHarvest.FIGURE_SELECTOR.contains(" p,")));
        }

        private String scriptSource() {
            try {
                var f = SlideHarvest.class.getDeclaredField("SCRIPT");
                f.setAccessible(true);
                return (String) f.get(null);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("SCRIPT field moved or was renamed", e);
            }
        }
    }
}
