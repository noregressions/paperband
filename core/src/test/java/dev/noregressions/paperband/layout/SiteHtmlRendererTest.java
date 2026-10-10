package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.CombinedAst;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SiteHtmlRendererTest {

    @TempDir
    Path dir;

    private String render(String layoutYaml, String... items) throws IOException {
        Files.writeString(dir.resolve("l.yml"), layoutYaml);
        Layouts layouts = Layouts.load(dir, PageSpec.A4);
        CombinedAst ast = new CombinedAst();
        ast.add(Path.of("docs/a.md"), "# Alpha & Co\n\nIntro *A*.\n");
        ast.add(Path.of("b.md"), "# Beta\n\nIntro B.\n");
        List<ResolvedSequence.Item> seq = new java.util.ArrayList<>();
        for (int i = 0; i < items.length; i += 2) {
            seq.add(new ResolvedSequence.Item("t", items[i] == null ? null : Path.of(items[i]), items[i + 1]));
        }
        List<Page> pages = LayoutEngine.build(new ResolvedSequence(seq, List.of()), ast, layouts, PageSpec.A4, new RunReport());
        return SiteHtmlRenderer.render("My <Site>", List.of(new SiteHtmlRenderer.Stylesheet("s.css", ".x { color: red }")), pages);
    }

    @Test
    void defaultPagesAreSectionsHoldingTheFileHtml() throws IOException {
        String html = render("boxes: [{ id: a, select: h1 }]\n", "docs/a.md", "default", "b.md", "default");
        assertTrue(html.contains("<title>My &lt;Site&gt;</title>"), html);
        assertTrue(html.contains("<style>/* s.css */\n.x { color: red }\n</style>"), html);
        assertTrue(html.contains("<section class=\"page layout-default\" id=\"docs-a-md\" data-layout=\"default\" "
                + "data-path=\"docs/a.md\">\n<h1 id=\"docs-a-md--alpha--co\">Alpha &amp; Co</h1>\n<p>Intro <em>A</em>.</p>\n</section>"), html);
        assertTrue(html.indexOf("docs-a-md") < html.indexOf("id=\"b-md\""), "sequence order");
    }

    @Test
    void boxesAreWrappedAndRenderedByTheirAsMode() throws IOException {
        String html = render("""
                class: special
                boxes:
                  - { id: label, class: tag, text: "Contents & more" }
                  - { id: plain, select: h1, scope: sequence, as: text }
                  - { id: items, select: h1, scope: sequence, as: list }
                  - { id: links, select: h1, scope: sequence, as: links, wrap: false }
                """, null, "l", "b.md", "default");
        assertTrue(html.contains("<section class=\"page layout-l special\" id=\"l-1\" data-layout=\"l\">"), html);
        assertTrue(html.contains("<div class=\"box box-label tag\" data-box=\"label\">\nContents &amp; more\n</div>"), html);
        assertTrue(html.contains("<div class=\"box box-plain\" data-box=\"plain\">\nBeta\n</div>"), html);
        assertTrue(html.contains("<div class=\"box box-items\" data-box=\"items\">\n<ul>\n<li>Beta</li>\n</ul>\n</div>"), html);
        assertTrue(html.contains("<ul>\n<li><a href=\"#b-md\">Beta</a></li>\n</ul>\n</section>"), "unwrapped links box: " + html);
    }

    @Test
    void gridLayoutsGetOneCssRuleSetAndAGridClass() throws IOException {
        String html = render("""
                grid: { columns: [1fr, 2fr], areas: ["left right"], gap: 8mm }
                boxes:
                  - { id: title, area: left, select: h1 }
                  - { id: body, area: right, select: rest }
                """, "docs/a.md", "l", "b.md", "l");
        assertTrue(html.contains(".layout-l.grid { display: grid; grid-template-columns: 1fr 2fr;"
                + " grid-template-rows: auto; grid-template-areas: \"left right\"; gap: 8mm; }"), html);
        assertTrue(html.contains(".layout-l > .box-title { grid-area: left; min-width: 0; }"), html);
        assertTrue(html.contains("@media (max-width: 700px) { .layout-l.grid { display: block; } }"), html);
        assertEquals(1, html.split("grid-template-areas", -1).length - 1, "one rule set per layout, not per page");
        assertTrue(html.contains("<section class=\"page layout-l grid\" id=\"docs-a-md\""), html);
    }

    @Test
    void sourceBoxesRenderHighlightedPre() throws IOException {
        Files.writeString(dir.resolve("src.yml"), "boxes: [{ id: md, content: source }]\n");
        Layouts layouts = Layouts.load(dir, PageSpec.A4);
        CombinedAst ast = new CombinedAst();
        ast.add(Path.of("b.md"), "# B\n");
        var texts = java.util.Map.of(Path.of("b.md"), new dev.noregressions.paperband.MarkdownPipeline.Texts("# B {{ x }} <b>\n", "", ""));
        List<Page> pages = LayoutEngine.build(new ResolvedSequence(List.of(new ResolvedSequence.Item("t", Path.of("b.md"), "src")),
                List.of()), ast, texts, layouts, PageSpec.A4, "default", new RunReport());
        String html = SiteHtmlRenderer.render("S", List.of(), pages);
        assertTrue(html.contains("<pre class=\"source\" data-stage=\"original\"><code><span class=\"tok-heading\">#</span>"
                + " B <span class=\"tok-pre tok-print\">{{ x }}</span> &lt;b&gt;\n</code></pre>"), html);
    }

    @Test
    void innerLayoutsAreNestedDivsWithTheirOwnGridCss() throws IOException {
        String html = render("""
                boxes:
                  - id: cards
                    each: h1
                    grid: { areas: ["t"] }
                    boxes: [{ id: t, area: t, select: h1 }]
                """, "b.md", "l");
        assertTrue(html.contains("<div class=\"box box-cards\" data-box=\"cards\">\n"
                + "<div class=\"layout layout-l--cards grid\" data-layout=\"l--cards\">\n"
                + "<div class=\"box box-t\" data-box=\"cards[0]/t\">\n<h1 id=\"b-md--beta\">Beta</h1>\n</div>\n</div>\n</div>"), html);
        assertTrue(html.contains(".layout-l--cards.grid { display: grid;"), html);
        assertTrue(html.contains(".layout-l--cards > .box-t { grid-area: t; min-width: 0; }"), html);
    }

    @Test
    void idsArePrefixedWithThePageAndStayUnique() throws IOException {
        Files.writeString(dir.resolve("twice.yml"), "boxes:\n  - { id: a, select: \"#tip\" }\n  - { id: b, select: \"#tip\" }\n"
                + "  - { id: c, select: rest }\n");
        Layouts layouts = Layouts.load(dir, PageSpec.A4);
        CombinedAst ast = new CombinedAst();
        ast.add(Path.of("t.md"), "# Title\n\nSee [the tip](#tip), [the title](#title) and [elsewhere](#nope).\n\n"
                + "Tip text. {! id=tip}\n");
        List<Page> pages = LayoutEngine.build(new ResolvedSequence(List.of(new ResolvedSequence.Item("t", Path.of("t.md"),
                "twice")), List.of()), ast, layouts, PageSpec.A4, new RunReport());
        String html = SiteHtmlRenderer.render("S", List.of(), pages);

        assertTrue(html.contains("<p id=\"t-md--tip\">Tip text.</p>"), html);
        assertTrue(html.contains("<p id=\"t-md--tip-2\">Tip text.</p>"), "the second copy gets a new id: " + html);
        assertTrue(html.contains("<h1 id=\"t-md--title\">Title</h1>"), html);
        assertTrue(html.contains("<a href=\"#t-md--tip\">the tip</a>"), html);
        assertTrue(html.contains("<a href=\"#t-md--title\">the title</a>"), html);
        assertTrue(html.contains("<a href=\"#nope\">elsewhere</a>"), "links to unknown anchors are left alone: " + html);
    }

    @Test
    void layoutStylesheetsAreInlinedOnceAndConfined() throws IOException {
        Files.writeString(dir.resolve("styled.css"), "h1 { color: red }\n& > .box-t { margin: 0 }\n");
        Files.writeString(dir.resolve("styled.yml"), "stylesheet: styled.css\nboxes: [{ id: t, select: h1 }]\n");
        String html = render("boxes: [{ id: x, select: rest }]\n", "docs/a.md", "styled", "b.md", "styled");
        assertEquals(1, html.split("/\\* layout styled \\*/", -1).length - 1, html);
        assertTrue(html.contains(".layout-styled h1 { color: red }\n.layout-styled > .box-t { margin: 0 }"), html);
        assertFalse(html.contains("/* layout l */"), "unused layouts add no CSS");
    }

    @Test
    void linksToFilesWithoutAPageArePlainText() throws IOException {
        String html = render("boxes: [{ id: links, select: h1, scope: sources, as: links }]\n", null, "l", "b.md", "default");
        assertTrue(html.contains("<li>Alpha &amp; Co</li>"), "docs/a.md has no page: " + html);
        assertTrue(html.contains("<li><a href=\"#b-md\">Beta</a></li>"), html);
    }
}
