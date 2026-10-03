package dev.noregressions.paperband.layout;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** A block a transform adds, read the way a card's attributes are. */
class BlockSpecTest {

    @Test
    void reads_an_id_classes_and_attributes_in_order() {
        BlockSpec s = BlockSpec.parse("{#q1 .answer .wide lines=4 label=\"Your answer\" note='a b'}");
        assertEquals("q1", s.id());
        assertEquals(List.of("answer", "wide"), List.copyOf(s.classes()));
        assertEquals(List.of("lines", "label", "note"), List.copyOf(s.attributes().keySet()));
        assertEquals("Your answer", s.attributes().get("label"));
    }

    @Test
    void the_braces_are_optional() {
        assertEquals(BlockSpec.parse("{.answer lines=4}"), BlockSpec.parse(".answer lines=4"));
    }

    @Test
    void writes_an_empty_div() {
        BlockSpec s = new BlockSpec(null, Set.of("answer"), Map.of("lines", "4"));
        assertEquals("<div class=\"answer\" lines=\"4\"></div>", s.node(null, List.of()).html());
        assertEquals("<div id=\"kept\" class=\"answer\" lines=\"4\"></div>", s.node("kept", List.of()).html());
    }

    @Test
    void refuses_what_the_content_policy_strips_and_urls() {
        for (String bad : List.of("{style=x}", "{width=3}", "{onload=x}", "{src=x.png}", "{href=x}",
                "{class=x}", "{lines}", "{.}", "{.answer", "{x=\"open}")) {
            assertThrows(IllegalArgumentException.class, () -> BlockSpec.parse(bad), bad);
        }
    }
}
