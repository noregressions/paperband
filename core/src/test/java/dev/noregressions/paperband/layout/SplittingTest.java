package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.Breaks;
import dev.noregressions.paperband.CombinedAst;
import dev.noregressions.paperband.Site;
import org.commonmark.node.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** How a splitting box's content divides into units, without a browser. */
class SplittingTest {

    @TempDir
    Path dir;

    private static final String MD = """
            # Guide

            Intro.

            ## One

            First.

            ```
            code
            ```

            ## Two {! break=avoid}

            Second.

            Kept. {! break=prefer}

            ## Three

            Third.
            """;

    /** The page's box "body" with this split rule, and its units' first words. */
    private Page page(String split) throws IOException {
        CombinedAst ast = new CombinedAst();
        ast.add(Path.of("a.md"), MD);
        Files.writeString(dir.resolve("l.yml"), "boxes:\n  - { id: body, select: rest, split: " + split + " }\n");
        Layouts layouts = Layouts.load(dir, PageSpec.A4);
        ResolvedSequence seq = new ResolvedSequence(List.of(new ResolvedSequence.Item("test", Path.of("a.md"), "l")),
                List.of());
        return LayoutEngine.build(seq, ast, layouts, PageSpec.A4, new RunReport()).getFirst();
    }

    private List<Splitting.Unit> units(Page page) {
        Page.PlacedBox body = page.boxes().getFirst();
        return Splitting.units(body, body.box().rules().split());
    }

    private static List<String> firsts(List<Splitting.Unit> units) {
        return units.stream().map(u -> Site.text(u.nodes().getFirst())).toList();
    }

    @Test
    void everyBlockIsAUnitWithoutBetween() throws IOException {
        List<Splitting.Unit> units = units(page("true"));
        assertEquals(List.of("Guide", "Intro.", "One", "First.", "code", "Two", "Second.", "Kept.", "Three", "Third."),
                firsts(units));
        assertFalse(units.get(0).breakable(), "nothing comes before the first");
        assertFalse(units.get(5).breakable(), "{! break=avoid}");
        assertEquals(Breaks.Break.PREFER, units.get(7).marker());
        assertEquals(5, units.get(2).line());
    }

    @Test
    void betweenGroupsBlocksIntoUnits() throws IOException {
        Page page = page("{ between: section.h2 }");
        List<Splitting.Unit> units = units(page);
        assertEquals(List.of("Guide", "One", "Two", "Three"), firsts(units));
        assertEquals(3, units.get(1).nodes().size(), "heading, paragraph, code");
        assertFalse(units.get(2).breakable());

        List<Splitting.Unit> added = units(page("{ between: section.h2, markers: add }"));
        assertEquals(List.of("Guide", "One", "Two", "Kept.", "Three"), firsts(added), "a marker adds a break point");

        List<Splitting.Unit> ignored = units(page("{ between: section.h2, markers: ignore }"));
        assertTrue(ignored.get(2).breakable(), "markers ignored: avoid doesn't count");
    }

    @Test
    void keepTogetherAndRepeat() throws IOException {
        List<Splitting.Unit> kept = units(page("{ keep-together: [section.h2] }"));
        assertEquals(List.of("Guide", "Intro.", "One", "Two", "Three"), firsts(kept), "no break inside a section");

        Page page = page("{ between: section.h2, repeat: h1 }");
        Page.PlacedBox body = page.boxes().getFirst();
        List<Splitting.Unit> units = units(page);
        Node repeat = Splitting.repeat(body, body.box().rules().split(), units, 2);
        assertEquals("Guide", Site.text(repeat), "the heading the continuation is part of");
        assertNull(Splitting.repeat(body, body.box().rules().split(), units, 0));
    }
}
