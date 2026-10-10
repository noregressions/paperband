package dev.noregressions.paperband.plantuml;

import dev.noregressions.paperband.CombinedAst;
import dev.noregressions.paperband.MarkdownHtmlRenderer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** plantuml fences become inline SVG; other fences stay code. */
class PlantUmlRendererTest {

    private static String html(String markdown) throws IOException {
        var f = new CombinedAst().add(Path.of("a.md"), markdown);
        List<org.commonmark.node.Node> blocks = new ArrayList<>();
        for (var n = f.getFirstChild(); n != null; n = n.getNext()) {
            blocks.add(n);
        }
        return MarkdownHtmlRenderer.renderNodes(blocks, new dev.noregressions.paperband.HtmlIds("a-md", new java.util.HashSet<>()));
    }

    @Test
    void diagramsBecomeSvgAndCodeStaysCode() throws IOException {
        String out = html("```plantuml\nAlice -> Bob: hello\n```\n\n```java\nint x = 1 < 2 ? 1 : 0;\n```\n");
        assertTrue(out.contains("<div class=\"block block-plantuml\"><svg"), out.substring(0, Math.min(300, out.length())));
        assertTrue(out.contains(">hello<"), "the diagram's text is SVG text");
        assertTrue(out.contains("<pre><code class=\"language-java\">int x = 1 &lt; 2 ? 1 : 0;\n</code></pre>"), out);
    }

    @Test
    void aDiagramPlantUmlCantDrawStopsTheBuild() {
        Exception e = assertThrows(RuntimeException.class, () -> html("```plantuml\n@startuml\nthis is not ->-> uml (\n@enduml\n```\n"));
        assertTrue(String.valueOf(e.getMessage()).contains("plantuml block"), e.getMessage());
    }
}
