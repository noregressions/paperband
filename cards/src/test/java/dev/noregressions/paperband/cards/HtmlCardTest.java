package dev.noregressions.paperband.cards;

import dev.noregressions.paperband.model.Card;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An {@code .html} source is a card in HTML's own idiom: the {@code <head>}
 * is the frontmatter ({@code <title>} → {@code title:}, {@code <meta>} →
 * fields), and the {@code <body>} goes down exactly the pipeline a markdown
 * card's rendered body does — heading walk into blocks, content policy, the
 * lot. One card concept, two source syntaxes.
 */
class HtmlCardTest {

    private final List<String> removals = new ArrayList<>();

    private Card parse(String name, String html) {
        CardLoader loader = new CardLoader();
        loader.setContentPolicy(ContentPolicy.CLEAN, removals::add);
        return loader.parse(Path.of(name), html);
    }

    @Test
    void headIsTheFrontmatter_titleAndTypedMeta() {
        Card card = parse("alpha.html", """
                <!DOCTYPE html>
                <html><head>
                  <title>Alpha Service</title>
                  <meta name="tier" content="1">
                  <meta name="draft" content="false">
                  <meta name="index" content="alpha, services">
                </head><body>
                  <p>intro prose</p>
                  <h2>Setup</h2>
                  <p>setup prose</p>
                </body></html>
                """);

        assertEquals("Alpha Service", card.title());
        assertEquals(1, card.frontmatter().values().get("tier"),
                "numeric meta content typed like yaml would type it");
        assertEquals(Boolean.FALSE, card.frontmatter().values().get("draft"));
        assertEquals("alpha, services", card.frontmatter().values().get("index"),
                "comma lists stay strings — downstream already splits them");
        assertEquals("alpha", card.id(), "id derives from the filename as always");
    }

    @Test
    void bodySplitsIntoBlocksAtHeadings_likeMarkdown() {
        Card card = parse("beta.html", """
                <html><head><title>Beta</title></head><body>
                  <p>before any heading</p>
                  <h2>First</h2><p>one</p>
                  <h2>Second</h2><p>two</p>
                </body></html>
                """);

        assertEquals(3, card.blocks().size(), "intro + two heading sections");
        assertTrue(card.blocks().get(0).classes().contains("intro"));
        assertTrue(card.blocks().get(1).html().contains("one"));
        assertTrue(card.blocks().get(2).html().contains("two"));
    }

    @Test
    void headlessFragmentWorks_titleFallsBackToTheFirstH1() {
        Card card = parse("gamma.html", """
                <h1>Gamma</h1>
                <p>prose</p>
                """);

        assertEquals("Gamma", card.title(), "no <title>: the first h1 names the card, as in markdown");
        assertFalse(card.blocks().isEmpty());
        assertFalse(card.blocks().get(0).html().contains("<h1"),
                "the consumed h1 doesn't render twice");
    }

    @Test
    void contentPolicyAppliesToHtmlCardsToo() {
        Card card = parse("styled.html", """
                <html><head><title>Styled</title>
                  <style>p { color: red }</style>
                </head><body>
                  <p style="color: red" class="warning">careful</p>
                </body></html>
                """);

        String html = card.blocks().get(0).html();
        assertFalse(html.contains("style="), html);
        assertTrue(html.contains("class=\"warning\""));
        assertTrue(removals.stream().anyMatch(r -> r.contains("style=")),
                "removal reported: " + removals);
    }

    @Test
    void metaTitleDoesNotLeakIntoTheBody() {
        Card card = parse("delta.html", """
                <html><head><title>Delta</title><meta name="tier" content="2"></head>
                <body><p>prose</p></body></html>
                """);

        StringBuilder all = new StringBuilder();
        card.blocks().forEach(b -> all.append(b.html()));
        assertFalse(all.toString().contains("<title"), all.toString());
        assertFalse(all.toString().contains("<meta"), all.toString());
    }
}
