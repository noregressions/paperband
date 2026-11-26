package dev.noregressions.paperband.layout;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A document split into a site: what moves into the shared stylesheet. */
class WebSiteTest {

    private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" class=\"pb-chart\">"
            + "<style>.pb-chart .f-accent{fill:teal}</style><rect class=\"f-accent\"/></svg>";

    @Test
    void theStylesInsideADrawingMoveToTheStylesheetOnce() {
        String html = "<!doctype html><html><head><style>body { color: navy; }</style></head><body>"
                + "<section class=\"page\" id=\"a\"><h1>A</h1>" + SVG + "</section>"
                + "<section class=\"page\" id=\"b\"><h1>B</h1>" + SVG + "</section></body></html>";
        Map<Path, String> site = WebSite.split(html, "T", "index.html", WebSite.Options.DEFAULT);
        String css = site.get(Path.of(WebSite.CSS));
        assertTrue(css.contains("body { color: navy; }"), css);
        assertEquals(1, css.split("\\.pb-chart \\.f-accent\\{fill:teal\\}", -1).length - 1, css);
        for (Map.Entry<Path, String> e : site.entrySet()) {
            if (e.getKey().toString().endsWith(".html")) {
                assertFalse(e.getValue().contains("<style>.pb-chart"), e.getValue());
                assertTrue(e.getValue().contains("class=\"f-accent\""), e.getValue());
            }
        }
    }

    private static String page(String id, String body) {
        return "<section class=\"page\" id=\"" + id + "\" data-path=\"" + id + ".md\">" + body + "</section>";
    }

    @Test
    void aPagesSectionsAreListedUnderItAndFoldUnlessItIsTheCurrentPage() {
        String html = "<!doctype html><html><body class=\"web\">"
                + page("a", "<h1 id=\"a-t\">A</h1><h2 id=\"a-one\">One</h2><h3 id=\"a-deep\">Deep</h3><h2 id=\"a-two\">Two</h2>")
                + page("b", "<h1 id=\"b-t\">B</h1><h2 id=\"b-one\">Uno</h2>")
                + page("c", "<h1 id=\"c-t\">C</h1>") + "</body></html>";
        Map<Path, String> site = WebSite.split(html, "T", "index.html", WebSite.Options.DEFAULT);
        String a = site.get(Path.of("index.html"));
        assertTrue(a.contains("<li class=\"site-nav-group\"><button class=\"site-nav-fold\" type=\"button\""
                + " aria-expanded=\"true\" aria-label=\"Show or hide: A\"></button>"
                + "<a href=\"index.html\" aria-current=\"page\">A</a><ul>"), a);
        assertTrue(a.contains("<a href=\"#a-one\">One</a><ul><li><a href=\"#a-deep\">Deep</a></li></ul></li>"
                + "<li><a href=\"#a-two\">Two</a></li>"), a);
        assertTrue(a.contains("<li class=\"site-nav-group folded\"><button class=\"site-nav-fold\" type=\"button\""
                + " aria-expanded=\"false\" aria-label=\"Show or hide: One\">"), "a section's own sections start folded: " + a);
        assertTrue(a.contains("aria-expanded=\"false\" aria-label=\"Show or hide: B\"></button><a href=\"b.html\">B</a>"
                + "<ul><li><a href=\"b.html#b-one\">Uno</a></li></ul>"), "another page's sections are folded: " + a);
        assertTrue(a.contains("<li><a href=\"c.html\">C</a></li>"), "a page without sections has no button: " + a);
        String b = site.get(Path.of("b.html"));
        assertTrue(b.contains("aria-expanded=\"true\" aria-label=\"Show or hide: B\">"), b);
        assertTrue(b.contains("aria-expanded=\"false\" aria-label=\"Show or hide: A\">"), b);
        assertTrue(site.get(Path.of(WebSite.JS)).contains(".site-nav-fold"));
        assertTrue(site.get(Path.of(WebSite.CSS)).contains("li.folded > ul"));
    }

    @Test
    void aContentsPagesHeadingsAndNestedListsFold() {
        String contents = "<section class=\"page layout-contents\" id=\"toc\" data-layout=\"contents\"><h1>Contents</h1>"
                + "<ul><li><a href=\"#a\">A</a></li></ul><h2>Part 1</h2>"
                + "<ul><li><a href=\"#b\">B</a><ul><li><a href=\"#c\">C</a></li></ul></li></ul><h2>Notes</h2><p>None.</p></section>";
        String html = "<!doctype html><html><body class=\"web\">" + contents + page("a", "<h1>A</h1>") + page("b", "<h1>B</h1>")
                + page("c", "<h1>C</h1>") + "</body></html>";
        Map<Path, String> site = WebSite.split(html, "T", "index.html", WebSite.Options.DEFAULT);
        String a = site.get(Path.of("index.html"));
        assertTrue(a.contains("<h2 class=\"site-nav-heading\"><button class=\"site-nav-fold\" type=\"button\""
                + " aria-expanded=\"true\" aria-label=\"Show or hide: Part 1\"></button>Part 1</h2>"), a);
        assertTrue(a.contains("<h2>Notes</h2>"), "a heading with no entries after it doesn't fold: " + a);
        assertTrue(a.contains("<li class=\"site-nav-group folded\">"), a);
        String c = site.get(Path.of("c.html"));
        assertTrue(c.contains("<li class=\"site-nav-group\"><button"), "the list the current page is in is shown: " + c);
    }

    @Test
    void imagesAreFoundFromEveryPage() {
        String img = "<img src=\"images/a.svg\"><img src=\"https://example.com/b.png\"><img src=\"data:image/png;base64,AA\">";
        String html = "<!doctype html><html><head></head><body>"
                + "<section class=\"page\" id=\"a\" data-path=\"docs/a.md\"><h1>A</h1>" + img + "</section>"
                + "<section class=\"page\" id=\"b\" data-path=\"docs/deep/b.md\"><h1>B</h1>" + img + "</section></body></html>";
        Map<Path, String> site = WebSite.split(html, "T", "index.html", WebSite.Options.DEFAULT);
        assertTrue(site.get(Path.of("index.html")).contains("<img src=\"images/a.svg\">"), site.get(Path.of("index.html")));
        String b = site.get(Path.of("docs/deep/b.html"));
        assertTrue(b.contains("<img src=\"../../images/a.svg\"><img src=\"https://example.com/b.png\"><img src=\"data:image/png;base64,AA\">"), b);
    }
}
