package dev.noregressions.paperband.layout;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generated pages placed into the card flow ({@code <page>} markers in a
 * POM-declared {@code <sections>}): the named template renders at its card
 * index with the whole book model in scope — the capability a card can't
 * have, since cards load before the model exists.
 */
class BookPagesTest {

    @Test
    void page_rendersBetweenTheCardsItsIndexNames_withTheBookModelInScope(@TempDir Path tmp)
            throws IOException {
        // The template proves it sees the full model: card count and book title.
        writeTemplate(tmp, "matrix.html",
                "GENERATED[{{ cards | length }} cards of {{ book.title }}]");

        String html = renderBook(tmp, List.of(new PlacedPage(1, "matrix")),
                card("alpha", "Alpha"), card("beta", "Beta"));

        assertTrue(html.contains("GENERATED[2 cards of Test Book]"),
                "the page template sees cards and book — the whole model");
        int page = html.indexOf("id=\"book-page-0\"");
        assertTrue(page > html.indexOf("id=\"card-alpha\""), "after the card before the marker");
        assertTrue(page < html.indexOf("id=\"card-beta\""), "before the card after it");
        assertTrue(html.contains("<section class=\"book-page\" id=\"book-page-0\">"),
                "wrapped in the page-break section");
        assertTrue(html.contains("href=\"#book-page-0\""),
                "anchor bait, so the page gets a named PDF destination");
    }

    @Test
    void page_atIndexZero_rendersBeforeTheFirstCard(@TempDir Path tmp) throws IOException {
        writeTemplate(tmp, "front-matter.html", "UP-FRONT");

        String html = renderBook(tmp, List.of(new PlacedPage(0, "front-matter")),
                card("alpha", "Alpha"));

        assertTrue(html.indexOf("UP-FRONT") < html.indexOf("id=\"card-alpha\""));
    }

    @Test
    void page_afterTheLastCard_rendersAtTheEnd(@TempDir Path tmp) throws IOException {
        writeTemplate(tmp, "appendix.html", "TRAILING");

        String html = renderBook(tmp, List.of(new PlacedPage(2, "appendix")),
                card("alpha", "Alpha"), card("beta", "Beta"));

        assertTrue(html.indexOf("TRAILING") > html.indexOf("id=\"card-beta\""));
    }

    @Test
    void severalPages_eachGetsItsOwnOrdinalAnchor(@TempDir Path tmp) throws IOException {
        writeTemplate(tmp, "one.html", "PAGE-ONE");
        writeTemplate(tmp, "two.html", "PAGE-TWO");

        String html = renderBook(tmp,
                List.of(new PlacedPage(0, "one"), new PlacedPage(1, "two")),
                card("alpha", "Alpha"));

        assertTrue(html.contains("id=\"book-page-0\""));
        assertTrue(html.contains("id=\"book-page-1\""));
        assertTrue(html.indexOf("PAGE-ONE") < html.indexOf("id=\"card-alpha\""));
        assertTrue(html.indexOf("PAGE-TWO") > html.indexOf("id=\"card-alpha\""));
    }

    @Test
    void noPages_leavesTheBookAlone(@TempDir Path tmp) {
        String html = renderBook(tmp, List.of(), card("alpha", "Alpha"));

        assertEquals(-1, html.indexOf("book-page-"), "no wrapper, no anchor bait");
    }

    @Test
    void diagnosticsPage_canDumpVarsAndPerCardFrontmatter(@TempDir Path tmp) throws IOException {
        // The recipe the docs recommend for a user-authored diagnostics page:
        // iterate vars (a LenientMap IS a HashMap, so Pebble walks its
        // entries) and walk every card's frontmatter. Pinned here so the
        // recipe can't rot.
        writeTemplate(tmp, "diagnostics.html", """
                VARS:{% for e in vars %}[{{ e.key }}={{ e.value }}]{% endfor %}
                {% for c in cards %}CARD:{{ c.id }}{% for f in c.frontmatter %}({{ f.key }}={{ f.value }}){% endfor %}
                {% endfor %}""");

        BookConfig book = new BookConfig(tmp, "Test Book", List.of(), List.of(), Map.of(),
                List.of(), null, null);
        RenderContext ctx = new RenderContext(book, List.of(),
                Map.of("product_name", "Paperband"), null, "pdf", "A4");
        LayoutEngine engine = new LayoutEngine(tmp);
        engine.setPagesAt(List.of(new PlacedPage(0, "diagnostics")));
        Card tiered = new Card("alpha", Path.of("alpha.md"),
                new Frontmatter(Map.of("tier", 1)), "Alpha",
                List.of(new Block(Block.Kind.HEADING_SECTION, null, Set.of("intro"), null, 0,
                        "<p>Content</p>", List.of())));
        String html = engine.renderBook(List.of(tiered), List.of(ctx), ctx);

        assertTrue(html.contains("[product_name=Paperband]"), "vars entries iterate");
        assertTrue(html.contains("CARD:alpha(tier=1)"), "per-card frontmatter iterates");
    }

    @Test
    void bookOwnLayoutsCanOverrideTheBookFrontHook(@TempDir Path tmp) throws IOException {
        // The route for a WALKED book (no <sections> in the POM, so no <page>
        // marker to place): layouts/_book-front.html overrides the bundled
        // empty hook — book layouts/ sits ahead of the classpath in the
        // loader chain — and renders between the cover and the first card
        // with the full model in scope.
        writeTemplate(tmp, "_book-front.html", "FRONT-DIAGNOSTICS[{{ cards | length }}]");

        String html = renderBook(tmp, List.of(), card("alpha", "Alpha"));

        assertTrue(html.contains("FRONT-DIAGNOSTICS[1]"));
        assertTrue(html.indexOf("FRONT-DIAGNOSTICS") < html.indexOf("id=\"card-alpha\""),
                "front hook renders before the first card");
    }

    @Test
    void explicitLayoutsDir_servesTemplatesFromThePomDecidedLocation(@TempDir Path tmp)
            throws IOException {
        // Split geography: content in one place, templates in another. The
        // engine's loader roots at the explicit dir instead of deriving
        // <contentRoot>/layouts.
        Path contentRoot = Files.createDirectory(tmp.resolve("docs"));
        Path layouts = Files.createDirectories(tmp.resolve("book/templates"));
        Files.writeString(layouts.resolve("matrix.html"), "SPLIT[{{ cards | length }}]");

        BookConfig book = new BookConfig(contentRoot, "Test Book", List.of(), List.of(), Map.of(),
                List.of(), null, null);
        RenderContext ctx = new RenderContext(book, List.of(), Map.of(), null, "pdf", "A4");
        LayoutEngine engine = new LayoutEngine(contentRoot, layouts, ThemeBundle.NONE);
        engine.setPagesAt(List.of(new PlacedPage(0, "matrix")));
        String html = engine.renderBook(List.of(card("alpha", "Alpha")), List.of(ctx), ctx);

        assertTrue(html.contains("SPLIT[1]"),
                "the page template resolved from the explicit layouts dir");
    }

    // ---- helpers ----

    private static void writeTemplate(Path bookRoot, String name, String body) throws IOException {
        Path layouts = bookRoot.resolve("layouts");
        Files.createDirectories(layouts);
        Files.writeString(layouts.resolve(name), body);
    }

    private static String renderBook(Path bookRoot, List<PlacedPage> pages, Card... cards) {
        BookConfig book = new BookConfig(bookRoot, "Test Book", List.of(), List.of(), Map.of(),
                List.of(), null, null);
        RenderContext ctx = new RenderContext(book, List.of(), Map.of(), null, "pdf", "A4");
        LayoutEngine engine = new LayoutEngine(bookRoot);
        engine.setPagesAt(pages);
        List<RenderContext> contexts = List.of(cards).stream().map(c -> ctx).toList();
        return engine.renderBook(List.of(cards), contexts, ctx);
    }

    private static Card card(String id, String title) {
        Block block = new Block(Block.Kind.HEADING_SECTION, null, Set.of("intro"), null, 0,
                "<p>Content</p>", List.of());
        return new Card(id, Path.of(id + ".md"), new Frontmatter(Map.of()),
                title, List.of(block));
    }
}
