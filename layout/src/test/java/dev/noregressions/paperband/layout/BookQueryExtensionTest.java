package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.cards.ContentNodes;
import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.BookConfig;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.Frontmatter;
import dev.noregressions.paperband.model.PlacedPage;
import dev.noregressions.paperband.model.RenderContext;
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
 * {@code cards | query('css')} in a generated page: the parts of every card
 * that match, each knowing the card, block and step it came from.
 */
class BookQueryExtensionTest {

    private static Block block(String heading, Set<String> classes, String html, List<Block> children,
                               String step) {
        return new Block(Block.Kind.HEADING_SECTION, null, classes, heading, heading == null ? 0 : 2, html,
                children, Map.of(), step == null ? Map.of() : Map.of("step", step), ContentNodes.of(html));
    }

    private static String command(String code) {
        return "<pre><code class=\"language-command\">" + code + "\n</code></pre>";
    }

    /** A step with a nested step, a second step, and a Watch Out. */
    private static Card setup() {
        Block check = block("Step 1 Check Java", Set.of("check-java"),
                "<p class=\"instructions\">Check it.</p>" + command("java -version"), List.of(), "1");
        Block install = block("Step 1 Install", Set.of("install"),
                "<p class=\"instructions\">Install a JDK.</p>", List.of(check), "1");
        Block build = block("Step 2 Build", Set.of("build"),
                "<p class=\"instructions\">Build it.</p>" + command("mvn package"), List.of(), "2");
        Block watch = block("Watch Out", Set.of("watch-out"), "<p>Use JDK 21.</p>", List.of(), null);
        return new Card("setup", Path.of("setup.md"), new Frontmatter(Map.of("level", "beginner")),
                "Setting Up", List.of(install, build, watch));
    }

    /** No steps; a command outside any step, and a Watch Out. */
    private static Card notes() {
        Block aside = block("Aside", Set.of("aside"), command("ls"), List.of(), null);
        Block watch = block("Watch Out", Set.of("watch-out"), "<p>Mind the cache.</p>", List.of(), null);
        return new Card("notes", Path.of("notes.md"),
                new Frontmatter(Map.of("level", "advanced", "index", List.of("cache", "maven"))),
                "Notes", List.of(aside, watch));
    }

    private static String page(Path book, String template) throws IOException {
        Files.createDirectories(book.resolve("layouts"));
        Files.writeString(book.resolve("layouts/page.html"), template + "<!--end-->");
        BookConfig config = new BookConfig(book, "Book", List.of(), List.of(), Map.of(), List.of(), null, null);
        RenderContext ctx = new RenderContext(config, List.of(), Map.of(), null, "pdf", "A4");
        LayoutEngine engine = new LayoutEngine(book);
        engine.setPagesAt(List.of(new PlacedPage(2, "page")));
        String html = engine.renderBook(List.of(setup(), notes()), List.of(ctx, ctx), ctx);
        int start = html.indexOf("id=\"book-page-0\">");
        return html.substring(start, html.indexOf("<!--end-->", start));
    }

    @Test
    void a_node_match_carries_its_card_block_and_step(@TempDir Path book) throws IOException {
        String out = page(book, "{% for e in cards | query('pre.command') %}"
                + "[{{ e.kind }}|{{ e.card.id }}|{{ e.block.heading }}|{{ e.step.heading }}|{{ e.node.code | trim }}]"
                + "{% endfor %}");
        assertEquals("id=\"book-page-0\">[node|setup|Step 1 Check Java|Step 1 Check Java|java -version]"
                + "[node|setup|Step 2 Build|Step 2 Build|mvn package]"
                + "[node|notes|Aside||ls]", out);
    }

    @Test
    void blocks_nest_so_a_selector_can_ask_for_commands_in_steps(@TempDir Path book) throws IOException {
        String out = page(book, "{% for e in cards | query('block[data-paperband-step] pre.command') %}"
                + "[{{ e.node.code | trim }}]{% endfor %}");
        assertTrue(out.endsWith("[java -version][mvn package]"), out);
    }

    @Test
    void a_block_match_is_the_block_itself(@TempDir Path book) throws IOException {
        String out = page(book, "{% for e in cards | query('block.watch-out') %}"
                + "[{{ e.kind }}|{{ e.card.title }}|{{ e.block.html | raw }}|{{ e.node is null }}]{% endfor %}");
        assertTrue(out.endsWith("[block|Setting Up|<p>Use JDK 21.</p>|true][block|Notes|<p>Mind the cache.</p>|true]"),
                out);
    }

    @Test
    void a_card_is_selected_by_its_frontmatter(@TempDir Path book) throws IOException {
        String out = page(book, "{% for e in cards | query('card[level=advanced] block.watch-out') %}"
                + "[{{ e.card.id }}]{% endfor %}"
                + "{% for e in cards | query('card[index~=maven]') %}[{{ e.kind }}:{{ e.card.id }}|{{ e.block is null }}]{% endfor %}");
        assertTrue(out.endsWith("[notes][card:notes|true]"), out);
    }

    @Test
    void one_card_can_be_queried_on_its_own(@TempDir Path book) throws IOException {
        String out = page(book, "{% for c in cards %}{{ c.id }}={{ c | query('pre.command') | length }};{% endfor %}");
        assertTrue(out.endsWith("setup=2;notes=1;"), out);
    }

    @Test
    void no_match_is_an_empty_list(@TempDir Path book) throws IOException {
        String out = page(book, "{% if cards | query('table') is empty %}none{% endif %}");
        assertTrue(out.endsWith("none"), out);
    }

    @Test
    void a_selector_jsoup_cant_read_fails_naming_it(@TempDir Path book) {
        Exception e = assertThrows(Exception.class, () -> page(book, "{{ cards | query('block[') }}"));
        assertTrue(messages(e).contains("query('block[')"), messages(e));
    }

    @Test
    void something_that_isnt_a_card_or_block_fails(@TempDir Path book) {
        Exception e = assertThrows(Exception.class, () -> page(book, "{{ 'text' | query('p') }}"));
        assertTrue(messages(e).contains("query works on cards, a card or a block"), messages(e));
    }

    /** The command reference in the Themes card, verbatim. */
    @Test
    void the_documented_command_reference_renders(@TempDir Path book) throws IOException {
        String out = page(book, """
                <h1>Command reference</h1>
                {% for c in cards %}
                  {% set commands = c | query('block[data-paperband-step] pre.command') %}
                  {% if commands is not empty %}
                  <h2><a href="card:{{ c.id }}">{{ c.title }}</a></h2>
                  <dl class="command-reference">
                  {% for e in commands %}
                    <dt>{{ e.step.heading }}</dt>
                    <dd><pre class="command"><code>{{ e.node.code | trim }}</code></pre></dd>
                  {% endfor %}
                  </dl>
                  {% endif %}
                {% endfor %}
                """);
        assertTrue(out.contains("<h2><a href=\"#card-setup\">Setting Up</a></h2>"), out);
        assertTrue(out.contains("<dt>Step 1 Check Java</dt>"), out);
        assertTrue(out.contains("<code>mvn package</code>"), out);
        assertFalse(out.contains("Notes"), "a card with no stepped command has no entry: " + out);
    }

    /** The Watch Out appendix in the Themes card, verbatim. */
    @Test
    void the_documented_watch_out_page_renders(@TempDir Path book) throws IOException {
        String out = page(book, """
                {% for e in cards | query('block.watch-out') %}
                  <section class="watch-out-entry">
                    <h2><a href="card:{{ e.card.id }}">{{ e.card.title }}</a></h2>
                    {{ e.block.html | raw }}
                  </section>
                {% endfor %}
                """);
        assertTrue(out.contains("<h2><a href=\"#card-setup\">Setting Up</a></h2>"), out);
        assertTrue(out.contains("<p>Use JDK 21.</p>"), out);
        assertTrue(out.contains("<h2><a href=\"#card-notes\">Notes</a></h2>"), out);
    }

    private static String messages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) sb.append(c.getMessage()).append('\n');
        return sb.toString();
    }
}
