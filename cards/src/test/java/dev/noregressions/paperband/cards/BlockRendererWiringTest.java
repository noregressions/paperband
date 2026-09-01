package dev.noregressions.paperband.cards;

import dev.noregressions.paperband.block.BlockRenderException;
import dev.noregressions.paperband.block.BlockRenderer;
import dev.noregressions.paperband.block.BlockRendererRegistry;
import dev.noregressions.paperband.block.BlockRequest;
import dev.noregressions.paperband.model.Card;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a {@link BlockRenderer} sits in the chain: after the theme's and the
 * book's own {@code blocks/<type>.html}, before the bundled ones.
 *
 * <p>Author beats jar beats default — the ordering that lets a book install a
 * diagram module and still override one diagram by hand.
 */
class BlockRendererWiringTest {

    /** Records what it was handed, so the request's contents can be asserted on. */
    private static final class Spy implements BlockRenderer {
        private final Set<String> types;
        private final String html;
        BlockRequest seen;

        Spy(String html, String... types) {
            this.html = html;
            this.types = Set.of(types);
        }

        @Override public String name() { return "spy"; }
        @Override public String description() { return "test"; }
        @Override public Set<String> types() { return types; }
        @Override public String render(BlockRequest request) {
            this.seen = request;
            return html;
        }
    }

    private static Card parse(Path layoutsDir, BlockRendererRegistry registry,
                              Map<String, Object> vars, String markdown) {
        CardLoader loader = new CardLoader();
        loader.setBlockTemplates(new BlockTemplates(null, layoutsDir, registry), vars);
        return loader.parse(Path.of("card.md"), markdown);
    }

    private static String html(Card card) {
        StringBuilder sb = new StringBuilder();
        card.blocks().forEach(b -> sb.append(b.html()));
        return sb.toString();
    }

    private static void template(Path layouts, String name, String body) throws IOException {
        Files.createDirectories(layouts.resolve("blocks"));
        Files.writeString(layouts.resolve("blocks").resolve(name), body);
    }

    private static final String DIAGRAM = """
            # T

            ```diagram {.wide}
            a -> b
            ```
            """;

    @Test
    void aClaimedType_rendersThroughTheRenderer(@TempDir Path layouts) {
        Spy spy = new Spy("<figure class=\"drawn\">svg here</figure>", "diagram");

        Card card = parse(layouts, BlockRendererRegistry.of(spy), Map.of(), DIAGRAM);

        assertTrue(html(card).contains("class=\"drawn\""), html(card));
        assertTrue(html(card).contains("svg here"), html(card));
        assertEquals("diagram", spy.seen.type());
        assertEquals("a -> b\n", spy.seen.content());
        assertEquals(List.of("wide"), spy.seen.classes());
        assertEquals(Path.of("card.md"), spy.seen.source());
    }

    @Test
    void theBooksOwnTemplate_outranksTheRenderer(@TempDir Path layouts) throws IOException {
        template(layouts, "diagram.html", "<p class=\"mine\">{{ content }}</p>");
        Spy spy = new Spy("<figure>from the jar</figure>", "diagram");

        Card card = parse(layouts, BlockRendererRegistry.of(spy), Map.of(), DIAGRAM);

        assertTrue(html(card).contains("<p class=\"mine\">"), html(card));
        assertTrue(spy.seen == null, "the renderer must not even be consulted");
    }

    @Test
    void aRendererOutranksABundledTemplate(@TempDir Path layouts) {
        // ```mermaid ships as a bundled template. A module claiming the type
        // takes it over -- that's how a server-side mermaid would work.
        Spy spy = new Spy("<figure>server-side</figure>", "mermaid");

        Card card = parse(layouts, BlockRendererRegistry.of(spy), Map.of(), """
                # T

                ```mermaid
                graph TD; A-->B;
                ```
                """);

        String html = html(card);
        assertTrue(html.contains("server-side"), html);
        assertFalse(html.contains("pre class=\"mermaid\""), html);
    }

    @Test
    void aRendererThatDeclines_leavesTheBlockToTheTemplates(@TempDir Path layouts) {
        // Returning null is "not mine after all" -- ```console still resolves
        // to the bundled template behind it.
        Spy spy = new Spy(null, "console");

        Card card = parse(layouts, BlockRendererRegistry.of(spy), Map.of(), """
                # T

                ```console
                $ ls
                ```
                """);

        assertTrue(html(card).contains("class=\"console\""), html(card));
    }

    @Test
    void theRenderersOwnVarsReachItAsConfig(@TempDir Path layouts) {
        Spy spy = new Spy("<i>x</i>", "diagram");
        Map<String, Object> vars = Map.of(
                "spy", Map.of("format", "png", "sharp", true),
                "product_name", "Paperband");

        parse(layouts, BlockRendererRegistry.of(spy), vars, DIAGRAM);

        // vars.<name> is the settings block; the whole cascade is there too.
        assertEquals("png", spy.seen.setting("format", "svg"));
        assertTrue(spy.seen.flag("sharp", false));
        assertEquals("svg", spy.seen.setting("missing", "svg"));
        assertEquals("Paperband", spy.seen.vars().get("product_name"));
    }

    @Test
    void aRejectedBlock_failsTheBuildNamingTheCardAndTheRenderer(@TempDir Path layouts) {
        BlockRenderer angry = new BlockRenderer() {
            @Override public String name() { return "spy"; }
            @Override public String description() { return "test"; }
            @Override public Set<String> types() { return Set.of("diagram"); }
            @Override public String render(BlockRequest r) {
                throw new BlockRenderException("line 1: syntax error");
            }
        };

        CardParseException e = assertThrows(CardParseException.class,
                () -> parse(layouts, BlockRendererRegistry.of(angry), Map.of(), DIAGRAM));

        assertTrue(e.getMessage().contains("card.md"), e.getMessage());
        assertTrue(e.getMessage().contains("```diagram"), e.getMessage());
        assertTrue(e.getMessage().contains("spy"), e.getMessage());
        assertTrue(e.getMessage().contains("line 1: syntax error"), e.getMessage());
    }

    @Test
    void withNoRenderersInstalled_nothingChanges(@TempDir Path layouts) {
        Card card = parse(layouts, BlockRendererRegistry.empty(), Map.of(), DIAGRAM);

        // An unclaimed, un-templated type is an ordinary code block, exactly
        // as before the SPI existed.
        assertTrue(html(card).contains("<code class=\"language-diagram"), html(card));
    }

    private static void assertFalse(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertFalse(condition, message);
    }
}
