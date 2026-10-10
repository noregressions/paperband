package dev.noregressions.paperband;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownHtmlRendererTest {

    private static CombinedAst ast() throws IOException {
        CombinedAst ast = new CombinedAst();
        ast.add(Path.of("a.md"), "---\ntitle: Secret Title\n---\n# Hello *world*\n\n- one\n");
        ast.add(Path.of("b.md"), "Text with <b>raw</b> & `code`.\n\n<script>alert(1)</script>\n");
        return ast;
    }

    @Test
    void rendersEachFileAsAnArticleInOrder() throws IOException {
        String html = MarkdownHtmlRenderer.render(ast());

        assertTrue(html.contains("<article class=\"md-file\" data-path=\"a.md\">")
                || html.contains("<article data-path=\"a.md\" class=\"md-file\">"), html);
        assertTrue(html.indexOf("a.md") < html.indexOf("b.md"), html);
        assertTrue(html.contains("<header class=\"md-file-path\">a.md</header>"), html);
        assertTrue(html.contains("<h1>Hello <em>world</em></h1>"), html);
        assertTrue(html.contains("<li>one</li>"), html);
        assertEquals(2, html.split("</article>", -1).length - 1);
    }

    @Test
    void leavesOutFrontMatter() throws IOException {
        assertFalse(MarkdownHtmlRenderer.render(ast()).contains("Secret Title"));
    }

    @Test
    void passesRawHtmlThroughAndEscapesText() throws IOException {
        String html = MarkdownHtmlRenderer.render(ast());
        assertTrue(html.contains("<b>raw</b> &amp; <code>code</code>"), html);
        assertTrue(html.contains("<script>alert(1)</script>"), html);
    }

    @Test
    void astPageEmbedsRenderedMarkdownInASandboxedFrame() throws IOException {
        String page = AstHtmlRenderer.render(ast());

        int frame = page.indexOf("<iframe class=\"rendered\"");
        assertTrue(frame > page.indexOf("<ul class=\"tree\">"), "rendered markdown comes after the tree");
        String tag = page.substring(frame, page.indexOf(">", frame));
        assertTrue(tag.contains("sandbox=\"allow-same-origin allow-popups allow-popups-to-escape-sandbox\""), tag);
        assertFalse(tag.contains("allow-scripts"), "scripts in the markdown must not run");
        // Inside srcdoc, the rendered HTML is attribute-escaped, so the raw script can't reach the page itself.
        assertTrue(page.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertFalse(page.contains("<script>alert(1)</script>"));
    }

    @Test
    void draftPageHasItsOwnTitle() throws IOException {
        String page = AstHtmlRenderer.render(ast(), AstHtmlRenderer.DRAFT_TITLE);
        assertTrue(page.contains("<title>Draft Markdown AST (after the pre pass)</title>"));
        assertTrue(page.contains("<h1>Draft Markdown AST (after the pre pass)</h1>"));
    }
}
