package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.CombinedAst;
import dev.noregressions.paperband.NodeInfo;
import dev.noregressions.paperband.Site;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LayoutEngineTest {

    @TempDir
    Path dir;

    private final CombinedAst ast = new CombinedAst();
    private Layouts layouts;

    @BeforeEach
    void setUp() throws IOException {
        ast.add(Path.of("a.md"), "# Alpha\n\nIntro A.\n\n## Points\n\n- one\n- two\n\nTail A.\n");
        ast.add(Path.of("b.md"), "# Beta\n\nIntro B.\n");
        ast.add(Path.of("hidden.md"), "# Hidden\n");
        layout("hero", "boxes:\n  - { id: title, select: h1, pick: first }\n  - { id: body, select: rest }\n");
        layout("bare", "boxes:\n  - { id: title, select: h1 }\n");
        layout("partial", "boxes:\n  - { id: first, select: li, pick: first }\n  - { id: body, select: rest }\n");
        layout("toc", "page: { size: A5 }\nboxes:\n  - { id: label, text: Contents }\n"
                + "  - { id: seq, select: h1, pick: first, scope: sequence, as: links }\n"
                + "  - { id: all, select: h1, scope: sources, as: list }\n");
        layouts = Layouts.load(dir, PageSpec.A4);
    }

    private void layout(String name, String yaml) throws IOException {
        Files.writeString(dir.resolve(name + ".yml"), yaml);
    }

    private static ResolvedSequence.Item item(String file, String layout) {
        return new ResolvedSequence.Item("test", file == null ? null : Path.of(file), layout);
    }

    private List<Page> build(RunReport report, ResolvedSequence.Item... items) throws ConfigException {
        return LayoutEngine.build(new ResolvedSequence(List.of(items), List.of()), ast, layouts, PageSpec.A4, report);
    }

    private static List<String> texts(Page.PlacedBox box) {
        return box.nodes().stream().map(n -> NodeInfo.name(n) + ":" + Site.text(n)).toList();
    }

    @Test
    void defaultLayoutPutsTheWholeFileInOneUnwrappedBox() throws IOException {
        Page p = build(new RunReport(), item("b.md", "default")).getFirst();
        assertEquals(1, p.boxes().size());
        assertFalse(p.boxes().getFirst().box().wrap());
        assertEquals(List.of("Heading:Beta", "Paragraph:Intro B."), texts(p.boxes().getFirst()));
        assertEquals(PageSpec.A4, p.size());
    }

    @Test
    void restGetsWhatOtherBoxesDidNotTake() throws IOException {
        Page p = build(new RunReport(), item("a.md", "hero")).getFirst();
        assertEquals(List.of("Heading:Alpha"), texts(p.boxes().get(0)));
        assertEquals(List.of("Paragraph:Intro A.", "Heading:Points", "BulletList:one two", "Paragraph:Tail A."),
                texts(p.boxes().get(1)));
    }

    @Test
    void withoutRestUnplacedBlocksAreReported() throws IOException {
        RunReport report = new RunReport();
        Page p = build(report, item("a.md", "bare")).getFirst();
        assertEquals(1, p.boxes().size());
        assertEquals(List.of("a.md (layout bare): 4 block(s) not placed by any box (add a box with select: rest to keep them): "
                + "Paragraph (line 3), Heading (line 5), BulletList (line 7), Paragraph (line 10)"), report.warnings());
    }

    @Test
    void partlyTakenBlocksStayInRestWithAWarning() throws IOException {
        RunReport report = new RunReport();
        Page p = build(report, item("a.md", "partial")).getFirst();
        assertEquals(List.of("ListItem:one"), texts(p.boxes().get(0)));
        assertTrue(texts(p.boxes().get(1)).contains("BulletList:one two"));
        assertTrue(report.warnings().getFirst().contains("rest box \"body\" includes blocks that other boxes took part of"
                + ", so that part appears twice: BulletList (line 7)"), report.warnings().toString());
    }

    @Test
    void scopesSearchTheSequenceOrEverySource() throws IOException {
        RunReport report = new RunReport();
        List<Page> pages = build(report, item(null, "toc"), item("b.md", "default"), item("a.md", "hero"));
        Page toc = pages.getFirst();
        assertTrue(toc.boxes().get(0).box().isText());
        assertEquals(List.of("Heading:Beta", "Heading:Alpha"), texts(toc.boxes().get(1)), "sequence order, generated page skipped");
        assertEquals(List.of("Heading:Alpha", "Heading:Beta", "Heading:Hidden"), texts(toc.boxes().get(2)), "every source");
        assertEquals(new PageSpec(148, 210, 0), toc.size(), "layout's own page size");
        assertTrue(report.warnings().isEmpty(), report.warnings().toString());
    }

    @Test
    void pageIdsComeFromPathsAndStayUnique() throws IOException {
        List<Page> pages = build(new RunReport(), item(null, "toc"), item("a.md", "hero"), item(null, "toc"));
        assertEquals(List.of("toc-1", "a-md", "toc-2"), pages.stream().map(Page::id).toList());
        assertNull(pages.getFirst().source());
        assertEquals(Path.of("a.md"), pages.get(1).source());
    }

    @Test
    void emptySelectionsAreReported() throws IOException {
        layout("tables", "boxes:\n  - { id: t, select: table }\n  - { id: body, select: rest }\n");
        layouts = Layouts.load(dir, PageSpec.A4);
        RunReport report = new RunReport();
        build(report, item("b.md", "tables"));
        assertEquals(List.of("b.md (layout tables): box \"t\" selected nothing (table)"), report.warnings());
    }

    @Test
    void generatedPagesCantUseLayoutsThatReadTheirOwnFile() throws IOException {
        ConfigException e = assertThrows(ConfigException.class, () -> LayoutEngine.validate(
                new ResolvedSequence(List.of(item(null, "hero")), List.of()), layouts));
        assertEquals("test: layout \"hero\" can't make a generated page: box \"title\" has scope: page, "
                + "which needs a source file (use scope: sequence or sources)", e.getMessage());
        assertDoesNotThrow(() -> LayoutEngine.validate(new ResolvedSequence(List.of(item(null, "toc")), List.of()), layouts));
    }

    @Test
    void sourceBoxesGetTheFileTextAtTheirStageAndLeaveAllContentForRest() throws IOException {
        layout("side", "boxes:\n  - { id: md, content: source, stage: pre }\n  - { id: body, select: rest }\n");
        layouts = Layouts.load(dir, PageSpec.A4);
        var texts = java.util.Map.of(Path.of("b.md"), new dev.noregressions.paperband.MarkdownPipeline.Texts("orig", "pre", "final"));
        Page p = LayoutEngine.build(new ResolvedSequence(List.of(item("b.md", "side")), List.of()), ast, texts,
                layouts, PageSpec.A4, "default", new RunReport()).getFirst();
        assertEquals("pre", p.boxes().get(0).source());
        assertEquals(List.of("Heading:Beta", "Paragraph:Intro B."), texts(p.boxes().get(1)));

        ConfigException e = assertThrows(ConfigException.class, () -> LayoutEngine.validate(
                new ResolvedSequence(List.of(item(null, "side")), List.of()), layouts));
        assertTrue(e.getMessage().contains("box \"md\" shows the page's source, which needs a source file"), e.getMessage());
    }

    private static List<String> nestedTexts(Page.PlacedBox box, int nested, int inner) {
        return texts(box.nested().get(nested).boxes().get(inner));
    }

    @Test
    void layoutPageUsesTheFilesOwnLayoutOrTheDefault() throws IOException {
        layout("show", "boxes:\n  - { id: md, content: source }\n  - { id: result, layout: page }\n");
        layouts = Layouts.load(dir, PageSpec.A4);
        CombinedAst a = new CombinedAst();
        a.add(Path.of("own.md"), "---\nlayout: hero\n---\n# Own\n\nText.\n");
        a.add(Path.of("plain.md"), "# Plain\n");
        var texts = java.util.Map.of(Path.of("own.md"), new dev.noregressions.paperband.MarkdownPipeline.Texts("o", "p", "f"),
                Path.of("plain.md"), new dev.noregressions.paperband.MarkdownPipeline.Texts("o", "p", "f"));
        List<Page> pages = LayoutEngine.build(new ResolvedSequence(List.of(item("own.md", "show"), item("plain.md", "show")),
                List.of()), a, texts, layouts, PageSpec.A4, "default", new RunReport());

        Page.Nested own = pages.get(0).boxes().get(1).nested().getFirst();
        assertEquals("hero", own.layout().name());
        assertEquals(List.of("Heading:Own"), texts(own.boxes().get(0)));
        assertEquals(List.of("Paragraph:Text."), texts(own.boxes().get(1)));
        assertEquals("default", pages.get(1).boxes().get(1).nested().getFirst().layout().name());
    }

    @Test
    void eachFillsOneInnerLayoutPerMatchWithinIt() throws IOException {
        CombinedAst a = new CombinedAst();
        a.add(Path.of("t.md"), """
                # Tutorial

                Intro.

                ## One {! step}

                Why one.

                Do one. {! instruction}

                ## Two {! step}

                Do two. {! instruction}
                """);
        layout("steps", """
                boxes:
                  - { id: intro, select: "section.h1 > p" }
                  - id: steps
                    each: ".step"
                    unplaced: ignore
                    boxes:
                      - { id: title, select: h2 }
                      - { id: todo, select: ".instruction" }
                """);
        layouts = Layouts.load(dir, PageSpec.A4);
        RunReport report = new RunReport();
        Page p = LayoutEngine.build(new ResolvedSequence(List.of(item("t.md", "steps")), List.of()), a, layouts,
                PageSpec.A4, report).getFirst();

        Page.PlacedBox steps = p.boxes().get(1);
        assertEquals(2, steps.nested().size());
        assertEquals("steps--steps", steps.nested().getFirst().layout().name());
        assertEquals(List.of("Heading:One"), nestedTexts(steps, 0, 0));
        assertEquals(List.of("Paragraph:Do one."), nestedTexts(steps, 0, 1), "inner selectors stay inside their match");
        assertEquals(List.of("Paragraph:Do two."), nestedTexts(steps, 1, 1));
        assertEquals("t.md (layout steps): 1 block(s) not placed by any box (add a box with select: rest to keep them):"
                + " Heading (line 1)", report.warnings().getFirst(), "only the outer layout reports");
        assertEquals(1, report.warnings().size(), "the inner layout ignores what it doesn't place");
    }

    @Test
    void innerRestIsTheRestOfWhatTheBoxPassedIn() throws IOException {
        layout("wrap", "boxes:\n  - { id: inner, select: \"#points\", layout: card }\n  - { id: body, select: rest }\n");
        layout("card", "boxes:\n  - { id: head, select: h2 }\n  - { id: rest, select: rest }\n");
        layouts = Layouts.load(dir, PageSpec.A4);
        Page p = build(new RunReport(), item("a.md", "wrap")).getFirst();
        Page.PlacedBox inner = p.boxes().get(0);
        assertEquals(List.of("Heading:Points"), nestedTexts(inner, 0, 0));
        assertEquals(List.of("BulletList:one two", "Paragraph:Tail A."), nestedTexts(inner, 0, 1));
        assertEquals(List.of("Heading:Alpha", "Paragraph:Intro A."), texts(p.boxes().get(1)), "outer rest excludes what went inside");
    }

    @Test
    void eachCanSpanTheSequenceOnAGeneratedPage() throws IOException {
        layout("cards", "boxes:\n  - id: c\n    each: article\n    scope: sequence\n    boxes: [{ id: t, select: h1, pick: first }]\n");
        layouts = Layouts.load(dir, PageSpec.A4);
        Page p = build(new RunReport(), item(null, "cards"), item("a.md", "default"), item("b.md", "default")).getFirst();
        Page.PlacedBox cards = p.boxes().getFirst();
        assertEquals(2, cards.nested().size());
        assertEquals(List.of("Heading:Alpha"), nestedTexts(cards, 0, 0));
        assertEquals(List.of("Heading:Beta"), nestedTexts(cards, 1, 0));

        RunReport report = new RunReport();
        build(report, item(null, "cards"), item("hidden.md", "default"));
        assertTrue(report.warnings().isEmpty(), "hidden.md has an h1: " + report.warnings());
        CombinedAst noHeading = new CombinedAst();
        noHeading.add(Path.of("x.md"), "No heading.\n");
        LayoutEngine.build(new ResolvedSequence(List.of(item(null, "cards"), item("x.md", "default")), List.of()),
                noHeading, layouts, PageSpec.A4, report);
        assertEquals("test [x.md] (layout cards > cards--c): box \"t\" selected nothing (h1)", report.warnings().getFirst(),
                "a nested layout's warning names the file it was working on");
        assertTrue(report.warnings().stream().allMatch(w -> w.startsWith("test [x.md] ")), report.warnings().toString());
    }

    @Test
    void unplacedCanFailTheBuild() throws IOException {
        layout("strict", "unplaced: error\nboxes: [{ id: t, select: h1 }]\n");
        layouts = Layouts.load(dir, PageSpec.A4);
        ConfigException e = assertThrows(ConfigException.class, () -> build(new RunReport(), item("b.md", "strict")));
        assertTrue(e.getMessage().startsWith("b.md (layout strict): 1 block(s) not placed"), e.getMessage());
    }

    @Test
    void layoutPageThatLoopsBackIsAnError() throws IOException {
        CombinedAst a = new CombinedAst();
        a.add(Path.of("loop.md"), "---\nlayout: show\n---\n# L\n");
        layout("show", "boxes:\n  - { id: result, layout: page }\n");
        layouts = Layouts.load(dir, PageSpec.A4);
        ConfigException e = assertThrows(ConfigException.class, () -> LayoutEngine.build(
                new ResolvedSequence(List.of(item("loop.md", "show")), List.of()), a, layouts, PageSpec.A4, new RunReport()));
        assertEquals("loop.md: layouts include each other in a loop: show > show", e.getMessage());
    }

    @Test
    void fileInfoBoxesShowThePathAndOwnLayout() throws IOException {
        layout("info", "boxes:\n  - { id: p, content: path }\n  - { id: l, content: layout }\n  - { id: r, select: rest }\n");
        layouts = Layouts.load(dir, PageSpec.A4);
        CombinedAst a = new CombinedAst();
        a.add(Path.of("docs/x.md"), "---\nlayout: hero\n---\n# X\n");
        a.add(Path.of("y.md"), "# Y\n");
        List<Page> pages = LayoutEngine.build(new ResolvedSequence(List.of(item("docs/x.md", "info"), item("y.md", "info")),
                List.of()), a, java.util.Map.of(), layouts, PageSpec.A4, "plain", new RunReport());
        assertEquals("docs/x.md", pages.get(0).boxes().get(0).source());
        assertEquals("hero", pages.get(0).boxes().get(1).source());
        assertEquals("plain", pages.get(1).boxes().get(1).source(), "no front matter: the default layout");

        ConfigException e = assertThrows(ConfigException.class, () -> LayoutEngine.validate(
                new ResolvedSequence(List.of(item(null, "info")), List.of()), layouts));
        assertTrue(e.getMessage().contains("box \"p\" shows the page's file"), e.getMessage());
    }

    @Test
    void layoutSourceBoxesShowTheOwnLayoutsFile() throws IOException {
        layout("yaml", "boxes:\n  - { id: y, content: layout-source }\n  - { id: r, select: rest }\n");
        layouts = Layouts.load(dir, PageSpec.A4);
        CombinedAst a = new CombinedAst();
        a.add(Path.of("x.md"), "---\nlayout: hero\n---\n# X\n");
        a.add(Path.of("y.md"), "# Y\n");
        List<Page> pages = LayoutEngine.build(new ResolvedSequence(List.of(item("x.md", "yaml"), item("y.md", "yaml")),
                List.of()), a, java.util.Map.of(), layouts, PageSpec.A4, "default", new RunReport());
        assertEquals(java.nio.file.Files.readString(dir.resolve("hero.yml")), pages.get(0).boxes().get(0).source());
        assertEquals(Layouts.DEFAULT_SOURCE, pages.get(1).boxes().get(0).source(), "the built-in default describes itself");
    }

    @Test
    void whenLeavesBoxesOutAndTheirContentGoesToRest() throws IOException {
        CombinedAst a = new CombinedAst();
        a.add(Path.of("draft.md"), "---\ndraft: true\n---\n# Draft\n\nA note. {! note}\n");
        a.add(Path.of("final.md"), "# Final\n\nNo notes here.\n");
        layout("cond", """
                boxes:
                  - { id: title, select: h1, pick: first }
                  - { id: banner, text: "Draft", when: { page: { draft: true } } }
                  - { id: notes, select: ".note", when: { not-empty: true, page: { draft: { ne: true } } } }
                  - { id: tips, select: ".tip", when: { not-empty: true } }
                  - { id: pdf-only, select: "p", when: { outputs: [pdf] } }
                  - { id: body, select: rest, when: { not-empty: true } }
                """);
        layouts = Layouts.load(dir, PageSpec.A4);
        RunReport report = new RunReport();
        List<Page> pages = LayoutEngine.build(new ResolvedSequence(List.of(item("draft.md", "cond"), item("final.md", "cond")),
                List.of()), a, java.util.Map.of(), layouts, PageSpec.A4, "default", report);

        List<String> draftBoxes = pages.get(0).boxes().stream().map(b -> b.box().id()).toList();
        assertEquals(List.of("title", "banner", "body"), draftBoxes,
                "notes is left out on a draft page, tips found nothing, pdf-only isn't this output");
        assertEquals(List.of("Paragraph:A note."), texts(pages.get(0).boxes().get(2)), "what notes would have taken goes to rest");

        List<String> finalBoxes = pages.get(1).boxes().stream().map(b -> b.box().id()).toList();
        assertEquals(List.of("title", "body"), finalBoxes, "no banner, no notes found, tips empty");
        assertTrue(report.warnings().isEmpty(), "boxes left out by when don't warn: " + report.warnings());

        List<Page> pdf = LayoutEngine.build(new ResolvedSequence(List.of(item("final.md", "cond")), List.of()), a,
                java.util.Map.of(), layouts, PageSpec.A4, "default", "pdf", new RunReport());
        List<String> pdfBoxes = pdf.getFirst().boxes().stream().map(b -> b.box().id()).toList();
        assertEquals(List.of("title", "pdf-only"), pdfBoxes, "in the pdf output, pdf-only takes the paragraph and rest is empty");
    }

    @Test
    void anchors() throws IOException {
        assertEquals("docs-intro-md", LayoutEngine.anchor("docs/Intro.md"));
        assertEquals("page", LayoutEngine.anchor("///"));
    }
}
