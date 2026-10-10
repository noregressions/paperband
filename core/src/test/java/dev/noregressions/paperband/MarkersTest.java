package dev.noregressions.paperband;

import org.commonmark.node.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MarkersTest {

    private static MarkdownFile parse(String markdown) throws IOException {
        return new CombinedAst().add(Path.of("a.md"), markdown);
    }

    private static String html(String markdown) throws IOException {
        CombinedAst ast = new CombinedAst();
        MarkdownFile f = ast.add(Path.of("a.md"), markdown);
        List<Node> blocks = new ArrayList<>();
        for (Node n = f.getFirstChild(); n != null; n = n.getNext()) {
            blocks.add(n);
        }
        return MarkdownHtmlRenderer.renderNodes(blocks).strip();
    }

    private static String error(String markdown) {
        return assertThrows(IOException.class, () -> parse(markdown)).getMessage();
    }

    private static Attributes attrs(String marker) {
        return Attributes.parseMarker(marker);
    }

    @Test
    void parsesTagsValuesAndIds() {
        Attributes a = attrs("{! step optional id=download level=beginner title=\"Quick start {now}\" esc=\"a\\\"b\"}");
        assertEquals("download", a.id());
        assertEquals(List.of("step", "optional"), a.tags());
        assertEquals(Map.of("level", "beginner", "title", "Quick start {now}", "esc", "a\"b"), a.values());
        assertEquals(Map.of("id", "download", "class", "step optional", "data-level", "beginner",
                "data-title", "Quick start {now}", "data-esc", "a\"b"), a.html());
        assertEquals("{! step optional id=download level=beginner title=\"Quick start {now}\" esc=\"a\\\"b\"}", a.toString());
        assertEquals(List.of("x"), attrs("{!x}").tags(), "no space needed");
    }

    @Test
    void rejectsMalformedMarkers() {
        assertThrows(Attributes.MarkerException.class, () -> attrs("{! }"));
        assertThrows(Attributes.MarkerException.class, () -> attrs("{! 1st}"));
        assertThrows(Attributes.MarkerException.class, () -> attrs("{! id}"));
        assertThrows(Attributes.MarkerException.class, () -> attrs("{! id=a id=b}"));
        assertThrows(Attributes.MarkerException.class, () -> attrs("{! id=\"two words\"}"));
        assertThrows(Attributes.MarkerException.class, () -> attrs("{! class=x}"));
        assertThrows(Attributes.MarkerException.class, () -> attrs("{! a=1 a=2}"));
        assertThrows(Attributes.MarkerException.class, () -> attrs("{! a=}"));
        assertThrows(Attributes.MarkerException.class, () -> attrs("{! a=\"open}"));
    }

    @Test
    void blockPositions() throws IOException {
        assertEquals("""
                <h3 id="dl" class="step">Download {braces} kept</h3>
                <p class="instruction">Do the thing.</p>
                <ul class="checklist">
                <li>Java</li>
                <li class="required">Maven</li>
                </ul>
                <pre class="command"><code class="language-bash">curl -LO x
                </code></pre>""", html("""
                ### Download {braces} kept {! step id=dl}

                Do the thing. {! instruction}

                {! checklist}
                - Java
                - Maven {! required}

                ```bash {! command}
                curl -LO x
                ```
                """));
    }

    @Test
    void paragraphMarkerCanBeOnItsOwnLastLine() throws IOException {
        assertEquals("<p class=\"note\">Line one\nline two</p>", html("Line one\nline two\n{! note}\n"));
    }

    @Test
    void severalMarkersMerge() throws IOException {
        assertEquals("<p class=\"a b\" data-k=\"v\">Text</p>", html("{! a}\nText {! b} {! k=v}\n"));
    }

    @Test
    void inlineSpansAndElements() throws IOException {
        assertEquals("<p>Click <span class=\"ui\">Save</span> and open <span class=\"file\" data-kind=\"yaml\">settings.yml</span> now.</p>",
                html("Click [Save]{! ui} and open [settings.yml]{! file kind=yaml} now.\n"));
        assertEquals("<p>A <span class=\"x\">mixed <strong>bold</strong> span</span>!</p>",
                html("A [mixed **bold** span]{! x}!\n"));
        assertEquals("<p>Run <code class=\"cmd\">make</code> or see <a href=\"https://x.example\" class=\"external\">docs</a>.</p>",
                html("Run `make`{! cmd} or see [docs](https://x.example){! external}.\n"));
        assertEquals("<p>Nested <span class=\"outer\">a <span class=\"inner\">b</span> c</span></p>",
                html("Nested [a [b]{! inner} c]{! outer}\n"));
    }

    @Test
    void fencedDivsGroupBlocksAndNest() throws IOException {
        assertEquals("""
                <div id="get" class="instruction">
                <p>Download it.</p>
                <div class="tip">
                <p>Inner.</p>
                </div>
                <ul>
                <li>and this</li>
                </ul>
                </div>
                <p>After.</p>""", html("""
                ::: {! instruction id=get}
                Download it.

                :::: tip
                Inner.
                ::::

                - and this
                :::

                After.
                """));
    }

    @Test
    void escapedMarkersStayLiteral() throws IOException {
        assertEquals("<p>Write {! tag} literally, and <code>\\{!x}</code> in code.</p>\n<pre><code>\\{! also} {! raw}\n</code></pre>",
                html("Write \\{! tag} literally, and `\\{!x}` in code.\n\n```\n\\{! also} {! raw}\n```\n"));
    }

    @Test
    void misplacedMarkersAreErrors() {
        assertEquals("a.md line 1: a {! } marker can't go here (\"Some {! x} text\"): put it at the end of a heading or"
                + " paragraph, on its own line above a block, after a code fence's language, or straight after [text]"
                + " or an inline element; write \\{! for literal text", error("Some {! x} text\n"));
        assertTrue(error("Glued{! x}\n").contains("can't go here"));
        assertEquals("a.md line 1: a marker on its own line must be directly above the block it tags (no blank line between)",
                error("{! x}\n\n- a\n"));
        assertEquals("a.md line 3: a marker on its own line must be directly above the block it tags, but nothing follows it",
                error("Para\n\n{! x}\n"));
        assertEquals("a.md line 1: a heading needs text as well as a marker", error("# {! x}\n"));
        assertEquals("a.md line 1: \"]{!\" has no matching \"[\"", error("No open]{! x}\n"));
        assertEquals("a.md line 1: the marker must end the code fence line: \"bash {! a} extra\"",
                error("```bash {! a} extra\nx\n```\n"));
    }

    @Test
    void divMistakesAreErrors() {
        assertEquals("a.md line 1: ::: opened here is never closed", error("::: x\ntext\n"));
        assertEquals("a.md line 3: closing ::: with no open ::: above it", error("Text\n\n:::\n"));
        assertTrue(error("::: {! a} b\nx\n:::\n").contains("expected only a {! ...} marker after :::"));
        assertTrue(error("::: 9x\nx\n:::\n").startsWith("a.md line 1: \"9x\""));
    }

    @Test
    void idsMustBeUniqueAndBecomeHeadingIds() throws IOException {
        assertEquals("a.md line 3: id \"x\" is used twice (also on line 1)", error("# A {! id=x}\n\nPara {! id=x}\n"));

        MarkdownFile f = parse("# Intro\n\n# Other {! id=intro}\n\n# Intro\n");
        List<String> ids = new ArrayList<>();
        for (Node n = f.getFirstChild(); n != null; n = n.getNext()) {
            ids.add(f.headingId((Heading) n));
        }
        assertEquals(List.of("intro-1", "intro", "intro-2"), ids, "explicit ids win; slugs avoid them");
    }

    @Test
    void markersShowInTheAstTree() throws IOException {
        CombinedAst ast = new CombinedAst();
        ast.add(Path.of("a.md"), "## Step {! step id=s1}\n\nClick [it]{! ui}\n");
        String tree = AstTextRenderer.render(ast.getRoot());
        assertTrue(tree.contains("Heading h2 {! step id=s1}"), tree);
        assertTrue(tree.contains("Span {! ui}"), tree);
    }

    @Test
    void templatesCanGenerateMarkers() throws IOException {
        MarkdownPipeline.Result r = new MarkdownPipeline(Path.of("templates"), Map.of()).run(List.of(
                new MarkdownPipeline.Source(Path.of("a.md"), """
                        {% for n in [1, 2] %}
                        ## Step {{ n }} {! step}
                        {% endfor %}
                        [[ "Done" ]] {! status}
                        """)));
        MarkdownFile f = r.ast().getFiles().getFirst();
        Node first = f.getFirstChild();
        assertEquals(List.of("step"), f.getAttributes(first).tags());
        assertEquals(List.of("status"), f.getAttributes(f.getLastChild()).tags());
    }
}
