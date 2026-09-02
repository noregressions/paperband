package dev.noregressions.paperband.number;

import dev.noregressions.paperband.model.CardNumber;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chapter numbers derived from book order: sections as groups by default,
 * several sections sharing one group when they declare the same {@code part:},
 * and front/back matter opting out with {@code numbered: false}.
 */
class NumberingTest {

    @Nested
    @DisplayName("Sections as their own groups")
    class DiscoveredSections {

        @Test
        void should_number_each_section_from_one() {
            var numbers = Numbering.resolve(
                    placements("intro:getting-started", "quickstart:getting-started",
                            "frontmatter:authoring", "includes:authoring"),
                    Map.of());

            assertEquals("1.1", label(numbers, "intro"));
            assertEquals("1.2", label(numbers, "quickstart"));
            assertEquals("2.1", label(numbers, "frontmatter"));
            assertEquals("2.2", label(numbers, "includes"));
        }

        @Test
        void should_number_groups_by_first_appearance_not_alphabetically() {
            var numbers = Numbering.resolve(
                    placements("a:zulu", "b:alpha"), Map.of());

            assertEquals(1, numbers.get("a").group());
            assertEquals(2, numbers.get("b").group());
        }

        @Test
        void should_return_nothing_for_an_empty_book() {
            assertTrue(Numbering.resolve(List.of(), Map.of()).isEmpty());
        }
    }

    @Nested
    @DisplayName("Sections sharing a part")
    class DeclaredParts {

        @Test
        void should_run_ordinals_continuously_across_the_whole_part() {
            var numbers = Numbering.resolve(
                    placements("a:wont-start", "b:wont-start",
                            "c:crashes", "d:crashes", "e:environment"),
                    parts(Map.of("wont-start", 3, "crashes", 3, "environment", 3)));

            assertEquals("3.1", label(numbers, "a"));
            assertEquals("3.2", label(numbers, "b"));
            assertEquals("3.3", label(numbers, "c"));
            assertEquals("3.4", label(numbers, "d"));
            assertEquals("3.5", label(numbers, "e"));
        }

        @Test
        void should_honour_the_declared_number_rather_than_position() {
            // The book's first part is 0, not 1 — front matter numbered 0.x is
            // a real convention and position must not override it.
            var numbers = Numbering.resolve(
                    placements("intro:introduction", "tools:tools"),
                    parts(Map.of("introduction", 0, "tools", 1)));

            assertEquals("0.1", label(numbers, "intro"));
            assertEquals("1.1", label(numbers, "tools"));
        }

        @Test
        void should_keep_separate_parts_counting_separately() {
            var numbers = Numbering.resolve(
                    placements("a:one", "b:two", "c:one"),
                    parts(Map.of("one", 1, "two", 2)));

            assertEquals("1.1", label(numbers, "a"));
            assertEquals("2.1", label(numbers, "b"));
            // Same part resumed after an interruption: ordinals follow book
            // order, they do not restart.
            assertEquals("1.2", label(numbers, "c"));
        }

        @Test
        void should_reject_a_book_that_declares_parts_only_some_of_the_time() {
            var e = assertThrows(IllegalStateException.class, () -> Numbering.resolve(
                    placements("a:declared", "b:undeclared"),
                    parts(Map.of("declared", 3))));

            assertTrue(e.getMessage().contains("declared"), e.getMessage());
            assertTrue(e.getMessage().contains("undeclared"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("Sections opting out")
    class Unnumbered {

        @Test
        void should_leave_unnumbered_cards_out_of_the_result() {
            var sections = new LinkedHashMap<String, SectionNumbering>();
            sections.put("appendix", SectionNumbering.unnumbered());

            var numbers = Numbering.resolve(
                    placements("a:tools", "changes:appendix", "author:appendix"), sections);

            assertEquals("1.1", label(numbers, "a"));
            assertFalse(numbers.containsKey("changes"));
            assertFalse(numbers.containsKey("author"));
        }

        @Test
        void should_not_let_an_unnumbered_section_consume_a_group_number() {
            // `99-appendix` must not become "part 99", nor push the next real
            // section's group along.
            var sections = new LinkedHashMap<String, SectionNumbering>();
            sections.put("appendix", SectionNumbering.unnumbered());

            var numbers = Numbering.resolve(
                    placements("a:tools", "x:appendix", "b:migrations"), sections);

            assertEquals(1, numbers.get("a").group());
            assertEquals(2, numbers.get("b").group());
        }

        @Test
        void should_ignore_unnumbered_sections_in_the_all_or_nothing_check() {
            var sections = new LinkedHashMap<String, SectionNumbering>();
            sections.put("intro", new SectionNumbering(true, 0));
            sections.put("tools", new SectionNumbering(true, 1));
            sections.put("back", SectionNumbering.unnumbered());

            var numbers = Numbering.resolve(
                    placements("a:intro", "b:tools", "z:back"), sections);

            assertEquals("0.1", label(numbers, "a"));
            assertEquals("1.1", label(numbers, "b"));
            assertFalse(numbers.containsKey("z"));
        }
    }

    @Nested
    @DisplayName("CardNumber")
    class Formatting {

        @Test
        void should_render_the_dotted_label() {
            assertEquals("3.14", new CardNumber(3, 14).label());
        }

        @Test
        void should_reject_a_zero_ordinal() {
            assertThrows(IllegalArgumentException.class, () -> new CardNumber(3, 0));
        }
    }

    // "cardId:sectionId" pairs, in book order.
    private static List<Numbering.Placement> placements(String... spec) {
        List<Numbering.Placement> out = new ArrayList<>(spec.length);
        for (String s : spec) {
            String[] parts = s.split(":", 2);
            out.add(new Numbering.Placement(parts[0], parts[1]));
        }
        return out;
    }

    private static Map<String, SectionNumbering> parts(Map<String, Integer> bySection) {
        Map<String, SectionNumbering> out = new LinkedHashMap<>();
        bySection.forEach((id, part) -> out.put(id, new SectionNumbering(true, part)));
        return out;
    }

    private static String label(Map<String, CardNumber> numbers, String cardId) {
        CardNumber n = numbers.get(cardId);
        return n == null ? null : n.label();
    }
}
