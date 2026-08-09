package dev.noregressions.paperband.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link CardSchema#fromYaml} takes the already-YAML-parsed node (SnakeYAML
 * lives in pagewright-config/-cards, not core), so these tests hand it plain
 * maps and lists — exactly what SnakeYAML would produce.
 */
class CardSchemaTest {

    @Test
    void parsesFrontmatterAndSections() {
        CardSchema schema = CardSchema.fromYaml(Map.of(
                "frontmatter", List.of("id", "tier", "title"),
                "sections", List.of(
                        Map.of("field", "oneliner"),
                        Map.of("field", "error_output", "heading", "Error", "fence", "error-output"),
                        Map.of("field", "what_changed", "heading", "What Changed",
                                "class", "changed", "level", 3))));

        assertEquals(List.of("id", "tier", "title"), schema.frontmatterFields());
        assertEquals(3, schema.sections().size());

        CardSchema.Section intro = schema.sections().get(0);
        assertNull(intro.heading());
        assertNull(intro.fence());
        assertEquals("oneliner", intro.effectiveClass());

        CardSchema.Section error = schema.sections().get(1);
        assertEquals("error-output", error.fence());
        assertEquals("error-output", error.effectiveClass(), "kebab-cased field name");

        CardSchema.Section changed = schema.sections().get(2);
        assertEquals("changed", changed.effectiveClass(), "explicit class wins");
        assertEquals(3, changed.level());
    }

    @Test
    void levelDefaultsTo2AndClampsOutOfRange() {
        CardSchema schema = CardSchema.fromYaml(Map.of(
                "sections", List.of(
                        Map.of("field", "a", "heading", "A"),
                        Map.of("field", "b", "heading", "B", "level", 9))));
        assertEquals(2, schema.sections().get(0).level());
        assertEquals(2, schema.sections().get(1).level(), "out-of-range level clamps to 2");
    }

    @Test
    void rejectsMalformedShapes() {
        assertThrows(IllegalArgumentException.class,
                () -> CardSchema.fromYaml("just a string"));
        assertThrows(IllegalArgumentException.class,
                () -> CardSchema.fromYaml(Map.of("frontmatter", List.of("id"), "sections", "nope")));
        assertThrows(IllegalArgumentException.class,
                () -> CardSchema.fromYaml(Map.of("sections", List.of(Map.of("heading", "NoField")))));
        assertThrows(IllegalArgumentException.class,
                () -> CardSchema.fromYaml(Map.of()), "empty schema rejected");
    }

    @Test
    void kebabCasesFieldNames() {
        assertEquals("error-output", CardSchema.kebab("error_output"));
        assertEquals("watch-out", CardSchema.kebab("WATCH out"));
        assertEquals("plain", CardSchema.kebab("plain"));
    }
}
