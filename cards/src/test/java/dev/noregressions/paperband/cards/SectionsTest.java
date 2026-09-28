package dev.noregressions.paperband.cards;

import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.Card;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The heading structure made explicit in the tree, read back as blocks. */
class SectionsTest {

    private static Card card(String md) {
        return new CardLoader().parse(Path.of("t.md"), md);
    }

    private static List<Block> blocks(String md) {
        return card("---\ntitle: T\n---\n" + md).blocks();
    }

    @Nested
    @DisplayName("Structure")
    class Structure {

        @Test
        void nests_by_rank_and_keeps_own_content_apart_from_children() {
            List<Block> bs = blocks("""
                    Intro.

                    ## Setup

                    Runs first.

                    ### Prerequisites

                    Java 21.

                    ## Usage

                    Go.
                    """);
            assertEquals(3, bs.size());
            assertEquals(Set.of("intro"), bs.get(0).classes());
            Block setup = bs.get(1);
            assertEquals("Setup", setup.heading());
            assertEquals("<p>Runs first.</p>", setup.html());
            assertEquals(1, setup.children().size());
            assertEquals("Prerequisites", setup.children().get(0).heading());
            assertEquals(3, setup.children().get(0).level());
            assertEquals("Usage", bs.get(2).heading());
        }

        @Test
        void no_section_markers_leak_into_block_html() {
            for (Block b : blocks("## A\n\ntext\n\n### B\n\nmore\n")) {
                assertFalse(b.html().contains("data-paperband"), b.html());
                for (Block c : b.children()) assertFalse(c.html().contains("data-paperband"), c.html());
            }
        }

        @Test
        void a_section_the_author_typed_is_content_not_structure() {
            Block b = blocks("## A\n\n<section class=\"mine\"><p>x</p></section>\n").get(0);
            assertTrue(b.children().isEmpty());
            assertTrue(b.html().contains("<section class=\"mine\">"), b.html());
        }
    }

    @Nested
    @DisplayName("Title")
    class Title {

        @Test
        void first_h1_is_the_title_and_not_a_block() {
            Card c = card("# The Title\n\n## A\n\nx\n");
            assertEquals("The Title", c.title());
            assertEquals(List.of("A"), c.blocks().stream().map(Block::heading).toList());
        }

        @Test
        void a_late_title_h1_is_taken_out_without_closing_the_open_section() {
            Card c = card("## A\n\none\n\n# Late Title\n\ntwo\n");
            assertEquals("Late Title", c.title());
            assertEquals(1, c.blocks().size());
            assertEquals("<p>one</p><p>two</p>", c.blocks().get(0).html());
        }

        @Test
        void with_a_frontmatter_title_every_h1_is_a_section() {
            List<Block> bs = blocks("# Step one\n\nx\n\n# Step two\n\ny\n");
            assertEquals(List.of("Step one", "Step two"), bs.stream().map(Block::heading).toList());
            assertEquals(1, bs.get(0).level());
        }
    }

    @Nested
    @DisplayName("Raw HTML")
    class RawHtml {

        @Test
        void self_contained_html_is_content_of_its_section() {
            Block b = blocks("""
                    ## Data

                    <table class="scores">
                    <tr><td>1</td></tr>
                    </table>

                    ## Next
                    """).get(0);
            assertTrue(b.html().contains("<table class=\"scores\">"), b.html());
        }

        @Test
        void a_heading_inside_open_raw_html_fails_and_names_both() {
            CardParseException e = assertThrows(CardParseException.class, () -> blocks("""
                    <div class="note">

                    ## A heading inside

                    </div>
                    """));
            assertTrue(e.getMessage().contains("<div>"), e.getMessage());
            assertTrue(e.getMessage().contains("A heading inside"), e.getMessage());
        }

        @Test
        void a_raw_html_heading_is_content_not_a_section() {
            List<Block> bs = blocks("## A\n\n<h2>Not a section</h2>\n\ntext\n");
            assertEquals(1, bs.size());
            assertTrue(bs.get(0).html().contains("<h2>Not a section</h2>"), bs.get(0).html());
        }

        @Test
        void elements_that_close_themselves_do_not_count_as_open() {
            List<Block> bs = blocks("<p>unclosed para\n\n## A\n\n<ul><li>item\n</ul>\n\n## B\n");
            assertEquals(java.util.Arrays.asList(null, "A", "B"), bs.stream().map(Block::heading).toList());
        }

        @Test
        void a_tag_inside_a_comment_does_not_count_as_open() {
            assertDoesNotThrow(() -> blocks("<!-- <div> -->\n\n## A\n"));
        }

        @Test
        void html_closed_before_the_heading_is_fine() {
            assertDoesNotThrow(() -> blocks("<div>\n\ntext\n\n</div>\n\n## A\n"));
        }
    }
}
