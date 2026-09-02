package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.model.Axis;
import dev.noregressions.paperband.model.AxisValue;
import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.BookConfig;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.Frontmatter;
import dev.noregressions.paperband.model.OutlineEntry;
import dev.noregressions.paperband.model.PageMatter;
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
 * The book's bookmark tree — what {@code LayoutEngine.outline()} hands the
 * maven plugin to write into the PDF (see {@code PdfOutline}). Same entries
 * the printed contents page is made of, built whether or not the book prints
 * one, plus the two pages a contents page can't list: itself and the back
 * page.
 */
class BookOutlineTest {

    @Nested
    @DisplayName("Entries")
    class Entries {

        @Test
        void should_list_every_card_without_any_contents_page_asked_for() {
            // The whole point: bookmarks are navigation, a contents page is
            // paper. A book wanting one and not the other is ordinary.
            List<OutlineEntry> outline = outline(Map.of(),
                    card("alpha", "Alpha", Map.of()), card("beta", "Beta", Map.of()));

            assertEquals(List.of("Alpha", "Beta"), labels(outline));
            assertEquals(List.of("card-alpha", "card-beta"), anchors(outline));
            assertTrue(outline.stream().allMatch(e -> e.depth() == 0),
                    "no dividers, so nothing to nest under");
        }

        @Test
        void should_nest_a_dividers_cards_under_it() {
            Axis tier = new Axis("tier", "Tier", List.of(
                    new AxisValue(1, "Critical", Map.of()),
                    new AxisValue(2, "Standard", Map.of())), null);
            List<OutlineEntry> outline = outline(Map.of(), List.of(tier), null, null,
                    card("one", "First", Map.of("tier", 1)),
                    card("two", "Second", Map.of("tier", 1)),
                    card("three", "Third", Map.of("tier", 2)));

            assertEquals(List.of("Critical", "First", "Second", "Standard", "Third"),
                    labels(outline));
            assertEquals(List.of(0, 1, 1, 0, 1), outline.stream().map(OutlineEntry::depth).toList());
            assertEquals("axis-divider-tier-1", outline.get(0).anchor());
        }

        @Test
        void should_end_with_the_index_when_the_book_has_one() {
            List<OutlineEntry> outline = outline(Map.of("index", true),
                    card("a", "A", Map.of("index", List.of("themes"))));

            OutlineEntry last = outline.get(outline.size() - 1);
            assertEquals("Index", last.label());
            assertEquals("book-index", last.anchor());
            assertEquals(0, last.depth());
        }

        @Test
        void should_be_empty_for_a_single_card_render() {
            LayoutEngine engine = new LayoutEngine();
            assertTrue(engine.outline().isEmpty(), "nothing rendered yet");

            engine.render(card("alpha", "Alpha", Map.of()), ctx(Map.of(), List.of(), null));

            assertTrue(engine.outline().isEmpty(),
                    "a card has no book structure to bookmark");
        }
    }

    @Nested
    @DisplayName("The contents page's own bookmark")
    class ContentsBookmark {

        @Test
        void should_not_appear_when_the_book_prints_no_contents_page() {
            List<OutlineEntry> outline = outline(Map.of(), card("alpha", "Alpha", Map.of()));

            assertFalse(anchors(outline).contains("book-toc"));
        }

        @Test
        void should_lead_the_outline_when_the_contents_page_leads_the_book() {
            List<OutlineEntry> outline = outline(Map.of("toc", true),
                    card("alpha", "Alpha", Map.of()));

            assertEquals(List.of("Contents", "Alpha"), labels(outline));
            assertEquals("book-toc", outline.get(0).anchor());
        }

        @Test
        void should_use_the_declared_contents_title() {
            List<OutlineEntry> outline = outline(Map.of("toc", true, "tocTitle", "In This Guide"),
                    card("alpha", "Alpha", Map.of()));

            assertEquals("In This Guide", outline.get(0).label(),
                    "the bookmark and the page it opens say the same thing");
        }

        @Test
        void should_sit_where_a_declared_marker_puts_the_page() {
            List<OutlineEntry> outline = outline(Map.of(), List.of(), 1, null,
                    card("alpha", "Alpha", Map.of()), card("beta", "Beta", Map.of()));

            assertEquals(List.of("Alpha", "Contents", "Beta"), labels(outline));
        }

        @Test
        void should_follow_the_last_card_for_a_trailing_marker() {
            List<OutlineEntry> outline = outline(Map.of(), List.of(), 2, null,
                    card("alpha", "Alpha", Map.of()), card("beta", "Beta", Map.of()));

            assertEquals(List.of("Alpha", "Beta", "Contents"), labels(outline));
        }

        @Test
        void should_precede_the_divider_of_the_card_it_is_placed_before() {
            // book.html emits the contents page ahead of that card's divider,
            // so the bookmark must too — and at the divider's own level, or it
            // would sit inside the previous group.
            Axis tier = new Axis("tier", "Tier", List.of(
                    new AxisValue(1, "Critical", Map.of()),
                    new AxisValue(2, "Standard", Map.of())), null);
            List<OutlineEntry> outline = outline(Map.of(), List.of(tier), 1, null,
                    card("one", "First", Map.of("tier", 1)),
                    card("two", "Second", Map.of("tier", 2)));

            assertEquals(List.of("Critical", "First", "Contents", "Standard", "Second"),
                    labels(outline));
            assertEquals(0, outline.get(2).depth());
        }

        @Test
        void should_borrow_the_depth_of_the_entry_it_displaces() {
            // A marker landing inside a group: a top-level bookmark here would
            // adopt the rest of that group's cards, so it joins them instead.
            Axis tier = new Axis("tier", "Tier",
                    List.of(new AxisValue(1, "Critical", Map.of())), null);
            List<OutlineEntry> outline = outline(Map.of(), List.of(tier), 1, null,
                    card("one", "First", Map.of("tier", 1)),
                    card("two", "Second", Map.of("tier", 1)));

            assertEquals(List.of("Critical", "First", "Contents", "Second"), labels(outline));
            assertEquals(1, outline.get(2).depth(), "a sibling of the cards it sits among");
        }
    }

    @Nested
    @DisplayName("The back page")
    class BackPage {

        @Test
        void should_close_the_outline_when_declared() {
            PageMatter back = new PageMatter(null, null, "Colophon",
                    null, null, null, false, false);
            List<OutlineEntry> outline = outline(Map.of(), List.of(), null, back,
                    card("alpha", "Alpha", Map.of()));

            assertEquals(List.of("Alpha", "Colophon"), labels(outline));
            assertEquals("book-back", outline.get(1).anchor());
        }

        @Test
        void should_fall_back_to_a_readable_label_when_it_declares_no_title() {
            PageMatter back = new PageMatter("back.png", null, null,
                    null, null, null, false, false);
            List<OutlineEntry> outline = outline(Map.of(), List.of(), null, back,
                    card("alpha", "Alpha", Map.of()));

            assertEquals("Back page", outline.get(1).label(),
                    "an anchor name is not a thing to show a reader");
        }

        @Test
        void should_stay_out_when_nothing_declares_one() {
            List<OutlineEntry> outline = outline(Map.of(), card("alpha", "Alpha", Map.of()));

            assertFalse(anchors(outline).contains("book-back"));
        }
    }

    // ---- helpers ----

    private static List<String> labels(List<OutlineEntry> outline) {
        return outline.stream().map(OutlineEntry::label).toList();
    }

    private static List<String> anchors(List<OutlineEntry> outline) {
        return outline.stream().map(OutlineEntry::anchor).toList();
    }

    private static List<OutlineEntry> outline(Map<String, Object> vars, Card... cards) {
        return outline(vars, List.of(), null, null, cards);
    }

    private static List<OutlineEntry> outline(
            Map<String, Object> vars, List<Axis> axes, Integer tocAt, PageMatter back,
            Card... cards) {
        RenderContext ctx = ctx(vars, axes, back);
        LayoutEngine engine = new LayoutEngine();
        engine.setTocAt(tocAt);
        List<RenderContext> contexts = List.of(cards).stream().map(c -> ctx).toList();
        engine.renderBook(List.of(cards), contexts, ctx);
        return engine.outline();
    }

    private static RenderContext ctx(Map<String, Object> vars, List<Axis> axes, PageMatter back) {
        BookConfig book = new BookConfig(null, "Test Book", axes, List.of(), Map.of(),
                List.of(), null, null, null, null, back, null, null);
        return new RenderContext(book, List.of(), vars, null, "pdf", "A4");
    }

    private static Card card(String id, String title, Map<String, Object> frontmatter) {
        Block block = new Block(Block.Kind.HEADING_SECTION, null, Set.of("intro"), null, 0,
                "<p>Content</p>", List.of());
        return new Card(id, Path.of(id + ".md"), new Frontmatter(frontmatter),
                title, List.of(block));
    }
}
