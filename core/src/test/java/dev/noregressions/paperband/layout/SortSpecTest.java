package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.FrontMatter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SortSpecTest {

    private static final Path A = Path.of("a.md"), B = Path.of("b.md"), C = Path.of("c.md"), D = Path.of("d.md");

    private static List<Path> sort(String yaml, Map<Path, Map<String, Object>> fm) throws ConfigException {
        return SortSpec.parse(FrontMatter.parseYaml(yaml), "m.yml: sort").sort(List.of(A, B, C, D), fm);
    }

    private static Map<Path, Map<String, Object>> fm(Object a, Object b, Object c, Object d) {
        Map<Path, Map<String, Object>> fm = new HashMap<>();
        fm.put(A, a == null ? Map.of() : Map.of("n", a, "part", 1));
        fm.put(B, b == null ? Map.of() : Map.of("n", b, "part", 2));
        fm.put(C, c == null ? Map.of() : Map.of("n", c, "part", 1));
        fm.put(D, d == null ? Map.of() : Map.of("n", d, "part", 2));
        return fm;
    }

    @Test
    void byOneKeyAndDescending() throws ConfigException {
        assertEquals(List.of(C, A, D, B), sort("page.n", fm(2, 10, 1, 3)), "numbers, not text order");
        assertEquals(List.of(B, D, A, C), sort("{ by: page.n, order: desc }", fm(2, 10, 1, 3)));
    }

    @Test
    void tieBreakersAndPath() throws ConfigException {
        assertEquals(List.of(C, A, D, B), sort("[page.part, page.n]", fm(2, 10, 1, 3)));
        assertEquals(List.of(B, D, A, C), sort("[{ by: page.part, order: desc }, path]", fm(1, 1, 1, 1)));
    }

    @Test
    void missingValuesAreAnErrorUnlessPlaced() throws ConfigException {
        ConfigException e = assertThrows(ConfigException.class, () -> sort("page.n", fm(2, null, 1, 3)));
        assertTrue(e.getMessage().startsWith("m.yml: sort: b.md has no value for sort key \"page.n\""), e.getMessage());

        assertEquals(List.of(C, A, D, B), sort("{ by: page.n, missing: last }", fm(2, null, 1, 3)));
        assertEquals(List.of(B, C, A, D), sort("{ by: page.n, missing: first }", fm(2, null, 1, 3)));
        assertEquals(List.of(B, D, A, C), sort("{ by: page.n, order: desc, missing: first }", fm(2, null, 1, 3)),
                "missing: first holds for descending order too");
    }

    @Test
    void mixedKindsAndBadKeysAreErrors() {
        ConfigException e = assertThrows(ConfigException.class, () -> sort("page.n", fm(2, "x", 1, 3)));
        assertTrue(e.getMessage().contains("can't compare"), e.getMessage());
        assertThrows(ConfigException.class, () -> sort("chapter", fm(1, 2, 3, 4)), "must be path or page.<key>");
        assertThrows(ConfigException.class, () -> sort("{ by: page.n, order: sideways }", fm(1, 2, 3, 4)));
        assertThrows(ConfigException.class, () -> sort("{ by: page.n, typo: 1 }", fm(1, 2, 3, 4)));
    }
}
