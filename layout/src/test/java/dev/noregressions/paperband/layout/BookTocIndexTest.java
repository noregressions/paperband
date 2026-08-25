package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.model.Axis;
import dev.noregressions.paperband.model.AxisValue;
import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.BookConfig;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.Frontmatter;
import dev.noregressions.paperband.model.RenderContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The printed table of contents and back-of-book index: opt-in via
 * {@code vars.toc} / {@code vars.index}, entries in PDF assembly order, and
 * page numbers rendered as {@code pw-pageref} placeholders for the build's
 * second render pass to fill in (see the maven plugin's {@code PageRefs}).
 */
class BookTocIndexTest {

    @Nested
    @DisplayName("Printed table of contents")
    class Toc {

        @Test
        void should_render_nothing_unless_asked() {
            String html = renderBook(Map.of(),
                    card("alpha", "Alpha", Map.of()), card("beta", "Beta", Map.of()));

            assertFalse(html.contains("id=\"book-toc\""), "no vars.toc, no contents page");
            assertFalse(html.contains("pw-pageref"), "and no page-number placeholders");
        }

        @Test
        void should_list_every_card_in_order_with_a_placeholder_page_ref() {
            String html = renderBook(Map.of("toc", true),
                    card("alpha", "Alpha", Map.of()), card("beta", "Beta", Map.of()));

            assertTrue(html.contains("<section class=\"book-toc\" id=\"book-toc\">"), html);
            int alpha = html.indexOf("data-pw-anchor=\"card-alpha\">000<");
            int beta = html.indexOf("data-pw-anchor=\"card-beta\">000<");
            assertTrue(alpha > 0, "alpha entry with placeholder page number");
            assertTrue(beta > alpha, "entries in book order");
            assertTrue(html.contains("href=\"#book-toc\""),
                    "anchor bait, so the contents page gets a named destination");
        }

        @Test
        void should_accept_the_string_spelling_of_true() {
            // <book><vars> arrive as strings; yaml sends a real boolean.
            String html = renderBook(Map.of("toc", "true"), card("alpha", "Alpha", Map.of()));
            assertTrue(html.contains("book-toc"));
        }

        @Test
        void should_front_each_axis_group_with_its_divider() {
            Axis tier = new Axis("tier", "Tier", List.of(
                    new AxisValue(1, "Critical", Map.of()),
                    new AxisValue(2, "Standard", Map.of())), null);
            String html = renderBook(Map.of("toc", true), List.of(tier),
                    card("one", "First", Map.of("tier", 1)),
                    card("two", "Second", Map.of("tier", 1)),
                    card("three", "Third", Map.of("tier", 2)));

            int critical = html.indexOf("data-pw-anchor=\"axis-divider-tier-1\"");
            int one = html.indexOf("data-pw-anchor=\"card-one\"");
            int standard = html.indexOf("data-pw-anchor=\"axis-divider-tier-2\"");
            int three = html.indexOf("data-pw-anchor=\"card-three\"");
            assertTrue(critical > 0 && critical < one, "divider before its first card");
            assertTrue(one < standard && standard < three, "second group after the first's cards");
            assertTrue(html.contains("toc-kind-divider"), "divider entries carry their kind");
            assertTrue(html.contains("toc-depth-1"), "cards under a divider indent one step");
        }

        @Test
        void should_use_the_declared_title_var() {
            String html = renderBook(Map.of("toc", true, "tocTitle", "In This Guide"),
                    card("alpha", "Alpha", Map.of()));
            assertTrue(html.contains("In This Guide"));
        }
    }

    @Nested
    @DisplayName("Back-of-book index")
    class Index {

        @Test
        void should_render_nothing_when_no_card_declares_terms() {
            String html = renderBook(Map.of("index", true), card("alpha", "Alpha", Map.of()));
            assertFalse(html.contains("id=\"book-index\""),
                    "index enabled but empty renders no page at all");
        }

        @Test
        void should_group_terms_by_letter_sorted_case_insensitively() {
            String html = renderBook(Map.of("index", true),
                    card("a", "A", Map.of("index", List.of("zebra", "axes"))),
                    card("b", "B", Map.of("index", List.of("Anchor"))));

            assertTrue(html.contains("<section class=\"book-index\" id=\"book-index\">"), html);
            int letterA = html.indexOf("<h2 class=\"index-letter\">A</h2>");
            int anchor = html.indexOf(">Anchor<");
            int axes = html.indexOf(">axes<");
            int letterZ = html.indexOf("<h2 class=\"index-letter\">Z</h2>");
            assertTrue(letterA > 0 && letterA < anchor, "A group holds its terms");
            assertTrue(anchor < axes, "terms sort case-insensitively within a group");
            assertTrue(axes < letterZ, "letter groups in order");
        }

        @Test
        void should_point_a_shared_term_at_every_declaring_card() {
            String html = renderBook(Map.of("index", true),
                    card("a", "A", Map.of("index", List.of("shared"))),
                    card("b", "B", Map.of("index", List.of("shared"))));

            int first = html.indexOf("data-pw-anchor=\"card-a\">000<");
            int second = html.indexOf("data-pw-anchor=\"card-b\">000<");
            assertTrue(first > 0 && second > first, "one page ref per declaring card, in book order");
            assertEquals(html.indexOf(">shared<"), html.lastIndexOf(">shared<"),
                    "but the term itself appears once");
        }

        @Test
        void should_accept_a_comma_separated_string() {
            String html = renderBook(Map.of("index", true),
                    card("a", "A", Map.of("index", "themes, rendering")));
            assertTrue(html.contains(">themes<"));
            assertTrue(html.contains(">rendering<"));
        }

        @Test
        void should_group_non_letter_terms_under_hash() {
            String html = renderBook(Map.of("index", true),
                    card("a", "A", Map.of("index", List.of("6x9"))));
            assertTrue(html.contains("<h2 class=\"index-letter\">#</h2>"));
        }
    }

    // ---- helpers ----

    private static String renderBook(Map<String, Object> vars, Card... cards) {
        return renderBook(vars, List.of(), cards);
    }

    private static String renderBook(Map<String, Object> vars, List<Axis> axes, Card... cards) {
        BookConfig book = new BookConfig(null, "Test Book", axes, List.of(), Map.of(),
                List.of(), null, null);
        RenderContext ctx = new RenderContext(book, List.of(), vars, null, "pdf", "A4");
        LayoutEngine engine = new LayoutEngine();
        List<RenderContext> contexts = List.of(cards).stream().map(c -> ctx).toList();
        return engine.renderBook(List.of(cards), contexts, ctx);
    }

    private static Card card(String id, String title, Map<String, Object> frontmatter) {
        Block block = new Block(Block.Kind.HEADING_SECTION, null, Set.of("intro"), null, 0,
                "<p>Content</p>", List.of());
        return new Card(id, Path.of(id + ".md"), new Frontmatter(frontmatter),
                title, List.of(block));
    }
}
