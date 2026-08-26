package dev.noregressions.paperband.cards;

import dev.noregressions.paperband.model.Card;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Block templates: a {@code ```type} block renders through
 * {@code blocks/<type>.html} when one exists — book-defined types, overrides
 * of the bundled ones, verbatim content, and loud failures.
 */
class BlockTemplatesTest {

    private static Card parse(Path layoutsDir, Map<String, Object> vars, String markdown) {
        CardLoader loader = new CardLoader();
        loader.setBlockTemplates(new BlockTemplates(null, layoutsDir), vars);
        return loader.parse(Path.of("card.md"), markdown);
    }

    private static String html(Card card) {
        StringBuilder sb = new StringBuilder();
        card.blocks().forEach(b -> sb.append(b.html()));
        return sb.toString();
    }

    private static void template(Path layouts, String name, String body) throws IOException {
        Path dir = layouts.resolve("blocks");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), body);
    }

    @Test
    void aBookDefinedType_rendersThroughItsTemplate(@TempDir Path layouts) throws IOException {
        template(layouts, "trace.html", """
                <figure class="trace{% for c in classes %} {{ c }}{% endfor %}">
                <figcaption>Trace — {{ vars.product_name }}</figcaption>
                <pre><code>{{ content }}</code></pre>
                </figure>""");

        Card card = parse(layouts, Map.of("product_name", "Paperband"), """
                # T

                ```trace {.wide}
                step 1 -> step 2
                <not markup>
                ```
                """);

        String html = html(card);
        assertTrue(html.contains("<figure class=\"trace wide\">"),
                "the template renders, info-line classes carried: " + html);
        assertTrue(html.contains("Trace — Paperband"), "vars are in scope");
        assertTrue(html.contains("step 1 -&gt; step 2"), "content is verbatim and escaped");
        assertTrue(html.contains("&lt;not markup&gt;"),
                "angle brackets in content stay text, not elements");
        assertFalse(html.contains("language-trace"), "the original code block is replaced");
    }

    @Test
    void aBookTemplate_overridesABundledType(@TempDir Path layouts) throws IOException {
        template(layouts, "output.html",
                "<div class=\"terminal\"><pre><code>{{ content }}</code></pre></div>");

        Card card = parse(layouts, Map.of(), """
                # T

                ```output
                [INFO] hello
                ```
                """);

        String html = html(card);
        assertTrue(html.contains("class=\"terminal\""), "the book's template wins: " + html);
        assertFalse(html.contains("class=\"output\""), "the bundled rendering is replaced");
    }

    @Test
    void aTypeWithNoTemplate_staysAnOrdinaryCodeBlock(@TempDir Path layouts) {
        Card card = parse(layouts, Map.of(), """
                # T

                ```java
                int x = 1;
                ```
                """);

        assertTrue(html(card).contains("language-java"), "real languages pass through");
    }

    @Test
    void aBrokenTemplate_failsNamingTheCardAndTheType(@TempDir Path layouts) throws IOException {
        template(layouts, "trace.html", "{% if unclosed %}");

        CardParseException e = assertThrows(CardParseException.class,
                () -> parse(layouts, Map.of(), """
                        # T

                        ```trace
                        x
                        ```
                        """));

        assertTrue(e.getMessage().contains("card.md"), e.getMessage());
        assertTrue(e.getMessage().contains("```trace"), e.getMessage());
        assertTrue(e.getMessage().contains("blocks/trace"), e.getMessage());
    }

    @Test
    void theBundledTypes_areThemselvesBlockTemplates() {
        // No layouts dir, no theme: the defaults still work, because command/
        // output/console ship as bundled templates on the same mechanism.
        CardLoader loader = new CardLoader();
        Card card = loader.parse(Path.of("card.md"), """
                # T

                ```console
                $ ls
                a.txt
                ```
                """);

        String html = html(card);
        assertTrue(html.contains("class=\"console\""), html);
        assertTrue(html.contains("language-shell-session"), html);
    }
}
