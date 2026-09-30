package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.cards.BlockTemplates;
import dev.noregressions.paperband.cards.CardLoader;
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

    /** A card as loaded, with the templates and vars an output writes it with. */
    private record Parsed(Card card, BlockTemplates templates, Map<String, Object> vars) {
    }

    private static Parsed parse(Path layoutsDir, Map<String, Object> vars, String markdown) {
        CardLoader loader = new CardLoader();
        BlockTemplates templates = new BlockTemplates(null, layoutsDir);
        loader.setBlockTemplates(templates, vars);
        return new Parsed(loader.parse(Path.of("card.md"), markdown), templates, vars);
    }

    /**
     * The card's content as an output writes it: block templates are layout,
     * so a fence goes through its template here, not when the card is read.
     */
    private static String html(Parsed p) {
        return html(p.card(), p.templates(), p.vars());
    }

    /** A card loaded with the bundled templates alone. */
    private static String html(Card card) {
        return html(card, BlockTemplates.bundled(), Map.of());
    }

    private static String html(Card card, BlockTemplates templates, Map<String, Object> vars) {
        ContentWriter writer = new ContentWriter(templates);
        StringBuilder sb = new StringBuilder();
        card.blocks().forEach(b -> sb.append(writer.html(b, vars, card.source(), "print", "pdf-a4")));
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

        Parsed card = parse(layouts, Map.of("product_name", "Paperband"), """
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

        Parsed card = parse(layouts, Map.of(), """
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
        Parsed card = parse(layouts, Map.of(), """
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

        // Loading leaves the fence alone; writing it is what reads the template.
        Parsed card = parse(layouts, Map.of(), """
                # T

                ```trace
                x
                ```
                """);
        LayoutException e = assertThrows(LayoutException.class, () -> html(card));

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

    @Test
    void semanticFenceLanguages_becomeBlockTypes() {
        // The language tag IS the editorial role: no attribute syntax needed.
        String html = html(new CardLoader().parse(Path.of("card.md"), """
                # T

                ```command
                mvn dependency:tree
                ```

                ```output
                [INFO] com.example:app:jar:1.0.0
                ```

                ```console
                $ ls
                a.txt
                ```

                ```java
                int x = 1;
                ```
                """));
        assertTrue(html.contains("class=\"command\""), "command class on the pre: " + html);
        assertTrue(html.contains("language-bash"), "command highlights as bash: " + html);
        assertTrue(html.contains("class=\"output\""), "output class on the pre: " + html);
        assertFalse(html.contains("language-output"), "no made-up language for Prism to 404 on");
        assertTrue(html.contains("class=\"console\""), "console class on the pre: " + html);
        assertTrue(html.contains("language-shell-session"), "console highlights as a session");
        assertTrue(html.contains("language-java"), "real languages pass through untouched");
    }

    @Test
    void aTemplate_seesWhichOutputIsBeingWritten(@TempDir Path layouts) throws IOException {
        template(layouts, "trace.html", "<pre class=\"trace-{{ output }} {{ target }}\">{{ content }}</pre>");
        Parsed card = parse(layouts, Map.of(), "# T\n\n```trace\nx\n```\n");
        assertTrue(html(card).contains("class=\"trace-print pdf-a4\""), html(card));
    }

    @Test
    void theBundledMermaidType_becomesADiagramContainer() {
        // ```mermaid renders to a pre.mermaid the page-side loader turns into
        // SVG; the source must be verbatim-escaped text (mermaid reads
        // textContent, which unescapes), not an ordinary code block.
        CardLoader loader = new CardLoader();
        Card card = loader.parse(Path.of("card.md"), """
                # T

                ```mermaid
                graph LR
                  A[Start] --> B{Works?}
                ```
                """);

        String html = html(card);
        assertTrue(html.contains("<pre class=\"mermaid\">"), html);
        assertTrue(html.contains("A[Start] --&gt; B{Works?}"),
                "diagram source stays verbatim, escaped: " + html);
        assertFalse(html.contains("language-mermaid"), "the code block is replaced");
    }
}
