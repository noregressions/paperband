package dev.noregressions.paperband;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FrontMatterTest {

    private static FrontMatter.Split split(String text) throws IOException {
        return FrontMatter.split("test.md", text);
    }

    @Test
    void parsesTypedYaml() throws IOException {
        Map<String, Object> data = split("""
                ---
                title: Typed
                chapter: 3
                ratio: 0.5
                draft: false
                published: 2026-10-03
                updated: 2026-10-03T14:30:00Z
                tags: [a, b]
                author:
                  name: Steve
                  links:
                    - https://example.com
                ---
                body
                """).data();

        assertEquals("Typed", data.get("title"));
        assertEquals(3, data.get("chapter"));
        assertEquals(0.5, data.get("ratio"));
        assertEquals(false, data.get("draft"));
        assertEquals(LocalDate.of(2026, 10, 3), data.get("published"));
        assertEquals(OffsetDateTime.of(2026, 10, 3, 14, 30, 0, 0, ZoneOffset.UTC), data.get("updated"));
        assertEquals(List.of("a", "b"), data.get("tags"));
        assertEquals(Map.of("name", "Steve", "links", List.of("https://example.com")), data.get("author"));
        assertEquals(List.of("title", "chapter", "ratio", "draft", "published", "updated", "tags", "author"),
                List.copyOf(data.keySet()), "key order is kept");
    }

    @Test
    void noFrontMatter() throws IOException {
        FrontMatter.Split s = split("# Title\n---\nnot: front matter\n");
        assertEquals("", s.block());
        assertEquals("# Title\n---\nnot: front matter\n", s.body());
        assertTrue(s.data().isEmpty());
    }

    @Test
    void emptyFrontMatterAndDotsCloser() throws IOException {
        FrontMatter.Split empty = split("---\n---\nbody");
        assertEquals("---\n---\n", empty.block());
        assertTrue(empty.data().isEmpty());

        FrontMatter.Split dots = split("---\na: 1\n...\nbody");
        assertEquals(Map.of("a", 1), dots.data());
        assertEquals("body", dots.body());
    }

    @Test
    void paddedBodyKeepsLineNumbers() throws IOException {
        FrontMatter.Split s = split("---\na: 1\nb: 2\n---\n# Line 5\n");
        assertEquals("\n\n\n\n# Line 5\n", s.paddedBody());
    }

    @Test
    void invalidYamlNamesTheSource() {
        IOException e = assertThrows(IOException.class, () -> split("---\na: [unclosed\n---\n"));
        assertTrue(e.getMessage().startsWith("invalid front matter in test.md: "), e.getMessage());
    }

    @Test
    void frontMatterMustBeAMapping() {
        IOException e = assertThrows(IOException.class, () -> split("---\n- a\n- b\n---\n"));
        assertTrue(e.getMessage().contains("expected key: value pairs, got a list"), e.getMessage());
    }

    @Test
    void refusesToConstructArbitraryJavaTypes() {
        IOException e = assertThrows(IOException.class, () -> split("---\nf: !!java.io.File /tmp\n---\n"));
        assertTrue(e.getMessage().startsWith("invalid front matter in test.md"), e.getMessage());
    }

    @Test
    void formatsNestedValuesCompactly() {
        Map<String, Object> author = new LinkedHashMap<>();
        author.put("name", "Steve");
        author.put("tags", List.of("a", "b"));
        assertEquals("{name: Steve, tags: [a, b]}", FrontMatter.format(author));
        assertEquals("null", FrontMatter.format(null));
        assertEquals("3", FrontMatter.format(3));
    }
}
