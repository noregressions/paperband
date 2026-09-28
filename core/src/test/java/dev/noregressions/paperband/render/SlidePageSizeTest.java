package dev.noregressions.paperband.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The 16:9 slide preset, and the reason it has to be a <em>named</em> one.
 *
 * <p>{@link PageConfigResolver} treats a size it doesn't recognise as having no
 * curated theme rules, and derives a font scale from the page width relative to
 * A4 — 1.61&times; for a 13.3in sheet. On a deck that is enough on its own to
 * push every card past a one-page budget, for a reason nothing in the theme
 * explains, so 16:9 belongs in {@code KNOWN_PRESETS}.
 *
 * <p>Recognition is by <em>value</em>, not by slug: {@link PageSize} is a
 * record, so a longhand {@code {width: 13.333, height: 7.5, unit: inch}} map is
 * the same size and gets the same protection. A near miss like {@code 13.33}
 * does not, which is why the slug is still what the deck theme's manifest
 * recommends. Both are pinned below.
 */
@DisplayName("16:9 slide page")
class SlidePageSizeTest {

    @Nested
    @DisplayName("The preset")
    class Preset {

        @Test
        void is_powerpoints_widescreen_slide() {
            // 13.333x7.5in is 960x540pt, which is the 12192000x6858000 EMU
            // PowerPoint writes as sldSz.
            assertAll(
                    () -> assertEquals(13.333, PageSize.SLIDE_16X9.width(), 0.0005),
                    () -> assertEquals(7.5, PageSize.SLIDE_16X9.height(), 0.0005),
                    () -> assertEquals(Unit.INCH, PageSize.SLIDE_16X9.unit()),
                    () -> assertEquals(960, Math.round(PageSize.SLIDE_16X9.width() * 72)),
                    () -> assertEquals(540, Math.round(PageSize.SLIDE_16X9.height() * 72)));
        }

        @Test
        void has_zero_margins_so_a_slide_can_reach_the_trim_edge() {
            // Chromium paints nothing into a PDF page margin, so a full-bleed
            // slide has to be built at zero margins; the deck theme then
            // supplies its own inset.
            Margins m = PageSpec.slide16x9().margins();
            assertAll(
                    () -> assertEquals(0, m.top(), 0.0001),
                    () -> assertEquals(0, m.right(), 0.0001),
                    () -> assertEquals(0, m.bottom(), 0.0001),
                    () -> assertEquals(0, m.left(), 0.0001));
        }

        @Test
        void is_reachable_by_slug_from_both_resolvers() {
            // PageSpec.forSizeName serves the plugin's <pageSize>;
            // PageConfigResolver serves the book's page.size. They have to
            // agree, or the POM and the yaml describe different sheets.
            assertAll(
                    () -> assertEquals(PageSize.SLIDE_16X9,
                            PageSpec.forSizeName("16x9").size()),
                    () -> assertEquals(PageSize.SLIDE_16X9,
                            PageSpec.forSizeName("slide").size()),
                    () -> assertEquals(PageSize.SLIDE_16X9,
                            PageConfigResolver.resolve(Map.of("size", "16x9"),
                                    PageSpec.a4()).pageSpec().size()),
                    () -> assertEquals(PageSize.SLIDE_16X9,
                            PageConfigResolver.resolve(Map.of("size", "slide"),
                                    PageSpec.a4()).pageSpec().size()));
        }

        @Test
        void the_slug_is_case_insensitive_like_the_others() {
            assertEquals(PageSize.SLIDE_16X9,
                    PageConfigResolver.resolve(Map.of("size", "16X9"), PageSpec.a4())
                            .pageSpec().size());
        }
    }

    @Nested
    @DisplayName("Font scale")
    class FontScale {

        @Test
        void the_named_preset_leaves_the_type_scale_alone() {
            // null means LayoutEngine never sets --pw-font-scale, so the deck
            // theme's own 22pt baseline is what renders.
            PageConfigResolver.Resolved r =
                    PageConfigResolver.resolve(Map.of("size", "16x9"), PageSpec.a4());
            assertNull(r.fontScale(),
                    "a curated preset must not get an auto-derived scale");
        }

        @Test
        void an_exact_custom_map_is_recognised_too_because_size_is_a_value() {
            // KNOWN_PRESETS is keyed on the PageSize value, and PageSize is a
            // record — so spelling the slide out longhand resolves to the same
            // value as the slug and gets the same protection. Worth pinning:
            // the slug is the advice, but it isn't load-bearing at these exact
            // dimensions.
            PageConfigResolver.Resolved r = PageConfigResolver.resolve(
                    Map.of("size", Map.of("width", 13.333, "height", 7.5, "unit", "inch")),
                    PageSpec.a4());
            assertAll(
                    () -> assertEquals(PageSize.SLIDE_16X9, r.pageSpec().size()),
                    () -> assertNull(r.fontScale()));
        }

        @Test
        void a_near_miss_on_the_dimensions_does_get_the_width_ratio_default() {
            // And this is why the slug is still the advice. 13.33 rather than
            // 13.333 is the same slide to a human and a different value to
            // KNOWN_PRESETS, so the theme's type scale is multiplied by 1.61
            // and every card overflows its one-page budget — which is exactly
            // how the first deck build failed.
            PageConfigResolver.Resolved r = PageConfigResolver.resolve(
                    Map.of("size", Map.of("width", 13.33, "height", 7.5, "unit", "inch")),
                    PageSpec.a4());
            assertAll(
                    () -> assertNotNull(r.fontScale(),
                            "an unrecognised size still gets the width-ratio default"),
                    () -> assertEquals(1.6123, r.fontScale(), 0.001));
        }

        @Test
        void an_explicit_scale_still_wins_on_the_preset() {
            // A book that genuinely wants to tune a deck must still be able to.
            PageConfigResolver.Resolved r = PageConfigResolver.resolve(
                    Map.of("size", "16x9", "fontScale", 1.2), PageSpec.a4());
            assertEquals(1.2, r.fontScale(), 0.0001);
        }

        @Test
        void the_preset_as_a_base_with_no_page_block_is_also_left_alone() {
            // The plugin's <pageSize>16x9</pageSize> path: no page: yaml at
            // all, so `raw` is empty and the base carries the preset.
            PageConfigResolver.Resolved r =
                    PageConfigResolver.resolve(Map.of(), PageSpec.slide16x9());
            assertAll(
                    () -> assertSame(PageSize.SLIDE_16X9, r.pageSpec().size()),
                    () -> assertNull(r.fontScale()));
        }
    }
}
