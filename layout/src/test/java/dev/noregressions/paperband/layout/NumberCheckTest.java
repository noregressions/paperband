package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.cards.ContentNodes;
import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.CardNumber;
import dev.noregressions.paperband.model.Frontmatter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hand-written chapter number in a cross-reference label is a copy of derived
 * data, so it drifts. This check is what makes the drift a build failure rather
 * than a silent falsehood.
 */
class NumberCheckTest {

    private static final Map<String, CardNumber> NUMBERS = Map.of(
            "unsafe-memory-access", new CardNumber(3, 14),
            "lombok-trap", new CardNumber(3, 29));

    @Nested
    @DisplayName("Stale labels")
    class Stale {

        @Test
        void should_fail_when_a_label_states_the_wrong_number() {
            var e = assertThrows(NumberCheckException.class, () -> NumberCheck.verify(
                    List.of(card("intro", "<a href=\"card:unsafe-memory-access\">"
                            + "Chapter 3.9</a>")),
                    NUMBERS));

            assertEquals(1, e.mismatches().size());
            assertEquals("3.9", e.mismatches().get(0).claimed());
            assertEquals("3.14", e.mismatches().get(0).actual());
        }

        @Test
        void should_report_every_stale_label_not_just_the_first() {
            var bad = NumberCheck.findMismatches(
                    List.of(card("a", "<a href=\"card:unsafe-memory-access\">Chapter 3.9</a>"),
                            card("b", "<a href=\"card:lombok-trap\">Chapter 3.1</a>")),
                    NUMBERS);

            assertEquals(2, bad.size());
        }

        @Test
        void should_name_the_file_the_stale_label_is_in() {
            var bad = NumberCheck.findMismatches(
                    List.of(card("intro", "<a href=\"card:lombok-trap\">Chapter 9.9</a>")),
                    NUMBERS);

            assertEquals(Path.of("content/intro.md"), bad.get(0).source());
            assertEquals("intro", bad.get(0).cardId());
            assertEquals("lombok-trap", bad.get(0).targetId());
        }

        @Test
        void should_explain_the_fix_in_the_message() {
            var e = assertThrows(NumberCheckException.class, () -> NumberCheck.verify(
                    List.of(card("a", "<a href=\"card:lombok-trap\">Chapter 3.1</a>")),
                    NUMBERS));

            assertTrue(e.getMessage().contains("derived from book order"), e.getMessage());
        }

        @Test
        void should_find_labels_nested_in_child_blocks() {
            String deeper = "<a href=\"card:lombok-trap\">Chapter 3.1</a>";
            Block child = new Block(Block.Kind.HEADING_SECTION, null, Set.of(), "Deeper", 3,
                    deeper, List.of(), Map.of(), Map.of(), ContentNodes.of(deeper));
            Block parent = new Block(Block.Kind.HEADING_SECTION, null, Set.of(), "Top", 2,
                    "nothing here", List.of(child), Map.of(), Map.of(), ContentNodes.of("nothing here"));
            Card c = new Card("a", Path.of("content/a.md"), Frontmatter.empty(), "A",
                    List.of(parent));

            assertEquals(1, NumberCheck.findMismatches(List.of(c), NUMBERS).size());
        }
    }

    @Nested
    @DisplayName("Labels left alone")
    class Allowed {

        @Test
        void should_accept_a_label_that_states_the_right_number() {
            NumberCheck.verify(
                    List.of(card("a", "<a href=\"card:unsafe-memory-access\">Chapter 3.14</a>")),
                    NUMBERS);
        }

        @Test
        void should_ignore_a_label_with_no_number_in_it() {
            // Prose must stay free to name a chapter in its own words.
            NumberCheck.verify(
                    List.of(card("a", "<a href=\"card:unsafe-memory-access\">"
                            + "the Unsafe chapter</a>")),
                    NUMBERS);
        }

        @Test
        void should_ignore_a_link_to_an_unnumbered_card() {
            // Appendices opt out of numbering; a label mentioning a version
            // number must not be read as a chapter number.
            NumberCheck.verify(
                    List.of(card("a", "<a href=\"card:about-the-author\">JDK 8.1</a>")),
                    NUMBERS);
        }

        @Test
        void should_see_through_markup_inside_the_label() {
            var bad = NumberCheck.findMismatches(
                    List.of(card("a", "<a href=\"card:unsafe-memory-access\">Chapter 3.9 — "
                            + "<code>sun.misc.Unsafe</code></a>")),
                    NUMBERS);

            assertEquals(1, bad.size());
            assertEquals("3.9", bad.get(0).claimed());
        }

        @Test
        void should_not_be_fooled_by_a_version_number_in_the_href_fragment() {
            NumberCheck.verify(
                    List.of(card("a", "<a href=\"card:unsafe-memory-access#detail\">"
                            + "Chapter 3.14</a>")),
                    NUMBERS);
        }

        @Test
        void should_do_nothing_when_the_book_has_no_numbers() {
            NumberCheck.verify(
                    List.of(card("a", "<a href=\"card:lombok-trap\">Chapter 3.1</a>")),
                    Map.of());
        }
    }

    @Nested
    @DisplayName("A section's own format")
    class Formatted {

        private final Map<String, CardNumber> scenarios = Map.of(
                "login", new CardNumber(1, 3, "Scenario {n}"));

        @Test
        void should_fail_when_a_label_names_the_wrong_scenario() {
            var e = assertThrows(NumberCheckException.class, () -> NumberCheck.verify(
                    List.of(card("intro", "<a href=\"card:login\">Scenario 2</a>")), scenarios));
            assertEquals("Scenario 2", e.mismatches().get(0).claimed());
            assertEquals("Scenario 3", e.mismatches().get(0).actual());
        }

        @Test
        void should_accept_the_right_scenario_in_any_case() {
            assertEquals(List.of(), NumberCheck.findMismatches(
                    List.of(card("intro", "<a href=\"card:login\">see scenario 3</a>")), scenarios));
        }

        @Test
        void should_ignore_a_label_that_doesnt_name_one() {
            assertEquals(List.of(), NumberCheck.findMismatches(
                    List.of(card("intro", "<a href=\"card:login\">the login scenario, step 2.1</a>")), scenarios));
        }

        @Test
        void should_not_read_scenario_30_as_scenario_3() {
            var bad = NumberCheck.findMismatches(
                    List.of(card("intro", "<a href=\"card:login\">Scenario 30</a>")), scenarios);
            assertEquals(1, bad.size());
        }
    }

    private static Card card(String id, String html) {
        Block b = new Block(Block.Kind.HEADING_SECTION, null, Set.of(), null, 0, html, List.of(),
                Map.of(), Map.of(), ContentNodes.of(html));
        return new Card(id, Path.of("content/" + id + ".md"), Frontmatter.empty(),
                id, List.of(b));
    }
}
