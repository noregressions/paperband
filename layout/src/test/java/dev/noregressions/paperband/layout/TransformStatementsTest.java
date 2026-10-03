package dev.noregressions.paperband.layout;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Statements read as the transforms they run. */
class TransformStatementsTest {

    private static TransformStatements.Statement one(String line) {
        List<TransformStatements.Statement> all = TransformStatements.parse(line);
        assertEquals(1, all.size(), line);
        return all.get(0);
    }

    private static void reads(String line, String transform, Map<String, Object> args) {
        TransformStatements.Statement s = one(line);
        assertEquals(transform, s.transform(), line);
        assertEquals(args, s.args(), line);
    }

    @Test
    void every_form_reads_as_its_transform() {
        reads("drop .instructor-note", "drop", Map.of("selector", ".instructor-note"));
        reads("keep card:has([data-paperband-step])", "keep", Map.of("selector", "card:has([data-paperband-step])"));
        reads("blank .solution as answer-space", "blank", Map.of("selector", ".solution", "name", "answer-space"));
        reads("replace block.solution with {.answer lines=4}", "replace",
                Map.of("selector", "block.solution", "block", "{.answer lines=4}"));
        reads("insert .answer lines=6 after .exercise:not(:has(.solution))", "insertAfter",
                Map.of("selector", ".exercise:not(:has(.solution))", "block", ".answer lines=6"));
        reads("insert {.note} before p.lead", "insertBefore", Map.of("selector", "p.lead", "block", "{.note}"));
        reads("insert {.first} first in ul", "prepend", Map.of("selector", "ul", "block", "{.first}"));
        reads("insert {.last} last in ul", "append", Map.of("selector", "ul", "block", "{.last}"));
        reads("wrap block.aside in {.box}", "wrap", Map.of("selector", "block.aside", "block", "{.box}"));
        reads("add .lead to p.instructions", "addClass", Map.of("selector", "p.instructions", "name", "lead"));
        reads("remove lead from p", "removeClass", Map.of("selector", "p", "name", "lead"));
        reads("set lines=8 on block.solution", "set", Map.of("selector", "block.solution", "attributes", "lines=8"));
    }

    @Test
    void a_selector_keeps_its_spaces_and_a_keyword_in_brackets_or_quotes_is_not_one() {
        reads("replace block.exercise > p[title=\"with in\"] with {.x label='before after'}", "replace",
                Map.of("selector", "block.exercise > p[title=\"with in\"]", "block", "{.x label='before after'}"));
    }

    @Test
    void blank_lines_and_hash_lines_are_skipped_and_order_kept() {
        List<TransformStatements.Statement> all = TransformStatements.parse("""

                # leave out the notes
                drop .note
                  blank .solution as answer-space
                """);
        assertEquals(List.of("drop", "blank"), all.stream().map(TransformStatements.Statement::transform).toList());
        assertEquals("blank .solution as answer-space", all.get(1).text());
    }

    @Test
    void what_isnt_a_statement_says_why() {
        Map<String, String> bad = Map.of(
                "delete .x", "doesn't start with a statement's word",
                "replace .x", "has no 'with'",
                "replace with {.x}", "is missing a selector",
                "insert {.x} beside .y", "says where",
                "add .a .b to p", "needs one class name",
                "drop", "is missing a selector",
                "drop p[title=\"x]", "doesn't close");
        bad.forEach((line, why) -> {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> TransformStatements.parse(line), line);
            assertTrue(e.getMessage().contains(why) && e.getMessage().contains(line), e.getMessage());
        });
    }
}
