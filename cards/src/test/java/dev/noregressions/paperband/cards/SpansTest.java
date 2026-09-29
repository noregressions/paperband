package dev.noregressions.paperband.cards;

import dev.noregressions.paperband.model.Block;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** {@code {.x}} in the middle of text: a span to {@code {/x}} or the end of the element. */
class SpansTest {

    private static Block block(String md) {
        return new CardLoader().parse(Path.of("t.md"), "---\ntitle: T\n---\n" + md).blocks().get(0);
    }

    private static String html(String md) {
        return block(md).html();
    }

    @Nested
    @DisplayName("Spans")
    class Ranges {

        @Test
        void a_span_runs_to_its_close() {
            assertEquals("<p>See <span class=\"warn\">this part</span> first.</p>",
                    html("See {.warn}this part{/warn} first.\n"));
        }

        @Test
        void a_span_with_no_close_runs_to_the_end_of_the_element() {
            assertEquals("<p><span class=\"foo\">The objective is <strong>what</strong> exists.</span></p>",
                    html("{.foo} The objective is **what** exists.\n"));
        }

        @Test
        void spans_nest() {
            assertEquals("<p>a <span class=\"a\">b <span class=\"b\">c</span> d</span> e</p>",
                    html("a {.a}b {.b}c{/b} d{/a} e\n"));
        }

        @Test
        void a_group_carries_ids_and_attributes_as_well_as_classes() {
            assertEquals("<p>text <span class=\"x\" id=\"i\" k=\"v\">rest</span></p>",
                    html("text {.x id=i k=v}rest\n"));
        }

        @Test
        void a_span_stays_inside_the_list_item_or_heading_it_opened_in() {
            String list = html("## A\n\n- one {.x}two\n- three\n");
            assertTrue(list.contains("<li>one <span class=\"x\">two</span></li>"), list);
            assertTrue(list.contains("<li>three</li>"), list);
            assertEquals("Intro Title", block("## Intro {.x}Title {.y}\n").heading());
            assertEquals(Set.of("y"), block("## Intro {.x}Title {.y}\n").classes());
        }
    }

    @Nested
    @DisplayName("Groups that keep their place")
    class Placed {

        @Test
        void a_trailing_group_still_classes_the_block() {
            Block b = block("## The map {.matrix}\n\nbody\n");
            assertEquals(Set.of("matrix"), b.classes());
            assertFalse(b.html().contains("<span"), b.html());
        }

        @Test
        void a_group_touching_an_inline_element_still_classes_it() {
            assertEquals("<p>see <a href=\"y\" class=\"ext\">x</a> ok</p>", html("see [x](y){.ext} ok\n"));
        }

        @Test
        void a_group_on_its_own_line_still_classes_the_block_above() {
            assertTrue(html("## A\n\n```bash\nx\n```\n{.fs--1}\n").contains("<pre class=\"fs--1\">"));
        }

        @Test
        void code_and_prose_braces_are_never_spans() {
            String p = html("use `{.x}` and {not a group} and ${home}\n");
            assertTrue(p.contains(">{.x}</code> and {not a group} and ${home}</p>"), p);
            assertFalse(p.contains("<span"), p);
        }
    }

    @Nested
    @DisplayName("Errors")
    class Errors {

        private void fails(String md, String expected) {
            CardParseException e = assertThrows(CardParseException.class, () -> block(md));
            assertTrue(e.getMessage().contains(expected), e.getMessage());
        }

        @Test
        void a_close_with_nothing_open() {
            fails("x {/warn} y\n", "{/warn} has no {.warn} to close");
        }

        @Test
        void a_close_in_a_different_element_from_its_open() {
            fails("{.z}**inside {/z}** out\n", "{/z} has no {.z} to close");
        }

        @Test
        void overlapping_spans() {
            fails("a {.a}b {.b}c{/a} d\n", "while a span inside it is still open");
        }

        @Test
        void a_directive_in_a_span_group() {
            fails("a {.a !step} b\n", "a span can't carry !step");
        }

        @Test
        void a_bare_word_in_braces() {
            fails("value {default} here\n", "unknown marker '{default}'");
        }
    }
}
