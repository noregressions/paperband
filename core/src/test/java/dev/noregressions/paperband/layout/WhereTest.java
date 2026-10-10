package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.FrontMatter;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WhereTest {

    private static Where where(String yaml) throws ConfigException {
        return Where.parse(YamlSpec.of(FrontMatter.parseYaml(yaml), "m.yml: where"));
    }

    private static final Map<String, Object> PAGE = Map.of(
            "draft", false, "chapter", 3, "title", "Intro to Java", "tags", List.of("java", "md"),
            "published", LocalDate.of(2026, 10, 3), "author", Map.of("name", "Steve"));

    private static boolean test(String yaml) throws ConfigException {
        return where(yaml).test(PAGE, "a.md");
    }

    @Test
    void exactMatchesAllMustHold() throws ConfigException {
        assertTrue(test("{ draft: false, chapter: 3 }"));
        assertTrue(test("{ chapter: 3.0 }"), "numbers compare by value");
        assertFalse(test("{ draft: false, chapter: 4 }"));
        assertTrue(test("{ author.name: Steve }"), "dotted keys reach nested values");
        assertFalse(test("{ missing: x }"), "a missing key equals nothing");
    }

    @Test
    void operators() throws ConfigException {
        assertTrue(test("{ chapter: { gte: 2, lt: 10 } }"));
        assertFalse(test("{ chapter: { gt: 3 } }"));
        assertTrue(test("{ chapter: { lte: 3 } }"));
        assertTrue(test("{ draft: { ne: true } }"));
        assertTrue(test("{ missing: { ne: true } }"), "ne holds for a missing key");
        assertFalse(test("{ missing: { gt: 1 } }"));
        assertTrue(test("{ tags: { contains: java } }"));
        assertTrue(test("{ title: { contains: Java } }"), "contains works on text too");
        assertTrue(test("{ chapter: { in: [1, 3, 5] } }"));
        assertTrue(test("{ title: { exists: true }, missing: { exists: false } }"));
        assertTrue(test("{ published: { gte: 2026-01-01 } }"), "dates compare as dates");
    }

    @Test
    void badOperatorsFailWhenParsed() {
        ConfigException e = assertThrows(ConfigException.class, () -> where("{ chapter: { gtt: 2 } }"));
        assertEquals("m.yml: where.chapter: unknown operator \"gtt\"; expected one of eq, ne, gt, gte, lt, lte, in, contains, exists",
                e.getMessage());
        assertThrows(ConfigException.class, () -> where("{ chapter: { in: 3 } }"));
        assertThrows(ConfigException.class, () -> where("{ chapter: { exists: yes-please } }"));
        assertThrows(ConfigException.class, () -> where("{ tags: [a, b] }"));
    }

    @Test
    void comparingDifferentKindsFailsWithTheFile() throws ConfigException {
        Where w = where("{ title: { gt: 3 } }");
        ConfigException e = assertThrows(ConfigException.class, () -> w.test(PAGE, "a.md"));
        assertEquals("m.yml: where.title: can't compare text \"Intro to Java\" with number 3 (in a.md)", e.getMessage());
    }
}
