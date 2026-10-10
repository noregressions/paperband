package dev.noregressions.paperband.mermaid;

import dev.noregressions.paperband.CombinedAst;
import dev.noregressions.paperband.HtmlIds;
import dev.noregressions.paperband.MarkdownHtmlRenderer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** mermaid fences become inline SVG, the same on every build; other fences stay code. */
class MermaidRendererTest {

    private static String html(String markdown) throws IOException {
        var f = new CombinedAst().add(Path.of("a.md"), markdown);
        List<org.commonmark.node.Node> blocks = new ArrayList<>();
        for (var n = f.getFirstChild(); n != null; n = n.getNext()) {
            blocks.add(n);
        }
        return MarkdownHtmlRenderer.renderNodes(blocks, new HtmlIds("a-md", new HashSet<>()));
    }

    private static final String FLOW = """
            ```mermaid
            flowchart TD
              a["build-input/route.json"] --> b["npm prepack"]
              b --> c["dist/index.js<br/>dist/prepack-evidence.json"]
            ```
            """;

    @Test
    void aFlowchartBecomesSvgWithItsLabelsAsText() throws IOException {
        String out = html(FLOW + "\n```java\nint x = 1 < 2 ? 1 : 0;\n```\n");
        assertTrue(out.contains("<div class=\"block block-mermaid\"><svg"), out.substring(0, Math.min(200, out.length())));
        String text = out.replaceAll("<style>.*?</style>", "").replaceAll("<[^>]+>", "");
        assertTrue(text.contains("npm prepack"), "labels are SVG text, a word to a tspan");
        assertTrue(text.contains("dist/index.js") && text.contains("dist/prepack-evidence.json"),
                "a <br/> label keeps both lines");
        assertFalse(out.contains("<foreignObject"), "no HTML labels: SVG text only");
        assertTrue(out.contains("<pre><code class=\"language-java\">"), "other fences are still code");
    }

    @Test
    void theSameSourceGivesTheSameSvg() throws IOException {
        assertEquals(html(FLOW), html(FLOW));
        assertTrue(html(FLOW).contains("id=\"" + MermaidRenderer.id(
                "flowchart TD\n  a[\"build-input/route.json\"] --> b[\"npm prepack\"]\n  b --> c[\"dist/index.js<br/>dist/prepack-evidence.json\"]\n")
                + "\""), "the id is the source's hash");
    }

    @Test
    void aDiagramMermaidCantParseStopsTheBuild() {
        Exception e = assertThrows(RuntimeException.class, () -> html("```mermaid\nflowchart TD\n  a --> --> (\n```\n"));
        assertTrue(String.valueOf(e.getMessage()).contains("mermaid block") && e.getMessage().contains("Mermaid couldn't draw"),
                e.getMessage());
    }
}
