package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.CombinedAst;
import dev.noregressions.paperband.HtmlIds;
import dev.noregressions.paperband.MarkdownFile;
import dev.noregressions.paperband.MarkdownHtmlRenderer;
import dev.noregressions.paperband.Site;
import dev.noregressions.paperband.SiteBuilder;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.node.Node;
import org.jsoup.select.Selector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** GitHub-style pipe tables, through parsing, selectors, text, markers and rendering. */
class TablesTest {

    @TempDir
    Path dir;

    private static final String MD = """
            # Flags

            {! flags}
            | Flag | Purpose |
            |------|---------|
            | `--jdk-internals` | Reports JDK internal APIs. |
            | `--class-path` | Where to look, [all of it]{! important}. |

            After.
            """;

    private static MarkdownFile parse(String markdown) throws IOException {
        return new CombinedAst().add(Path.of("a.md"), markdown);
    }

    private static Node table(MarkdownFile f) {
        for (Node n = f.getFirstChild(); n != null; n = n.getNext()) {
            if (n instanceof TableBlock) {
                return n;
            }
        }
        throw new AssertionError("no table");
    }

    @Test
    void tablesAreParsedAndRendered() throws IOException {
        MarkdownFile f = parse(MD);
        Node table = table(f);
        String html = MarkdownHtmlRenderer.renderNodes(List.of(table), new HtmlIds("p", new HashSet<>()));
        assertTrue(html.startsWith("<table class=\"flags\">"), html);
        assertTrue(html.contains("<th>Flag</th>") && html.contains("<td><code>--jdk-internals</code></td>"), html);
        assertTrue(html.contains("<span class=\"important\">all of it</span>"), "a marker inside a cell: " + html);
        assertEquals("Flag Purpose --jdk-internals Reports JDK internal APIs. --class-path Where to look, all of it.",
                Site.text(table));
        assertEquals(4, NodeLine.of(table), "the table starts below its marker line");
    }

    /** Lines are 1-based; the marker line above the table isn't part of it. */
    private static final class NodeLine {
        static int of(Node n) {
            return n.getSourceSpans().getFirst().getLineIndex() + 1;
        }
    }

    @Test
    void selectorsSeeTableElements() throws IOException {
        AstView view = new AstView(parse(MD));
        assertEquals(1, view.select(Selector.evaluatorOf("table.flags"), null).size());
        assertEquals(2, view.select(Selector.evaluatorOf("tbody tr"), null).size());
        List<Node> firstCells = view.select(Selector.evaluatorOf("tbody td:first-child"), null);
        assertEquals(List.of("--jdk-internals", "--class-path"), firstCells.stream().map(Site::text).toList());
        assertEquals(List.of("Flag", "Purpose"), view.select(Selector.evaluatorOf("th"), null).stream().map(Site::text).toList());
    }

    @Test
    void aLayoutCanPutATableInABox() throws IOException {
        Files.createDirectories(dir.resolve("docs"));
        Files.createDirectories(dir.resolve("layouts"));
        Files.writeString(dir.resolve("docs/a.md"), MD);
        Files.writeString(dir.resolve("layouts/l.yml"), "boxes:\n  - { id: table, select: table }\n  - { id: rest, select: rest }\n");
        Files.writeString(dir.resolve("manifest.yml"), "sources: [docs/a.md]\nsequence: [{ file: docs/a.md, layout: l }]\n");
        String html = SiteBuilder.build(Manifest.load(dir.resolve("manifest.yml"))).html();
        assertTrue(html.contains("<div class=\"box box-table\" data-box=\"table\">\n<table class=\"flags\">"), html);
        assertTrue(html.contains("th, td { border: 1px solid"), "tables are styled");
    }
}
