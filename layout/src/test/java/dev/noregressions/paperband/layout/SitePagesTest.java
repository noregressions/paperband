package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.cards.ContentNodes;
import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.BookConfig;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.Frontmatter;
import dev.noregressions.paperband.model.PlacedPage;
import dev.noregressions.paperband.model.RenderContext;
import dev.noregressions.paperband.model.Section;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Generated pages ({@code <page>} markers) on the site: each its own page in
 * the site shell, with the book model a PDF page gets, and a nav entry where
 * the marker sits in the book.
 */
class SitePagesTest {

    private static final String COMMANDS = """
            <h1>Every command</h1>
            {% for c in cards %}{% for e in c | query('pre.command') %}\
            <p><a href="card:{{ c.id }}">{{ c.title }}</a> {{ e.node.code | trim }}</p>\
            {% endfor %}{% endfor %}""";

    private static Card card(Path root, String folder, String id, String title, String command) {
        String html = "<pre><code class=\"language-command\">" + command + "\n</code></pre>";
        Block b = new Block(Block.Kind.HEADING_SECTION, null, Set.of("run"), "Run", 2, html, List.of(), Map.of(),
                Map.of(), ContentNodes.of(html));
        return new Card(id, root.resolve(folder).resolve(id + ".md"), new Frontmatter(Map.of()), title, List.of(b));
    }

    /** Two declared sections, setup then build, with pages placed at the given card indexes. */
    private static Map<String, String> site(Path root, List<PlacedPage> pages) {
        Card install = card(root, "setup", "install", "Install", "java -version");
        Card compile = card(root, "build", "compile", "Compile", "mvn package");
        Section setup = new Section("setup", "Setup", List.of(), null, List.of(install.source()));
        Section build = new Section("build", "Build", List.of(), null, List.of(compile.source()));
        BookConfig book = new BookConfig(root, "Test Book", List.of(), List.of(), Map.of(),
                List.of(), null, null, null, null, null, null, null, List.of(setup, build));
        RenderContext ctx = new RenderContext(book, List.of(), Map.of(), null, "web", null);
        LayoutEngine engine = new LayoutEngine(root);
        engine.setPagesAt(pages);
        return engine.renderSite(List.of(install, compile), List.of(ctx, ctx), ctx);
    }

    private static void template(Path root, String name, String body) throws IOException {
        Files.createDirectories(root.resolve("layouts"));
        Files.writeString(root.resolve("layouts/" + name + ".html"), body);
    }

    @Test
    void a_page_is_its_own_site_page_with_the_whole_book_in_scope(@TempDir Path root) throws IOException {
        template(root, "commands", COMMANDS);
        String page = site(root, List.of(new PlacedPage(1, "commands"))).get("commands.html");
        assertNotNull(page, "commands.html");
        assertTrue(page.contains("<title>Every command — Test Book</title>"), page);
        assertTrue(page.contains("<main class=\"site-main\">"), "in the site shell: " + page);
        assertTrue(page.contains("<article class=\"site-generated-page\" id=\"commands\">"), page);
        assertTrue(page.contains("java -version"), page);
        assertTrue(page.contains("mvn package"), page);
    }

    @Test
    void the_page_sees_that_it_is_on_the_site(@TempDir Path root) throws IOException {
        template(root, "where", "<h1>Where</h1>OUTPUT[{{ output }}]");
        assertTrue(site(root, List.of(new PlacedPage(1, "where"))).get("where.html").contains("OUTPUT[site]"));
    }

    @Test
    void the_page_lists_each_sections_cards_as_in_the_pdf(@TempDir Path root) throws IOException {
        template(root, "map", "<h1>Map</h1>{% for s in sections %}[{{ s.label }}:{% for c in s.cards %}{{ c.title }}{% endfor %}]{% endfor %}");
        assertTrue(site(root, List.of(new PlacedPage(2, "map"))).get("map.html").contains("[Setup:Install][Build:Compile]"));
    }

    @Test
    void card_links_on_the_page_resolve_for_the_site(@TempDir Path root) throws IOException {
        template(root, "commands", COMMANDS);
        String page = site(root, List.of(new PlacedPage(1, "commands"))).get("commands.html");
        assertTrue(page.contains("<a href=\"cards/install.html\">Install</a>"), page);
    }

    @Test
    void the_nav_lists_the_page_where_its_marker_sits(@TempDir Path root) throws IOException {
        template(root, "commands", COMMANDS);
        Map<String, String> site = site(root, List.of(new PlacedPage(1, "commands")));
        String index = site.get("index.html");
        int setup = index.indexOf("href=\"setup.html\"");
        int commands = index.indexOf("href=\"commands.html\"");
        int build = index.indexOf("href=\"build.html\"");
        assertTrue(setup >= 0 && setup < commands && commands < build, "between the two sections: " + index);
        assertTrue(index.contains("<span class=\"tier-box-label\">Every command</span>"), "labelled by its h1");
        assertTrue(site.get("cards/compile.html").contains("href=\"../commands.html\""), "on card pages too");
    }

    @Test
    void a_page_at_the_front_comes_first(@TempDir Path root) throws IOException {
        template(root, "commands", COMMANDS);
        String index = site(root, List.of(new PlacedPage(0, "commands"))).get("index.html");
        assertTrue(index.indexOf("href=\"commands.html\"") < index.indexOf("href=\"setup.html\""), index);
    }

    @Test
    void a_template_placed_twice_is_one_page(@TempDir Path root) throws IOException {
        template(root, "commands", COMMANDS);
        String index = site(root, List.of(new PlacedPage(0, "commands"), new PlacedPage(2, "commands")))
                .get("index.html");
        assertEquals(index.indexOf("<span class=\"tier-box-label\">Every command</span>"),
                index.lastIndexOf("<span class=\"tier-box-label\">Every command</span>"), index);
    }

    @Test
    void a_page_named_like_a_section_fails(@TempDir Path root) throws IOException {
        template(root, "setup", "<h1>Setup matrix</h1>");
        LayoutException e = assertThrows(LayoutException.class,
                () -> site(root, List.of(new PlacedPage(1, "setup"))));
        assertTrue(e.getMessage().contains("already a section or axis page"), e.getMessage());
    }

    @Test
    void no_page_changes_nothing(@TempDir Path root) {
        Map<String, String> site = site(root, List.of());
        assertEquals(Set.of("index.html", "setup.html", "build.html", "cards/install.html", "cards/compile.html"),
                site.keySet());
    }
}
