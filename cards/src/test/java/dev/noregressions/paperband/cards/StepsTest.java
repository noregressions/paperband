package dev.noregressions.paperband.cards;

import dev.noregressions.paperband.model.Block;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code {!step}}: replaced with "Step N" where it's written, N numbered by
 * position among the siblings of one parent.
 */
class StepsTest {

    private static List<Block> blocks(String md) {
        return new CardLoader().parse(Path.of("t.md"), "---\ntitle: T\n---\n" + md).blocks();
    }

    private static String step(Block b) {
        return b.directives().get("step");
    }

    @Nested
    @DisplayName("Numbering")
    class Numbering {

        @Test
        void siblings_count_from_one_and_each_parent_restarts() {
            List<Block> bs = blocks("""
                    ## Install

                    ### {!step} Get it

                    ### {!step} Build it

                    ## Configure

                    ### {!step} Edit

                    ### {!step} Reload
                    """);
            assertEquals(List.of("1", "2"), bs.get(0).children().stream().map(StepsTest::step).toList());
            assertEquals(List.of("1", "2"), bs.get(1).children().stream().map(StepsTest::step).toList());
        }

        @Test
        void unstepped_siblings_in_between_do_not_reset_or_count() {
            List<Block> bs = blocks("## {!step} A\n\n## Aside\n\n## {!step} B\n");
            assertEquals("1", step(bs.get(0)));
            assertNull(step(bs.get(1)));
            assertEquals("2", step(bs.get(2)));
        }

        @Test
        void a_steps_level_is_its_own_whatever_is_nested_under_it() {
            List<Block> bs = blocks("## {!step} One\n\n### {!step} Inner\n\n## {!step} Two\n");
            assertEquals("1", step(bs.get(0)));
            assertEquals("1", step(bs.get(0).children().get(0)));
            assertEquals("2", step(bs.get(1)));
        }
    }

    @Nested
    @DisplayName("Replacement")
    class Replacement {

        @Test
        void a_marker_becomes_step_n_wherever_it_is_written() {
            List<Block> bs = blocks("# {!step} Inspect\n\n# {!step}: Build it\n\n# Tail {!step}\n");
            assertEquals(List.of("Step 1 Inspect", "Step 2: Build it", "Tail Step 3"),
                    bs.stream().map(Block::heading).toList());
        }

        @Test
        void class_and_anchor_come_from_the_heading_as_written() {
            Block b = blocks("## {!step} Build it\n").get(0);
            assertEquals(Set.of("build-it"), b.classes());
            assertEquals("Build it", b.plainHeading());
        }

        @Test
        void list_items_and_paragraphs_are_replaced_in_place() {
            String html = blocks("""
                    ## A

                    - {!step} one
                    - two {!step}
                    - three

                    Run it again ({!step}).
                    """).get(0).html();
            assertTrue(html.contains("<li data-paperband-step=\"1\">Step 1 one</li>"), html);
            assertTrue(html.contains("<li data-paperband-step=\"2\">two Step 2</li>"), html);
            assertTrue(html.contains("<li>three</li>"), html);
            assertTrue(html.contains("<p data-paperband-step=\"1\">Run it again (Step 1).</p>"), html);
        }

        @Test
        void two_markers_in_one_block_show_the_same_number() {
            assertEquals("Step 1 of the setup, Step 1", blocks("## {!step} of the setup, {!step}\n").get(0).heading());
        }

        @Test
        void a_marker_in_code_is_an_example_not_an_instruction() {
            Block b = blocks("## A\n\nWrite `{!step}` to number it.\n\n```\n{!step}\n```\n").get(0);
            assertTrue(b.html().contains("<code>{!step}</code>"), b.html());
            assertTrue(b.directives().isEmpty());
            assertFalse(b.html().contains("Step 1"), b.html());
        }
    }

    @Nested
    @DisplayName("In an attribute group")
    class Grouped {

        @Test
        void numbers_the_element_without_writing_text() {
            Block b = blocks("## What to do first {.step !step data-x=y}\n").get(0);
            assertEquals("What to do first", b.heading());
            assertEquals(Set.of("step"), b.classes());
            assertEquals(Map.of("data-x", "y"), b.attributes());
            assertEquals(Map.of("step", "1"), b.directives());
        }

        @Test
        void a_fence_counts_alongside_a_marked_paragraph() {
            String html = blocks("""
                    ## A

                    {!step} Do this first.

                    ```bash {!step}
                    mvn verify
                    ```
                    """).get(0).html();
            assertTrue(html.contains("<p data-paperband-step=\"1\">Step 1 Do this first.</p>"), html);
            assertTrue(html.contains("<pre data-paperband-step=\"2\">"), html);
        }
    }

    @Nested
    @DisplayName("Errors")
    class Errors {

        @Test
        void an_unknown_directive_fails_and_lists_the_known_ones() {
            CardParseException e = assertThrows(CardParseException.class, () -> blocks("## {!stpe} A\n"));
            assertTrue(e.getMessage().contains("unknown directive '{!stpe}'"), e.getMessage());
            assertTrue(e.getMessage().contains("{!step}"), e.getMessage());
        }

        @Test
        void step_takes_no_value() {
            CardParseException e = assertThrows(CardParseException.class, () -> blocks("## {!step=3} A\n"));
            assertTrue(e.getMessage().contains("takes no value"), e.getMessage());
        }

        @Test
        void the_card_title_cannot_carry_a_step() {
            CardParseException e = assertThrows(CardParseException.class,
                    () -> new CardLoader().parse(Path.of("t.md"), "# {!step} Title\n\n## A\n"));
            assertTrue(e.getMessage().contains("card title 'Title'"), e.getMessage());
        }

        @Test
        void step_without_the_bang_fails_and_suggests_it() {
            CardParseException e = assertThrows(CardParseException.class, () -> blocks("# {step} Ask Syft\n"));
            assertTrue(e.getMessage().contains("unknown marker '{step}'. Did you mean {!step}?"), e.getMessage());
        }

        @Test
        void braces_that_are_not_marker_shaped_are_left_alone() {
            assertDoesNotThrow(() -> blocks("## A\n\n{not !a group} and {a, b} stay as text\n"));
        }
    }
}
