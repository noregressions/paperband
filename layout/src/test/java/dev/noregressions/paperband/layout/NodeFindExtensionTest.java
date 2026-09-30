package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.BookConfig;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.Frontmatter;
import dev.noregressions.paperband.model.Node;
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

/** {@code block.nodes} and {@code | find('css')} in layout templates: a block's content as data. */
class NodeFindExtensionTest {

    private static Node element(String type, String tag, Set<String> classes, String text,
                                List<Node> children, Map<String, String> props) {
        return new Node(type, tag, null, classes, Map.of(), Map.of(), text, "<" + tag + ">", children, props);
    }

    /** One stepped block: background, the instructions with a link in them, a command, a list. */
    private static Card card() {
        Node background = element("paragraph", "p", Set.of(), "Background.", List.of(), Map.of());
        Node link = new Node("link", "a", null, Set.of(), Map.of("href", "card:setup"), Map.of(),
                "setup", "<a href=\"card:setup\">setup</a>", List.of(), Map.of());
        Node instructions = element("paragraph", "p", Set.of("instructions"), "Run <setup> first.",
                List.of(Node.text("Run ", "Run "), link), Map.of());
        Node command = new Node("fence", "pre", null, Set.of("command"), Map.of(), Map.of("step", "1"),
                "mvn package", "<pre class=\"command\">", List.of(),
                Map.of("lang", "command", "code", "mvn package\n"));
        Node item = element("item", "li", Set.of(), "a", List.of(), Map.of());
        Node inner = element("item", "li", Set.of(), "b", List.of(), Map.of());
        Node nested = element("list", "ul", Set.of(), "b", List.of(inner), Map.of("ordered", "false"));
        Node list = element("list", "ul", Set.of(), "a b", List.of(item, nested), Map.of("ordered", "false"));
        Block step = new Block(Block.Kind.HEADING_SECTION, null, Set.of("build"), "Step 1 Build", 2,
                "<p>…</p>", List.of(), Map.of(), Map.of("step", "1"),
                List.of(background, instructions, command, list));
        return new Card("c", Path.of("c.md"), new Frontmatter(Map.of()), "C", List.of(step));
    }

    private static String render(Path book, String body) throws IOException {
        Path layouts = Files.createDirectories(book.resolve("layouts"));
        Files.writeString(layouts.resolve("t.html"), body);
        BookConfig config = new BookConfig(null, "Book", List.of(), List.of(), Map.of(), List.of(), null, null);
        return new LayoutEngine(book).render(card(), new RenderContext(config, List.of(), Map.of(), null, "pdf", "A4"), "t");
    }

    @Test
    void a_fragment_is_filled_from_the_nodes_it_finds(@TempDir Path book) throws IOException {
        String html = render(book, """
                {% set b = card.blocks[0] %}\
                {% set cmd = b.nodes | find('pre.command') | first %}\
                [{{ (b.nodes | find('.instructions') | first).text }}][{{ cmd.lang }}][{{ cmd.code | trim }}]""");
        assertTrue(html.contains("[Run &lt;setup&gt; first.][command][mvn package]"),
                "text is escaped like any value: " + html);
    }

    @Test
    void the_themes_card_example_fills_a_fragment_of_the_books_own(@TempDir Path book) throws IOException {
        Files.createDirectories(book.resolve("layouts/fragments"));
        Files.writeString(book.resolve("layouts/fragments/step.html"), """
                <div class="step">
                  <h3>{{ heading }}</h3>
                  <p>{{ instructions.text }}</p>
                  {% if command is not null %}<kbd>{{ command.code | trim }}</kbd>{% endif %}
                </div>
                """);
        String html = render(book, """
                {% for s in card.steps %}
                  {% include "fragments/step" with {
                       "heading": s.block.heading,
                       "instructions": s.block | find('.instructions') | first,
                       "command": s.block | find('pre.command, pre.console') | first } %}
                {% endfor %}""");
        assertTrue(html.contains("<h3>Step 1 Build</h3>"), html);
        assertTrue(html.contains("<p>Run &lt;setup&gt; first.</p>"), html);
        assertTrue(html.contains("<kbd>mvn package</kbd>"), html);
    }

    @Test
    void a_block_can_be_searched_directly_and_nested_matches_are_kept(@TempDir Path book) throws IOException {
        String html = render(book, "{% for n in card.blocks[0] | find('li') %}<{{ n.text }}>{% endfor %}");
        assertTrue(html.contains("<a><b>"), html);
    }

    @Test
    void the_selector_sees_attributes_and_directives(@TempDir Path book) throws IOException {
        String html = render(book, """
                {{ (card.blocks[0].nodes | find('a[href^=card:]') | first).text }}|\
                {{ (card.blocks[0].nodes | find('[data-paperband-step]') | first).type }}""");
        assertTrue(html.contains("setup|fence"), html);
    }

    @Test
    void sparse_keys_are_null_and_yes_no_props_are_booleans(@TempDir Path book) throws IOException {
        String html = render(book, """
                {% set list = card.blocks[0].nodes | find('ul') | first %}\
                [{% if list.ordered %}ordered{% else %}bullets{% endif %}][{% if list.lang %}x{% else %}none{% endif %}]\
                [{{ card.blocks[0].nodes | find('table') | length }}]""");
        assertTrue(html.contains("[bullets][none][0]"), html);
    }

    @Test
    void a_fence_left_as_code_matches_its_type_as_a_class(@TempDir Path book) throws IOException {
        // Loading leaves ```console as <pre><code class="language-console">; its
        // template makes it pre.console later. find matches it either way.
        Node console = new Node("fence", "pre", null, Set.of(), Map.of(), Map.of(), "$ ls",
                "<pre><code class=\"language-console\">$ ls</code></pre>", List.of(), Map.of("lang", "console", "code", "$ ls\n"));
        Block b = new Block(Block.Kind.HEADING_SECTION, null, Set.of("x"), "X", 2, "", List.of(), Map.of(), Map.of(),
                List.of(console));
        Path layouts = Files.createDirectories(book.resolve("layouts"));
        Files.writeString(layouts.resolve("t.html"), "[{{ (card.blocks[0] | find('pre.console') | first).code | trim }}]");
        BookConfig config = new BookConfig(null, "Book", List.of(), List.of(), Map.of(), List.of(), null, null);
        String html = new LayoutEngine(book).render(new Card("c", Path.of("c.md"), new Frontmatter(Map.of()), "C", List.of(b)),
                new RenderContext(config, List.of(), Map.of(), null, "pdf", "A4"), "t");
        assertTrue(html.contains("[$ ls]"), html);
    }

    @Test
    void a_bad_selector_fails_with_the_selector_named(@TempDir Path book) {
        Exception e = assertThrows(Exception.class,
                () -> render(book, "{{ card.blocks[0].nodes | find('p[[') }}"));
        String message = e.getMessage() + (e.getCause() == null ? "" : e.getCause().getMessage());
        assertTrue(message.contains("find('p[[')"), message);
    }
}
