package dev.noregressions.paperband.cards;

import dev.noregressions.paperband.model.Block;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@code {!step}}: numbered by position among the siblings of one parent. */
class StepsTest {

    private static List<Block> blocks(String md) {
        return new CardLoader().parse(Path.of("t.md"), "---\ntitle: T\n---\n" + md).blocks();
    }

    private static String step(Block b) {
        return b.directives().get("step");
    }

    @Nested
    @DisplayName("Headings")
    class Headings {

        @Test
        void siblings_count_from_one_and_each_parent_restarts() {
            List<Block> bs = blocks("""
                    ## Install

                    ### Get it {!step}

                    ### Build it {!step}

                    ## Configure

                    ### Edit {!step}

                    ### Reload {!step}
                    """);
            assertEquals(List.of("1", "2"), bs.get(0).children().stream().map(StepsTest::step).toList());
            assertEquals(List.of("1", "2"), bs.get(1).children().stream().map(StepsTest::step).toList());
        }

        @Test
        void unstepped_siblings_in_between_do_not_reset_or_count() {
            List<Block> bs = blocks("## A {!step}\n\n## Aside\n\n## B {!step}\n");
            assertEquals("1", step(bs.get(0)));
            assertNull(step(bs.get(1)));
            assertEquals("2", step(bs.get(2)));
        }

        @Test
        void directive_is_kept_out_of_classes_attributes_and_heading() {
            Block b = blocks("## What to do first {.step !step data-x=y}\n").get(0);
            assertEquals(java.util.Set.of("step"), b.classes());
            assertEquals(Map.of("data-x", "y"), b.attributes());
            assertEquals(Map.of("step", "1"), b.directives());
            assertEquals("What to do first", b.heading());
        }

        @Test
        void a_steps_level_is_its_own_whatever_is_nested_under_it() {
            List<Block> bs = blocks("## One {!step}\n\n### Inner {!step}\n\n## Two {!step}\n");
            assertEquals("1", step(bs.get(0)));
            assertEquals("1", step(bs.get(0).children().get(0)));
            assertEquals("2", step(bs.get(1)));
        }

        @Test
        void the_card_title_cannot_carry_a_step() {
            CardParseException e = assertThrows(CardParseException.class,
                    () -> new CardLoader().parse(Path.of("t.md"), "# Title {!step}\n\n## A\n"));
            assertTrue(e.getMessage().contains("card title 'Title'"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("Any kind of element")
    class AnyElement {

        @Test
        void list_items_number_within_their_list() {
            String html = blocks("## A\n\n- one {!step}\n- two {!step}\n- three\n").get(0).html();
            assertTrue(html.contains("<li data-paperband-step=\"1\">one</li>"), html);
            assertTrue(html.contains("<li data-paperband-step=\"2\">two</li>"), html);
            assertTrue(html.contains("<li>three</li>"), html);
        }

        @Test
        void paragraphs_and_fences_under_one_parent_share_one_sequence() {
            String html = blocks("""
                    ## A

                    Do this first. {!step}

                    ```bash {!step}
                    mvn verify
                    ```
                    """).get(0).html();
            assertTrue(html.contains("<p data-paperband-step=\"1\">Do this first.</p>"), html);
            assertTrue(html.contains("<pre data-paperband-step=\"2\">"), html);
        }

        @Test
        void a_heading_step_and_a_paragraph_step_at_one_level_count_together() {
            List<Block> bs = blocks("Intro step. {!step}\n\n## A {!step}\n");
            assertTrue(bs.get(0).html().contains("data-paperband-step=\"1\""), bs.get(0).html());
            assertEquals("2", step(bs.get(1)));
        }
    }

    @Nested
    @DisplayName("Errors")
    class Errors {

        @Test
        void an_unknown_directive_fails_and_lists_the_known_ones() {
            CardParseException e = assertThrows(CardParseException.class, () -> blocks("## A {!stpe}\n"));
            assertTrue(e.getMessage().contains("unknown directive '!stpe'"), e.getMessage());
            assertTrue(e.getMessage().contains("!step"), e.getMessage());
        }

        @Test
        void step_takes_no_value() {
            CardParseException e = assertThrows(CardParseException.class, () -> blocks("## A {!step=3}\n"));
            assertTrue(e.getMessage().contains("takes no value"), e.getMessage());
        }

        @Test
        void braces_that_are_not_a_group_are_never_read_as_directives() {
            assertDoesNotThrow(() -> blocks("## A\n\nuse `{!anything}` in code, or {not !a group}\n"));
        }
    }
}
