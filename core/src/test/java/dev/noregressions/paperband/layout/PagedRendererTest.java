package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.CombinedAst;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Paged output: fixed-size sheets, positions, priority-sized rows, stacks, box paths. */
class PagedRendererTest {

    @TempDir
    Path dir;

    private String paged(String layoutYaml, String markdown) throws IOException {
        Files.writeString(dir.resolve("l.yml"), layoutYaml);
        Layouts layouts = Layouts.load(dir, PageSpec.A4);
        CombinedAst ast = new CombinedAst();
        ast.add(Path.of("a.md"), markdown);
        List<Page> pages = LayoutEngine.build(new ResolvedSequence(List.of(new ResolvedSequence.Item("t", Path.of("a.md"), "l")),
                List.of()), ast, layouts, PageSpec.A4, new RunReport());
        return SiteHtmlRenderer.render("T", List.of(), pages, SiteHtmlRenderer.Mode.PAGED);
    }

    @Test
    void fixedLayoutsAreOneSheetOfTheirPageSize() throws IOException {
        String html = paged("""
                page: { size: A5, orientation: landscape, margin: 10mm }
                boxes:
                  - { id: title, select: h1, at: { x: 10mm, y: 12mm, w: 190mm, h: 20mm } }
                  - { id: body, select: rest, at: { x: 10mm, y: 40mm, w: 190mm, h: 98mm } }
                """, "# Hello\n\nText.\n");
        assertTrue(html.contains("<body class=\"paged\">"), html);
        assertTrue(html.contains("style=\"width: 210mm; height: 148mm; padding: 10mm; page: p210x148;\""), html);
        assertTrue(html.contains("@page p210x148 { size: 210mm 148mm; margin: 0; @top-left-corner { content: none; }"), html);
        assertTrue(html.contains("<div class=\"box box-title\" data-box=\"title\" style=\"position: absolute; left: 10mm;"
                + " top: 12mm; width: 190mm; height: 20mm;\">"), html);
    }

    @Test
    void flowingLayoutsMayRunOverSeveralPages() throws IOException {
        String html = paged("boxes: [{ id: body, select: rest }]\n", "# Long\n\nText.\n");
        assertTrue(html.contains("style=\"width: 210mm; min-height: 297mm; padding: 0mm; page: f210x297m0;\""), html);
        assertTrue(html.contains("@page f210x297m0 { size: 210mm 297mm; margin: 0mm; }"), "a flowing page's margin is its @page's");
    }

    @Test
    void gridRowsAreSizedByPriorityUnlessWrittenOut() throws IOException {
        String html = paged("""
                grid: { areas: ["title", "steps", "notes", "aside"] }
                boxes:
                  - { id: title, area: title, select: h1 }
                  - { id: steps, area: steps, select: p, priority: normal }
                  - { id: notes, area: notes, select: ul, priority: optional, size: { min: 25mm, max: 6cm } }
                  - { id: aside, area: aside, select: rest }
                """, "# T\n\nP.\n\n- x\n");
        assertTrue(html.contains("grid-template-rows: auto minmax(0mm, 1fr) minmax(25mm, 1fr) auto;"), html);
        assertTrue(html.contains("data-box=\"notes\" style=\"min-height: 25mm; max-height: 60mm;\""), html);

        String given = paged("grid: { areas: [\"a\", \"b\"], rows: [30mm, 1fr] }\nboxes:\n  - { id: a, area: a, select: h1 }\n"
                + "  - { id: b, area: b, select: rest, priority: optional }\n", "# T\n");
        assertTrue(given.contains("grid-template-rows: 30mm 1fr;"), "rows written out are kept: " + given);
    }

    @Test
    void stacksGiveTheRestToNonEssentialBoxes() throws IOException {
        String html = paged("""
                stack: { gap: 6mm }
                boxes:
                  - { id: title, select: h1 }
                  - { id: body, select: rest, priority: normal }
                """, "# T\n\nText.\n");
        assertTrue(html.contains(".layout-l.stack { display: flex; flex-direction: column; gap: 6mm; }"), html);
        assertTrue(html.contains(".layout-l > .box-title { flex: 0 0 auto; }"), html);
        assertTrue(html.contains(".layout-l > .box-body { flex: 1 1 0; min-height: 0; }"), html);
        assertTrue(html.contains("class=\"page layout-l stack\""), html);
        assertTrue(html.contains("height: 297mm;"), "a stack is a fixed shape: " + html);
    }

    @Test
    void boxPathsNameNestedBoxesByMatch() throws IOException {
        String html = paged("""
                boxes:
                  - id: steps
                    each: "section.h2"
                    boxes: [{ id: name, select: h2 }]
                """, "## One\n\n## Two\n");
        assertTrue(html.contains("data-box=\"steps[0]/name\""), html);
        assertTrue(html.contains("data-box=\"steps[1]/name\""), html);
    }

    @Test
    void webOutputIgnoresGeometry() throws IOException {
        Files.writeString(dir.resolve("l.yml"), "boxes: [{ id: t, select: h1, at: { x: 1mm, y: 1mm, w: 9mm, h: 9mm } }]\n");
        Layouts layouts = Layouts.load(dir, PageSpec.A4);
        CombinedAst ast = new CombinedAst();
        ast.add(Path.of("a.md"), "# T\n");
        List<Page> pages = LayoutEngine.build(new ResolvedSequence(List.of(new ResolvedSequence.Item("t", Path.of("a.md"), "l")),
                List.of()), ast, layouts, PageSpec.A4, new RunReport());
        String html = SiteHtmlRenderer.render("T", List.of(), pages);
        assertFalse(html.contains("position: absolute"), html);
        assertFalse(html.contains("@page"), html);
        assertTrue(html.contains("<body class=\"web\">"), html);
    }

    @Test
    void stackLayoutMistakes() throws IOException {
        Files.writeString(dir.resolve("l.yml"), "stack: {}\ngrid: { areas: [a] }\nboxes: [{ id: a, area: a, select: h1 }]\n");
        assertTrue(assertThrows(ConfigException.class, () -> Layouts.load(dir, PageSpec.A4)).getMessage()
                .contains("use either grid or stack, not both"));
        Files.writeString(dir.resolve("l.yml"), "stack: { gap: wide }\nboxes: [{ id: a, select: h1 }]\n");
        assertTrue(assertThrows(ConfigException.class, () -> Layouts.load(dir, PageSpec.A4)).getMessage().contains("isn't a length"));
        Files.writeString(dir.resolve("l.yml"), "stack: {}\nboxes: [{ id: a, select: h1, at: { x: 0mm, y: 0mm, w: 9mm, h: 9mm } }]\n");
        assertTrue(assertThrows(ConfigException.class, () -> Layouts.load(dir, PageSpec.A4)).getMessage()
                .contains("a box in a stack can't set area or at"));
    }
}
