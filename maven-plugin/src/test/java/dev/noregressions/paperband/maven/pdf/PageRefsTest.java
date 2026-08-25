package dev.noregressions.paperband.maven.pdf;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The placeholder-substitution half of two-pass page numbering. The other
 * half — reading real pages from a rendered PDF's named destinations — is
 * {@link PagesReport#analyse}, covered by {@code PagesReportTest}.
 */
class PageRefsTest {

    private static String ref(String anchor) {
        return "<span class=\"toc-page pw-pageref\" data-pw-anchor=\"" + anchor + "\">000</span>";
    }

    @Test
    void should_replace_each_placeholder_with_its_anchors_page() {
        String html = "<li>" + ref("card-intro") + "</li><li>" + ref("card-setup") + "</li>";

        PageRefs.Resolved out = PageRefs.resolve(html,
                Map.of("card-intro", 3, "card-setup", 17));

        assertTrue(out.html().contains("data-pw-anchor=\"card-intro\">3</span>"), out.html());
        assertTrue(out.html().contains("data-pw-anchor=\"card-setup\">17</span>"), out.html());
        assertEquals(2, out.resolved());
        assertTrue(out.unresolved().isEmpty());
    }

    @Test
    void should_render_an_unknown_anchor_as_a_question_mark_and_name_it() {
        PageRefs.Resolved out = PageRefs.resolve(ref("card-ghost"), Map.of());

        assertTrue(out.html().contains(">?</span>"),
                "visibly wrong beats silently wrong: " + out.html());
        assertEquals(0, out.resolved());
        assertEquals(List.of("card-ghost"), out.unresolved());
    }

    @Test
    void should_leave_html_without_placeholders_alone() {
        String html = "<p>no refs here</p>";
        assertFalse(PageRefs.present(html));
        assertEquals(html, PageRefs.resolve(html, Map.of("card-x", 1)).html());
    }

    @Test
    void should_only_touch_placeholder_span_text() {
        // The substitution must not disturb pagination: everything outside
        // the placeholder's own text has to survive byte-for-byte.
        String html = "<a href=\"#card-a\"><span class=\"toc-label\">A &amp; B</span>"
                + ref("card-a") + "</a>";

        String out = PageRefs.resolve(html, Map.of("card-a", 7)).html();

        assertTrue(out.contains("<span class=\"toc-label\">A &amp; B</span>"));
        assertTrue(out.contains("data-pw-anchor=\"card-a\">7</span>"));
    }
}
