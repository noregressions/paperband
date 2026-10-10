package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.CombinedAst;
import dev.noregressions.paperband.MarkdownFile;
import dev.noregressions.paperband.NodeInfo;
import dev.noregressions.paperband.Site;
import org.commonmark.node.Node;
import org.jsoup.select.Selector;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AstViewTest {

    private static final String DOC = """
            ---
            title: T
            ---
            # Intro

            First *para*.

            Second para with [a link](https://x.example).

            ## Key points

            - one
            - two

            ```java
            int x;
            ```

            # Second

            Closing.
            """;

    private static AstView view() throws IOException {
        CombinedAst ast = new CombinedAst();
        MarkdownFile f = ast.add(Path.of("a.md"), DOC);
        return new AstView(f);
    }

    private static List<String> select(String css, Integer pick) throws IOException {
        return describe(view().select(Selector.evaluatorOf(css), pick));
    }

    private static List<String> describe(List<Node> nodes) {
        return nodes.stream().map(n -> NodeInfo.name(n) + ":" + Site.text(n)).toList();
    }

    @Test
    void buildsNestedSectionsWithHeadingIds() throws IOException {
        String outline = view().outline();
        assertTrue(outline.contains("<section class=\"h1\" id=\"intro\">"), outline);
        assertTrue(outline.contains("<section class=\"h2\" id=\"key-points\">"), outline);
        assertTrue(outline.contains("<pre data-lang=\"java\" class=\"language-java\">"), outline);
        assertFalse(outline.contains("title: T"), "front matter isn't in the view");
    }

    @Test
    void selectsBlocksAndInlines() throws IOException {
        assertEquals(List.of("Heading:Intro", "Heading:Second"), select("h1", null));
        assertEquals(List.of("Heading:Second"), select("h1", -1));
        assertEquals(List.of("Paragraph:Second para with a link."), select("h1 ~ p", 2));
        assertEquals(List.of("Link:a link"), select("a[href^=https]", null));
        assertEquals(List.of("FencedCodeBlock:int x;"), select("pre.language-java", null));
        assertEquals(List.of("Emphasis:para"), select("em", null));
    }

    @Test
    void directChildrenOfASectionSkipNestedSections() throws IOException {
        assertEquals(List.of("Paragraph:First para.", "Paragraph:Second para with a link.", "Paragraph:Closing."),
                select("section.h1 > p", null));
    }

    @Test
    void selectingASectionSelectsItsHeadingAndContent() throws IOException {
        assertEquals(List.of("Heading:Key points", "BulletList:one two", "FencedCodeBlock:int x;"),
                select("section#key-points", null));
    }

    @Test
    void outermostMatchWinsSoNothingIsPlacedTwice() throws IOException {
        // Both h1 sections match, and the h2 section nested inside the first.
        List<String> all = select("section", null);
        assertEquals(1, all.stream().filter(s -> s.equals("Heading:Key points")).count());
        assertEquals(List.of("Heading:Second", "Paragraph:Closing."), select("section", 2));
    }

    private static List<String> selectIn(String markdown, String css) throws IOException {
        MarkdownFile f = new CombinedAst().add(Path.of("t.md"), markdown);
        return describe(new AstView(f).select(Selector.evaluatorOf(css), null));
    }

    private static final String TUTORIAL = """
            # Install

            ## Steps

            ### Download {! step id=download level=beginner}

            Why it matters.

            Get the archive. {! instruction}

            ```bash {! command}
            curl -LO x
            ```

            ### Unpack {! step}

            ::: {! instruction}
            Unpack [paperband.zip]{! file} here.
            :::
            """;

    @Test
    void markerHandlesAreSelectable() throws IOException {
        assertEquals(List.of("Heading:Download", "Paragraph:Why it matters.", "Paragraph:Get the archive.",
                "FencedCodeBlock:curl -LO x", "Heading:Unpack", "Div:Unpack paperband.zip here."),
                selectIn(TUTORIAL, ".step"), "a heading's tags select its whole section");
        assertEquals(List.of("Heading:Download"), selectIn(TUTORIAL, "h3.step").subList(0, 1));
        assertEquals("Heading:Download", selectIn(TUTORIAL, "#download").getFirst(), "explicit id names the section");
        assertEquals(4, selectIn(TUTORIAL, "[data-level=beginner]").size());
        assertEquals(List.of("Paragraph:Get the archive.", "Div:Unpack paperband.zip here."), selectIn(TUTORIAL, ".instruction"));
        assertEquals(List.of("FencedCodeBlock:curl -LO x"), selectIn(TUTORIAL, ".step pre.command"));
        assertEquals(List.of("Span:paperband.zip"), selectIn(TUTORIAL, "span.file"));
    }

    @Test
    void siteSectionsUseExplicitIds() throws IOException {
        CombinedAst ast = new CombinedAst();
        ast.add(Path.of("t.md"), TUTORIAL);
        assertEquals(List.of("install", "steps", "download", "unpack"),
                new Site(ast).file("t.md").getSections().stream().map(Site.Section::getId).toList());
    }

    @Test
    void pickOutOfRangeSelectsNothing() throws IOException {
        assertEquals(List.of(), select("h1", 5));
        assertEquals(List.of(), select("table", null));
    }
}
