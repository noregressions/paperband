package dev.noregressions.paperband.maven.pdf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Full-page cover splice detection")
class FullPageCoverTest {

    private static final String BAND = "<span>page 1</span>";

    @Test
    void css_mentioning_the_marker_does_not_trigger_the_splice() {
        // book.html always emits these rules -- the @media screen block styles
        // the class whether or not the book uses it. Matching them cost every
        // book with a running band a second full render.
        String html = "<style>.book-cover-fullpage { height: 100%; }</style>"
                + "<section class=\"book-cover book-cover-has-image\">x</section>";

        assertFalse(FullPageCover.needsBareFirstPage(html, BAND, null));
    }

    @Test
    void the_marker_as_a_real_class_triggers_it() {
        String html = "<section class=\"book-cover book-cover-fullpage\">x</section>";

        assertTrue(FullPageCover.needsBareFirstPage(html, BAND, null));
        assertTrue(FullPageCover.needsBareFirstPage(html, null, BAND));
    }

    @Test
    void no_running_band_means_no_splice_even_with_a_full_page_cover() {
        // Nothing would print over the cover, so there is nothing to strip.
        String html = "<section class=\"book-cover book-cover-fullpage\">x</section>";

        assertFalse(FullPageCover.needsBareFirstPage(html, null, null));
        assertFalse(FullPageCover.needsBareFirstPage(html, "  ", ""));
    }

    @Test
    void a_longer_class_name_containing_the_marker_does_not_match() {
        String html = "<section class=\"book-cover-fullpageish\">x</section>";

        assertFalse(FullPageCover.needsBareFirstPage(html, BAND, null));
    }

    @Test
    void single_quoted_class_attributes_match_too() {
        String html = "<section class='book-cover book-cover-fullpage'>x</section>";

        assertTrue(FullPageCover.needsBareFirstPage(html, BAND, null));
    }
}
