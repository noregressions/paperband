package dev.noregressions.paperband;

import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CombinedAstTest {

    private static final String A = """
            ---
            title: First
            tags:
              - one
              - two
            ---
            # Heading A

            Some *text*.
            """;

    private static final String B = """
            # Heading B

            No front matter here.
            """;

    private CombinedAst combined() throws IOException {
        CombinedAst ast = new CombinedAst();
        ast.add(Path.of("a.md"), A);
        ast.add(Path.of("b.md"), B);
        return ast;
    }

    @Test
    void eachFileBecomesAChildOfTheRoot() throws IOException {
        CombinedAst ast = combined();
        Node first = ast.getRoot().getFirstChild();
        Node second = first.getNext();

        assertInstanceOf(MarkdownFile.class, first);
        assertInstanceOf(MarkdownFile.class, second);
        assertNull(second.getNext());
        assertEquals(Path.of("a.md"), ((MarkdownFile) first).getPath());
        assertEquals(Path.of("b.md"), ((MarkdownFile) second).getPath());
    }

    @Test
    void frontMatterIsAvailablePerFile() throws IOException {
        CombinedAst ast = combined();
        MarkdownFile a = ast.getFiles().get(0);
        MarkdownFile b = ast.getFiles().get(1);

        assertEquals("First", a.getFrontMatter().get("title"));
        assertEquals(List.of("one", "two"), a.getFrontMatter().get("tags"));
        assertTrue(b.getFrontMatter().isEmpty());
        assertEquals(a.getFrontMatter(), ast.getFrontMatter().get(Path.of("a.md")));
    }

    @Test
    void contentIsKeptUnderEachFile() throws IOException {
        MarkdownFile a = combined().getFiles().get(0);

        Node n = a.getFirstChild();
        assertInstanceOf(FrontMatterBlock.class, n);
        assertInstanceOf(Heading.class, n = n.getNext());
        assertInstanceOf(Paragraph.class, n.getNext());
    }

    @Test
    void frontMatterBlockHoldsTypedEntriesAndLineNumbersMatchTheFile() throws IOException {
        CombinedAst ast = new CombinedAst();
        MarkdownFile f = ast.add(Path.of("c.md"), "---\nn: 2\nauthor: {name: S}\n---\n\n# Line 6\n");

        FrontMatterBlock fm = (FrontMatterBlock) f.getFirstChild();
        assertEquals(1, NodeInfo.line(fm));
        FrontMatterBlock.Entry n = (FrontMatterBlock.Entry) fm.getFirstChild();
        assertEquals("n", n.getKey());
        assertEquals(2, n.getValue());
        assertEquals(java.util.Map.of("name", "S"), ((FrontMatterBlock.Entry) n.getNext()).getValue());
        assertEquals(6, NodeInfo.line(fm.getNext()), "heading keeps its line number in the file");
    }

    @Test
    void invalidFrontMatterNamesTheFile() {
        IOException e = assertThrows(IOException.class,
                () -> new CombinedAst().add(Path.of("bad.md"), "---\na: [\n---\n"));
        assertTrue(e.getMessage().startsWith("invalid front matter in bad.md"), e.getMessage());
    }

    @Test
    void htmlRenderingContainsFilesAndFrontMatter() throws IOException {
        String html = AstHtmlRenderer.render(combined());

        assertTrue(html.contains("a.md"));
        assertTrue(html.contains("b.md"));
        assertTrue(html.contains("title: First"));
        assertTrue(html.contains("FrontMatterBlock"));
        assertTrue(html.contains("No front matter"));
    }
}
