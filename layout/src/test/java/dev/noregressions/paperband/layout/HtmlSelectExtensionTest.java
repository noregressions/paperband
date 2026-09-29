package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.BookConfig;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.Frontmatter;
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

/** {@code | select('css')} in layout templates: parts of a block's HTML, by selector. */
class HtmlSelectExtensionTest {

    private static final String INSTALL_HTML = "<p>Background.</p>"
            + "<p class=\"instructions\">Run the installer.</p>"
            + "<p>A note about proxies.</p>"
            + "<pre class=\"console\"><code>$ ./install.sh</code></pre>";

    /** A steps card: two stepped sections, one with a stepped child, and an unstepped one. */
    private static Card stepsCard() {
        Block check = new Block(Block.Kind.HEADING_SECTION, null, Set.of("check-java"), "Step 1 Check Java", 3,
                "<p class=\"instructions\">Check the version.</p><pre class=\"console\"><code>$ java -version</code></pre>",
                List.of(), Map.of(), Map.of("step", "1"));
        Block install = new Block(Block.Kind.HEADING_SECTION, null, Set.of("install"), "Step 1 Install", 2,
                INSTALL_HTML, List.of(check), Map.of(), Map.of("step", "1"));
        Block aside = new Block(Block.Kind.HEADING_SECTION, null, Set.of("aside"), "Aside", 2,
                "<p class=\"instructions\">Not a step.</p>", List.of());
        Block build = new Block(Block.Kind.HEADING_SECTION, null, Set.of("build"), "Step 2 Build", 2,
                "<p class=\"instructions\">Build it.</p><pre class=\"console\"><code>$ mvn package</code></pre>",
                List.of(), Map.of(), Map.of("step", "2"));
        return new Card("steps", Path.of("steps.md"), new Frontmatter(Map.of()), "Steps",
                List.of(install, aside, build));
    }

    private static RenderContext ctx() {
        BookConfig book = new BookConfig(null, "Book", List.of(), List.of(), Map.of(), List.of(), null, null);
        return new RenderContext(book, List.of(), Map.of(), null, "pdf", "A4");
    }

    private static String render(Path book, String template, String body) throws IOException {
        Path layouts = Files.createDirectories(book.resolve("layouts"));
        Files.writeString(layouts.resolve(template + ".html"), body);
        return new LayoutEngine(book).render(stepsCard(), ctx(), template);
    }

    @Test
    void a_card_body_can_build_one_entry_per_step_from_parts_of_each_block(@TempDir Path book) throws IOException {
        Files.createDirectories(book.resolve("layouts"));
        Files.writeString(book.resolve("layouts/_card-body.html"), """
                {% macro entries(blocks) %}{% for b in blocks %}{% if b.directives.step %}\
                <section class="step-entry"><h3>{{ b.heading }}</h3>\
                {{ b.html | select('p.instructions, pre.console') | raw }}</section>\
                {% endif %}{{ entries(b.children) }}{% endfor %}{% endmacro %}\
                {{ entries(card.blocks) }}""");

        String html = new LayoutEngine(book).render(stepsCard(), ctx());

        assertTrue(html.contains("<h3>Step 1 Install</h3><p class=\"instructions\">Run the installer.</p>\n"
                + "<pre class=\"console\"><code>$ ./install.sh</code></pre>"), html);
        assertTrue(html.contains("<h3>Step 1 Check Java</h3>"), "a nested step gets its own entry: " + html);
        assertTrue(html.contains("<h3>Step 2 Build</h3>"), html);
        assertFalse(html.contains("Background."), "only the selected parts: " + html);
        assertFalse(html.contains("proxies"), html);
        assertFalse(html.contains("Not a step."), "an unstepped block has no entry: " + html);
        assertEquals(3, html.split("class=\"step-entry\"", -1).length - 1, html);
    }

    @Test
    void no_match_is_an_empty_string_and_works_as_a_test(@TempDir Path book) throws IOException {
        String html = render(book, "sel", """
                [{{ card.blocks[1].html | select('pre.console') | raw }}]\
                {% if card.blocks[1].html | select('pre.console') %}YES{% else %}NO{% endif %}""");
        assertTrue(html.contains("[]NO"), html);
    }

    @Test
    void a_match_inside_another_match_is_not_repeated(@TempDir Path book) throws IOException {
        String html = render(book, "sel", """
                {{ '<div class="x"><p class="x">in</p></div><p class="x">out</p>' | select('.x') | raw }}""");
        assertTrue(html.contains("<div class=\"x\">\n <p class=\"x\">in</p>\n</div>\n<p class=\"x\">out</p>")
                || html.contains("<div class=\"x\"><p class=\"x\">in</p></div>\n<p class=\"x\">out</p>"), html);
        assertEquals(1, html.split(">in<", -1).length - 1, html);
    }

    @Test
    void without_raw_the_result_is_escaped_like_any_other_value(@TempDir Path book) throws IOException {
        String html = render(book, "sel", "{{ card.blocks[2].html | select('pre.console') }}");
        assertTrue(html.contains("&lt;pre class=&quot;console&quot;&gt;"), html);
    }

    @Test
    void a_bad_selector_fails_with_the_selector_named(@TempDir Path book) throws IOException {
        Exception e = assertThrows(Exception.class,
                () -> render(book, "sel", "{{ card.blocks[0].html | select('p[[') | raw }}"));
        String message = e.getMessage() + (e.getCause() == null ? "" : e.getCause().getMessage());
        assertTrue(message.contains("select('p[[')"), message);
    }
}
