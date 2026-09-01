package dev.noregressions.paperband.block.plantuml;

import dev.noregressions.paperband.block.BlockRendererRegistry;
import dev.noregressions.paperband.cards.BlockTemplates;
import dev.noregressions.paperband.cards.CardLoader;
import dev.noregressions.paperband.cards.ContentPolicy;
import dev.noregressions.paperband.model.Card;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The end of the story: a {@code ```plantuml} fence in a real card comes out
 * of the loader as a drawn diagram.
 *
 * <p>Worth its own test because the renderer's HTML does not go into the page
 * as written — jsoup re-parses it and prints it back, and an SVG that survives
 * a unit test can still come out of that reformatted into something a browser
 * draws differently.
 */
class PlantumlCardPipelineTest {

    private static String render(String markdown) {
        return render(markdown, ContentPolicy.CLEAN);
    }

    private static String render(String markdown, ContentPolicy policy) {
        CardLoader loader = new CardLoader();
        loader.setContentPolicy(policy, null);
        loader.setBlockTemplates(
                new BlockTemplates(null, null,
                        BlockRendererRegistry.of(new PlantumlBlockRenderer())),
                Map.of());
        Card card = loader.parse(Path.of("card.md"), markdown);
        StringBuilder sb = new StringBuilder();
        card.blocks().forEach(b -> sb.append(b.html()));
        return sb.toString();
    }

    @Test
    void aFenceInACardBecomesADrawnDiagram() {
        String html = render("""
                # Sequence

                Before.

                ```plantuml
                Alice -> Bob: hello
                Bob --> Alice: hi
                ```

                After.
                """);

        assertTrue(html.contains("<figure class=\"plantuml\""), html);
        assertTrue(html.contains("<svg"), html);
        assertTrue(html.contains("</svg>"), html);
        // The prose either side is untouched, and the fence is gone.
        assertTrue(html.contains("Before."), html);
        assertTrue(html.contains("After."), html);
        assertFalse(html.contains("language-plantuml"), html);
    }

    @Test
    void theLabelsSurviveTheRoundTrip() {
        // Whitespace inside an SVG <text> is the thing a pretty-printer would
        // damage, and a label with a space in it is where it would show.
        String html = render("""
                # T

                ```plantuml
                Alice -> Bob: order placed
                ```
                """);

        assertTrue(html.contains(">order placed<") || html.contains("order placed"), html);
        assertFalse(html.contains("order\nplaced"), html);
    }

    @Test
    void theContentPolicyLeavesTheDrawingAlone() {
        // Every colour and font in a PlantUML diagram arrives as style= and
        // fill= on its shapes -- exactly what the sanitizer strips out of
        // prose. Under `strict` it would have failed the build outright.
        for (ContentPolicy policy : new ContentPolicy[] {ContentPolicy.CLEAN, ContentPolicy.STRICT}) {
            String html = render("""
                    # T

                    ```plantuml
                    Alice -> Bob: hello
                    ```
                    """, policy);

            assertTrue(html.contains("<svg"), policy + ": " + html);
            assertTrue(html.contains("style=") || html.contains("fill="), policy + ": " + html);
        }
    }

    @Test
    void withoutTheModule_theSameFenceStaysACodeBlock() {
        CardLoader loader = new CardLoader();
        Card card = loader.parse(Path.of("card.md"), """
                # T

                ```plantuml
                Alice -> Bob: hello
                ```
                """);
        StringBuilder sb = new StringBuilder();
        card.blocks().forEach(b -> sb.append(b.html()));

        // The book still builds; the source is simply visible where the
        // diagram would have been. That is the promise of an optional module.
        assertTrue(sb.toString().contains("language-plantuml"), sb.toString());
    }
}
