package dev.noregressions.paperband.layout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LayoutTest {

    @TempDir
    Path dir;

    private Layout load(String yaml) throws IOException {
        Path f = dir.resolve("test.yml");
        Files.writeString(f, yaml);
        return Layout.load(f, PageSpec.A4);
    }

    private void assertError(String yaml, String fragment) {
        ConfigException e = assertThrows(ConfigException.class, () -> load(yaml));
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
    }

    @Test
    void loadsBoxesWithDefaults() throws IOException {
        Layout l = load("""
                page: { size: A5, orientation: landscape }
                class: x y
                boxes:
                  - { id: title, select: h1, pick: first, class: big }
                  - { id: toc, select: h1, pick: last, scope: sequence, as: links, wrap: false }
                  - { id: label, text: Contents, at: { x: 1cm, y: 1cm, w: 5cm, h: 1cm } }
                  - { id: body, select: rest }
                """);
        assertEquals("test", l.name());
        assertEquals(new PageSpec(210, 148, 0), l.page());
        assertEquals("x y", l.cssClass());
        Layout.Box title = l.boxes().get(0);
        assertEquals(1, title.pick());
        assertEquals(Layout.Scope.PAGE, title.scope());
        assertEquals(Layout.As.HTML, title.as());
        assertTrue(title.wrap());
        Layout.Box toc = l.boxes().get(1);
        assertEquals(-1, toc.pick());
        assertEquals(Layout.As.LINKS, toc.as());
        assertFalse(toc.wrap());
        assertTrue(l.boxes().get(2).isText());
        assertEquals(new Layout.Rect(10, 10, 50, 10), l.boxes().get(2).at());
        assertSame(l.boxes().get(3), l.rest());
        assertSame(title, l.firstPageScopedBox());
    }

    @Test
    void rejectsMistakes() {
        assertError("boxes: []\n", "missing required key \"boxes\"");
        assertError("boxes: [{ select: h1 }]\n", "missing required key \"id\"");
        assertError("boxes: [{ id: 1x, select: h1 }]\n", "must start with a letter");
        assertError("boxes: [{ id: a, select: h1 }, { id: a, select: p }]\n", "duplicate box id \"a\"");
        assertError("boxes: [{ id: a, select: rest }, { id: b, select: rest }]\n", "only one box can select rest");
        assertError("boxes: [{ id: a, select: \"h1[\" }]\n", "invalid selector \"h1[\"");
        assertError("boxes: [{ id: a, select: h1, pick: second }]\n", "expected first, last or a number from 1");
        assertError("boxes: [{ id: a, select: h1, pick: 0 }]\n", "expected first, last or a number from 1");
        assertError("boxes: [{ id: a, select: h1, scope: world }]\n", "isn't one of [page, sequence, sources]");
        assertError("boxes: [{ id: a, select: h1, as: pdf }]\n", "isn't one of [html, text, list, links]");
        assertError("boxes: [{ id: a, select: rest, pick: first }]\n", "a rest box can't set pick, scope or as");
        assertError("boxes: [{ id: a, text: Hi, select: h1 }]\n", "a text box can't also set select");
        assertError("boxes: [{ id: a }]\n", "missing required key \"select\" (or \"text\" for fixed text, content: source, or a layout)");
        assertError("boxes: [{ id: a, select: h1, colour: red }]\n", "unknown key \"colour\"");
        assertError("boxes: [{ id: a, select: h1, class: \"bad!\" }]\n", "isn't a list of CSS class names");
        assertError("page: { size: 16:9 }\nboxes: [{ id: a, select: h1 }]\n", "write it in quotes: size: \"16:9\"");
    }

    @Test
    void positionsMustFitOnThePage() {
        assertError("boxes: [{ id: a, select: h1, at: { x: 200mm, y: 0mm, w: 20mm, h: 10mm } }]\n",
                "box (200, 0, 20 x 10mm) doesn't fit on the 210 x 297mm page");
        assertError("page: { size: \"16:9\" }\nboxes: [{ id: a, select: h1, at: { x: 0mm, y: 0mm, w: 300mm, h: 200mm } }]\n",
                "doesn't fit on the 338.7 x 190.5mm page");
        assertError("boxes: [{ id: a, select: h1, at: { x: 0mm, y: 0mm, w: 10mm } }]\n", "missing required key \"h\"");
    }

    @Test
    void loadsSourceBoxes() throws IOException {
        Layout l = load("boxes:\n  - { id: md, content: source }\n  - { id: parsed, content: source, stage: final }\n");
        assertTrue(l.boxes().get(0).isSource());
        assertEquals(dev.noregressions.paperband.MarkdownPipeline.Stage.ORIGINAL, l.boxes().get(0).source());
        assertEquals(dev.noregressions.paperband.MarkdownPipeline.Stage.FINAL, l.boxes().get(1).source());
        assertFalse(l.boxes().get(0).isRest());
        assertNull(l.rest());
        assertSame(l.boxes().get(0), l.firstPageScopedBox(), "a source box reads the page's own file");

        assertError("boxes: [{ id: a, content: html }]\n", "\"html\" isn't a content kind; use source, path, layout or layout-source");
        assertError("boxes: [{ id: a, content: source, stage: later }]\n", "isn't one of [original, pre, final]");
        assertError("boxes: [{ id: a, content: path, stage: pre }]\n", "stage only applies to content: source");
        Layout info = load("boxes:\n  - { id: p, content: path }\n  - { id: l, content: layout }\n");
        assertEquals(Layout.Kind.PATH, info.boxes().get(0).kind());
        assertEquals(Layout.Kind.OWN_LAYOUT, info.boxes().get(1).kind());
        assertTrue(info.boxes().get(0).isFileInfo());
        assertError("boxes: [{ id: a, content: source, select: h1 }]\n", "a content box can't also set select");
        assertError("boxes: [{ id: a, select: h1, stage: pre }]\n", "stage only applies to content: source");
    }

    @Test
    void loadsAGrid() throws IOException {
        Layout l = load("""
                grid:
                  columns: [1fr, 2fr]
                  rows: [auto, 1fr]
                  areas: ["title title", "intro body"]
                  gap: 24px
                boxes:
                  - { id: title, area: title, select: h1 }
                  - { id: intro, area: intro, select: p }
                  - { id: body, area: body, select: rest }
                """);
        assertEquals(new Grid(List.of("1fr", "2fr"), List.of("auto", "1fr"),
                List.of(List.of("title", "title"), List.of("intro", "body")), "24px", true), l.grid());
        assertEquals(java.util.Set.of("title", "intro", "body"), l.grid().names());
        assertEquals("body", l.rest().area());
        assertEquals("display: grid; grid-template-columns: 1fr 2fr; grid-template-rows: auto 1fr;"
                + " grid-template-areas: \"title title\" \"intro body\"; gap: 24px;", l.grid().css());

        assertEquals("8px 24px", load("grid: { areas: [\"a\"], gap: \"8px 24px\" }\nboxes: [{ id: x, area: a, select: h1 }]\n")
                .grid().gap(), "rows then columns");
        Layout defaults = load("grid: { areas: [\"a . b\"] }\nboxes: [{ id: x, area: a, select: h1 }]\n");
        assertEquals(List.of("1fr", "1fr", "1fr"), defaults.grid().columns());
        assertEquals(List.of("auto"), defaults.grid().rows());
    }

    @Test
    void rejectsGridMistakes() {
        String box = "boxes: [{ id: x, area: a, select: h1 }]\n";
        assertError("grid: { columns: [1fr] }\n" + box, "missing required key \"areas\"");
        assertError("grid: { areas: [\"a b\", \"a\"] }\n" + box, "every row needs the same number of cells");
        assertError("grid: { areas: [\"a b a\"] }\n" + box, "area \"a\" isn't a rectangle");
        assertError("grid: { areas: [\"a 9\"] }\n" + box, "\"9\" isn't an area name");
        assertError("grid: { areas: [\"a b\"], columns: [1fr] }\n" + box, "has 1 size(s) but the areas have 2");
        assertError("grid: { areas: [\"a b\"], columns: [1fr, wide] }\n" + box, "\"wide\" isn't a track size");
        assertError("grid: { areas: [\"a\"], gap: big }\n" + box, "isn't a length");
        assertError("grid: { areas: [\"a\"], gap: \"1px 2px 3px\" }\n" + box, "expected one length, or two");
        assertError("grid: { areas: [\"a b\"] }\nboxes: [{ id: x, select: h1 }]\n", "in a grid layout every box needs an area; areas are [a, b]");
        assertError("grid: { areas: [\"a b\"] }\nboxes: [{ id: x, area: c, select: h1 }]\n", "unknown area \"c\"");
        assertError("grid: { areas: [\"a b\"] }\nboxes: [{ id: x, area: a, select: h1 }, { id: y, area: a, select: p }]\n",
                "area \"a\" already holds box \"x\"");
        assertError("grid: { areas: [\"a\"] }\nboxes: [{ id: x, area: a, select: h1, wrap: false }]\n", "can't use wrap: false");
        assertError("grid: { areas: [\"a\"] }\nboxes: [{ id: x, area: a, select: h1, at: { x: 0mm, y: 0mm, w: 1mm, h: 1mm } }]\n",
                "use either area or at, not both");
        assertError("boxes: [{ id: x, area: a, select: h1 }]\n", "area \"a\" needs a grid in the layout");
    }

    @Test
    void loadsBoxesHoldingLayouts() throws IOException {
        Layout l = load("""
                unplaced: ignore
                boxes:
                  - { id: result, layout: page }
                  - { id: card, select: "#summary", layout: card }
                  - id: steps
                    each: ".step"
                    pick: 2
                    unplaced: error
                    grid: { areas: ["t c"] }
                    boxes:
                      - { id: t, area: t, select: h3 }
                      - { id: c, area: c, select: "pre.command" }
                """);
        assertEquals(Layout.Unplaced.IGNORE, l.unplaced());
        Layout.Box result = l.boxes().get(0);
        assertEquals(Layout.Kind.LAYOUT, result.kind());
        assertEquals("page", result.layoutName());
        assertNull(result.select());
        Layout.Box card = l.boxes().get(1);
        assertEquals("#summary", card.select());
        assertEquals("card", card.layoutName());
        Layout.Box steps = l.boxes().get(2);
        assertEquals(Layout.Kind.EACH, steps.kind());
        assertEquals(".step", steps.select());
        assertEquals(2, steps.pick());
        assertEquals("test--steps", steps.nested().name());
        assertEquals(Layout.Unplaced.ERROR, steps.nested().unplaced());
        assertNotNull(steps.nested().grid());
        assertEquals(java.util.Set.of("card"), l.references(), "page is resolved per file, not referenced");
        assertTrue(steps.isNested());
    }

    @Test
    void rejectsLayoutBoxMistakes() {
        assertError("boxes: [{ id: a, layout: x, boxes: [{ id: b, select: p }] }]\n", "use either layout or boxes, not both");
        assertError("boxes: [{ id: a, each: h2 }]\n", "each needs a layout (layout: name) or nested boxes");
        assertError("boxes: [{ id: a, each: h2, select: p, layout: x }]\n", "use either each or select, not both");
        assertError("boxes: [{ id: a, layout: x, as: text }]\n", "as doesn't apply to a box holding a layout");
        assertError("boxes: [{ id: a, select: rest, layout: x }]\n", "rest can't be passed into a layout");
        assertError("boxes: [{ id: a, layout: x, pick: 1 }]\n", "pick needs a select");
        assertError("boxes: [{ id: a, layout: x, scope: sequence }]\n", "scope sequence needs each");
        assertError("boxes: [{ id: a, layout: x, grid: { areas: [a] } }]\n", "grid, stack and unplaced go in the named layout");
        assertError("boxes: [{ id: a, select: p, grid: { areas: [a] } }]\n", "grid, stack and unplaced only apply to a box with nested boxes");
        assertError("boxes: [{ id: a, each: \"h2[\", layout: x }]\n", "boxes[0].each: invalid selector");
        assertError("boxes: [{ id: a, each: h2, boxes: [{ id: b, select: p, typo: 1 }] }]\n", "boxes[0].boxes[0]: unknown key \"typo\"");
        assertError("unplaced: maybe\nboxes: [{ id: a, select: p }]\n", "isn't one of [warn, ignore, error]");
    }

    @Test
    void layoutReferencesAreCheckedWhenLoading() throws IOException {
        Path refs = Files.createDirectories(dir.resolve("refs"));
        Files.writeString(refs.resolve("a.yml"), "boxes: [{ id: x, layout: missing }]\n");
        ConfigException e = assertThrows(ConfigException.class, () -> Layouts.load(refs, PageSpec.A4));
        assertTrue(e.getMessage().startsWith("a: unknown layout \"missing\""), e.getMessage());

        Files.writeString(refs.resolve("a.yml"), "boxes: [{ id: x, layout: b }]\n");
        Files.writeString(refs.resolve("b.yml"), "boxes: [{ id: y, each: h2, boxes: [{ id: z, select: p, layout: a }] }]\n");
        e = assertThrows(ConfigException.class, () -> Layouts.load(refs, PageSpec.A4));
        assertEquals("layouts include each other in a loop: a > b > a", e.getMessage());

        Path reserved = Files.createDirectories(dir.resolve("reserved"));
        Files.writeString(reserved.resolve("page.yml"), "boxes: [{ id: x, select: p }]\n");
        e = assertThrows(ConfigException.class, () -> Layouts.load(reserved, PageSpec.A4));
        assertTrue(e.getMessage().contains("\"page\" is reserved"), e.getMessage());
    }

    @Test
    void stylesheetsAreLoadedAndConfined() throws IOException {
        Files.writeString(dir.resolve("test.css"), "h1 { color: red }");
        assertEquals(".layout-test h1 { color: red }\n", load("stylesheet: test.css\nboxes: [{ id: a, select: h1 }]\n").css());
        assertNull(load("boxes: [{ id: a, select: h1 }]\n").css());

        assertError("stylesheet: none.css\nboxes: [{ id: a, select: h1 }]\n", "test.yml: stylesheet: not found:");
        Files.writeString(dir.resolve("bad.css"), "body { margin: 0 }");
        assertError("stylesheet: bad.css\nboxes: [{ id: a, select: h1 }]\n", "bad.css line 1: \"body\" can't be styled");
    }

    @Test
    void loadsBoxRules() throws IOException {
        Layout l = load("""
                boxes:
                  - id: notes
                    select: ".note"
                    when: { not-empty: true, page: { draft: { ne: true } }, outputs: [pdf, handbook] }
                    priority: optional
                    size: { min: 25mm, max: 8cm }
                    drop: { below-min: true, space: leave }
                  - { id: plain, select: h1 }
                """);
        Layout.Rules r = l.boxes().get(0).rules();
        assertTrue(r.when().notEmpty());
        assertNotNull(r.when().page());
        assertEquals(List.of("pdf", "handbook"), r.when().outputs());
        assertEquals(Layout.Priority.OPTIONAL, r.priority());
        assertEquals(new Layout.Size(25.0, 80.0), r.size());
        assertEquals(new Layout.Drop(true, Layout.Space.LEAVE), r.drop());
        assertEquals(Layout.Rules.DEFAULT, l.boxes().get(1).rules(), "essential, always shown, no limits");
    }

    @Test
    void rejectsBoxRuleMistakes() {
        assertError("boxes: [{ id: a, select: h1, priority: high }]\n", "isn't one of [essential, normal, optional]");
        assertError("boxes: [{ id: a, select: h1, size: {} }]\n", "size: give min, max or both");
        assertError("boxes: [{ id: a, select: h1, size: { min: 9cm, max: 2cm } }]\n", "min is larger than max");
        assertError("boxes: [{ id: a, select: h1, size: { min: tall } }]\n", "isn't a length");
        assertError("boxes: [{ id: a, select: h1, size: { min: 1cm }, at: { x: 0mm, y: 0mm, w: 9mm, h: 9mm } }]\n",
                "size doesn't apply to a box with at");
        assertError("boxes: [{ id: a, select: h1, drop: { space: leave } }]\n", "an essential box is never dropped");
        assertError("boxes: [{ id: a, select: h1, priority: normal, drop: { below-min: true } }]\n",
                "drop.below-min needs a size.min");
        assertError("boxes: [{ id: a, select: h1, priority: normal, drop: { space: sideways } }]\n",
                "isn't one of [collapse, leave]");
        assertError("boxes: [{ id: a, text: Hi, when: { not-empty: true } }]\n", "when.not-empty doesn't apply to a text box");
        assertError("boxes: [{ id: a, select: h1, when: { page: { draft: { gtt: 1 } } } }]\n", "unknown operator \"gtt\"");
        assertError("boxes: [{ id: a, select: h1, when: { sometimes: true } }]\n", "unknown key \"sometimes\"");
    }

    @Test
    void layoutsDirectoryAddsToTheBuiltInDefault() throws IOException {
        assertEquals(java.util.Set.of("default"), Layouts.load(dir.resolve("missing"), PageSpec.A4).names());
        Files.writeString(dir.resolve("hero.yml"), "boxes: [{ id: a, select: h1 }]\n");
        Files.writeString(dir.resolve("default.yaml"), "boxes: [{ id: everything, select: rest }]\n");
        Layouts layouts = Layouts.load(dir, PageSpec.A4);
        assertEquals(java.util.Set.of("default", "hero"), layouts.names());
        assertEquals("everything", layouts.get("default").boxes().getFirst().id(), "default.yaml replaces the built-in");

        Files.writeString(dir.resolve("hero.yaml"), "boxes: [{ id: a, select: h1 }]\n");
        ConfigException e = assertThrows(ConfigException.class, () -> Layouts.load(dir, PageSpec.A4));
        assertTrue(e.getMessage().startsWith("layout \"hero\" is defined twice"), e.getMessage());
    }
}
