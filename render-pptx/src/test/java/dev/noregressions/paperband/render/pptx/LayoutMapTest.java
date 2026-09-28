package dev.noregressions.paperband.render.pptx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slot signature to PowerPoint layout.
 *
 * <p>The table has to be <em>total</em>, which is the whole point of these
 * tests: the first version of this mapping was a chain of {@code if}s, and a
 * {@code title+subtitle+body} card matched "Title and Content" — a layout with
 * no subtitle placeholder — so the card's oneliner vanished with no error at
 * all. A signature with nowhere to land must throw.
 */
@DisplayName("LayoutMap")
class LayoutMapTest {

    private static LayoutMap.Signature sig(boolean title, boolean subtitle,
                                           boolean body, boolean aside) {
        return new LayoutMap.Signature(title, subtitle, body, aside);
    }

    @Nested
    @DisplayName("Signature keys")
    class Keys {

        @Test
        void names_the_regions_present_in_order() {
            assertAll(
                    () -> assertEquals("title", sig(true, false, false, false).key()),
                    () -> assertEquals("title+body", sig(true, false, true, false).key()),
                    () -> assertEquals("title+body+aside", sig(true, false, true, true).key()),
                    () -> assertEquals("title+subtitle+body+aside", sig(true, true, true, true).key()));
        }

        @Test
        void a_region_set_without_a_title_still_reads_correctly() {
            // The separator logic has to cope with the first present region
            // being any of the four, not just the title.
            assertAll(
                    () -> assertEquals("body", sig(false, false, true, false).key()),
                    () -> assertEquals("aside", sig(false, false, false, true).key()),
                    () -> assertEquals("body+aside", sig(false, false, true, true).key()),
                    () -> assertEquals("subtitle+body", sig(false, true, true, false).key()));
        }

        @Test
        void a_card_with_no_regions_is_named_rather_than_blank() {
            assertEquals("(empty)", sig(false, false, false, false).key());
        }
    }

    @Nested
    @DisplayName("Layout selection")
    class Selection {

        @Test
        void title_only_gets_a_title_layout() {
            assertEquals("Title Only", LayoutMap.forSignature(sig(true, false, false, false), "c"));
        }

        @Test
        void title_and_body_get_one_content_region() {
            assertEquals("Title and Content",
                    LayoutMap.forSignature(sig(true, false, true, false), "c"));
        }

        @Test
        void body_and_aside_get_the_two_content_layout() {
            // The pairing that matters: two CONTENT placeholders, which is what
            // a body-plus-aside card needs to fill.
            assertEquals("Two Content",
                    LayoutMap.forSignature(sig(true, false, true, true), "c"));
        }

        @Test
        void a_subtitle_without_a_body_reaches_the_only_layout_that_has_one() {
            assertEquals("Title Slide",
                    LayoutMap.forSignature(sig(true, true, false, false), "c"));
        }

        @Test
        void an_aside_alone_still_lands_in_a_content_region() {
            // A card whose only slot matched 'aside' shouldn't be a build
            // error; it just occupies the single content placeholder.
            assertEquals("Title and Content",
                    LayoutMap.forSignature(sig(true, false, false, true), "c"));
        }

        @Test
        void the_same_signature_always_resolves_to_the_same_layout() {
            // Layout reuse is the reason for slots: two cards with one shape
            // must share one slideLayout rather than carry bespoke geometry.
            assertEquals(
                    LayoutMap.forSignature(sig(true, false, true, false), "a"),
                    LayoutMap.forSignature(sig(true, false, true, false), "b"));
        }
    }

    @Nested
    @DisplayName("Signatures with no stock layout")
    class Unsupported {

        @Test
        void a_subtitle_alongside_a_body_fails_instead_of_dropping_the_subtitle() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> LayoutMap.forSignature(sig(true, true, true, false), "intro"));
            assertAll(
                    () -> assertTrue(e.getMessage().contains("intro"),
                            "names the offending card: " + e.getMessage()),
                    () -> assertTrue(e.getMessage().contains("title+subtitle+body"),
                            "names the signature: " + e.getMessage()),
                    () -> assertTrue(e.getMessage().contains("oneliner"),
                            "says what to change: " + e.getMessage()));
        }

        @Test
        void a_subtitle_with_two_content_regions_also_fails() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> LayoutMap.forSignature(sig(true, true, true, true), "wide"));
            assertTrue(e.getMessage().contains("oneliner"),
                    "advice should name the field to drop: " + e.getMessage());
        }

        @Test
        void an_unlisted_signature_reports_what_is_supported() {
            // subtitle+aside with no title: no advice row, so the message falls
            // back to enumerating the table rather than saying nothing useful.
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> LayoutMap.forSignature(sig(false, true, false, true), "odd"));
            assertAll(
                    () -> assertTrue(e.getMessage().contains("odd")),
                    () -> assertTrue(e.getMessage().contains("title+body"),
                            "should list the supported signatures: " + e.getMessage()));
        }
    }
}
